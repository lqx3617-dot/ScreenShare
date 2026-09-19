/**
 * 简单的 APK 下载服务器：明确设置 Content-Length 并流式发送，避免下载截断。
 * 支持 Range 请求（断点续传）。
 */
"use strict";
const http = require("http");
const fs = require("fs");
const path = require("path");
const crypto = require("crypto");

const PORT = process.env.PORT || 8090;
const APK = "/workspace/ScreenShare-allarch-signed.apk";
const ARM64_APK = "/workspace/ScreenShare-arm64-signed.apk";
const GRADLE = "/workspace/app/build.gradle.kts";

// 下载引导首页展示的 MD5 校验值（与 version.json 动态计算的 md5 对应）
const MAIN_MD5 = process.env.MAIN_MD5 || "";
const ALBUM_MD5 = process.env.ALBUM_MD5 || "";

const publish = require("./publish");

// 发布配置：从 release-config.json 加载（App 内云发布会更新此文件），forced 是否强制更新
const RELEASE_CONFIG = publish.loadConfig();

// 发布接口鉴权 token：未配置时拒绝所有发布（防公网任意触发构建），部署必须显式设置 PUBLISH_TOKEN
const PUBLISH_TOKEN = process.env.PUBLISH_TOKEN || "";
function publishAuthorized(req) {
  if (!PUBLISH_TOKEN) return false;
  const h = req.headers["x-publish-token"] || req.headers["authorization"] || "";
  const presented = h.startsWith("Bearer ") ? h.slice(7) : h;
  // 定长比较防时序侧信道；长度不等直接拒绝，避免 timingSafeEqual 抛错
  if (!presented || presented.length !== PUBLISH_TOKEN.length) return false;
  try {
    return crypto.timingSafeEqual(Buffer.from(presented), Buffer.from(PUBLISH_TOKEN));
  } catch (e) {
    return false;
  }
}

// 客户端运行日志上传：存 server/logs/，保留 12 小时，超期自动清理
const LOGS_DIR = path.join(__dirname, "logs");
const LOG_MAX_AGE_MS = 12 * 60 * 60 * 1000;
const LOG_MAX_BYTES = 2 * 1024 * 1024;
const DIAG_TOKEN = process.env.DIAG_TOKEN || "";
function diagAuthorized(req) {
  if (!DIAG_TOKEN) return false;
  const presented = req.headers["x-diag-token"] || "";
  if (!presented || presented.length !== DIAG_TOKEN.length) return false;
  try {
    return crypto.timingSafeEqual(Buffer.from(presented), Buffer.from(DIAG_TOKEN));
  } catch (e) {
    return false;
  }
}
// 删除 mtime 超过 12 小时的日志；启动时执行一次，之后每 12 小时巡检
function purgeOldLogs() {
  try {
    fs.mkdirSync(LOGS_DIR, { recursive: true });
    const cut = Date.now() - LOG_MAX_AGE_MS;
    for (const name of fs.readdirSync(LOGS_DIR)) {
      if (!name.endsWith(".log")) continue;
      const f = path.join(LOGS_DIR, name);
      try {
        const st = fs.statSync(f);
        if (st.mtimeMs < cut) {
          fs.unlinkSync(f);
          console.log(`[logs] purged ${name} (age=${Math.round((Date.now() - st.mtimeMs) / 3600000)}h)`);
        }
      } catch (e) {}
    }
  } catch (e) {
    console.error("[logs] purge failed:", e.message);
  }
}
// 从日志首部提取设备键（诊断ID+型号）：客户端每次上传的是本地累积日志的
// 完整快照，历史内容已包含在最新快照中，同设备旧文件纯属重复占用。
// 旧版日志头无诊断ID时退化为仅型号
function deviceKeyOf(head) {
  const m = /设备=(\S+)\s+(\S+)/.exec(head);
  if (!m) return null;
  const d = /诊断ID=([^\s]+)/.exec(head);
  return (d ? d[1] : "nodiag") + "|" + m[1] + " " + m[2];
}
// 只读文件首部 1KB 提取设备键，避免对大日志全量 IO
function logFileHead(f) {
  let fd;
  try {
    fd = fs.openSync(f, "r");
    const buf = Buffer.alloc(1024);
    const n = fs.readSync(fd, buf, 0, 1024, 0);
    return buf.subarray(0, n).toString("utf8");
  } catch (e) {
    return "";
  } finally {
    if (fd !== undefined) { try { fs.closeSync(fd); } catch (e) {} }
  }
}
// 删除同设备的旧日志（保留本次新上传的）
function dedupeDeviceLogs(newName, key) {
  if (!key) return;
  try {
    for (const name of fs.readdirSync(LOGS_DIR)) {
      if (!name.endsWith(".log") || name === newName) continue;
      const f = path.join(LOGS_DIR, name);
      if (deviceKeyOf(logFileHead(f)) === key) {
        try {
          fs.unlinkSync(f);
          console.log(`[logs] dedupe removed ${name}`);
        } catch (e) {}
      }
    }
  } catch (e) {
    console.error("[logs] dedupe failed:", e.message);
  }
}
// 启动时全量去重：同设备键只留 mtime 最新的一份
function dedupeAllLogs() {
  try {
    const entries = [];
    for (const name of fs.readdirSync(LOGS_DIR)) {
      if (!name.endsWith(".log")) continue;
      const f = path.join(LOGS_DIR, name);
      const key = deviceKeyOf(logFileHead(f));
      if (!key) continue;
      try { entries.push({ f, name, key, mtime: fs.statSync(f).mtimeMs }); } catch (e) {}
    }
    const keep = new Map();
    for (const e of entries) {
      const prev = keep.get(e.key);
      if (!prev || e.mtime > prev.mtime) keep.set(e.key, e);
    }
    for (const e of entries) {
      if (keep.get(e.key) !== e) {
        try {
          fs.unlinkSync(e.f);
          console.log(`[logs] dedupe removed ${e.name}`);
        } catch (err) {}
      }
    }
  } catch (e) {
    console.error("[logs] dedupeAll failed:", e.message);
  }
}
purgeOldLogs();
dedupeAllLogs();
setInterval(purgeOldLogs, LOG_MAX_AGE_MS);

