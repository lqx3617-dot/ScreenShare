/**
 * 简单的 APK 下载服务器：明确设置 Content-Length 并流式发送，避免下载截断。
 * 支持 Range 请求（断点续传）。
 */
"use strict";
const http = require("http");
const fs = require("fs");
const path = require("path");

const PORT = process.env.PORT || 8090;
const APK = "/workspace/ScreenShare-allarch-signed.apk";
const GRADLE = "/workspace/app/build.gradle.kts";

// 发布配置：每次发版更新此处（changelog 为多行更新说明，forced 是否强制更新）
const RELEASE_CONFIG = {
  changelog: "拍照失败原因改用对话框完整显示（不再被 Toast 截断），便于查看具体错误码",
  forced: false,
};

/** 分享链接兜底页 HTML：会议号 + 打开 App + 下载 App */
function renderSharePage(code) {
  if (!code) {
    return `<!DOCTYPE html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>ScreenShare 分享链接</title><style>
body{font-family:-apple-system,sans-serif;background:#f5f7fa;margin:0;display:flex;align-items:center;justify-content:center;min-height:100vh;text-align:center}
.card{background:#fff;border-radius:20px;box-shadow:0 8px 30px rgba(0,0,0,.08);padding:40px 32px;max-width:340px;margin:20px}
h1{font-size:18px;color:#1f2937;margin:0 0 12px}.tip{color:#6b7280;font-size:14px;line-height:1.6}
</style></head><body><div class="card"><h1>无效的分享链接</h1><div class="tip">链接缺少有效的会议号，请让对方重新分享。</div></div></body></html>`;
  }
  return `<!DOCTYPE html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>ScreenShare 屏幕共享邀请</title><style>
body{font-family:-apple-system,sans-serif;background:#f5f7fa;margin:0;display:flex;align-items:center;justify-content:center;min-height:100vh;text-align:center}
.card{background:#fff;border-radius:20px;box-shadow:0 8px 30px rgba(0,0,0,.08);padding:40px 32px;max-width:340px;margin:20px}
.logo{font-size:40px}.title{font-size:18px;color:#1f2937;margin:12px 0 4px}.sub{color:#6b7280;font-size:13px;margin-bottom:20px}
.code{font-size:44px;font-weight:700;letter-spacing:10px;color:#111827;background:#f3f4f6;border-radius:12px;padding:14px 0;margin:8px 0 24px}
.btn{display:block;background:#4f46e5;color:#fff;text-decoration:none;font-size:16px;font-weight:600;border-radius:12px;padding:14px 0;margin-bottom:12px}
.btn.ghost{background:#fff;color:#4f46e5;border:1px solid #e5e7eb}
.hint{color:#9ca3af;font-size:12px;line-height:1.6;margin-top:16px}
</style></head><body><div class="card"><div class="logo">🖥</div><div class="title">ScreenShare 屏幕共享</div><div class="sub">对方邀请你观看屏幕</div><div class="code">${code}</div>
<a class="btn" href="intent://join?code=${code}#Intent;scheme=screenshare;package=com.screenshare;end">打开 App 加入</a>
<a class="btn ghost" href="screenshare://join?code=${code}">备用：直接用链接唤起</a>
<a class="btn ghost" href="./ScreenShare-allarch-signed.apk">下载 ScreenShare App</a>
<div class="hint">建议使用系统浏览器（Chrome）打开本页，点击「打开 App 加入」自动唤起<br>若未唤起：请确认已安装最新版 App；也可以记住上方会议号，在 App 内手动输入加入</div></div></body></html>`;
}

