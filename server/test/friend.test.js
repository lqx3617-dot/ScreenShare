"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const http = require("node:http");

const { openDb } = require("../db");
const { RateLimiter } = require("../RateLimiter");
const { AccountManager, AccountError } = require("../AccountManager");
const { FriendManager } = require("../FriendManager");
const { PresenceManager } = require("../PresenceManager");
const { AccountRouter } = require("../AccountRouter");

const PASSWORD = "Passw0rd!";

function makeEnv() {
  const db = openDb(":memory:");
  const rateLimiter = new RateLimiter();
  const accounts = new AccountManager(db);
  const friends = new FriendManager(db);
  const presence = new PresenceManager();
  return { db, rateLimiter, accounts, friends, presence };
}

async function register(env, nickname) {
  return env.accounts.register(nickname, PASSWORD);
}

test("好友申请经对方接受后双向成立", async () => {
  const env = makeEnv();
  const a = await register(env, "小南");
  const b = await register(env, "阿远");

  const r = env.friends.request(a.userId, b.profile.friendCode);
  assert.equal(r.accepted, false);
  assert.equal(env.friends.list(a.userId).length, 0);

  const pending = env.friends.pendingRequests(b.userId);
  assert.equal(pending.length, 1);
  assert.equal(pending[0].from.userId, a.userId);

  env.friends.accept(b.userId, r.requestId);
  assert.deepEqual(env.friends.list(a.userId).map((f) => f.userId), [b.userId]);
  assert.deepEqual(env.friends.list(b.userId).map((f) => f.userId), [a.userId]);
});

test("重复申请幂等；已是好友再申请被拒", async () => {
  const env = makeEnv();
  const a = await register(env, "小南");
  const b = await register(env, "阿远");

  const r1 = env.friends.request(a.userId, b.profile.friendCode);
  const r2 = env.friends.request(a.userId, b.profile.friendCode);
  assert.equal(r1.requestId, r2.requestId);

  env.friends.accept(b.userId, r1.requestId);
  assert.throws(
    () => env.friends.request(a.userId, b.profile.friendCode),
    (e) => e instanceof AccountError && e.code === "already_friends"
  );
});

test("反向申请直接互为好友", async () => {
  const env = makeEnv();
  const a = await register(env, "小南");
  const b = await register(env, "阿远");

  env.friends.request(a.userId, b.profile.friendCode);
  const back = env.friends.request(b.userId, a.profile.friendCode);
  assert.equal(back.accepted, true);
  assert.deepEqual(env.friends.list(a.userId).map((f) => f.userId), [b.userId]);
  assert.equal(env.friends.pendingRequests(b.userId).length, 0);
});

test("无效好友码与添加自己被拒", async () => {
  const env = makeEnv();
  const a = await register(env, "小南");
  assert.throws(
    () => env.friends.request(a.userId, "ZZZZZZ"),
    (e) => e instanceof AccountError && e.code === "invalid_code"
  );
  assert.throws(
    () => env.friends.request(a.userId, a.profile.friendCode),
    (e) => e instanceof AccountError && e.code === "self_request"
  );
});

test("拒绝后申请方可再次申请；删除好友双向解除", async () => {
  const env = makeEnv();
  const a = await register(env, "小南");
  const b = await register(env, "阿远");

  const r = env.friends.request(a.userId, b.profile.friendCode);
  env.friends.reject(b.userId, r.requestId);
  const again = env.friends.request(a.userId, b.profile.friendCode);
  assert.notEqual(again.requestId, r.requestId);

  env.friends.accept(b.userId, again.requestId);
  env.friends.remove(a.userId, b.userId);
  assert.equal(env.friends.list(a.userId).length, 0);
  assert.equal(env.friends.list(b.userId).length, 0);
});

test("PresenceManager 多连接聚合在线状态", () => {
  const presence = new PresenceManager();
  const ws1 = {};
  const ws2 = {};
  assert.equal(presence.attach("u1", ws1), true);
  assert.equal(presence.attach("u1", ws2), false);
  assert.equal(presence.isOnline("u1"), true);
  assert.equal(presence.detach("u1", ws1), false);
  assert.equal(presence.isOnline("u1"), true);
  assert.equal(presence.detach("u1", ws2), true);
  assert.equal(presence.isOnline("u1"), false);
});

test("REST 好友流程与在线状态注入", async () => {
  const env = makeEnv();
  const a = await register(env, "小南");
  const b = await register(env, "阿远");

  const router = new AccountRouter({
    accountManager: env.accounts,
    friendManager: env.friends,
    rateLimiter: env.rateLimiter,
    presence: env.presence,
  });
  const server = http.createServer((req, res) => {
    if (router.handle(req, res)) return;
    res.writeHead(404);
    res.end();
  });
  await new Promise((resolve) => server.listen(0, resolve));
  const base = `http://127.0.0.1:${server.address().port}`;
  const call = (method, path, body, token) =>
    fetch(base + path, {
      method,
      headers: {
        ...(body ? { "Content-Type": "application/json" } : {}),
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
      },
      ...(body ? { body: JSON.stringify(body) } : {}),
    });

  try {
    let r = await call("POST", "/friends/request", { friendCode: b.profile.friendCode }, a.token);
    assert.equal(r.status, 200);
    const { requestId } = await r.json();

    r = await call("GET", "/friends/requests", null, b.token);
    assert.equal((await r.json()).length, 1);

    r = await call("POST", "/friends/accept", { requestId }, b.token);
    assert.equal(r.status, 200);

    r = await call("GET", "/friends", null, a.token);
    let list = await r.json();
    assert.equal(list.length, 1);
    assert.equal(list[0].online, false);

    env.presence.attach(b.userId, {});
    r = await call("GET", "/friends", null, a.token);
    list = await r.json();
    assert.equal(list[0].online, true);

    r = await call("DELETE", `/friends/${b.userId}`, null, a.token);
    assert.equal(r.status, 200);
    r = await call("GET", "/friends", null, a.token);
    assert.equal((await r.json()).length, 0);
  } finally {
    await new Promise((resolve) => server.close(resolve));
  }
});