// 当前正在执行的发布任务（单任务互斥）+ 已完成任务历史（供状态查询）
let currentTask = null;
const taskHistory = new Map();

// 发布历史落盘：进程重启后 /api/publish/status 仍可查询历史任务
// （进行中的任务随进程退出丢失，构建子进程无法恢复，只能事后查记录）
const HISTORY_FILE = path.join(__dirname, "data", "publish-history.json");
function persistHistory() {
  try {
    const arr = Array.from(taskHistory.values()).map((t) => ({
      id: t.id, state: t.state, phase: t.phase, versionName: t.versionName,
      changelog: t.changelog, app: t.app, error: t.error, createdAt: t.createdAt,
    }));
    fs.mkdirSync(path.dirname(HISTORY_FILE), { recursive: true });
    fs.writeFileSync(HISTORY_FILE, JSON.stringify(arr));
  } catch (e) {
    console.error("persist publish history failed:", e.message);
  }
}
try {
  const raw = fs.readFileSync(HISTORY_FILE, "utf8");
  const arr = JSON.parse(raw);
  if (Array.isArray(arr)) arr.forEach((t) => { if (t && t.id) taskHistory.set(t.id, t); });
} catch (e) {
  // 首次启动或历史文件损坏：忽略，空历史启动
}

/** 分享链接兜底页 HTML：会议号 + 打开 App + 下载 App */
function renderSharePage(code, token) {
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
<a class="btn" href="intent://join?code=${code}${token ? "&token=" + token : ""}#Intent;scheme=screenshare;package=com.screenshare;end">打开 App 加入</a>
<a class="btn ghost" href="screenshare://join?code=${code}${token ? "&token=" + token : ""}">备用：直接用链接唤起</a>
<a class="btn ghost" href="./ScreenShare-allarch-signed.apk">下载 ScreenShare App</a>
<div class="hint">建议使用系统浏览器（Chrome）打开本页，点击「打开 App 加入」自动唤起<br>若未唤起：请确认已安装最新版 App；也可以记住上方会议号，在 App 内手动输入加入</div></div></body></html>`;
}

// 版本信息缓存：每次读取 build.gradle.kts 的 versionCode/versionName + 计算 APK md5
let cachedVersion = null;
let cachedMtime = 0;
let buildingVersion = null; // 正在计算的 Promise，避免并发重复计算
// 相册查看 APP 独立版本缓存（version.json 只反映主 APP；AlbumViewer 用独立端点）
let cachedAlbumVersion = null;
let cachedAlbumMtime = 0;
let buildingAlbumVersion = null;
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
    // 版本门禁：客户端本地 versionCode 低于此值时阻断使用，强制更新
    minVersionCode: RELEASE_CONFIG.minVersionCode || 0,
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

// ==================== 相册查看 APP 独立版本 ====================
const ALBUM_APK = "/workspace/AlbumViewer-signed.apk";
const ALBUM_GRADLE = "/workspace/albumviewer/build.gradle.kts";
async function buildAlbumVersion(host) {
  const gradle = fs.readFileSync(ALBUM_GRADLE, "utf8");
  const vc = /versionCode\s*=\s*(\d+)/.exec(gradle);
  const vn = /versionName\s*=\s*"([^"]+)"/.exec(gradle);
  const stat = fs.statSync(ALBUM_APK);
  const md5 = await fileMd5(ALBUM_APK);
  const base = (process.env.DOWNLOAD_BASE || host || "localhost").trim().replace(/^https?:\/\//i, "");
  return {
    versionCode: vc ? parseInt(vc[1], 10) : 0,
    versionName: vn ? vn[1] : "0",
    url: `https://${base}/AlbumViewer-signed.apk`,
    md5,
    size: stat.size,
    note: "相册查看：直接查看全部照片，上传后自动刷新",
    forced: false,
    changelog: "",
  };
}
async function getAlbumVersion(host) {
  let mtime = 0;
  try {
    mtime = fs.statSync(ALBUM_APK).mtimeMs;
  } catch (e) {
    cachedAlbumVersion = null;
    return { error: "AlbumViewer APK not found" };
  }
  if (cachedAlbumVersion && cachedAlbumMtime === mtime) return cachedAlbumVersion;
  if (!buildingAlbumVersion) {
    buildingAlbumVersion = buildAlbumVersion(host).then((v) => {
      cachedAlbumVersion = v;
      cachedAlbumMtime = mtime;
      buildingAlbumVersion = null;
      return v;
    }).catch((e) => {
      buildingAlbumVersion = null;
      throw e;
    });
  }
  return buildingAlbumVersion;
}

