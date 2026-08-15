/**
 * 远程相册同步中继服务器（独立于会议信令）。
 * 端口 8097。共享方设备注册 8 位设备码并保持长连接；观看方经此转发开启同步指令。
 * 消息协议：
 *   {type:"relay-register", deviceCode, deviceName, ip}  共享方注册（deviceCode 格式化 XXXX XXXX）
 *   {type:"relay-sync",    deviceCode, action}           观看方请求（action: start/status）
 *   {type:"relay-sync-ack", deviceCode, syncing, synced, total, ip}  共享方回执
 *   {type:"relay-offline", deviceCode}                   共享方离线通知（观看方触发时若离线返回）
 *   {type:"ping"} -> {type:"pong"}
 */
"use strict";
const http = require("http");
const PORT = process.env.PORT || 8097;

// 是否启用 WebSocket 握手后的消息帧解析（本服务使用精简 ws 实现，避免引入依赖）
// deviceCode(normalized) -> connection 信息
const registry = new Map(); // deviceCode(8位无空格) -> { ws, deviceName, ip, registeredAt }

function normalizeCode(raw) {
  return String(raw || "").replace(/[^0-9A-Za-z]/g, "").toUpperCase();
}

function isValidCode(code) {
  return /^[0-9A-Z]{8}$/.test(code);
}

/** 生成随机 8 位设备码（避免与冲突） */
function genCode() {
  const chars = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
  let s = "";
  for (let i = 0; i < 8; i++) s += chars[Math.floor(Math.random() * chars.length)];
  return s;
}

function sendJson(ws, obj) {
  try {
    if (ws && ws.readyState === 1) ws.send(JSON.stringify(obj));
  } catch (e) {}
}

// ==================== 精简 WebSocket 服务器 ====================
// 使用 node 原生 http upgrade + 手写 ws 帧（服务端对客户端发送掩码帧进行解包）
const crypto = require("crypto");

