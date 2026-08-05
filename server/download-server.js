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
    note: "新增会议内麦克风语音（双向对讲，与系统音频叠加），连接后状态栏出现麦克风按钮",
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
