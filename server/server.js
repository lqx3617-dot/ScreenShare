/**
 * ScreenShare 信令服务器（腾讯会议式房间，V4 多客户端）
 *
 * 协议（JSON over WebSocket）：
 *   client -> server:
 *     { "type": "create", "code": "1234" }                       // 共享方创建会议
 *     { "type": "join",   "code": "1234" }                       // 观看方请求加入（进入待确认）
 *     { "type": "accept", "viewerId": n }                        // host 同意加入请求
 *     { "type": "reject", "viewerId": n }                        // host 拒绝加入请求
 *     { "type": "relay",  "data": "<payload>", "viewerId": n }   // 中转信令（viewerId: host发往指定viewer）
 *     { "type": "pls-join" }                                     // 观看方「喊TA」：host 收到 come-on
 *     { "type": "ping" }
 *
 *   server -> client:
 *     { "type": "created", "code": "1234" }                      // 创建成功
 *     { "type": "joined",  "code": "1234", "viewerId": n }       // 加入成功（直接加入，无需 host 确认）
 *     { "type": "join-rejected" }                                // host 拒绝加入（保留兼容）
 *     { "type": "join-cancelled", "viewerId": n }                // 请求者超时/断开（仅 host 收到，保留兼容）
 *     { "type": "peer-ready" }                                   // 对端已加入（host 视角）
 *     { "type": "viewer-joined", "viewerId": n }                 // viewer 正式加入（仅 host 收到）
 *     { "type": "relay",     "data": "<payload>", "viewerId": n }
 *     { "type": "viewer-left", "viewerId": n }                   // 某 viewer 离开（仅 host 收到）
 *     { "type": "host-left" }                                    // host 离开（所有 viewer 收到）
 *     { "type": "come-on" }                                      // 观看方喊TA（host 收到提示）
 *     { "type": "error",     "message": "..." }
 *
 * 行为：
 * - 会议 1 host + 多 viewer（从 1对1 升级为 1对多）
 * - viewer 直接加入（无需 host 确认）：join 即正式加入并通知双方；旧版 join-pending/join-request 流程保留兼容
 * - 连接级状态互斥：同一连接已有角色时拒绝二次 create/join（防僵尸房间）
 * - host 离开：整房销毁，通知所有 viewer
 * - viewer 离开：仅移除该 viewer，通知 host
 */
"use strict";

const http = require("http");
const crypto = require("crypto");
const fs = require("fs");
const path = require("path");
const { WebSocketServer } = require("ws");
const RoomManager = require("./RoomManager");
const AuthManager = require("./AuthManager");
const { openDb } = require("./db");

const { RateLimiter } = require("./RateLimiter");
const { AccountManager } = require("./AccountManager");
const { FriendManager } = require("./FriendManager");
const { ShareHistory } = require("./ShareHistory");
const { FcmPusher } = require("./FcmPusher");
const { PresenceManager } = require("./PresenceManager");
const { AccountRouter } = require("./AccountRouter");

