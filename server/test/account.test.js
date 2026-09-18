"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const http = require("node:http");

const { openDb } = require("../db");
const { VerificationStore } = require("../VerificationStore");
const { RateLimiter } = require("../RateLimiter");
const { AccountManager, AccountError } = require("../AccountManager");
const { AccountRouter } = require("../AccountRouter");

const PASSWORD = "Passw0rd!";

function makeEnv() {
  const db = openDb(":memory:");
  const verificationStore = new VerificationStore(db);
  const rateLimiter = new RateLimiter();
  const sent = new Map();
  const mailer = {
    sendCode: async (email, code, purpose) => {
      sent.set(`${email}:${purpose}`, code);
      return { ok: true };
    },
  };
  const manager = new AccountManager(db, { verificationStore, rateLimiter, mailer });
  return { db, verificationStore, rateLimiter, sent, manager };
}

async function registerUser(env, email, password = PASSWORD) {
  await env.manager.requestRegisterCode(email);
  const code = env.sent.get(`${String(email).trim().toLowerCase()}:register`);
  assert.ok(code, "注册验证码应已发送");
  return env.manager.register(email, code, password);
}

test("注册成功签发 userId/token/friendCode 与默认昵称", async () => {
  const env = makeEnv();
  const res = await registerUser(env, "Alice@Example.com");
  assert.match(res.userId, /^[0-9a-f-]{36}$/);
  assert.equal(typeof res.token, "string");
  assert.equal(res.token.length, 64);
  assert.equal(res.profile.friendCode.length, 6);
  assert.equal(res.profile.email, "alice@example.com");
  assert.equal(res.profile.nickname, "alice");
});

test("邮箱重复注册被拒绝", async () => {
  const env = makeEnv();
  await registerUser(env, "bob@example.com");
  await assert.rejects(
    () => env.manager.requestRegisterCode("bob@example.com"),
    (e) => e instanceof AccountError && e.code === "email_taken"
  );
});

test("弱密码注册被拒绝", async () => {
  const env = makeEnv();
  await env.manager.requestRegisterCode("weak@example.com");
  const code = env.sent.get("weak@example.com:register");
  await assert.rejects(
    () => env.manager.register("weak@example.com", code, "12345678"),
    (e) => e instanceof AccountError && e.code === "weak_password"
  );
  await assert.rejects(
    () => env.manager.register("weak@example.com", code, "short"),
    (e) => e instanceof AccountError && e.code === "weak_password"
  );
});

test("错误验证码注册被拒绝", async () => {
  const env = makeEnv();
  await env.manager.requestRegisterCode("code@example.com");
  await assert.rejects(
    () => env.manager.register("code@example.com", "000000", PASSWORD),
    (e) => e instanceof AccountError && e.code === "invalid_code"
  );
});

test("登录成功并可鉴权，登出后令牌失效", async () => {
  const env = makeEnv();
  const reg = await registerUser(env, "carol@example.com");
  const login = await env.manager.login("carol@example.com", PASSWORD);
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
  await registerUser(env, "lock@example.com");
  for (let i = 0; i < 5; i++) {
    await assert.rejects(
      () => env.manager.login("lock@example.com", "WrongPass1!"),
      (e) => e instanceof AccountError && e.code === "invalid_credentials"
    );
  }
  await assert.rejects(
    () => env.manager.login("lock@example.com", PASSWORD),
    (e) => e instanceof AccountError && e.code === "too_many_attempts"
  );
});

test("多设备登录互不影响，单设备登出只失效本设备令牌", async () => {
  const env = makeEnv();
  await registerUser(env, "multi@example.com");
  const a = await env.manager.login("multi@example.com", PASSWORD, "device-a");
  const b = await env.manager.login("multi@example.com", PASSWORD, "device-b");
  env.manager.logout(a.token);
  assert.throws(() => env.manager.authenticate(a.token));
  assert.equal(typeof env.manager.authenticate(b.token), "string");
});

test("重置密码后全部旧令牌失效", async () => {
  const env = makeEnv();
  const reg = await registerUser(env, "reset@example.com");
  await env.manager.requestResetCode("reset@example.com");
  const code = env.sent.get("reset@example.com:reset");
  await env.manager.resetPassword("reset@example.com", code, "NewPassw0rd!");
  assert.throws(
    () => env.manager.authenticate(reg.token),
    (e) => e instanceof AccountError && e.code === "unauthenticated"
  );
  const relogin = await env.manager.login("reset@example.com", "NewPassw0rd!");
  assert.equal(relogin.userId, reg.userId);
});

test("资料更新校验昵称长度与头像档位", async () => {
  const env = makeEnv();
  const reg = await registerUser(env, "profile@example.com");
  const updated = env.manager.updateProfile(reg.userId, { nickname: "小南", avatar: "3" });
  assert.equal(updated.nickname, "小南");
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
    let r = await post("/account/register/request-code", { email: "e2e@example.com" });
    assert.equal(r.status, 200);
    const code = env.sent.get("e2e@example.com:register");

    r = await post("/account/register", { email: "e2e@example.com", code, password: PASSWORD });
    assert.equal(r.status, 200);
    const reg = await r.json();
    assert.ok(reg.token);

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