function acceptWs(req, socket) {
  const key = req.headers["sec-websocket-key"];
  if (!key) { socket.destroy(); return null; }
  const accept = crypto.createHash("sha1")
    .update(key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11")
    .digest("base64");
  socket.write(
    "HTTP/1.1 101 Switching Protocols\r\n" +
    "Upgrade: websocket\r\n" +
    "Connection: Upgrade\r\n" +
    `Sec-WebSocket-Accept: ${accept}\r\n\r\n`
  );
  return socket;
}

/** 读取一个 ws 文本帧的 payload（处理分片与掩码）。返回 {text, opcode} 或 null（需更多数据/连接关闭） */
function createWsParser(onText, onClose) {
  let buf = Buffer.alloc(0);
  let fragmentedText = "";
  return {
    push(chunk) {
      buf = Buffer.concat([buf, chunk]);
      while (true) {
        if (buf.length < 2) return;
        const fin = (buf[0] & 0x80) !== 0;
        const opcode = buf[0] & 0x0f;
        let len = buf[1] & 0x7f;
        let offset = 2;
        if (len === 126) {
          if (buf.length < 4) return;
          len = buf.readUInt16BE(2);
          offset = 4;
        } else if (len === 127) {
          if (buf.length < 10) return;
          len = Number(buf.readBigUInt64BE(2));
          offset = 10;
        }
        const masked = (buf[1] & 0x80) !== 0;
        const maskLen = masked ? 4 : 0;
        if (buf.length < offset + maskLen + len) return;
        let payload = buf.subarray(offset + maskLen, offset + maskLen + len);
        if (masked) {
          const mask = buf.subarray(offset, offset + maskLen);
          payload = Buffer.from(payload);
          for (let i = 0; i < payload.length; i++) payload[i] ^= mask[i % 4];
        }
        buf = buf.subarray(offset + maskLen + len);
        if (opcode === 0x8) { onClose(); return; }
        if (opcode === 0x9) { return; } // ping 忽略
        if (opcode === 0x2) { /* binary 忽略 */ continue; }
        fragmentedText += payload.toString("utf8");
        if (fin) {
          onText(fragmentedText);
          fragmentedText = "";
        }
      }
    },
  };
}

const server = http.createServer((req, res) => {
  res.writeHead(200, { "Content-Type": "application/json" });
  res.end(JSON.stringify({ ok: true, service: "album-sync-relay", online: registry.size, port: PORT }));
});

server.on("upgrade", (req, socket) => {
  if ((req.headers.upgrade || "").toLowerCase() !== "websocket") { socket.destroy(); return; }
  acceptWs(req, socket);

  let deviceCode = null;
  let role = null; // "host" | "viewer"
  let ip = "";

  const send = (obj) => sendJson({ readyState: 1, send: (s) => socket.write(encodeWsText(s)) }, obj);

  const parser = createWsParser(
    (text) => {
      let msg;
      try { msg = JSON.parse(text); } catch (e) { return; }
      handleMessage(msg, send);
    },
    () => { try { socket.destroy(); } catch (e) {} }
  );

  function handleMessage(msg, send) {
    switch (msg.type) {
      case "relay-register": {
        if (role && role === "host") return; // 已注册
        role = "host";
        const want = normalizeCode(msg.deviceCode);
        const code = want && isValidCode(want) ? want : genCode();
        deviceCode = code;
        ip = String(msg.ip || "");
        registry.set(code, { ws: socket, deviceName: String(msg.deviceName || "").slice(0, 40), ip, registeredAt: Date.now() });
        console.log(`[relay] host registered ${code} ip=${ip} name=${msg.deviceName || ""} online=${registry.size}`);
        send({ type: "relay-registered", deviceCode: formatCode(code), ip });
        break;
      }
      case "relay-sync": {
        if (role && role === "host") break; // host 不应发此类型
        role = "viewer";
        const code = normalizeCode(msg.deviceCode);
        const target = registry.get(code);
        if (!target) {
          send({ type: "relay-sync-ack", deviceCode: msg.deviceCode, error: "设备不在线或设备码无效" });
          return;
        }
        console.log(`[relay] viewer->host ${code} action=${msg.action}`);
        sendJson({ readyState: 1, send: (s) => target.ws.write(encodeWsText(s)) },
          { type: "relay-sync", deviceCode: formatCode(code), action: msg.action, from: "viewer" });
        break;
      }
      case "relay-sync-ack": {
        // host 回执，转发给对应的 viewer？单连接转发无法定位 viewer。
        // 简化：host 回执只用于确认（观看方另经相册服务器轮询），此处记录日志
        console.log(`[relay] host ack ${msg.deviceCode} syncing=${msg.syncing}`);
        break;
      }
      case "ping": {
        send({ type: "pong" });
        break;
      }
      default: {
        send({ type: "error", message: `未知消息类型: ${msg.type}` });
      }
    }
  }

  socket.on("data", (chunk) => parser.push(chunk));
  socket.on("close", () => {
    if (deviceCode && registry.get(deviceCode)?.ws === socket) {
      registry.delete(deviceCode);
      console.log(`[relay] host offline ${deviceCode} online=${registry.size}`);
    }
  });
  socket.on("error", () => { try { socket.destroy(); } catch (e) {} });
});

/** 将 8 位码格式化为 XXXX XXXX */
function formatCode(code) {
  return code.length === 8 ? `${code.slice(0, 4)} ${code.slice(4)}` : code;
}

/** 服务端发送 ws 文本帧（无掩码） */
function encodeWsText(text) {
  const payload = Buffer.from(String(text), "utf8");
  const len = payload.length;
  let header;
  if (len < 126) {
    header = Buffer.from([0x81, len]);
  } else if (len < 65536) {
    header = Buffer.alloc(4);
    header[0] = 0x81; header[1] = 126;
    header.writeUInt16BE(len, 2);
  } else {
    header = Buffer.alloc(10);
    header[0] = 0x81; header[1] = 127;
    header.writeBigUInt64BE(BigInt(len), 2);
  }
  return Buffer.concat([header, payload]);
}

server.listen(PORT, () => {
  console.log(`Album sync relay listening on :${PORT}`);
});