const PORT = process.env.PORT || 8080;
// 诊断模式：DIAG=1 时打印 SDP/候选统计（默认关闭，转发零解析零日志最快）
const DIAG = process.env.DIAG === "1";
// /diag 与 /crash 上报鉴权 token：与 App 构建参数 screenshare.diag.token 保持一致；
// 未配置时拒绝所有上报（防止日志注入），部署需显式设置
const DIAG_TOKEN = process.env.DIAG_TOKEN || "";
// 密钥轮换过渡期旧 token（2026-08-26 轮换）：旧版 App 崩溃上报仍接受，双端更新后应移除
const DIAG_TOKEN_OLD = process.env.DIAG_TOKEN_OLD || "";
// 兼容方案：设置 REQUIRE_TOKEN=1 才强制房间 token 认证，默认关闭保持旧客户端可用
const REQUIRE_TOKEN = process.env.REQUIRE_TOKEN === "1";
// 心跳超时（毫秒）：客户端每 10s 发 ping，超过该时长未有任何消息视为掉线，强制清理房间
const HEARTBEAT_TIMEOUT = 45 * 1000;
// 所有 ws 连接（用于心跳扫描）
const allClients = new Set();
// 并发连接上限：防单 IP 打开大量 ws 耗尽服务端 FD/内存（DoS 兜底）
const MAX_TOTAL_CLIENTS = 200;
const MAX_CLIENTS_PER_IP = 10;
const ipClientCount = new Map();

  // 轻量限流：同一 IP 每分钟最多 create/join/room-status 20 次，防 4 位会议号枚举爆破。
  // 不引入额外口令，不改变客户端使用流程。
  const AUTH_WINDOW_MS = 60 * 1000;
  const AUTH_MAX_ATTEMPTS = 20;
  // 「喊TA」(pls-join) 单独限流 6 次/分，防提醒轰炸
  const PLS_JOIN_MAX_ATTEMPTS = 6;
  // room-status 是纯查询（专属房间在线状态轮询，客户端每 10 秒一次），
  // 与 create/join 的防枚举限流分开计数，避免多设备共用出口 IP 时互相挤占配额
  const ROOM_STATUS_MAX_ATTEMPTS = 120;
  const authAttempts = new Map();
  const plsJoinAttempts = new Map();
  const roomStatusAttempts = new Map();

  function remoteIp(obj) {
    if (!obj) return "unknown";
    // 反向代理后优先取 X-Forwarded-For 首段真实客户端 IP，避免所有请求都归到
    // 代理地址导致多设备共享限流配额。仅 TRUST_PROXY=1（明确运行在可信反代后）
    // 才信任 XFF 头，默认取 socket 地址，防伪造 XFF 头绕过限流。
    const headers = obj._headers || obj.headers;
    if (process.env.TRUST_PROXY === "1" && headers) {
      const xff = String(headers["x-forwarded-for"] || "");
      const first = xff.split(",")[0].trim();
      if (first) return first.replace(/^::ffff:/, "");
    }
    const sock = obj._socket || obj.socket;
    return String((sock && sock.remoteAddress) || "").replace(/^::ffff:/, "") || "unknown";
  }

  function allowAuthAttempt(ip) {
    const now = Date.now();
    const entry = authAttempts.get(ip);
    if (!entry || now >= entry.resetAt) {
      authAttempts.set(ip, { count: 1, resetAt: now + AUTH_WINDOW_MS });
      return true;
    }
    entry.count += 1;
    return entry.count < AUTH_MAX_ATTEMPTS;
  }

  function allowPlsJoin(ip) {
    const now = Date.now();
    const entry = plsJoinAttempts.get(ip);
    if (!entry || now >= entry.resetAt) {
      plsJoinAttempts.set(ip, { count: 1, resetAt: now + AUTH_WINDOW_MS });
      return true;
    }
    entry.count += 1;
    return entry.count < PLS_JOIN_MAX_ATTEMPTS;
  }

  function allowRoomStatus(ip) {
    const now = Date.now();
    const entry = roomStatusAttempts.get(ip);
    if (!entry || now >= entry.resetAt) {
      roomStatusAttempts.set(ip, { count: 1, resetAt: now + AUTH_WINDOW_MS });
      return true;
    }
    entry.count += 1;
    return entry.count < ROOM_STATUS_MAX_ATTEMPTS;
  }

  // 每分钟清理过期限流记录，避免长期运行内存堆积
  setInterval(() => {
    const now = Date.now();
    for (const [ip, entry] of authAttempts) {
      if (now >= entry.resetAt) authAttempts.delete(ip);
    }
    for (const [ip, entry] of plsJoinAttempts) {
      if (now >= entry.resetAt) plsJoinAttempts.delete(ip);
    }
  }, 60 * 1000).unref();


/** 常量时间比较，避免逐字节短路造成时序侧信道 */
function safeCompare(a, b) {
  if (typeof a !== "string" || typeof b !== "string") return false;
  const ab = Buffer.from(a, "utf8"), bb = Buffer.from(b, "utf8");
  if (ab.length !== bb.length) return false;
  return crypto.timingSafeEqual(ab, bb);
}

/** 校验诊断上报 token（x-diag-token header） */
function diagAuthorized(req) {
  if (DIAG_TOKEN === "") return false;
  const t = req.headers["x-diag-token"];
  return safeCompare(t, DIAG_TOKEN) || (DIAG_TOKEN_OLD !== "" && safeCompare(t, DIAG_TOKEN_OLD));
}

// 账号/好友系统：账号库、限流与 REST 路由（与信令同进程，在线状态与房间同内存）
const accountDb = openDb();
const rateLimiter = new RateLimiter();
const accountManager = new AccountManager(accountDb);
const friendManager = new FriendManager(accountDb);
const presenceManager = new PresenceManager();
const shareHistory = new ShareHistory(accountDb);
// FCM 推送：私钥文件路径由 FCM_KEY_FILE 指定，否则自动找 server/*firebase-adminsdk*.json
const fcmKeyFile =
  process.env.FCM_KEY_FILE ||
  (() => {
    const found = require("fs")
      .readdirSync(__dirname)
      .find((f) => /firebase-adminsdk.*\.json$/.test(f));
    return found ? path.join(__dirname, found) : "";
  })();
