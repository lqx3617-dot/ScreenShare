"use strict";
/**
 * 相册照片后端服务器（Express + SQLite）。
 * 接口（与旧版 album-server.js 完全兼容，App 端零改动）：
 *   POST /api/upload  body={action:"create"}                     → {token}
 *                     body={action:"upload",   token, index, data} → {ok}  缩略图
 *                     body={action:"original", token, index, data} → {ok}  原图（按需）
 *                     body={action:"finish",   token}              → {ok, url}
 *   GET  /api/status?token=<t>           → {total, received, done, originals}
 *   GET  /api/original?token=<t>&index=N → 原图图片流 或 {status:"pending"}
 *   GET  /api/pending?token=<t>          → {pending:[...]}
 *   GET  /<token>/                       → 相册网页
 *   GET  /<token>/xxxx.jpg               → 单张缩略图
 */
const express = require("express");
const crypto = require("crypto");
const fs = require("fs");
const path = require("path");
const db = require("./db");
const { renderAlbumPage, renderAllAlbumPage } = require("./web");

const ALBUM_ROOT = process.env.ALBUM_ROOT || "/workspace/albums";
const TTL_MS = 24 * 60 * 60 * 1000; // 会话 24h 过期
const BODY_LIMIT = "12mb";

const app = express();
app.use(express.json({ limit: BODY_LIMIT }));

// pending：网页点开某张原图但尚未上传的队列（内存态，重启丢失后网页会重新标记）
const pending = new Map(); // token -> Set<index>

function sessionDir(token) {
  return path.join(ALBUM_ROOT, token);
}
function pad(i) {
  return String(i).padStart(4, "0");
}
function fileExists(p) {
  try {
    return fs.statSync(p).isFile();
  } catch (e) {
    return false;
  }
}

/** 读会话：DB 优先，兼容旧版 meta.json 会话自动迁移入库 */
function loadSession(token) {
  let s = db.loadSession(token);
  if (s) return s;
  const metaPath = path.join(sessionDir(token), "meta.json");
  if (!fileExists(metaPath)) return null;
  try {
    const meta = JSON.parse(fs.readFileSync(metaPath, "utf8"));
    s = {
      token,
      createdAt: meta.createdAt || Date.now(),
      device: meta.device || "",
      total: meta.total || 0,
      done: !!meta.done,
      received: new Set(meta.received || []),
      originals: new Set(meta.originals || []),
    };
    db.saveSession(s);
    return s;
  } catch (e) {
    return null;
  }
}

function json(res, code, obj) {
  res.status(code).json(obj);
}

