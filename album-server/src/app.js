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
const fsp = fs.promises;
const path = require("path");
const rateLimit = require("express-rate-limit");
const db = require("./db");
const { renderAlbumPage, renderAllAlbumPage } = require("./web");

const ALBUM_ROOT = process.env.ALBUM_ROOT || "/workspace/albums";
// 相册访问密钥：客户端（主 App 上传端 / 相册查看 App）请求须携带 x-album-key header。
// 未配置密钥时保持开放（本地开发），生产必须设置。
const ALBUM_KEY = process.env.ALBUM_KEY || "";
if (!ALBUM_KEY) {
  console.warn("[album] ⚠️ 未配置 ALBUM_KEY 环境变量！当前为全开放模式（任何人可上传/查看/删除相册），生产环境必须设置。");
}
const TTL_MS = 24 * 60 * 60 * 1000; // 会话 24h 过期
const BODY_LIMIT = "12mb";

const app = express();
app.use(express.json({ limit: BODY_LIMIT }));

// 速率限制：写操作（上传/去重/删除）30 次/分钟，读操作 60 次/分钟，防暴力破解与 DoS
const writeLimiter = rateLimit({
  windowMs: 60 * 1000,
  max: 30,
  standardHeaders: true,
  legacyHeaders: false,
  message: { error: "too many requests" },
});
const readLimiter = rateLimit({
  windowMs: 60 * 1000,
  max: 60,
  standardHeaders: true,
  legacyHeaders: false,
  message: { error: "too many requests" },
});

