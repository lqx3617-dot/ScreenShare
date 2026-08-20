"use strict";
/**
 * 发布任务执行器：改版本号 -> gradle 构建 -> 签名 -> 更新版本配置。
 * 由 download-server 调用，单任务互斥（同一时刻只允许一个发布任务）。
 */
const fs = require("fs");
const path = require("path");
const { execFile } = require("child_process");

const ANDROID_HOME = process.env.ANDROID_HOME || "/opt/android-sdk";
const GRADLE = "/workspace/gradlew";
const PROJECT_DIR = "/workspace";
const KEYSTORE = process.env.KEYSTORE_PATH || "/workspace/signing/release.keystore";
// 签名密钥口令：优先从环境变量注入，不写死源码（安全加固）；未设置时回退旧值并告警
const KEYSTORE_PASS = process.env.KEYSTORE_PASS || (() => {
  console.warn("[publish] ⚠️ 未设置 KEYSTORE_PASS 环境变量，使用默认口令（建议尽快改为环境变量注入）");
  return "screenshare123";
})();
const KEYSTORE_ALIAS = process.env.KEYSTORE_ALIAS || "screenshare";
const BUILD_TOOLS = path.join(ANDROID_HOME, "build-tools", "34.0.0");
const ZIPALIGN = path.join(BUILD_TOOLS, "zipalign");
const APKSIGNER = path.join(BUILD_TOOLS, "apksigner");
const CONFIG_FILE = "/workspace/server/release-config.json";

// app 目标 -> gradle 文件 / 构建产物 / 最终 APK
const APPS = {
  main: {
    gradle: "/workspace/app/build.gradle.kts",
    unsigned: "/workspace/app/build/outputs/apk/release/app-release-unsigned.apk",
    final: "/workspace/ScreenShare-allarch-signed.apk",
    label: "ScreenShare 主 APP",
  },
  albumviewer: {
    gradle: "/workspace/albumviewer/build.gradle.kts",
    unsigned: "/workspace/albumviewer/build/outputs/apk/release/albumviewer-release-unsigned.apk",
    final: "/workspace/AlbumViewer-signed.apk",
    label: "相册查看 APP",
  },
};

// 读取 build.gradle.kts 的版本号
function readGradleVersion(gradleFile) {
  const txt = fs.readFileSync(gradleFile, "utf8");
  const vc = /versionCode\s*=\s*(\d+)/.exec(txt);
  const vn = /versionName\s*=\s*"([^"]+)"/.exec(txt);
  return { versionCode: vc ? parseInt(vc[1], 10) : 0, versionName: vn ? vn[1] : "0" };
}

// 把版本号写回 build.gradle.kts
function writeGradleVersion(gradleFile, versionCode, versionName) {
  let txt = fs.readFileSync(gradleFile, "utf8");
  txt = txt.replace(/versionCode\s*=\s*\d+/, `versionCode = ${versionCode}`);
  txt = txt.replace(/versionName\s*=\s*"[^"]*"/, `versionName = "${versionName}"`);
  fs.writeFileSync(gradleFile, txt, "utf8");
}

// 原子写文件：先写临时文件再 rename，避免下载读到半截文件
function atomicWrite(dest, content) {
  const tmp = dest + ".tmp" + process.pid;
  fs.writeFileSync(tmp, content);
  fs.renameSync(tmp, dest);
}

function run(cmd, args, opts = {}) {
  return new Promise((resolve, reject) => {
    const child = execFile(cmd, args, { maxBuffer: 64 * 1024 * 1024, ...opts }, (err, stdout, stderr) => {
      if (err) {
        err.stdout = stdout;
        err.stderr = stderr;
        reject(err);
      } else {
        resolve({ stdout, stderr });
      }
    });
  });
}

async function buildApk(task) {
  task.phase = "build";
  task.log.push(`构建 APK（${task.apps.map((a) => APPS[a].label).join(" + ") || "全部"}）…`);
  await run(GRADLE, ["assembleRelease"], {
    cwd: PROJECT_DIR,
    env: { ...process.env, ANDROID_HOME },
    timeout: 15 * 60 * 1000,
  });
}