const server = http.createServer((req, res) => {
  const urlPath = req.url.split("?")[0];
  const t0 = Date.now();
  const done = (code) => {
    console.log(`[${new Date().toISOString()}] ${req.method} ${urlPath} -> ${code} (${Date.now() - t0}ms)${req.headers.range ? " range=" + req.headers.range : ""} ua=${(req.headers["user-agent"] || "").slice(0, 80)}`);
  };
  // 下载引导首页：手机浏览器打开根路径时展示两个 APK 下载入口 + 加速提示
  if (urlPath === "/" && req.method === "GET") {
    const base = (process.env.DOWNLOAD_BASE || req.headers.host || "localhost").trim().replace(/^https?:\/\//i, "");
    res.writeHead(200, { "Content-Type": "text/html; charset=utf-8" });
    res.end(`<!DOCTYPE html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>共享屏界 下载中心</title><style>
body{font-family:-apple-system,sans-serif;background:#f4f7fb;margin:0;padding:24px 16px;color:#0f172a}
h1{font-size:20px;margin:0 0 4px}.sub{color:#64748b;font-size:13px;margin:0 0 24px}
.card{background:#fff;border-radius:16px;box-shadow:0 4px 16px rgba(15,23,42,.08);padding:20px;margin-bottom:16px}
.card h2{font-size:16px;margin:0 0 4px}.card p{color:#475569;font-size:13px;margin:0 0 12px}
.btn{display:block;background:#3b82f6;color:#fff;text-decoration:none;font-size:15px;font-weight:600;border-radius:12px;padding:12px 0;text-align:center}
.btn.violet{background:#4f46e5}
.tip{background:#eff6ff;border:1px solid #bfdbfe;border-radius:12px;padding:12px;font-size:12px;color:#1e40af;line-height:1.7;margin-top:16px}
.tip b{color:#1d4ed8}.md5{font-family:monospace;font-size:11px;color:#64748b;word-break:break-all}
</style></head><body>
<h1>共享屏界 下载中心</h1>
<p class="sub">视频通话 / 屏幕共享 / 远程相册</p>
<div class="card"><h2>主 App（共享屏界）</h2><p>屏幕共享、视频通话、远程相册上传</p><a class="btn" href="https://${base}/ScreenShare-allarch-signed.apk">下载主 App（约 21MB，全兼容）</a><a class="btn" style="background:#10b981" href="https://${base}/ScreenShare-arm64-signed.apk">快速版（约 15MB，64 位手机专用）</a></div>
<div class="card"><h2>相册查看 App</h2><p>独立相册浏览，查看全部照片</p><a class="btn violet" href="https://${base}/AlbumViewer-signed.apk">下载相册 App（约 6MB）</a></div>
<div class="tip"><b>下载慢？</b> 当前网络单连接限速较慢，请使用支持「多线程下载」的浏览器或下载器（如夸克、UC、IDM、Aria2）下载，速度可提升数倍。下载后建议校验 MD5。</div>
<div class="card"><h2>MD5 校验</h2><p class="md5">主 App: ${MAIN_MD5}<br>相册 App: ${ALBUM_MD5}</p></div>
</body></html>`);
    done(200);
    return;
  }
  // App 内云发布：提交发布任务
  if (urlPath === "/api/publish" && req.method === "POST") {
    if (!publishAuthorized(req)) {
      res.writeHead(403, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ error: "forbidden" }));
      done(403);
      return;
    }
    let body = "";
    let tooLarge = false;
    // 超大请求体：优雅返回 413 并断开，而非直接 destroy（客户端收到 ECONNRESET 无法区分网络故障）
    req.on("data", (c) => {
      body += c;
      if (body.length > 1024 * 64) {
        if (!tooLarge) {
          tooLarge = true;
          try {
            res.writeHead(413, { "Content-Type": "application/json" });
            res.end(JSON.stringify({ error: "请求体过大" }));
            done(413);
          } catch (e) {}
        }
        req.destroy();
      }
    });
    req.on("end", () => {
      if (tooLarge) return;
      let payload;
      try { payload = JSON.parse(body || "{}"); } catch (e) { payload = {}; }
      const versionName = String(payload.versionName || "").trim();
      const changelog = String(payload.changelog || "").trim();
      const app = String(payload.app || "both");
      // minVersion: 可选，启用版本门禁的最低 versionCode。缺省/0 = 保持现有门禁配置不变
      const minVersion = parseInt(payload.minVersion, 10) || 0;
      if (!/^\d+\.\d+$/.test(versionName)) {
        res.writeHead(400, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ error: "版本号格式应为 数字.数字（如 1.183）" }));
        done(400);
        return;
      }
      if (!changelog || changelog.length > 2000) {
        res.writeHead(400, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ error: "更新说明不能为空且不超过 2000 字" }));
        done(400);
        return;
      }
      if (!["main", "albumviewer", "both"].includes(app)) {
        res.writeHead(400, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ error: "app 目标非法" }));
        done(400);
        return;
      }
      if (currentTask) {
        res.writeHead(409, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ error: "已有发布任务进行中" }));
        done(409);
        return;
      }
      const taskId = Date.now().toString(36) + Math.random().toString(36).slice(2, 6);
      const task = {
        id: taskId,
        state: "building",
        phase: "bump",
        versionName,
        changelog,
        app,
        apps: app === "both" ? ["main", "albumviewer"] : [app],
        minVersion,
        log: [],
        error: null,
        createdAt: Date.now(),
      };
      currentTask = task;
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ taskId }));
      done(200);
      // 异步执行，不阻塞下载
      publish.executeTask(task).then(() => {
        Object.assign(RELEASE_CONFIG, publish.loadConfig());
        currentTask = null;
        taskHistory.set(task.id, task);
        // 任务历史上限 50 条，防长期内存增长
        if (taskHistory.size > 50) {
          const oldest = taskHistory.keys().next().value;
          if (oldest) taskHistory.delete(oldest);
        }
        persistHistory();
      }).catch((e) => {
        console.error("publish task error", e);
        currentTask = null;
        taskHistory.set(task.id, task);
        if (taskHistory.size > 50) {
          const oldest = taskHistory.keys().next().value;
          if (oldest) taskHistory.delete(oldest);
        }
        persistHistory();
      });
    });
    return;
  }
  // App 内云发布：查询任务状态
  if (urlPath === "/api/publish/status") {
    // taskId = 时间基36 + 4 位随机，随机空间约 170 万，不鉴权可批量枚举他人构建状态
    // 与失败日志片段（含版本号、stderr）。复用发版鉴权。
    if (!publishAuthorized(req)) {
      res.writeHead(403, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ error: "forbidden" }));
      done(403);
      return;
    }
    const q = new URLSearchParams(req.url.split("?")[1] || "");
    const taskId = q.get("task") || "";
    const task = (currentTask && currentTask.id === taskId) ? currentTask : taskHistory.get(taskId);
    if (task) {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({
        state: task.state,
        phase: task.phase,
        versionName: task.versionName,
        error: task.error,
        log: task.log.slice(-30),
      }));
      done(200);
    } else {
      res.writeHead(404, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ error: "任务不存在" }));
      done(404);
    }
    return;
  }
  // 客户端运行日志上传：raw body 存盘，便于开发者分析 bug（12 小时后自动清理）
  if (urlPath === "/api/upload-log" && req.method === "POST") {
    if (!diagAuthorized(req)) {
      res.writeHead(403, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ error: "forbidden" }));
      done(403);
      return;
    }
    const chunks = [];
    let totalSize = 0;
    let tooLarge = false;
    req.on("data", (c) => {
      // 计数替代 reduce 全量遍历（O(n²)），2MB 上限下影响小但保持线性
      totalSize += c.length;
      if (totalSize > LOG_MAX_BYTES) {
        if (!tooLarge) {
          tooLarge = true;
          try {
            res.writeHead(413, { "Content-Type": "application/json" });
            res.end(JSON.stringify({ error: "日志过大" }));
            done(413);
          } catch (e) {}
        }
        // 不再 req.destroy()：destroy 常使客户端收到 ECONNRESET 而非完整 413 响应，
        // 响应已写完，丢弃后续数据即可
        return;
      }
      chunks.push(c);
    });
    req.on("end", () => {
      if (tooLarge) return;
      const body = Buffer.concat(chunks);
      if (body.length === 0) {
        res.writeHead(400, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ error: "日志为空" }));
        done(400);
        return;
      }
      try {
        fs.mkdirSync(LOGS_DIR, { recursive: true });
        const ts = new Date().toISOString().replace(/[:.]/g, "-");
        const rand = crypto.randomBytes(4).toString("hex");
        const name = `log-${ts}-${rand}.log`;
        fs.writeFileSync(path.join(LOGS_DIR, name), body);
        // 同设备旧快照已完整包含本次内容，去重删除
        dedupeDeviceLogs(name, deviceKeyOf(body.subarray(0, 1024).toString("utf8")));
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ ok: true, file: name, size: body.length }));
        done(200);
      } catch (e) {
        res.writeHead(500, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ error: "保存失败" }));
        done(500);
      }
    });
    return;
  }
  if (urlPath === "/version.json") {
    getVersion(req.headers.host).then((v) => {
      const err = !!(v && v.error);
      res.writeHead(err ? 500 : 200, { "Content-Type": "application/json; charset=utf-8", "Cache-Control": "no-store" });
      res.end(JSON.stringify(v || { error: "internal" }));
      done(err ? 500 : 200);
    }).catch(() => {
      res.writeHead(500, { "Content-Type": "application/json; charset=utf-8", "Cache-Control": "no-store" });
      res.end(JSON.stringify({ error: "internal" }));
      done(500);
    });
    return;
  }
  // 相册查看 APP 独立版本检查（AlbumViewer 云更新）
  if (urlPath === "/albumviewer-version.json") {
    getAlbumVersion(req.headers.host).then((v) => {
      const err = !!(v && v.error);
      res.writeHead(err ? 500 : 200, { "Content-Type": "application/json; charset=utf-8", "Cache-Control": "no-store" });
      res.end(JSON.stringify(v || { error: "internal" }));
      done(err ? 500 : 200);
    }).catch(() => {
      res.writeHead(500, { "Content-Type": "application/json; charset=utf-8", "Cache-Control": "no-store" });
      res.end(JSON.stringify({ error: "internal" }));
      done(500);
    });
    return;
  }
  // 分享链接兜底页：/j?code=XXXX 展示会议号 + 打开 App + 下载 App
  if (urlPath === "/j") {
    const qs = req.url.split("?")[1] ? new URLSearchParams(req.url.split("?")[1]) : null;
    const code = String(qs ? qs.get("code") || "" : "");
    // 房间口令（服务器 REQUIRE_TOKEN=1 时必需）：随链接透传给 App，观看方无需手动输入
    const tokenRaw = String(qs ? qs.get("token") || "" : "");
    const token = /^[A-Za-z0-9]{4,16}$/.test(tokenRaw) ? tokenRaw : "";
    const valid = /^[0-9]{4}$/.test(code);
    res.writeHead(200, { "Content-Type": "text/html; charset=utf-8" });
    res.end(renderSharePage(valid ? code : null, token));
    done(200);
    return;
  }
  // 更新日志：/changelog 返回全部历史更新说明
  if (urlPath === "/changelog" || urlPath === "/changelog.txt") {
    let status = 200;
    try {
      const txt = fs.readFileSync("/workspace/CHANGELOG.md", "utf8");
      res.writeHead(200, { "Content-Type": "text/plain; charset=utf-8" });
      res.end(txt);
    } catch (e) {
      status = 500;
      res.writeHead(500, { "Content-Type": "text/plain" });
      res.end("changelog missing");
    }
    done(status);
    return;
  }
  if (urlPath !== "/ScreenShare-allarch-signed.apk" && urlPath !== "/ScreenShare-arm64-signed.apk" && urlPath !== "/AlbumViewer-signed.apk") {
    res.writeHead(404, { "Content-Type": "text/plain" });
    res.end("not found");
    done(404);
    return;
  }
  const apkPath = urlPath === "/AlbumViewer-signed.apk"
    ? "/workspace/AlbumViewer-signed.apk"
    : urlPath === "/ScreenShare-arm64-signed.apk"
      ? ARM64_APK
      : APK;
  let stat = null;
  try {
    stat = fs.statSync(apkPath);
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
    let start = m && m[1] ? parseInt(m[1], 10) : 0;
    let end = m && m[2] ? parseInt(m[2], 10) : total - 1;
    // 钳制非法/越界 Range（含 NaN、start>end），防负 Content-Length / 非安全整数导致进程崩溃；
    // 带 Range 的请求一律返回 206（除非请求的恰好是完整 0-(total-1)），
    // 避免客户端分段请求（如 bytes=0--1）拿到 200 全量后各段互相覆盖导致下载卡死
    if (!Number.isFinite(start) || !Number.isFinite(end) || start > end) { start = 0; end = total - 1; }
    start = Math.max(0, Math.min(start, total - 1));
    end = Math.max(start, Math.min(end, total - 1));
    if (start === 0 && end === total - 1 && m && m[1] === "" && m[2] === "") {
      res.writeHead(200, {
        "Content-Length": total,
        "Content-Type": "application/vnd.android.package-archive",
        "Content-Disposition": `attachment; filename="${urlPath.split("/").pop()}"`,
        "Accept-Ranges": "bytes",
      });
      const full = fs.createReadStream(apkPath);
      full.on("error", () => { try { res.destroy(); } catch (_) {} });
      full.pipe(res);
      done(200);
      return;
    }
    res.writeHead(206, {
      "Content-Range": `bytes ${start}-${end}/${total}`,
      "Content-Length": end - start + 1,
      "Content-Type": "application/vnd.android.package-archive",
      "Content-Disposition": `attachment; filename="${urlPath.split("/").pop()}"`,
      "Accept-Ranges": "bytes",
    });
    const stream = fs.createReadStream(apkPath, { start, end });
    stream.on("error", () => { try { res.destroy(); } catch (_) {} });
    stream.pipe(res);
    done(206);
  } else {
    res.writeHead(200, {
      "Content-Length": total,
      "Content-Type": "application/vnd.android.package-archive",
      "Content-Disposition": `attachment; filename="${urlPath.split("/").pop()}"`,
      "Accept-Ranges": "bytes",
    });
    const stream = fs.createReadStream(apkPath);
    stream.on("error", () => { try { res.destroy(); } catch (_) {} });
    stream.pipe(res);
    done(200);
  }
});

server.listen(PORT, () => {
  console.log(`APK download server listening on :${PORT}`);
});