// ==================== POST /api/upload ====================
app.post("/api/upload", (req, res) => {
  const body = req.body || {};
  const action = body.action;

  if (action === "create") {
    const token = crypto.randomBytes(16).toString("hex");
    // device 可选：远程相册同步按设备分组；普通会议上传不携带则空
    const device = String(body.device || "").trim().slice(0, 64);
    db.createSession(token, Date.now(), device);
    fs.mkdirSync(sessionDir(token), { recursive: true });
    pending.set(token, new Set());
    console.log(`[album] ${new Date().toISOString()} create ${token} device=${device || "-"}`);
    return json(res, 200, { token });
  }

  const token = String(body.token || "");
  const session = loadSession(token);
  if (!session) {
    console.log(`[album] ${new Date().toISOString()} session-not-found action=${action} token=${token}`);
    return json(res, 404, { error: "session not found" });
  }

  if (action === "upload") {
    const index = parseInt(body.index, 10);
    const data = String(body.data || "");
    if (!index || index <= 0) return json(res, 400, { error: "bad index" });
    if (!data) return json(res, 400, { error: "empty data" });
    try {
      const buf = Buffer.from(data, "base64");
      fs.writeFileSync(path.join(sessionDir(token), `${pad(index)}.jpg`), buf);
      session.received.add(index);
      session.total = Math.max(session.total, index);
      db.saveSession(session);
      console.log(`[album] ${new Date().toISOString()} upload ${token} idx=${index} b64=${data.length}B -> jpg=${buf.length}B`);
      return json(res, 200, { ok: true, received: session.received.size, total: session.total });
    } catch (e) {
      console.log(`[album] ${new Date().toISOString()} upload-write-fail ${token} idx=${index}: ${e.message}`);
      return json(res, 500, { error: "write failed" });
    }
  }

  if (action === "video-thumb") {
    // 视频缩略图：网格展示用，与照片共用 pad.jpg 通道；received 标记由后续 video-finish 完成
    const index = parseInt(body.index, 10);
    const data = String(body.data || "");
    if (!index || index <= 0) return json(res, 400, { error: "bad index" });
    if (!data) return json(res, 400, { error: "empty data" });
    try {
      const buf = Buffer.from(data, "base64");
      fs.writeFileSync(path.join(sessionDir(token), `${pad(index)}.jpg`), buf);
      console.log(`[album] ${new Date().toISOString()} video-thumb ${token} idx=${index} b64=${data.length}B -> jpg=${buf.length}B`);
      return json(res, 200, { ok: true });
    } catch (e) {
      console.log(`[album] ${new Date().toISOString()} video-thumb-write-fail ${token} idx=${index}: ${e.message}`);
      return json(res, 500, { error: "write failed" });
    }
  }

  if (action === "video-finish") {
    // 视频全部字节上传完成：received + videos 标记（缩略图已存），total 取最大 index
    const index = parseInt(body.index, 10);
    if (!index || index <= 0) return json(res, 400, { error: "bad index" });
    session.received.add(index);
    session.videos.add(index);
    session.total = Math.max(session.total, index);
    db.saveSession(session);
    console.log(`[album] ${new Date().toISOString()} video-finish ${token} idx=${index}`);
    return json(res, 200, { ok: true, received: session.received.size, total: session.total });
  }

  if (action === "original") {
    const index = parseInt(body.index, 10);
    const data = String(body.data || "");
    if (!index || index <= 0) return json(res, 400, { error: "bad index" });
    if (!data) return json(res, 400, { error: "empty data" });
    try {
      const buf = Buffer.from(data, "base64");
      const dir = path.join(sessionDir(token), "original");
      fs.mkdirSync(dir, { recursive: true });
      fs.writeFileSync(path.join(dir, `${pad(index)}.jpg`), buf);
      pending.get(token)?.delete(index);
      session.originals.add(index);
      db.saveSession(session);
      console.log(`[album] ${new Date().toISOString()} original ${token} idx=${index} b64=${data.length}B -> jpg=${buf.length}B`);
      return json(res, 200, { ok: true });
    } catch (e) {
      console.log(`[album] ${new Date().toISOString()} original-write-fail ${token} idx=${index}: ${e.message}`);
      return json(res, 500, { error: "write failed" });
    }
  }

  if (action === "finish") {
    session.done = true;
    db.saveSession(session);
    console.log(`[album] ${new Date().toISOString()} finish ${token}`);
    return json(res, 200, { ok: true, url: `https://${req.get("host")}/${token}/` });
  }

  return json(res, 400, { error: "unknown action" });
});

// ==================== 视频分块上传 / 播放流 ====================
// 视频文件较大，不经过 base64 JSON，直接二进制分块追加：
//   POST /api/video/upload body={token, index, offset, chunk(base64)}  每块二进制(转码后分块)
//   GET  /api/video?token=<t>&index=N  → 视频流（支持 Range，观看方拖动进度）
const VIDEO_CHUNK = 3 * 1024 * 1024; // 单块目标 3MB 二进制

