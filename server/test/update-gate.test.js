// 版本门禁配置测试：release-config.json 的 minVersionCode 读取/持久化
const fs = require("fs");
const test = require("node:test");
const assert = require("node:assert/strict");
const { loadConfig, saveConfig } = require("../publish");

const CONFIG_FILE = "/workspace/server/release-config.json";
const orig = fs.existsSync(CONFIG_FILE) ? fs.readFileSync(CONFIG_FILE, "utf8") : null;

function restore() {
  // 仅进程退出时恢复，避免在测试用例执行前就改写配置文件
  if (orig !== null) fs.writeFileSync(CONFIG_FILE, orig, "utf8");
  else {
    try { fs.unlinkSync(CONFIG_FILE); } catch (e) {}
  }
}

test("loadConfig 读取 minVersionCode（含值）", () => {
  saveConfig({ changelog: "t", forced: false, minVersionCode: 321 });
  const cfg = loadConfig();
  assert.strictEqual(cfg.minVersionCode, 321);
});

test("loadConfig 缺省 minVersionCode 为 0（不启用门禁）", () => {
  fs.writeFileSync(CONFIG_FILE, JSON.stringify({ changelog: "x", forced: true }), "utf8");
  const cfg = loadConfig();
  assert.strictEqual(cfg.minVersionCode, 0);
});

test("loadConfig 对非法/缺失 minVersionCode 容错为 0", () => {
  fs.writeFileSync(CONFIG_FILE, JSON.stringify({ changelog: "x", minVersionCode: "abc" }), "utf8");
  assert.strictEqual(loadConfig().minVersionCode, 0);
  fs.writeFileSync(CONFIG_FILE, JSON.stringify({ changelog: "x", minVersionCode: null }), "utf8");
  assert.strictEqual(loadConfig().minVersionCode, 0);
});

test("saveConfig 回写 minVersionCode 并可被 loadConfig 还原", () => {
  saveConfig({ changelog: "gate", forced: false, minVersionCode: 330 });
  const again = loadConfig();
  assert.strictEqual(again.minVersionCode, 330);
  assert.strictEqual(again.changelog, "gate");
});

test("配置文件不存在时 loadConfig 返回安全默认", () => {
  try { fs.unlinkSync(CONFIG_FILE); } catch (e) {}
  const cfg = loadConfig();
  assert.strictEqual(cfg.minVersionCode, 0);
  assert.strictEqual(cfg.forced, false);
});

// 无论测试成功失败都恢复原始配置，避免污染生产 version.json
process.on("exit", restore);
