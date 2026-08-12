/**
 * 相册照片服务器：接收共享方 App 上传的相册照片，生成带随机 token 的网页链接，
 * 观看方在手机浏览器打开链接查看网页版相册。
 *
 * 接口：
 *  POST /api/upload  body={action:"create"}                    → {token}
 *                    body={action:"upload", token, index, data} → {ok}
 *                    body={action:"finish", token}              → {ok}
 *  GET  /api/status?token=<t>                                  → {total, received, done}
 *  GET  /<token>/                                              → 相册网页
 *  GET  /<token>/<index>.jpg                                   → 单张照片
 */
"use strict";
const http = require("http");
const fs = require("fs");
const path = require("path");
const crypto = require("crypto");

const PORT = process.env.PORT || 8096;
const ALBUM_ROOT = "/workspace/albums";
const TTL_MS = 24 * 60 * 60 * 1000; // 会话 24h 过期
const MAX_BODY = 8 * 1024 * 1024; // 单请求上限 8MB（单张压缩图 + base64 足够）

function readBody(req) {
  return new Promise((resolve, reject) => {
    let size = 0;
    const chunks = [];
    req.on("data", (c) => {
      size += c.length;
      if (size > MAX_BODY) {
        reject(new Error("body too large"));
        req.destroy();
        return;
      }
      chunks.push(c);
    });
    req.on("end", () => resolve(Buffer.concat(chunks).toString("utf8")));
    req.on("error", reject);
  });
}

/** 会话：token 为 128 位随机熵；数据在磁盘，内存只存元信息 */
const sessions = new Map();

function sessionDir(token) {
  return path.join(ALBUM_ROOT, token);
}

function loadSession(token) {
  if (sessions.has(token)) return sessions.get(token);
  const dir = sessionDir(token);
  const metaPath = path.join(dir, "meta.json");
  if (!fs.existsSync(metaPath)) return null;
  try {
    const meta = JSON.parse(fs.readFileSync(metaPath, "utf8"));
    const session = {
      token,
      createdAt: meta.createdAt || Date.now(),
      total: meta.total || 0,
      received: new Set(meta.received || []),
      done: !!meta.done,
    };
    sessions.set(token, session);
    return session;
  } catch (e) {
    return null;
  }
}

function persistSession(session) {
  const dir = sessionDir(session.token);
  fs.mkdirSync(dir, { recursive: true });
  const meta = {
    token: session.token,
    createdAt: session.createdAt,
    total: session.total,
    received: Array.from(session.received),
    done: session.done,
  };
  fs.writeFileSync(path.join(dir, "meta.json"), JSON.stringify(meta));
}

function json(res, code, obj) {
  const body = JSON.stringify(obj);
  res.writeHead(code, { "Content-Type": "application/json; charset=utf-8" });
  res.end(body);
}

function notFound(res) {
  res.writeHead(404, { "Content-Type": "text/plain; charset=utf-8" });
  res.end("not found");
}

/** 相册网页：响应式网格 + 点击全屏查看原图（无外部依赖） */
function renderAlbumPage(session) {
  const idx = Array.from({ length: session.total }, (_, i) => i + 1)
    .filter((i) => session.received.has(i))
    .map((i) => `<a class="p" href="${session.token}/${String(i).padStart(4, "0")}.jpg" target="_blank"><img loading="lazy" src="${session.token}/${String(i).padStart(4, "0")}.jpg" alt=""></a>`)
    .join("");
  const done = session.done;
  return `<!DOCTYPE html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>相册</title><style>
body{margin:0;background:#111;font-family:-apple-system,sans-serif}
.top{position:sticky;top:0;background:rgba(17,17,17,.92);backdrop-filter:blur(8px);color:#fff;padding:12px 16px;font-size:15px;z-index:10}
.grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(150px,1fr));gap:3px;padding:3px}
.p{display:block;aspect-ratio:1;overflow:hidden;background:#222}
.p img{width:100%;height:100%;object-fit:cover;display:block}
.none{color:#888;text-align:center;padding:40px 16px;font-size:14px}
@media(min-width:768px){.grid{grid-template-columns:repeat(auto-fill,minmax(220px,1fr))}}
</style></head><body>
<div class="top">📷 相册${done ? ` · ${session.received.size} 张` : " · 上传中…"}</div>
${idx ? `<div class="grid">${idx}</div>` : `<div class="none">${done ? "相册是空的" : "照片正在上传，请稍后刷新"}</div>`}
</body></html>`;
}