async function signApk(task, key) {
  const cfg = APPS[key];
  if (!fs.existsSync(cfg.unsigned)) {
    throw new Error(`${cfg.label} 构建产物不存在: ${cfg.unsigned}`);
  }
  task.log.push(`签名 ${cfg.label}…`);
  const aligned = cfg.final + ".aligned" + process.pid;
  await run(ZIPALIGN, ["-f", "4", cfg.unsigned, aligned]);
  const signed = cfg.final + ".signed" + process.pid;
  await run(APKSIGNER, [
    "sign", "--ks", KEYSTORE, "--ks-pass", `pass:${KEYSTORE_PASS}`,
    "--ks-key-alias", KEYSTORE_ALIAS, "--out", signed, aligned,
  ]);
  await run(APKSIGNER, ["verify", "--verbose", signed]);
  atomicWrite(cfg.final, fs.readFileSync(signed));
  fs.unlinkSync(aligned);
  fs.unlinkSync(signed);
  task.log.push(`${cfg.label} 签名完成 -> ${cfg.final}`);
}

function loadConfig() {
  try {
    const j = JSON.parse(fs.readFileSync(CONFIG_FILE, "utf8"));
    return { changelog: j.changelog || "", forced: !!j.forced };
  } catch (e) {
    return { changelog: "", forced: false };
  }
}

function saveConfig(cfg) {
  atomicWrite(CONFIG_FILE, JSON.stringify(cfg, null, 2));
}

function bumpVersion(task) {
  task.phase = "bump";
  const targets = task.apps.length ? task.apps : ["main", "albumviewer"];
  task.bumpedBackup = [];
  for (const key of targets) {
    const cfg = APPS[key];
    const cur = readGradleVersion(cfg.gradle);
    const nextCode = cur.versionCode + 1;
    writeGradleVersion(cfg.gradle, nextCode, task.versionName);
    task.bumpedBackup.push({ gradle: cfg.gradle, content: fs.readFileSync(cfg.gradle, "utf8") });
    task.log.push(`版本号 ${cur.versionName}(${cur.versionCode}) -> ${task.versionName}(${nextCode}) ${cfg.label}`);
  }
}

function rollback(task) {
  for (const b of task.bumpedBackup || []) {
    try {
      fs.writeFileSync(b.gradle, b.content, "utf8");
      task.log.push(`回滚 ${b.gradle}`);
    } catch (e) {
      task.log.push(`回滚失败 ${b.gradle}: ${e.message}`);
    }
  }
}

function updateConfig(task) {
  task.phase = "config";
  // release-config.json 的 changelog 只服务于主 APP version.json；
  // 仅发布 albumviewer 时不覆盖，避免污染主 APP 的更新说明
  const targets = task.apps.length ? task.apps : ["main", "albumviewer"];
  if (!targets.includes("main")) {
    task.log.push("跳过更新版本配置（仅发布相册查看 APP）");
    return;
  }
  const cfg = loadConfig();
  cfg.changelog = task.changelog;
  cfg.forced = false;
  saveConfig(cfg);
  task.log.push("更新版本配置 release-config.json");
}

async function executeTask(task) {
  try {
    bumpVersion(task);
    await buildApk(task);
    task.phase = "sign";
    const targets = task.apps.length ? task.apps : ["main", "albumviewer"];
    for (const key of targets) {
      await signApk(task, key);
    }
    updateConfig(task);
    task.state = "success";
    task.phase = "done";
    task.log.push("发布成功");
  } catch (err) {
    task.log.push(`失败: ${err.message || err}`);
    if (err.stdout) task.log.push((err.stdout + "").split("\n").filter(Boolean).slice(-15).join("\n"));
    if (err.stderr) task.log.push((err.stderr + "").split("\n").filter(Boolean).slice(-15).join("\n"));
    rollback(task);
    task.state = "failed";
    task.error = err.message || String(err);
  }
}

module.exports = { executeTask, loadConfig, saveConfig, APPS };
