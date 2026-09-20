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
// 签名密钥口令：仅从环境变量注入，禁止写入源码
// 校验延迟到 executeTask：loadConfig/saveConfig 等纯配置操作可在无密钥环境（如测试）中使用
const KEYSTORE_PASS = process.env.KEYSTORE_PASS;
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
    // stdin 可选：用于经 /dev/stdin 传 keystore 口令，避免出现在 argv（/proc/pid/cmdline 明文可见）
    if (opts.input !== undefined) {
      child.stdin.write(opts.input);
      child.stdin.end();
    }
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
  const signed = cfg.final + ".signed" + process.pid;
  // 工具挂起（keystore 锁、磁盘满、僵尸进程）时必须超时，否则 currentTask 永不释放、
  // 后续发布全部 409；finally 兜底清理临时文件，避免 verify 失败后残留 21MB×2
  const SIGN_TIMEOUT = 10 * 60 * 1000;
  try {
    await run(ZIPALIGN, ["-f", "4", cfg.unsigned, aligned], { timeout: SIGN_TIMEOUT });
    // keystore 口令经 stdin 传入：--ks-pass pass:xxx 会让口令出现在子进程 argv，
    // 同机其他用户可从 /proc/<pid>/cmdline 明文读到，导致签名私钥口令泄漏
    await run(APKSIGNER, [
      "sign", "--ks", KEYSTORE, "--ks-pass", "file:/dev/stdin",
      "--ks-key-alias", KEYSTORE_ALIAS, "--out", signed, aligned,
    ], { timeout: SIGN_TIMEOUT, input: KEYSTORE_PASS });
    await run(APKSIGNER, ["verify", "--verbose", signed], { timeout: SIGN_TIMEOUT });
    atomicWrite(cfg.final, fs.readFileSync(signed));
  } finally {
    try { fs.unlinkSync(aligned); } catch (e) {}
    try { fs.unlinkSync(signed); } catch (e) {}
  }
  task.log.push(`${cfg.label} 签名完成 -> ${cfg.final}`);
}

function loadConfig() {
  try {
    const j = JSON.parse(fs.readFileSync(CONFIG_FILE, "utf8"));
    // minVersion: 最低可用版本号（versionCode）。0 或缺省 = 不启用版本门禁
    return { changelog: j.changelog || "", forced: !!j.forced, minVersionCode: parseInt(j.minVersionCode, 10) || 0 };
  } catch (e) {
    return { changelog: "", forced: false, minVersionCode: 0 };
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
    // 备份必须在写入之前，否则 rollback 恢复的是已 bump 的内容，等于空操作
    task.bumpedBackup.push({ gradle: cfg.gradle, content: fs.readFileSync(cfg.gradle, "utf8") });
    writeGradleVersion(cfg.gradle, nextCode, task.versionName);
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
  // 版本门禁：显式传 minVersion>0 则更新门禁值；显式传 0 解除门禁；缺省（undefined）保留现有配置
  if (task.minVersion !== undefined) cfg.minVersionCode = task.minVersion;
  saveConfig(cfg);
  task.log.push(`更新版本配置 release-config.json（minVersionCode=${cfg.minVersionCode}）`);
}

async function executeTask(task) {
  try {
    if (!KEYSTORE_PASS) {
      throw new Error("[publish] 必须设置 KEYSTORE_PASS 环境变量（签名密钥口令），当前未设置，终止发布");
    }
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