const fcmPusher = new FcmPusher(fcmKeyFile);
console.log(`[fcm] 推送 ${fcmPusher.enabled ? "已启用" : "未启用（缺私钥文件）"}`);
const accountRouter = new AccountRouter({
  accountManager,
  friendManager,
  rateLimiter,
  presence: presenceManager,
  shareHistory,
  notifyUser: (userId, obj) => sendToUser(userId, obj),
});
setInterval(() => { rateLimiter.sweep(); accountManager.sweepLoginLimiter(); }, 60 * 1000).unref();

const server = http.createServer((req, res) => {
  // 账号/好友 REST：命中 /account 或 /friends 时由 AccountRouter 接管（自带鉴权与限流）
  if (accountRouter.handle(req, res)) return;
  // 崩溃日志上报：App Java 层崩溃 POST 到这里落盘
  if (req.method === "POST" && req.url.startsWith("/crash")) {
    if (!diagAuthorized(req)) {
      res.writeHead(403, { "Content-Type": "text/plain; charset=utf-8" });
      res.end("forbidden");
      return;
    }
    let body = "";
    req.on("data", (c) => { body = (body + c).slice(-200000); });
    req.on("end", () => {
      try {
        const dir = path.join(__dirname, "crashes");
        fs.mkdirSync(dir, { recursive: true });
        const stamp = new Date().toISOString().replace(/[:.]/g, "-");
        // 同一毫秒多个崩溃上报会覆盖同名文件，加随机后缀保数据不丢
        const crashFile = path.join(dir, `crash-${stamp}-${Math.random().toString(36).slice(2, 8)}.log`);
        fs.writeFileSync(crashFile, body);
        console.log(`[crash] ${stamp} len=${body.length}`);
      } catch (e) {
        console.error("[crash] 写入失败:", e.message);
      }
      res.writeHead(200, { "Content-Type": "text/plain; charset=utf-8" });
      res.end("ok");
    });
    return;
  }
  // 诊断上报：App 检测到软编/CPU瓶颈/高丢包时 POST 到这里落盘
  if (req.method === "POST" && req.url.startsWith("/diag")) {
    if (!diagAuthorized(req)) {
      res.writeHead(403, { "Content-Type": "text/plain; charset=utf-8" });
      res.end("forbidden");
      return;
    }
    let body = "";
    req.on("data", (c) => { body = (body + c).slice(-200000); });
    req.on("end", () => {
      try {
        const dir = path.join(__dirname, "diag");
        fs.mkdirSync(dir, { recursive: true });
        const stamp = new Date().toISOString().replace(/[:.]/g, "-");
        fs.appendFileSync(path.join(dir, "diag.log"), `${stamp} ${body.trim()}\n`);
        console.log(`[diag] ${stamp} ${body.trim().slice(0, 160)}`);
      } catch (e) {
        console.error("[diag] 写入失败:", e.message);
      }
      res.writeHead(200, { "Content-Type": "text/plain; charset=utf-8" });
      res.end("ok");
    });
    return;
  }
  // 房间在线状态查询：客户端 GET /room-status?code=XXXX 判断该会议号是否有 host 在线
  // 用于专属房间卡片显示「对方在线/不在线」，纯查询不建连，不参与房间流程
  if (req.method === "GET" && req.url.startsWith("/room-status")) {
      if (!allowRoomStatus(remoteIp(req))) {
        res.writeHead(429, { "Content-Type": "text/plain; charset=utf-8" });
        res.end("too many requests");
        return;
      }
    try {
      const u = new URL(req.url, "http://localhost");
      const code = (u.searchParams.get("code") || "").trim().toUpperCase();
      const ok = /^[0-9]{4}$/.test(code);
      const room = ok ? rooms.getRoom(code) : null;
      const online = !!(room && room.host);
      res.writeHead(200, { "Content-Type": "application/json; charset=utf-8", "Cache-Control": "no-store" });
      res.end(JSON.stringify({ code, online }));
      return;
    } catch (e) {
      res.writeHead(500, { "Content-Type": "application/json; charset=utf-8" });
      res.end(JSON.stringify({ error: "internal" }));
      return;
    }
  }
  // 网页观看端：GET / 与 /viewer 返回静态页面（浏览器 WebRTC 观看）
  if (req.method === "GET" && (req.url === "/" || req.url === "/viewer" || req.url === "/index.html")) {
    fs.readFile(path.join(__dirname, "public", "index.html"), (err, buf) => {
      if (err) {
        res.writeHead(404, { "Content-Type": "text/plain; charset=utf-8" });
        res.end("not found");
        return;
      }
      res.writeHead(200, {
        "Content-Type": "text/html; charset=utf-8",
        "Cache-Control": "no-store",
      });
      res.end(buf);
    });
    return;
  }
  res.writeHead(200, { "Content-Type": "text/plain; charset=utf-8" });
  res.end("ScreenShare signaling server is running");
});

