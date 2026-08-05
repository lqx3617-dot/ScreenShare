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
  changelog: "更新提示优化：显示版本对比与更新说明，支持强制更新\n下载优化：通知栏进度、后台下载可取消、4线程加速、失败自动重试\n文件复用：下载过的安装包校验通过后直接安装\n启动自动静默检查（12小时内不重复）",
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
    note: "检查更新优化：通知栏下载、强制更新、自动检查、下载加速",
    forced: RELEASE_CONFIG.forced,
    changelog: RELEASE_CONFIG.changelog,
  };
  cachedMtime = mtime;
  return cachedVersion;
}

const server = http.createServer((req, res) => {
  const urlPath = req.url.split("?")[0];
  if (urlPath === "/version.json") {
    res.writeHead(200, { "Content-Type": "application/json" });
    res.end(JSON.stringify(getVersion()));
    return;
  }
  if (urlPath !== "/ScreenShare-allarch-signed.apk") {
    res.writeHead(404, { "Content-Type": "text/plain" });
    res.end("not found");
    return;
  }
  if (req.method === "HEAD") {
    res.writeHead(200, {
      "Content-Length": fs.statSync(APK).size,
      "Content-Type": "application/vnd.android.package-archive",
      "Accept-Ranges": "bytes",
    });
    res.end();
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
  } else {
    res.writeHead(200, {
      "Content-Length": total,
      "Content-Type": "application/vnd.android.package-archive",
      "Accept-Ranges": "bytes",
    });
    fs.createReadStream(APK).pipe(res);
  }
});

server.listen(PORT, () => {
  console.log(`APK download server listening on :${PORT}`);
});