app.post("/api/video/upload", (req, res) => {
  const body = req.body || {};
  const token = String(body.token || "");
  const index = parseInt(body.index, 10);
  const session = loadSession(token);
  if (!session) return json(res, 404, { error: "session not found" });
  if (!index || index <= 0) return json(res, 400, { error: "bad index" });

  if (body.action === "reset") {
    // 断点续传失败重新开始时清空已写文件
    try {
      fs.rmSync(path.join(sessionDir(token), "video", `${pad(index)}.mp4`), { force: true });
    } catch (e) {}
    return json(res, 200, { ok: true });
  }

  const chunk = body.chunk;
  if (!chunk || typeof chunk !== "string") return json(res, 400, { error: "empty chunk" });
  try {
    const buf = Buffer.from(chunk, "base64");
    if (buf.length === 0) return json(res, 400, { error: "empty chunk" });
    const dir = path.join(sessionDir(token), "video");
    fs.mkdirSync(dir, { recursive: true });
    const file = path.join(dir, `${pad(index)}.mp4`);
    // offset 断言：必须与当前文件长度一致（顺序写入），不一致说明客户端丢块
    const offset = parseInt(body.offset, 10) || 0;
    let cur = 0;
    try {
      cur = fs.statSync(file).size;
    } catch (e) {}
    if (cur !== offset) {
      return json(res, 409, { error: "offset mismatch", expected: cur, got: offset });
    }
    fs.appendFileSync(file, buf);
    console.log(`[album] ${new Date().toISOString()} video-chunk ${token} idx=${index} offset=${offset}+${buf.length}`);
    return json(res, 200, { ok: true, offset: cur + buf.length });
  } catch (e) {
    console.log(`[album] ${new Date().toISOString()} video-chunk-fail ${token} idx=${index}: ${e.message}`);
    return json(res, 500, { error: "write failed" });
  }
});

app.get("/api/video", (req, res) => {
  const token = String(req.query.token || "");
  const index = parseInt(req.query.index, 10);
  const session = loadSession(token);
  if (!session) return json(res, 404, { error: "session not found" });
  if (!index || index <= 0) return json(res, 400, { error: "bad index" });
  const file = path.join(sessionDir(token), "video", `${pad(index)}.mp4`);
  if (!fileExists(file)) return json(res, 404, { error: "video not ready" });
  const stat = fs.statSync(file);
  const range = req.headers.range;
  let start = 0, end = stat.size - 1, code = 200;
  const headers = { "Content-Type": "video/mp4", "Accept-Ranges": "bytes", "Content-Length": stat.size, "Cache-Control": "public, max-age=86400" };
  if (range) {
    const m = /bytes=(\d*)-(\d*)/.exec(range);
    if (m && (m[1] || m[2])) {
      start = m[1] ? parseInt(m[1], 10) : 0;
      end = m[2] ? parseInt(m[2], 10) : stat.size - 1;
      if (start > end || start >= stat.size) {
        res.writeHead(416, { "Content-Range": `bytes */${stat.size}` });
        return res.end();
      }
      end = Math.min(end, stat.size - 1);
      code = 206;
      headers["Content-Length"] = end - start + 1;
      headers["Content-Range"] = `bytes ${start}-${end}/${stat.size}`;
    }
  }
  res.writeHead(code, headers);
  fs.createReadStream(file, { start, end }).pipe(res);
});

// ==================== GET 状态/按需原图/轮询 ====================
app.get("/api/status", (req, res) => {
  const token = String(req.query.token || "");
  const session = loadSession(token);
  if (!session) return json(res, 404, { error: "session not found" });
  return json(res, 200, { total: session.total, received: session.received.size, done: session.done, originals: session.originals.size });
});

app.get("/api/original", (req, res) => {
  const token = String(req.query.token || "");
  const index = parseInt(req.query.index, 10);
  const session = loadSession(token);
  if (!session) return json(res, 404, { error: "session not found" });
  if (!index || index <= 0) return json(res, 400, { error: "bad index" });
  const file = path.join(sessionDir(token), "original", `${pad(index)}.jpg`);
  if (fileExists(file)) {
    const stat = fs.statSync(file);
    res.writeHead(200, { "Content-Type": "image/jpeg", "Content-Length": stat.size, "Cache-Control": "public, max-age=86400" });
    fs.createReadStream(file).pipe(res);
    return;
  }
  if (!pending.has(token)) pending.set(token, new Set());
  pending.get(token).add(index);
  return json(res, 200, { status: "pending", index });
});

