/**
 * ScreenShare 信令服务器（腾讯会议式房间）
 *
 * 协议（JSON over WebSocket）：
 *   client -> server:
 *     { "type": "create", "code": "1234" }           // 共享方创建会议（4 位数字会议号）
 *     { "type": "join",   "code": "123456789" }     // 观看方加入会议
 *     { "type": "relay",  "data": "<payload>" }     // 中转信令数据（SDP/ICE，原样转发给对端）
 *     { "type": "ping" }
 *
 *   server -> client:
 *     { "type": "created",   "code": "123456789" }  // 会议创建成功
 *     { "type": "joined",    "code": "123456789" }  // 加入成功，等待对端
 *     { "type": "peer-ready" }                       // 对端已加入，可以开始交换信令
 *     { "type": "relay",     "data": "<payload>" }   // 对端转发来的信令数据
 *     { "type": "peer-left" }                        // 对端断开
 *     { "type": "error",     "message": "..." }
 *
 * 行为：
 * - 会议最多 2 人（1 个共享方 + 1 个观看方）
 * - 会议号不区分大小写（纯数字）
 * - 会议内任一方断开，通知另一方并清理会议
 */
"use strict";

const http = require("http");
const fs = require("fs");
const path = require("path");
const { WebSocketServer } = require("ws");

const PORT = process.env.PORT || 8080;
// 诊断模式：DIAG=1 时打印 SDP/候选统计（默认关闭，转发零解析零日志最快）
const DIAG = process.env.DIAG === "1";

const server = http.createServer((req, res) => {
  // 崩溃日志上报：App Java 层崩溃 POST 到这里落盘，便于定位真机闪退
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

// maxPayload 限制 512KB：信令消息都很小，防滥用大包拖慢转发
const wss = new WebSocketServer({ server, path: "/ws", maxPayload: 512 * 1024 });

// code(lowercase) -> { host: ws, join: ws }
const rooms = new Map();

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
  let role = null; // "host" | "join"

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
        if (!/^[0-9]{4}$/.test(code)) {
          send(ws, { type: "error", message: "会议号需为 4 位数字" });
          return;
        }
        if (rooms.has(code)) {
          send(ws, { type: "error", message: "会议号已被占用，请重试" });
          return;
        }
        rooms.set(code, { host: ws, join: null });
        roomCode = code;
        role = "host";
        send(ws, { type: "created", code });
        console.log(`[room ${code}] created by host`);
        break;
      }

      case "join": {
        const code = normalizeCode(msg.code);
        const room = rooms.get(code);
        if (!room) {
          send(ws, { type: "error", message: "会议号不存在或会议已结束" });
          return;
        }
        if (room.join) {
          send(ws, { type: "error", message: "会议已满（仅支持一对一）" });
          return;
        }
        room.join = ws;
        roomCode = code;
        role = "join";
        send(ws, { type: "joined", code });
        // 通知双方：对端已就绪
        send(room.host, { type: "peer-ready" });
        send(room.join, { type: "peer-ready" });
        console.log(`[room ${code}] joined by viewer`);
        break;
      }

      case "relay": {
        if (!roomCode || !role) {
          send(ws, { type: "error", message: "尚未加入房间" });
          return;
        }
        const room = rooms.get(roomCode);
        if (!room) {
          send(ws, { type: "error", message: "房间已失效" });
          return;
        }
        const target = role === "host" ? room.join : room.host;
        if (!target) {
          send(ws, { type: "error", message: "对端尚未加入" });
          return;
        }
        // 原样转发，不解析内容（默认零解析零日志，最快转发）
        const data = msg.data;
        if (DIAG) {
          // 诊断模式：解析并统计 SDP/候选类型
          const payloadLen = String(data || "").length;
          let host = 0, srflx = 0, relay = 0, sdpCand = 0;
          let mediaLines = [];
          try {
            const p = JSON.parse(data || "{}");
            const sdpBody = String(p.sdp && p.sdp.sdp || "");
            sdpCand = (sdpBody.match(/a=candidate/g) || []).length;
            mediaLines = (sdpBody.match(/^m=/gm) || []).slice(0, 6);
            const iceArr = Array.isArray(p.ice) ? p.ice : [];
            iceArr.forEach((c) => {
              const s = String(c && c.candidate || "");
              if (s.includes("typ host")) host++;
              else if (s.includes("typ srflx")) srflx++;
              else if (s.includes("typ relay")) relay++;
            });
          } catch (e) {}
          console.log(`[room ${roomCode}] relay ${role}->${role === "host" ? "join" : "host"} ${payloadLen}B | sdp a=candidate:${sdpCand} | media:[${mediaLines.join(" | ")}] | ice[]: host=${host} srflx=${srflx} relay=${relay}`);
        }
        send(target, { type: "relay", data });
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
    const room = rooms.get(roomCode);
    if (room) {
      const peer = role === "host" ? room.join : room.host;
      if (peer) send(peer, { type: "peer-left" });
      rooms.delete(roomCode);
      console.log(`[room ${roomCode}] closed (${role} left)`);
    }
  });

  ws.on("error", () => {});
});

server.listen(PORT, () => {
  console.log(`ScreenShare signaling server listening on :${PORT} (ws://<host>:${PORT}/ws)`);
});
