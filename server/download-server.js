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
  changelog: "修复通知栏下载失败问题：下载改为后台线程执行\nv1.67 起通知栏下载在点击「立即更新」后立即报「下载失败，请重试」的缺陷已修复",
  forced: false,
};

// 版本信息缓存：每次读取 build.gradle.kts 的 versionCode/versionName + 计算 APK md5
let cachedVersion = null;
let cachedMtime = 0;
function getVersion() {
  const mtime = fs.statSync(APK).mtimeMs;
  if (cachedVersion && cachedMtime === mtime) return cachedVersion;
  const gradle = fs.readFileSync(GRADLE, "utf8");
  const vc = /versionCode\s*=\s*(\d+)/.exec(gradle);
  const vn = /versionName\s*=\s*"([^"]+)"/.exec(gradle);
  const md5 = require("child_process")
    .execSync(`md5sum "${APK}"`)
    .toString()
    .split(/\s+/)[0];
  cachedVersion = {
    versionCode: vc ? parseInt(vc[1], 10) : 0,
    versionName: vn ? vn[1] : "0",
    url: "https://8090-6d639d2de20eb686.monkeycode-ai.online/ScreenShare-allarch-signed.apk",
    md5,
    size: fs.statSync(APK).size,
    note: "美化加入会议弹窗（液态玻璃风格）",
    forced: RELEASE_CONFIG.forced,
    changelog: RELEASE_CONFIG.changelog,
  };
  cachedMtime = mtime;
  return cachedVersion;
}

const server = http.createServer((req, res) => {
  const urlPath = req.url.split("?")[0];
  const t0 = Date.now();
  const done = (code) => {
    console.log(`[${new Date().toISOString()}] ${req.method} ${urlPath} -> ${code} (${Date.now() - t0}ms)${req.headers.range ? " range=" + req.headers.range : ""} ua=${(req.headers["user-agent"] || "").slice(0, 80)}`);
  };
  if (urlPath === "/version.json") {
    res.writeHead(200, { "Content-Type": "application/json" });
    res.end(JSON.stringify(getVersion()));
    done(200);
    return;
  }
  if (urlPath !== "/ScreenShare-allarch-signed.apk") {
    res.writeHead(404, { "Content-Type": "text/plain" });
    res.end("not found");
    done(404);
    return;
  }
  if (req.method === "HEAD") {
    res.writeHead(200, {
      "Content-Length": fs.statSync(APK).size,
      "Content-Type": "application/vnd.android.package-archive",
      "Accept-Ranges": "bytes",
    });
    res.end();
    done(200);
    return;
  }

  const stat = fs.statSync(APK);
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
