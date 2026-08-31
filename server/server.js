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
 *     { "type": "join-pending" }                                 // 加入请求已提交，等待 host 确认
 *     { "type": "join-request", "viewerId": n }                  // 有人请求加入（仅 host 收到，需确认）
 *     { "type": "joined",  "code": "1234", "viewerId": n }       // host 同意，加入成功
 *     { "type": "join-rejected" }                                // host 拒绝加入
 *     { "type": "join-cancelled", "viewerId": n }                // 请求者超时/断开（仅 host 收到）
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
 * - viewer 加入需 host 确认（防撞房）：join 只进入待确认队列，host accept 后才正式加入
 * - 连接级状态互斥：同一连接已有角色时拒绝二次 create/join（防僵尸房间）
 * - host 离开：整房销毁，通知所有 viewer
 * - viewer 离开：仅移除该 viewer，通知 host
 */
"use strict";

const http = require("http");
const fs = require("fs");
const path = require("path");
const { WebSocketServer } = require("ws");
const RoomManager = require("./RoomManager");
const AuthManager = require("./AuthManager");

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

  // 轻量限流：同一 IP 每分钟最多 create/join/room-status 30 次，防 4 位会议号枚举爆破。
  // 不引入额外口令，不改变客户端使用流程。
  const AUTH_WINDOW_MS = 60 * 1000;
  const AUTH_MAX_ATTEMPTS = 30;
  const authAttempts = new Map();

  function remoteIp(obj) {
    if (!obj) return "unknown";
    // 反向代理后优先取 X-Forwarded-For 首段真实客户端 IP，避免所有请求都归到
    // 代理地址导致多设备共享限流配额（依赖反代覆写 XFF 头，仅内网反代可直连本端口）。
    const headers = obj._headers || obj.headers;
    if (headers) {
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
    return entry.count <= AUTH_MAX_ATTEMPTS;
  }

  // 每分钟清理过期限流记录，避免长期运行内存堆积
  setInterval(() => {
    const now = Date.now();
    for (const [ip, entry] of authAttempts) {
      if (now >= entry.resetAt) authAttempts.delete(ip);
    }
  }, 60 * 1000).unref();


/** 校验诊断上报 token（x-diag-token header） */
function diagAuthorized(req) {
  if (DIAG_TOKEN === "") return false;
  const t = req.headers["x-diag-token"];
  return t === DIAG_TOKEN || (DIAG_TOKEN_OLD !== "" && t === DIAG_TOKEN_OLD);
}

const server = http.createServer((req, res) => {
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
        fs.writeFileSync(path.join(dir, `crash-${stamp}.log`), body);
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
      if (!allowAuthAttempt(remoteIp(req))) {
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

const rooms = new RoomManager();

function send(ws, obj) {
  if (ws && ws.readyState === ws.OPEN) {
    ws.send(JSON.stringify(obj));
  }
}

function normalizeCode(code) {
  return String(code || "").trim().toUpperCase();
}

wss.on("connection", (ws, request) => {
  let roomCode = null;
  let role = null; // "host" | "viewer"
  let viewerId = null;
  ws.lastSeen = Date.now();
  ws._socketId = ws._socket && ws._socket.remotePort;
  ws._headers = request && request.headers;
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
        const err = rooms.create(code, ws);
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
        console.log(`[room ${code}] created by host${REQUIRE_TOKEN ? ` (token=${token})` : ""}`);
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
        const res = rooms.requestJoin(code, ws);
        if (!res.ok) {
          send(ws, { type: "error", message: res.error });
          return;
        }
        roomCode = code;
        role = "viewer";
        viewerId = res.viewerId;
        // 通知 viewer：等待 host 确认
        send(ws, { type: "join-pending" });
        // 通知 host：有人请求加入（等待确认）
        const host = rooms.getHost(code);
        send(host, { type: "join-request", viewerId });
        console.log(`[room ${code}] viewer#${viewerId} requested join (awaiting host)`);
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
        const code = normalizeCode(msg.code || roomCode);
        const host = code ? rooms.getHost(code) : null;
        if (host) {
          send(host, { type: "come-on", code });
          console.log(`[room ${code}] viewer#${viewerId} pls-join (喊TA)`);
        } else {
          send(ws, { type: "error", message: "对方不在线，无法提醒（可先点这里创建房间等 TA）" });
        }
        break;
      }

      default:
        send(ws, { type: "error", message: `未知消息类型: ${msg.type}` });
    }
  });

  ws.on("close", () => {
    allClients.delete(ws);
    if (!roomCode) return;
    const r = rooms.onDisconnect(roomCode, role, viewerId);
    if (r.removedHost) {
      // host 离开：释放房间 token，通知所有 viewer
      AuthManager.releaseTokens(roomCode);
      r.remainingViewers.forEach((v) => send(v, { type: "host-left" }));
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
  });

  ws.on("error", () => {});
});

server.listen(PORT, () => {
  console.log(`ScreenShare signaling server listening on :${PORT} (ws://<host>:${PORT}/ws)`);
});