// 心跳超时扫描：每 30s 检查一次，超时未通信的 ws 强制断开（触发 close → 房间清理）
setInterval(() => {
  const now = Date.now();
  allClients.forEach((ws) => {
    if (now - (ws.lastSeen || now) > HEARTBEAT_TIMEOUT) {
      console.log(`[heartbeat] ws ${ws._socketId || ""} idle > ${HEARTBEAT_TIMEOUT}ms, terminate`);
      try { ws.terminate(); } catch (e) {}
    }
  });
  // pending 加入请求超时（30s 未确认）自动取消，通知 host
  rooms.expirePendingAll().forEach(({ roomCode, viewerId, ws }) => {
    try { ws.send(JSON.stringify({ type: "error", message: "加入请求已超时，请重试" })); } catch (e) {}
    const host = rooms.getHost(roomCode);
    send(host, { type: "join-cancelled", viewerId });
    console.log(`[room ${roomCode}] pending join #${viewerId} expired`);
  });
}, 30 * 1000).unref();

const wss = new WebSocketServer({ server, path: "/ws", maxPayload: 512 * 1024 });

const rooms = new RoomManager((code, viewerId, userId) => {
  // viewer 重连宽限期超时：此时才通知 host 并结账共享会话
  const host = rooms.getHost(code);
  if (host) send(host, { type: "viewer-left", viewerId });
  if (userId) shareHistory.onViewerLeft(code, userId);
  console.log(`[room ${code}] viewer#${viewerId} reconnect timeout, left`);
}, parseInt(process.env.RECONNECT_TIMEOUT_MS, 10) || undefined);

function send(ws, obj) {
  if (ws && ws.readyState === ws.OPEN) {
    ws.send(JSON.stringify(obj));
  }
}

function normalizeCode(code) {
  return String(code || "").trim().toUpperCase();
}

// 向某用户的全部在线设备投递消息（多设备同时在线时每台设备都收到）
function sendToUser(userId, obj) {
  for (const target of presenceManager.socketsOf(userId)) send(target, obj);
}

// 用户上下线时向其好友广播在线状态
function broadcastPresence(userId, online) {
  for (const f of friendManager.list(userId)) {
    sendToUser(f.userId, { type: "presence", userId, online });
  }
}

/** 用户上线时：补投离线期间收到的共享邀请 */
function flushPendingInvites(userId) {
  for (const [id, inv] of [...pendingInvites]) {
    if (inv.toUserId !== userId) continue;
    const fromProfile = accountManager.getProfile(inv.fromUserId);
    sendToUser(userId, {
      type: "share-invite",
      inviteId: id,
      code: inv.code,
      from: { userId: inv.fromUserId, nickname: fromProfile ? fromProfile.nickname : "" },
    });
    console.log(`[invite] 补投离线邀请 room=${inv.code} to=${userId.slice(0, 8)}…`);
  }
}

/** 房间销毁时：作废该房间的暂存邀请，通知被邀请方关掉已弹出的邀请框 */
function cancelPendingInvites(roomCode) {
  for (const [id, inv] of [...pendingInvites]) {
    if (inv.code !== roomCode) continue;
    pendingInvites.delete(id);
    sendToUser(inv.toUserId, { type: "invite-cancelled", inviteId: id, code: roomCode });
    console.log(`[invite] 房间关闭，作废邀请 room=${roomCode} to=${inv.toUserId.slice(0, 8)}…`);
  }
}

// 待处理共享邀请：inviteId -> { fromUserId, toUserId, code, createdAt }，5 分钟未处理自动过期
const pendingInvites = new Map();
setInterval(() => {
  const now = Date.now();
  for (const [id, inv] of pendingInvites) {
    if (now - inv.createdAt > 5 * 60 * 1000) pendingInvites.delete(id);
  }
}, 60 * 1000).unref();

