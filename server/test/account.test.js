"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const http = require("node:http");

const { openDb } = require("../db");
const { RateLimiter } = require("../RateLimiter");
const { AccountManager, AccountError } = require("../AccountManager");
const { AccountRouter } = require("../AccountRouter");

const PASSWORD = "Passw0rd!";

function makeEnv() {
  const db = openDb(":memory:");
  const rateLimiter = new RateLimiter();
  const manager = new AccountManager(db);
  return { db, rateLimiter, manager };
}

async function registerUser(env, nickname, password = PASSWORD) {
  return env.manager.register(nickname, password);
}

test("注册成功签发 userId/token/friendCode 与昵称", async () => {
  const env = makeEnv();
  const res = await registerUser(env, "小南");
  assert.match(res.userId, /^[0-9a-f-]{36}$/);
  assert.equal(typeof res.token, "string");
  assert.equal(res.token.length, 64);
  assert.equal(res.profile.friendCode.length, 6);
  assert.equal(res.profile.nickname, "小南");
});

test("昵称重复注册被拒绝", async () => {
  const env = makeEnv();
  await registerUser(env, "阿远");
  await assert.rejects(
    () => registerUser(env, "阿远"),
    (e) => e instanceof AccountError && e.code === "nickname_taken"
  );
});

test("弱密码注册被拒绝", async () => {
  const env = makeEnv();
  await assert.rejects(
    () => registerUser(env, "弱密码", "12345678"),
    (e) => e instanceof AccountError && e.code === "weak_password"
  );
  await assert.rejects(
    () => registerUser(env, "短密码", "short"),
    (e) => e instanceof AccountError && e.code === "weak_password"
  );
});

test("昵称长度校验", async () => {
  const env = makeEnv();
  await assert.rejects(
    () => registerUser(env, "一"),
    (e) => e instanceof AccountError && e.code === "invalid_nickname"
  );
  await assert.rejects(
    () => registerUser(env, "x".repeat(21)),
    (e) => e instanceof AccountError && e.code === "invalid_nickname"
  );
});

test("登录成功并可鉴权，登出后令牌失效", async () => {
  const env = makeEnv();
  const reg = await registerUser(env, "carol");
  const login = await env.manager.login("carol", PASSWORD);
  assert.equal(login.userId, reg.userId);

  assert.equal(env.manager.authenticate(login.token), reg.userId);
  env.manager.logout(login.token);
  assert.throws(
    () => env.manager.authenticate(login.token),
    (e) => e instanceof AccountError && e.code === "unauthenticated"
  );
});

test("连续 5 次密码失败后第 6 次被限流", async () => {
  const env = makeEnv();
  await registerUser(env, "锁定用户");
  for (let i = 0; i < 5; i++) {
    await assert.rejects(
      () => env.manager.login("锁定用户", "WrongPass1!"),
      (e) => e instanceof AccountError && e.code === "invalid_credentials"
    );
  }
  await assert.rejects(
    () => env.manager.login("锁定用户", PASSWORD),
    (e) => e instanceof AccountError && e.code === "too_many_attempts"
  );
});

test("多设备登录互不影响，单设备登出只失效本设备令牌", async () => {
  const env = makeEnv();
  await registerUser(env, "多设备");
  const a = await env.manager.login("多设备", PASSWORD, "device-a");
  const b = await env.manager.login("多设备", PASSWORD, "device-b");
  env.manager.logout(a.token);
  assert.throws(() => env.manager.authenticate(a.token));
  assert.equal(typeof env.manager.authenticate(b.token), "string");
});

test("资料更新校验昵称长度与头像档位", async () => {
  const env = makeEnv();
  const reg = await registerUser(env, "资料用户");
  const updated = env.manager.updateProfile(reg.userId, { nickname: "新昵称", avatar: "3" });
  assert.equal(updated.nickname, "新昵称");
  assert.equal(updated.avatar, "3");
  assert.throws(
    () => env.manager.updateProfile(reg.userId, { nickname: "x".repeat(21) }),
    (e) => e instanceof AccountError && e.code === "invalid_nickname"
  );
  assert.throws(
    () => env.manager.updateProfile(reg.userId, { avatar: "9" }),
    (e) => e instanceof AccountError && e.code === "invalid_avatar"
  );
});

test("REST 端到端：注册→登录→me→改资料→登出", async () => {
  const env = makeEnv();
  const router = new AccountRouter({ accountManager: env.manager, rateLimiter: env.rateLimiter });
  const server = http.createServer((req, res) => {
    if (router.handle(req, res)) return;
    res.writeHead(404);
    res.end();
  });
  await new Promise((resolve) => server.listen(0, resolve));
  const base = `http://127.0.0.1:${server.address().port}`;
  const post = (path, body, token) =>
    fetch(base + path, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
      },
      body: JSON.stringify(body),
    });

  try {
    let r = await post("/account/register", { nickname: "端到端", password: PASSWORD });
    assert.equal(r.status, 200);
    const reg = await r.json();
    assert.ok(reg.token);

    r = await post("/account/login", { nickname: "端到端", password: PASSWORD });
    assert.equal(r.status, 200);
    assert.equal((await r.json()).userId, reg.userId);

    r = await fetch(base + "/account/me", { headers: { Authorization: `Bearer ${reg.token}` } });
    assert.equal(r.status, 200);
    assert.equal((await r.json()).userId, reg.userId);

    r = await fetch(base + "/account/profile", {
      method: "PATCH",
      headers: { "Content-Type": "application/json", Authorization: `Bearer ${reg.token}` },
      body: JSON.stringify({ nickname: "阿远" }),
    });
    assert.equal(r.status, 200);
    assert.equal((await r.json()).nickname, "阿远");

    r = await fetch(base + "/account/me");
    assert.equal(r.status, 401);

    r = await fetch(base + "/account/nope");
    assert.equal(r.status, 404);

    r = await post("/account/logout", {}, reg.token);
    assert.equal(r.status, 200);
    r = await fetch(base + "/account/me", { headers: { Authorization: `Bearer ${reg.token}` } });
    assert.equal(r.status, 401);
  } finally {
    await new Promise((resolve) => server.close(resolve));
  }
});