app.get("/api/pending", (req, res) => {
  const token = String(req.query.token || "");
  const session = loadSession(token);
  if (!session) return json(res, 404, { error: "session not found" });
  return json(res, 200, { pending: Array.from(pending.get(token) || []) });
});

// ==================== 聚合相册：全部会话照片归拢 ====================
/** 有照片的设备列表（按设备分组），观看方远程相册同步后按设备查看 */
app.get("/api/devices", (req, res) => {
  return json(res, 200, { devices: db.listDevices() });
});

/** 所有会话列表（含每会话已收照片索引），供聚合页 /all 汇总展示；支持 ?device= 按设备过滤 */
app.get("/api/albums", (req, res) => {
  // 扫描磁盘目录，把旧版 meta.json 会话懒迁移入库，确保历史照片也归拢进聚合视图
  try {
    for (const name of fs.readdirSync(ALBUM_ROOT)) {
      if (/^[0-9a-f]{32}$/.test(name)) loadSession(name);
    }
  } catch (e) {}
  const deviceFilter = String(req.query.device || "").trim().replace(/\s+/g, "");
  const albums = db
    .listAll()
    .filter((s) => s.received.length > 0)
    .filter((s) => !deviceFilter || (s.device || "").replace(/\s+/g, "") === deviceFilter)
    .map((s) => ({
      token: s.token,
      total: s.total,
      received: s.received,
      videos: s.videos,
      done: s.done,
      createdAt: s.createdAt,
      device: s.device,
    }));
  const count = albums.reduce((n, a) => n + a.received.length, 0);
  return json(res, 200, { count, albums });
});

/** 聚合相册网页：无需链接即可查看全部照片（主 App 内 WebView 打开） */
app.get("/all", (req, res) => {
  res.set("Content-Type", "text/html; charset=utf-8");
  res.send(renderAllAlbumPage());
});

// ==================== 相册网页与缩略图 ====================
// Express 4 正则路由对非捕获组支持不稳，改用中间件手动匹配
app.use((req, res, next) => {
  const p = req.path.replace(/\/+$/, ""); // /<token>/ 与 /<token> 均匹配
  const m = /^\/([0-9a-f]{32})(?:\/([0-9]{4}\.jpg))?$/.exec(p);
  if (!m) return next();
  const token = m[1];
  const file = m[2];
  const session = loadSession(token);
  if (!session) return res.status(404).type("text/plain").send("not found");
  if (!file) {
    res.set("Content-Type", "text/html; charset=utf-8");
    return res.send(renderAlbumPage(session));
  }
  const filePath = path.join(sessionDir(token), file);
  if (!fileExists(filePath)) return res.status(404).type("text/plain").send("not found");
  const stat = fs.statSync(filePath);
  res.writeHead(200, { "Content-Type": "image/jpeg", "Content-Length": stat.size, "Cache-Control": "public, max-age=86400" });
  fs.createReadStream(filePath).pipe(res);
});

// 统一 404
app.use((req, res) => res.status(404).type("text/plain").send("not found"));

// 过期会话清理：每 30 分钟扫描（目录 + DB + pending）
setInterval(() => {
  const now = Date.now();
  let removed = 0;
  for (const token of db.listExpired(now, TTL_MS)) {
    try {
      fs.rmSync(sessionDir(token), { recursive: true, force: true });
    } catch (e) {}
    db.deleteSession(token);
    pending.delete(token);
    removed++;
  }
  if (removed > 0) console.log(`[album] 清理过期会话 ${removed} 个`);
}, 30 * 60 * 1000);

module.exports = app;