wss.on("connection", (ws, request) => {
  let roomCode = null;
  let role = null; // "host" | "viewer"
  let viewerId = null;
  let userId = null; // 账号身份（收到 auth 消息后认领）
  ws.lastSeen = Date.now();
  ws._socketId = ws._socket && ws._socket.remotePort;
  ws._headers = request && request.headers;
  // 连接数兜底：全局 200 / 单 IP 10，超限直接拒接（1013 客户端会重试）
  ws._ip = remoteIp({ _socket: request && request.socket, _headers: request && request.headers });
  const perIp = (ipClientCount.get(ws._ip) || 0) + 1;
  if (allClients.size >= MAX_TOTAL_CLIENTS || perIp > MAX_CLIENTS_PER_IP) {
    console.log(`[ws] reject ${ws._ip} (total=${allClients.size}, perIp=${perIp})`);
    try { ws.close(1013, "try again later"); } catch (e) {}
    return;
  }
  ipClientCount.set(ws._ip, perIp);
  allClients.add(ws);

  ws.on("message", (raw) => {
    ws.lastSeen = Date.now();
    let msg;
    try {
      msg = JSON.parse(raw.toString());
    } catch (e) {
      send(ws, { type: "error", message: "无效的消息格式" });
      return;
    }
    // JSON.parse("null") 返回 null、原始值自动装箱不报错但无 .type，统一拒绝非对象消息
    if (msg === null || typeof msg !== "object" || Array.isArray(msg)) {
      send(ws, { type: "error", message: "无效的消息格式" });
      return;
    }

    // 消息分发整体兜底：switch 内的同步 DB 调用（shareHistory/friendManager/
    // accountManager）在磁盘满或文件锁异常时同步抛出，沿 EventEmitter 冒泡为
    // 未捕获异常会杀死整个进程（全项目无 process.on("uncaughtException")），
    // 任一连接的 DB 抖动不应让所有房间连锁销毁
    try {
      switch (msg.type) {
      case "create": {
          if (!allowAuthAttempt(remoteIp(ws))) {
            send(ws, { type: "error", message: "操作过于频繁，请稍后再试" });
            return;
          }
        // 状态机互斥：已有角色的连接不允许再 create（防同一连接制造僵尸房间）
        if (role) {
          send(ws, { type: "error", message: "已在房间中，请先离开当前会议" });
          return;
        }
        const code = normalizeCode(msg.code);
        if (!rooms.isValidCode(code)) {
          send(ws, { type: "error", message: "会议号需为 4 位数字" });
          return;
        }
        const err = rooms.create(code, ws, userId);
        if (err) {
          send(ws, { type: "error", message: err });
          return;
        }
        roomCode = code;
        role = "host";
        // 房间 token：签发前先释放该房间旧 token（host 重连/同 code 重建防残留）
        AuthManager.releaseTokens(code);
        const token = AuthManager.issueToken(code);
        send(ws, { type: "created", code, token });
        // token 为 8 位定长口令，slice(0,8) 等于明文记录完整加入口令，只打前 4 位
        console.log(`[room ${code}] created by host${REQUIRE_TOKEN ? ` (token=${token.slice(0, 4)}…)` : ""}`);
        break;
      }

      case "join": {
          if (!allowAuthAttempt(remoteIp(ws))) {
            send(ws, { type: "error", message: "操作过于频繁，请稍后再试" });
            return;
          }
        // 状态机互斥
        if (role) {
          send(ws, { type: "error", message: "已在房间中，请先离开当前会议" });
          return;
        }
        const code = normalizeCode(msg.code);
        // REQUIRE_TOKEN=1 时强制校验房间 token（防止撞房/未授权观看）；默认关闭保持旧客户端兼容
        if (REQUIRE_TOKEN && !AuthManager.verify(code, msg.token)) {
          send(ws, { type: "error", message: "加入口令无效" });
          console.log(`[room ${code}] join rejected (bad token)`);
          return;
        }
        // 重连恢复优先：同 userId 在宽限期内断开过，直接恢复原 viewerId 槽位，
        // 会话不结账、host 侧 PC 幂等复用（ICE 失败会自动 restart）
        if (userId) {
          const resumed = rooms.resumeViewer(code, userId, ws);
          if (resumed) {
            roomCode = code;
            role = "viewer";
            viewerId = resumed.viewerId;
            send(ws, { type: "joined", code, viewerId });
            const host = rooms.getHost(code);
            // reconnected=true：host 侧 PC 幂等复用，不重发 offer（ICE 断会自动 restart 重建）
            if (host) send(host, { type: "viewer-joined", viewerId, reconnected: true });
            console.log(`[room ${code}] viewer#${viewerId} reconnected (resumed)`);
            break;
          }
        }
        const res = rooms.requestJoin(code, ws);
        if (!res.ok) {
          send(ws, { type: "error", message: res.error });
          return;
        }
        roomCode = code;
        role = "viewer";
        viewerId = res.viewerId;
        // 直接加入（无需 host 确认）：通知 viewer 已加入，并通知 host
        send(ws, { type: "joined", code, viewerId });
        const host = rooms.getHost(code);
        send(host, { type: "viewer-joined", viewerId });
        console.log(`[room ${code}] viewer#${viewerId} joined (auto-approved)`);
        break;
      }

      case "accept": {
        if (role !== "host") {
          send(ws, { type: "error", message: "仅会议创建者可同意加入" });
          return;
        }
        const vid = parseInt(msg.viewerId, 10);
        const viewerWs = rooms.acceptJoin(roomCode, vid);
        if (!viewerWs) {
          send(ws, { type: "error", message: "加入请求不存在或已过期" });
          return;
        }
        send(viewerWs, { type: "joined", code: roomCode, viewerId: vid });
        send(ws, { type: "viewer-joined", viewerId: vid });
        console.log(`[room ${roomCode}] host accepted viewer#${vid}`);
        break;
      }

      case "reject": {
        if (role !== "host") {
          send(ws, { type: "error", message: "仅会议创建者可拒绝加入" });
          return;
        }
        const vid = parseInt(msg.viewerId, 10);
        const viewerWs = rooms.rejectJoin(roomCode, vid);
        if (!viewerWs) {
          send(ws, { type: "error", message: "加入请求不存在或已过期" });
          return;
        }
        send(viewerWs, { type: "join-rejected" });
        console.log(`[room ${roomCode}] host rejected viewer#${vid}`);
        break;
      }

      case "relay": {
        if (!roomCode || !role) {
          send(ws, { type: "error", message: "尚未加入房间" });
          return;
        }
        const target = rooms.route(roomCode, role, msg.viewerId);
        if (!target) {
          send(ws, { type: "error", message: "对端尚未加入" });
          return;
        }
        const data = msg.data;
        if (DIAG) {
          const payloadLen = String(data || "").length;
          let host = 0, srflx = 0, relay = 0, sdpCand = 0;
          let mediaLines = [];
          let hasApp = false;
          let candInfo = "";
          try {
            const p = JSON.parse(data || "{}");
            const sdpBody = String(p.sdp && p.sdp.sdp || "");
            sdpCand = (sdpBody.match(/a=candidate/g) || []).length;
            mediaLines = (sdpBody.match(/^m=/gm) || []).slice(0, 6);
            hasApp = /^m=application\b/m.test(sdpBody);
            if (p.type === "candidate") {
              const s = String(p.candidate || "");
              const ct = s.includes("typ relay") ? "relay" : s.includes("typ srflx") ? "srflx" : s.includes("typ host") ? "host" : "other";
              candInfo = ` | cand:${ct}(${s.slice(0, 90)})`;
            }
            const iceArr = Array.isArray(p.ice) ? p.ice : [];
            iceArr.forEach((c) => {
              const s = String(c && c.candidate || "");
              if (s.includes("typ host")) host++;
              else if (s.includes("typ srflx")) srflx++;
              else if (s.includes("typ relay")) relay++;
            });
          } catch (e) {}
          console.log(`[room ${roomCode}] relay ${role}#${viewerId||""}->${role === "host" ? "viewer#" + msg.viewerId : "host"} ${payloadLen}B | app=${hasApp ? "有DataChannel" : "无DataChannel"} | sdp a=candidate:${sdpCand} | media:[${mediaLines.join(" | ")}] | ice[]: host=${host} srflx=${srflx} relay=${relay}${candInfo}`);
        }
        send(target, { type: "relay", data, viewerId: role === "host" ? msg.viewerId : viewerId });
        break;
      }

      case "auth": {
        if (userId) {
          send(ws, { type: "auth-ok", userId });
          break;
        }
        try {
          userId = accountManager.authenticate(msg.token);
        } catch (e) {
          send(ws, { type: "auth-error", reason: "unauthenticated" });
          break;
        }
        const cameOnline = presenceManager.attach(userId, ws);
        send(ws, { type: "auth-ok", userId });
        if (cameOnline) {
          broadcastPresence(userId, true);
          flushPendingInvites(userId);
        }
        console.log(`[account] ws authed user=${userId.slice(0, 8)}… ${cameOnline ? "(online)" : "(extra device)"}`);
        break;
      }

      case "share-invite": {
        if (!userId) {
          console.log(`[invite] 拒绝：未登录`)
          send(ws, { type: "error", message: "请先登录" });
          break;
        }
        if (!allowAuthAttempt(remoteIp(ws))) {
          send(ws, { type: "error", message: "操作过于频繁，请稍后再试" });
          break;
        }
        const toUserId = String(msg.toUserId || "");
        const inviteCode = normalizeCode(msg.code);
        if (!rooms.isValidCode(inviteCode)) {
          console.log(`[invite] 拒绝：房间号非法 from=${userId.slice(0, 8)}… code=${msg.code}`)
          send(ws, { type: "error", message: "房间号需为 4 位数字" });
          break;
        }
        // 房间归属校验：邀请方必须是该房间的 host 账号，且房间处于活跃状态，
        // 否则不能把好友导向别人的房间或不存在的房间
        if (!rooms.isHostOf(inviteCode, userId)) {
          console.log(`[invite] 拒绝：非房间 host from=${userId.slice(0, 8)}… room=${inviteCode}`)
          send(ws, { type: "error", message: "请先进入共享房间后再邀请好友" });
          break;
        }
        if (!friendManager.list(userId).some((f) => f.userId === toUserId)) {
          console.log(`[invite] 拒绝：非好友 from=${userId.slice(0, 8)}… to=${toUserId.slice(0, 8)}…`)
          send(ws, { type: "error", message: "只能邀请好友" });
          break;
        }
        const inviteId = crypto.randomUUID();
        const fromProfile = accountManager.getProfile(userId);
        const fromNickname = fromProfile ? fromProfile.nickname : "";
        pendingInvites.set(inviteId, { fromUserId: userId, toUserId, code: inviteCode, createdAt: Date.now() });
        if (presenceManager.isOnline(toUserId)) {
          // 在线：直接投递
          sendToUser(toUserId, {
            type: "share-invite",
            inviteId,
            code: inviteCode,
            from: { userId, nickname: fromNickname },
          });
          console.log(`[invite] ${userId.slice(0, 8)}… -> ${toUserId.slice(0, 8)}… room=${inviteCode}`);
        } else {
          // 离线：邀请先存服务端，再发推送提醒；对方上线时 flushPendingInvites 补投邀请
          const pushToken = accountManager.getPushToken(toUserId);
          if (!pushToken) {
            send(ws, { type: "share-invite-result", inviteId, accepted: false, reason: "offline" });
            console.log(`[invite] 对方离线且无推送令牌 to=${toUserId.slice(0, 8)}… room=${inviteCode}`);
            break;
          }
          fcmPusher
            .send(pushToken, "共享邀请", `${fromNickname || "好友"} 邀请你观看 TA 的屏幕共享`)
            .then((r) => {
              if (r === "invalid_token") {
                accountManager.clearPushToken(toUserId);
                console.log(`[invite] 对方推送令牌失效，已清除 to=${toUserId.slice(0, 8)}…`);
              }
              const pushed = r === "ok";
              send(ws, {
                type: "share-invite-result",
                inviteId,
                accepted: false,
                reason: pushed ? "offline_pushed" : "offline",
              });
              console.log(`[invite] 对方离线，邀请已存，推送 pushed=${pushed} to=${toUserId.slice(0, 8)}… room=${inviteCode}`);
            })
            // 无 catch 时 .then 内的同步 DB 调用（clearPushToken）抛错会变成
            // unhandled rejection，Node 22 默认 --unhandled-rejections=throw
            // 会杀死整个进程（信令+账号+好友同进程），一次令牌清理异常即全部会议室连锁销毁
            .catch((e) => {
              console.error(`[invite] 推送链路异常 to=${toUserId.slice(0, 8)}…:`, e?.message || e);
              try { send(ws, { type: "share-invite-result", inviteId, accepted: false, reason: "offline" }); } catch (_) {}
            });
        }
        break;
      }

      case "share-invite-accept":
      case "share-invite-reject": {
        const inv = pendingInvites.get(String(msg.inviteId || ""));
        if (!inv || inv.toUserId !== userId) {
          send(ws, { type: "error", message: "邀请不存在或已过期" });
          break;
        }
        const accepted = msg.type === "share-invite-accept";
        pendingInvites.delete(inv.inviteId);
        // 接受时房间可能已关闭：不开账、不算会话，让观看方直接得到错误
        if (accepted && !rooms.getRoom(inv.code)) {
          send(ws, { type: "error", message: "会议已结束，请让对方重新发起共享" });
          sendToUser(inv.fromUserId, {
            type: "share-invite-result",
            inviteId: inv.inviteId,
            accepted: false,
            reason: "expired",
          });
          console.log(`[invite] 接受时房间已关闭 room=${inv.code} to=${userId.slice(0, 8)}…`);
          break;
        }
        if (accepted) {
          // 最近共享记录：开账，任意一方断开时结账
          shareHistory.onStart(inv.code, inv.fromUserId, userId);
          console.log(`[share] 会话开始 room=${inv.code} host=${inv.fromUserId.slice(0, 8)}… viewer=${userId.slice(0, 8)}…`);
        }
        sendToUser(inv.fromUserId, {
          type: "share-invite-result",
          inviteId: inv.inviteId,
          accepted,
          reason: accepted ? "accepted" : "rejected",
        });
        break;
      }

      case "ping": {
        send(ws, { type: "pong" });
        break;
      }

      case "pls-join": {
        // 观看方「喊TA」：提醒 host 快点上屏。
        // 不要求 viewer 已 join——只要该 code 有 host 建了房间即可投递（host 建房后 viewer 随时可喊）。
        if (role === "host") {
          send(ws, { type: "error", message: "共享方无需发起提醒" });
          return;
        }
        if (!allowPlsJoin(remoteIp(ws))) {
          send(ws, { type: "error", message: "提醒过于频繁，请稍后再试" });
          return;
        }
        const code = normalizeCode(msg.code || roomCode);
        const host = code ? rooms.getHost(code) : null;
        // 归属校验：任何已登录用户知道 code 即可发提醒会被滥用骚扰。
        // 已在该房间的 viewer 直接放行（最常见路径，免 DB 查询）；
        // 否则必须是 host 的好友。rooms 非空房间必有 hostUserId（create 时注入）
        const room = code ? rooms.rooms.get(code) : null;
        const inRoom = role === "viewer" && roomCode === code;
        const hostUid = room && room.hostUserId;
        const isFriend = !inRoom && userId && hostUid && friendManager.list(userId).some((f) => f.userId === hostUid);
        if (host && (inRoom || isFriend)) {
          send(host, { type: "come-on", code });
          console.log(`[room ${code}] viewer#${viewerId != null ? viewerId : "?"} pls-join (喊TA)`);
        } else if (host) {
          send(ws, { type: "error", message: "仅好友可发起提醒" });
          console.log(`[room ${code}] pls-join rejected (not friend, user=${userId?.slice(0, 8)}…)`);
        } else {
          send(ws, { type: "error", message: "对方不在线，无法提醒（可先点这里创建房间等 TA）" });
        }
        break;
      }

      default:
        send(ws, { type: "error", message: `未知消息类型: ${msg.type}` });
    }
    } catch (e) {
      // 单条消息处理失败降级为该连接的错误响应，绝不冒泡到进程级
      console.error(`[ws] message handler error (type=${msg?.type}, user=${userId?.slice(0, 8)}…):`, e?.message || e);
      try { send(ws, { type: "error", message: "服务器内部错误，请重试" }); } catch (_) {}
    }
  });

  ws.on("close", () => {
    // close 回调内同样有同步 DB 调用（结账/通知），兜底防进程退出
    try {
    allClients.delete(ws);
    if (userId) {
      const wentOffline = presenceManager.detach(userId, ws);
      if (wentOffline) broadcastPresence(userId, false);
    }
    const n = (ipClientCount.get(ws._ip) || 1) - 1;
    if (n <= 0) ipClientCount.delete(ws._ip); else ipClientCount.set(ws._ip, n);
    if (!roomCode) return;
    const r = rooms.onDisconnect(roomCode, role, viewerId, userId);
    // viewer 重连宽限期：保留槽位，不结账、不通知 host，等 60s 内重连或超时回调
    if (r.reconnecting) {
      console.log(`[room ${roomCode}] viewer#${viewerId} disconnected, awaiting reconnect (${Math.round(rooms.reconnectTimeout / 1000)}s)`);
      return;
    }
    // 共享会话结账：host 走批量，viewer 走单条
    if (userId) {
      if (role === "host") shareHistory.onHostLeft(roomCode);
      else if (role === "viewer") shareHistory.onViewerLeft(roomCode, userId);
    }
    if (r.removedHost) {
      // host 离开：释放房间 token，通知所有 viewer
      AuthManager.releaseTokens(roomCode);
      cancelPendingInvites(roomCode);
      r.remainingViewers.forEach((v) => send(v, { type: "host-left" }));
      // pending 中的请求者也要通知，否则干等 30s 超时
      r.pendingWs?.forEach((v) => send(v, { type: "join-cancelled" }));
      console.log(`[room ${roomCode}] closed (host left, ${r.remainingViewers.length} viewer(s) disconnected)`);
    } else if (r.pendingRemoved != null) {
      // pending 请求者断开：通知 host 取消
      send(r.peerLeftWs, { type: "join-cancelled", viewerId: r.pendingRemoved });
      console.log(`[room ${roomCode}] viewer#${viewerId} cancelled join request`);
    } else {
      // viewer 离开：通知 host
      if (r.peerLeftWs) send(r.peerLeftWs, { type: "viewer-left", viewerId });
      console.log(`[room ${roomCode}] viewer#${viewerId} left`);
    }
    } catch (e) {
      // 结账失败不应让进程退出；房间清理可由后续连接或超时兜底
      console.error(`[ws] close handler error (room=${roomCode}, role=${role}):`, e?.message || e);
    }
  });

  ws.on("error", () => {});
});

server.listen(PORT, () => {
  const actual = server.address().port;
  console.log(`ScreenShare signaling server listening on :${actual} (ws://<host>:${actual}/ws)`);
});
