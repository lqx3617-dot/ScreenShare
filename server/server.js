/**
 * ScreenShare 信令服务器（腾讯会议式房间，V4 多客户端）
 *
 * 协议（JSON over WebSocket）：
 *   client -> server:
 *     { "type": "create", "code": "1234" }                       // 共享方创建会议
 *     { "type": "join",   "code": "1234" }                       // 观看方加入
 *     { "type": "relay",  "data": "<payload>", "viewerId": n }   // 中转信令（viewerId: host发往指定viewer）
 *     { "type": "ping" }
 *
 *   server -> client:
 *     { "type": "created", "code": "1234" }                      // 创建成功
 *     { "type": "joined",  "code": "1234", "viewerId": n }       // 加入成功
 *     { "type": "peer-ready" }                                   // 对端已加入（host 视角）
 *     { "type": "viewer-joined", "viewerId": n }                 // 新 viewer 加入（仅 host 收到）
 *     { "type": "relay",     "data": "<payload>", "viewerId": n }
 *     { "type": "viewer-left", "viewerId": n }                   // 某 viewer 离开（仅 host 收到）
 *     { "type": "host-left" }                                    // host 离开（所有 viewer 收到）
 *     { "type": "error",     "message": "..." }
 *
 * 行为：
 * - 会议 1 host + 多 viewer（从 1对1 升级为 1对多）
 * - host 离开：整房销毁，通知所有 viewer
 * - viewer 离开：仅移除该 viewer，通知 host
 */
"use strict";

const http = require("http");
const fs = require("fs");
const path = require("path");
const { WebSocketServer } = require("ws");
const RoomManager = require("./RoomManager");

const PORT = process.env.PORT || 8080;
// 诊断模式：DIAG=1 时打印 SDP/候选统计（默认关闭，转发零解析零日志最快）
const DIAG = process.env.DIAG === "1";

const server = http.createServer((req, res) => {
  // 崩溃日志上报：App Java 层崩溃 POST 到这里落盘
  if (req.method === "POST" && req.url.startsWith("/crash")) {
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
  res.writeHead(200, { "Content-Type": "text/plain; charset=utf-8" });
  res.end("ScreenShare signaling server is running");
});

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

wss.on("connection", (ws) => {
  let roomCode = null;
  let role = null; // "host" | "viewer"
  let viewerId = null;

  ws.on("message", (raw) => {
    let msg;
    try {
      msg = JSON.parse(raw.toString());
    } catch (e) {
      send(ws, { type: "error", message: "无效的消息格式" });
      return;
    }

    switch (msg.type) {
      case "create": {
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
        send(ws, { type: "created", code });
        console.log(`[room ${code}] created by host`);
        break;
      }

      case "join": {
        const code = normalizeCode(msg.code);
        const res = rooms.join(code, ws);
        if (!res.ok) {
          send(ws, { type: "error", message: res.error });
          return;
        }
        roomCode = code;
        role = "viewer";
        viewerId = res.viewerId;
        send(ws, { type: "joined", code, viewerId });
        // 通知 host：新 viewer 加入（携带 viewerId 供 host 建立指定连接）
        const host = rooms.getHost(code);
        send(host, { type: "viewer-joined", viewerId });
        console.log(`[room ${code}] viewer#${viewerId} joined`);
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

      default:
        send(ws, { type: "error", message: `未知消息类型: ${msg.type}` });
    }
  });

  ws.on("close", () => {
    if (!roomCode) return;
    const r = rooms.onDisconnect(roomCode, role, viewerId);
    if (r.removedHost) {
      // host 离开：通知所有 viewer
      r.remainingViewers.forEach((v) => send(v, { type: "host-left" }));
      console.log(`[room ${roomCode}] closed (host left, ${r.remainingViewers.length} viewer(s) disconnected)`);
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