const server = http.createServer((req, res) => {
  const url = new URL(req.url, `http://${req.headers.host || "localhost"}`);
  const p = url.pathname;

  if (req.method === "POST" && p === "/api/upload") {
    readBody(req).then((bodyStr) => {
      let body;
      try {
        body = JSON.parse(bodyStr);
      } catch (e) {
        return json(res, 400, { error: "bad json" });
      }
      const action = body.action;
      if (action === "create") {
        const token = crypto.randomBytes(16).toString("hex");
        const session = { token, createdAt: Date.now(), total: 0, received: new Set(), done: false };
        sessions.set(token, session);
        fs.mkdirSync(sessionDir(token), { recursive: true });
        return json(res, 200, { token });
      }
      const token = String(body.token || "");
      const session = loadSession(token);
      if (!session) return json(res, 404, { error: "session not found" });
      if (action === "upload") {
        const index = parseInt(body.index, 10);
        const data = String(body.data || "");
        if (!index || index <= 0) return json(res, 400, { error: "bad index" });
        if (!data) return json(res, 400, { error: "empty data" });
        try {
          const buf = Buffer.from(data, "base64");
          const file = path.join(sessionDir(token), `${String(index).padStart(4, "0")}.jpg`);
          fs.writeFileSync(file, buf);
          session.received.add(index);
          session.total = Math.max(session.total, index);
          persistSession(session);
          return json(res, 200, { ok: true, received: session.received.size, total: session.total });
        } catch (e) {
          return json(res, 500, { error: "write failed" });
        }
      }
      if (action === "finish") {
        session.done = true;
        persistSession(session);
        return json(res, 200, { ok: true, url: `https://${url.host}/${token}/` });
      }
      return json(res, 400, { error: "unknown action" });
    }).catch((e) => json(res, 400, { error: e.message }));
    return;
  }

  if (req.method === "GET" && p === "/api/status") {
    const token = String(url.searchParams.get("token") || "");
    const session = loadSession(token);
    if (!session) return json(res, 404, { error: "session not found" });
    return json(res, 200, { total: session.total, received: session.received.size, done: session.done });
  }

  // /<token>/ 相册网页；/<token>/xxxx.jpg 单张照片
  const m = /^\/([0-9a-f]{32})\/(?:([0-9]{4}\.jpg)?)$/.exec(p);
  if (m) {
    const session = loadSession(m[1]);
    if (!session) return notFound(res);
    if (!m[2]) {
      if (!session.done && session.received.size === 0) {
        // 未完成且无照片：显示"上传中"
        res.writeHead(200, { "Content-Type": "text/html; charset=utf-8" });
        res.end(renderAlbumPage(session));
        return;
      }
      res.writeHead(200, { "Content-Type": "text/html; charset=utf-8" });
      res.end(renderAlbumPage(session));
      return;
    }
    const file = path.join(sessionDir(m[1]), m[2]);
    if (!fs.existsSync(file)) return notFound(res);
    const stat = fs.statSync(file);
    res.writeHead(200, { "Content-Type": "image/jpeg", "Content-Length": stat.size, "Cache-Control": "public, max-age=86400" });
    fs.createReadStream(file).pipe(res);
    return;
  }

  notFound(res);
});

// 过期会话清理：每 30 分钟扫描
setInterval(() => {
  const now = Date.now();
  let removed = 0;
  for (const [token, session] of sessions) {
    if (now - session.createdAt > TTL_MS) {
      try { fs.rmSync(sessionDir(token), { recursive: true, force: true }); } catch (e) {}
      sessions.delete(token);
      removed++;
    }
  }
  if (removed > 0) console.log(`[album] 清理过期会话 ${removed} 个`);
}, 30 * 60 * 1000);

server.listen(PORT, () => {
  console.log(`Album server listening on :${PORT}`);
});