// 统一安全响应头：防 MIME 嗅探执行、点击劫持、CSP、HSTS（存储型 XSS 缓解）
app.use((req, res, next) => {
  res.set("X-Content-Type-Options", "nosniff");
  res.set("X-Frame-Options", "SAMEORIGIN");
  res.set("Referrer-Policy", "no-referrer");
  res.set("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
  res.set("Content-Security-Policy", "default-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; script-src 'self' 'unsafe-inline'");
  if (req.secure || req.headers["x-forwarded-proto"] === "https") {
    res.set("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
  }
  next();
});

// index 范围校验：照片 1..9999（与缩略图路由 [0-9]{4} 对齐），视频 1000000+（App 端 VIDEO_INDEX_BASE）
function validPhotoIndex(index) {
  return Number.isInteger(index) && index >= 1 && index <= 9999;
}
function validVideoIndex(index) {
  return Number.isInteger(index) && index >= 1000000 && index <= 1999999;
}

/** JPEG 魔数校验：检查 buffer 前 2 字节是否为 FF D8，拒绝非图片内容（防存储型 XSS 与伪装文件） */
function isJpeg(buf) {
  return buf.length >= 2 && buf[0] === 0xFF && buf[1] === 0xD8;
}

// ==================== 访问鉴权中间件 ====================
// /api/*（App 用 OkHttp/Coil，可带自定义 header）：密钥仅经 header `x-album-key` 传递，
// 废弃 query ?key=（避免密钥进 URL 日志/Referer 泄露）。
// 网页 /all、/<token>/、/<token>/<pad>.jpg（浏览器 <img> 无法带自定义 header）：
// 保留 query+header 双通道，否则网页缩略图全部 401。
function auth(req, res, next) {
  if (!ALBUM_KEY) return next();
  const fromHeader = String(req.headers["x-album-key"] || "");
  if (fromHeader === ALBUM_KEY) return next();
  const isApi = req.path.startsWith("/api/");
  if (!isApi && String(req.query.key || "") === ALBUM_KEY) return next();
  return res.status(401).type("text/plain").send("unauthorized");
}
app.use(auth);

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
app.post("/api/upload", writeLimiter, async (req, res) => {
  const body = req.body || {};
  const action = body.action;

  if (action === "create") {
    const token = crypto.randomBytes(16).toString("hex");
    // device 可选：远程相册同步按设备分组；普通会议上传不携带则空
    const device = String(body.device || "").trim().slice(0, 64);
    db.createSession(token, Date.now(), device);
    try {
      await fsp.mkdir(sessionDir(token), { recursive: true });
    } catch (e) {}
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
    if (!validPhotoIndex(index)) return json(res, 400, { error: "bad index" });
    if (!data) return json(res, 400, { error: "empty data" });
    try {
      const buf = Buffer.from(data, "base64");
      if (!isJpeg(buf)) {
        console.log(`[album] ${new Date().toISOString()} upload-reject ${token} idx=${index}: 非 JPEG 数据`);
        return json(res, 400, { error: "invalid image data" });
      }
      await fsp.writeFile(path.join(sessionDir(token), `${pad(index)}.jpg`), buf);
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
    if (!validVideoIndex(index)) return json(res, 400, { error: "bad index" });
    if (!data) return json(res, 400, { error: "empty data" });
    try {
      const buf = Buffer.from(data, "base64");
      if (!isJpeg(buf)) {
        console.log(`[album] ${new Date().toISOString()} video-thumb-reject ${token} idx=${index}: 非 JPEG 数据`);
        return json(res, 400, { error: "invalid image data" });
      }
      await fsp.writeFile(path.join(sessionDir(token), `${pad(index)}.jpg`), buf);
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
    if (!validVideoIndex(index)) return json(res, 400, { error: "bad index" });
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
    if (!validPhotoIndex(index)) return json(res, 400, { error: "bad index" });
    if (!data) return json(res, 400, { error: "empty data" });
    try {
      const buf = Buffer.from(data, "base64");
      if (!isJpeg(buf)) {
        console.log(`[album] ${new Date().toISOString()} original-reject ${token} idx=${index}: 非 JPEG 数据`);
        return json(res, 400, { error: "invalid image data" });
      }
      const dir = path.join(sessionDir(token), "original");
      await fsp.mkdir(dir, { recursive: true });
      await fsp.writeFile(path.join(dir, `${pad(index)}.jpg`), buf);
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

app.post("/api/video/upload", writeLimiter, async (req, res) => {
  const body = req.body || {};
  const token = String(body.token || "");
  const index = parseInt(body.index, 10);
  const session = loadSession(token);
  if (!session) return json(res, 404, { error: "session not found" });
  if (!validVideoIndex(index)) return json(res, 400, { error: "bad index" });

  if (body.action === "reset") {
    // 断点续传失败重新开始时清空已写文件
    try {
      await fsp.rm(path.join(sessionDir(token), "video", `${pad(index)}.mp4`), { force: true });
    } catch (e) {}
    return json(res, 200, { ok: true });
  }

  const chunk = body.chunk;
  if (!chunk || typeof chunk !== "string") return json(res, 400, { error: "empty chunk" });
  try {
    const buf = Buffer.from(chunk, "base64");
    if (buf.length === 0) return json(res, 400, { error: "empty chunk" });
    const dir = path.join(sessionDir(token), "video");
    await fsp.mkdir(dir, { recursive: true });
    const file = path.join(dir, `${pad(index)}.mp4`);
    // offset 断言：必须与当前文件长度一致（顺序写入），不一致说明客户端丢块
    const offset = parseInt(body.offset, 10) || 0;
    let cur = 0;
    try {
      cur = (await fsp.stat(file)).size;
    } catch (e) {}
    if (cur !== offset) {
      return json(res, 409, { error: "offset mismatch", expected: cur, got: offset });
    }
    await fsp.appendFile(file, buf);
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
  if (!validVideoIndex(index)) return json(res, 400, { error: "bad index" });
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
      // 钳制非法/越界 Range（parseInt 可能 NaN，需显式校验），防流错误与负 Content-Length
      if (!Number.isFinite(start) || !Number.isFinite(end)) start = 0, end = stat.size - 1;
      start = Math.max(0, Math.min(start, stat.size - 1));
      end = Math.max(start, Math.min(end, stat.size - 1));
      if (start > 0 || end < stat.size - 1) {
        code = 206;
        headers["Content-Length"] = end - start + 1;
        headers["Content-Range"] = `bytes ${start}-${end}/${stat.size}`;
      }
    }
  }
  res.writeHead(code, headers);
  const stream = fs.createReadStream(file, { start, end });
  stream.on("error", (e) => { try { res.destroy(); } catch (_) {} });
  stream.pipe(res);
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
  if (!validPhotoIndex(index)) return json(res, 400, { error: "bad index" });
  const file = path.join(sessionDir(token), "original", `${pad(index)}.jpg`);
  if (fileExists(file)) {
    const stat = fs.statSync(file);
    res.writeHead(200, { "Content-Type": "image/jpeg", "Content-Length": stat.size, "Cache-Control": "public, max-age=86400" });
    const stream = fs.createReadStream(file);
    stream.on("error", (e) => { try { res.destroy(); } catch (_) {} });
    stream.pipe(res);
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
// ==================== 聚合相册：全部会话照片归拢 ====================

/**
 * 照片重复检测与删除：
 *   POST /api/dedup
 * 扫描全部会话的照片缩略图（视频项跳过），按文件内容 md5 分组；
 * 对每组重复照片保留「较清晰」的一份（优先级：有原图 > 缩略图更大 > 会话更早 > 序号更小），
 * 删除其余照片的缩略图与原图文件，并从 DB received/originals 中移除。
 * 返回 {ok, groups, removed:[{token,index}], freedBytes}。
 */
app.post("/api/dedup", writeLimiter, async (req, res) => {
  try {
    const sessions = db.listAll();
    const byMd5 = new Map(); // md5 -> [{token,index,thumbSize,hasOriginal,createdAt}]
    let scanned = 0;
    const MAX_SCAN = 5000; // 单次去重扫描上限，防全库大相册 OOM/CPU DoS
    for (const s of sessions) {
      if (scanned >= MAX_SCAN) break;
      const dir = sessionDir(s.token);
      const vids = s.videos || [];
      const isVideo = (i) => (Array.isArray(vids) ? vids.indexOf(i) >= 0 : vids.has(i));
      for (const idx of s.received) {
        if (scanned >= MAX_SCAN) break;
        if (isVideo(idx)) continue; // 视频不参与照片去重
        const thumb = path.join(dir, `${pad(idx)}.jpg`);
        if (!fileExists(thumb)) continue;
        let buf;
        try {
          const st = fs.statSync(thumb);
          if (st.size > 8 * 1024 * 1024) continue; // 单张超大文件跳过，防内存耗尽
          buf = fs.readFileSync(thumb);
        } catch (e) {
          continue;
        }
        scanned++;
        const md5 = crypto.createHash("md5").update(buf).digest("hex");
        const hasOriginal = fileExists(path.join(dir, "original", `${pad(idx)}.jpg`));
        if (!byMd5.has(md5)) byMd5.set(md5, []);
        byMd5.get(md5).push({ token: s.token, index: idx, thumbSize: buf.length, hasOriginal, createdAt: s.createdAt });
      }
    }

    const removed = [];
    let freedBytes = 0;
    let groups = 0;
    for (const list of byMd5.values()) {
      if (list.length < 2) continue;
      groups++;
      // 保留评分：有原图 +1e9，其次缩略图字节数，再按创建时间/序号取早
      list.sort((a, b) => {
        const pa = (a.hasOriginal ? 1 : 0) * 1e9 + a.thumbSize;
        const pb = (b.hasOriginal ? 1 : 0) * 1e9 + b.thumbSize;
        if (pa !== pb) return pb - pa;
        if (a.createdAt !== b.createdAt) return a.createdAt - b.createdAt;
        return a.index - b.index;
      });
      for (let i = 1; i < list.length; i++) {
        const it = list[i];
        const dir = sessionDir(it.token);
        const files = [path.join(dir, `${pad(it.index)}.jpg`), path.join(dir, "original", `${pad(it.index)}.jpg`)];
        for (const f of files) {
          try {
            if (fileExists(f)) freedBytes += fs.statSync(f).size;
            await fsp.rm(f, { force: true });
          } catch (e) {}
        }
        // 更新 DB：从 received/originals 移除该序号
        const s = loadSession(it.token);
        if (s) {
          s.received.delete(it.index);
          s.originals.delete(it.index);
          db.saveSession(s);
        }
        removed.push({ token: it.token, index: it.index });
      }
    }
    console.log(`[album] ${new Date().toISOString()} dedup groups=${groups} removed=${removed.length} freed=${freedBytes}B scanned=${scanned}`);
    return json(res, 200, { ok: true, groups, removed, freedBytes, scanned });
  } catch (e) {
    console.log(`[album] ${new Date().toISOString()} dedup-fail: ${e.message}`);
    return json(res, 500, { error: "dedup failed" });
  }
});

/**
 * 手动删除一张照片/视频：删除该序号的所有文件（缩略图、原图、视频），
 * 并从 DB received/originals/videos 移除。返回 {ok}。
 *   POST /api/photo/delete body={token, index}
 */
app.post("/api/photo/delete", writeLimiter, async (req, res) => {
  const token = String((req.body || {}).token || "");
  const index = parseInt((req.body || {}).index, 10);
  const session = loadSession(token);
  if (!session) return json(res, 404, { error: "session not found" });
  // 照片或视频 index 均可删
  if (!validPhotoIndex(index) && !validVideoIndex(index)) return json(res, 400, { error: "bad index" });
  const dir = sessionDir(token);
  // 删除缩略图 / 原图 / 视频文件
  const files = [
    path.join(dir, `${pad(index)}.jpg`),
    path.join(dir, "original", `${pad(index)}.jpg`),
    path.join(dir, "video", `${pad(index)}.mp4`),
  ];
  for (const f of files) {
    try {
      await fsp.rm(f, { force: true });
    } catch (e) {}
  }
  // 从 DB 移除
  session.received.delete(index);
  session.originals.delete(index);
  session.videos.delete(index);
  db.saveSession(session);
  console.log(`[album] ${new Date().toISOString()} photo-delete ${token} idx=${index} (剩 ${session.received.size})`);
  return json(res, 200, { ok: true, received: session.received.size });
});

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
  // 网页需把 key 传给页面（页面内 <img> 无法带 header），header 与 query 双通道
  const key = String(req.query.key || req.headers["x-album-key"] || "");
  res.send(renderAllAlbumPage(key));
});

// ==================== 相册网页与缩略图 ====================
// Express 4 正则路由对非捕获组支持不稳，改用中间件手动匹配
app.use((req, res, next) => {
  const p = req.path.replace(/\/+$/, ""); // /<token>/ 与 /<token> 均匹配
  // 文件名 4-7 位数字：照片缩略图 0001..9999，视频缩略图 1000000+（App 端 VIDEO_INDEX_BASE）
  const m = /^\/([0-9a-f]{32})(?:\/([0-9]{4,7}\.jpg))?$/.exec(p);
  if (!m) return next();
  const token = m[1];
  const file = m[2];
  const session = loadSession(token);
  if (!session) return res.status(404).type("text/plain").send("not found");
  if (!file) {
    res.set("Content-Type", "text/html; charset=utf-8");
    // 网页内 <img> 加载缩略图无法带 header，key 经 query 传给页面（Header 通道保留）
    const key = String(req.query.key || req.headers["x-album-key"] || "");
    return res.send(renderAlbumPage(session, key));
  }
  const filePath = path.join(sessionDir(token), file);
  if (!fileExists(filePath)) return res.status(404).type("text/plain").send("not found");
  const stat = fs.statSync(filePath);
  res.writeHead(200, { "Content-Type": "image/jpeg", "Content-Length": stat.size, "Cache-Control": "public, max-age=86400" });
  const stream = fs.createReadStream(filePath);
  stream.on("error", (e) => { try { res.destroy(); } catch (_) {} });
  stream.pipe(res);
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