// 版本信息缓存：每次读取 build.gradle.kts 的 versionCode/versionName + 计算 APK md5
let cachedVersion = null;
let cachedMtime = 0;
let buildingVersion = null; // 正在计算的 Promise，避免并发重复计算
const crypto = require("crypto");
// APK 下载 URL：优先用 DOWNLOAD_BASE 环境变量（公网域名，反代会把 Host 改写为 localhost，
// 此时用请求 Host 生成的 url 手机端无法访问），否则随请求 Host 动态生成（http/https 统一 https）
function fileMd5(file) {
  return new Promise((resolve, reject) => {
    const hash = crypto.createHash("md5");
    const s = fs.createReadStream(file);
    s.on("error", reject);
    s.on("data", (c) => hash.update(c));
    s.on("end", () => resolve(hash.digest("hex")));
  });
}
async function buildVersion(host) {
  const gradle = fs.readFileSync(GRADLE, "utf8");
  const vc = /versionCode\s*=\s*(\d+)/.exec(gradle);
  const vn = /versionName\s*=\s*"([^"]+)"/.exec(gradle);
  const stat = fs.statSync(APK);
  const md5 = await fileMd5(APK);
  const base = (process.env.DOWNLOAD_BASE || host || "localhost").trim().replace(/^https?:\/\//i, "");
  return {
    versionCode: vc ? parseInt(vc[1], 10) : 0,
    versionName: vn ? vn[1] : "0",
    url: `https://${base}/ScreenShare-allarch-signed.apk`,
    md5,
    size: stat.size,
    note: "全量优化版（双端需同步更新）",
    forced: RELEASE_CONFIG.forced,
    changelog: RELEASE_CONFIG.changelog,
  };
}
async function getVersion(host) {
  let mtime = 0;
  try {
    mtime = fs.statSync(APK).mtimeMs;
  } catch (e) {
    cachedVersion = null;
    return { error: "APK not found" };
  }
  if (cachedVersion && cachedMtime === mtime) return cachedVersion;
  if (!buildingVersion) {
    buildingVersion = buildVersion(host).then((v) => {
      cachedVersion = v;
      cachedMtime = mtime;
      buildingVersion = null;
      return v;
    }).catch((e) => {
      buildingVersion = null;
      throw e;
    });
  }
  return buildingVersion;
}

const server = http.createServer((req, res) => {
  const urlPath = req.url.split("?")[0];
  const t0 = Date.now();
  const done = (code) => {
    console.log(`[${new Date().toISOString()}] ${req.method} ${urlPath} -> ${code} (${Date.now() - t0}ms)${req.headers.range ? " range=" + req.headers.range : ""} ua=${(req.headers["user-agent"] || "").slice(0, 80)}`);
  };
  if (urlPath === "/version.json") {
    getVersion(req.headers.host).then((v) => {
      res.writeHead(v && v.error ? 500 : 200, { "Content-Type": "application/json" });
      res.end(JSON.stringify(v || { error: "internal" }));
      done(v && v.error ? 500 : 200);
    }).catch(() => {
      res.writeHead(500, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ error: "internal" }));
      done(500);
    });
    return;
  }
  // 分享链接兜底页：/j?code=XXXX 展示会议号 + 打开 App + 下载 App
  if (urlPath === "/j") {
    const code = String(req.url.split("?")[1] ? new URLSearchParams(req.url.split("?")[1]).get("code") || "" : "");
    const valid = /^[0-9]{4}$/.test(code);
    res.writeHead(200, { "Content-Type": "text/html; charset=utf-8" });
    res.end(renderSharePage(valid ? code : null));
    done(200);
    return;
  }
  if (urlPath !== "/ScreenShare-allarch-signed.apk") {
    res.writeHead(404, { "Content-Type": "text/plain" });
    res.end("not found");
    done(404);
    return;
  }
  let stat = null;
  try {
    stat = fs.statSync(APK);
  } catch (e) {
    res.writeHead(500, { "Content-Type": "text/plain" });
    res.end("apk missing");
    done(500);
    return;
  }
  if (req.method === "HEAD") {
    res.writeHead(200, {
      "Content-Length": stat.size,
      "Content-Type": "application/vnd.android.package-archive",
      "Accept-Ranges": "bytes",
    });
    res.end();
    done(200);
    return;
  }

  const total = stat.size;
  const range = req.headers.range;

  if (range) {
    const m = /bytes=(\d*)-(\d*)/.exec(range);
    const start = m && m[1] ? parseInt(m[1], 10) : 0;
    const end = m && m[2] ? parseInt(m[2], 10) : total - 1;
    res.writeHead(206, {
      "Content-Range": `bytes ${start}-${end}/${total}`,
      "Content-Length": end - start + 1,
      "Content-Type": "application/vnd.android.package-archive",
      "Accept-Ranges": "bytes",
    });
    fs.createReadStream(APK, { start, end }).pipe(res);
    done(206);
  } else {
    res.writeHead(200, {
      "Content-Length": total,
      "Content-Type": "application/vnd.android.package-archive",
      "Accept-Ranges": "bytes",
    });
    fs.createReadStream(APK).pipe(res);
    done(200);
  }
});

server.listen(PORT, () => {
  console.log(`APK download server listening on :${PORT}`);
});
