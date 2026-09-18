"use strict";

/**
 * 端到端集成测试：真实启动 server.js 子进程，验证 WS 认领、在线状态广播与一键共享邀请。
 */

const test = require("node:test");
const assert = require("node:assert/strict");
const { spawn } = require("node:child_process");
const http = require("node:http");
const path = require("node:path");
const WebSocket = require("ws");

const FIXED_CODE = "123456";
const PASSWORD = "Passw0rd!";

function startServer() {
  return new Promise((resolve, reject) => {
    const proc = spawn(process.execPath, [path.join(__dirname, "..", "server.js")], {
      env: {
        ...process.env,
        PORT: "0",
        ACCOUNT_DB: ":memory:",
        ACCOUNT_FIXED_CODE: FIXED_CODE,
        MAIL_DEV_MODE: "1",
      },
      stdio: ["ignore", "pipe", "pipe"],
    });
    let buf = "";
    proc.stdout.on("data", (c) => (buf += c));
    proc.stderr.on("data", (c) => (buf += c));
    const timer = setTimeout(() => {
      proc.kill("SIGKILL");
      reject(new Error("server start timeout: " + buf.slice(-600)));
    }, 6000);
    const iv = setInterval(() => {
      const m = /listening on :(\d+)/.exec(buf);
      if (m) {
        clearTimeout(timer);
        clearInterval(iv);
        resolve({ proc, port: Number(m[1]) });
      }
    }, 100);
    proc.on("exit", (code) => {
      clearTimeout(timer);
      clearInterval(iv);
      reject(new Error(`server exited code=${code}: ${buf.slice(-600)}`));
    });
  });
}

function httpJson(port, method, p, body, token) {
  return new Promise((resolve, reject) => {
    const data = body ? JSON.stringify(body) : null;
    const req = http.request(
      {
        host: "127.0.0.1",
        port,
        method,
        path: p,
        headers: {
          "Content-Type": "application/json",
          ...(data ? { "Content-Length": Buffer.byteLength(data) } : {}),
          ...(token ? { Authorization: `Bearer ${token}` } : {}),
        },
      },
      (res) => {
        let raw = "";
        res.on("data", (c) => (raw += c));
        res.on("end", () => {
          try {
            resolve({ status: res.statusCode, json: raw ? JSON.parse(raw) : {} });
          } catch (e) {
            resolve({ status: res.statusCode, json: null, raw });
          }
        });
      }
    );
    req.on("error", reject);
    if (data) req.write(data);
    req.end();
  });
}

async function registerUser(port, email) {
  await httpJson(port, "POST", "/account/register/request-code", { email });
  const r = await httpJson(port, "POST", "/account/register", {
    email,
    code: FIXED_CODE,
    password: PASSWORD,
  });
  assert.equal(r.status, 200, `注册失败: ${JSON.stringify(r.json)}`);
  return r.json;
}

function connectWs(port, token) {
  const ws = new WebSocket(`ws://127.0.0.1:${port}/ws`);
  const messages = [];
  const ready = new Promise((resolve, reject) => {
    ws.on("open", () => ws.send(JSON.stringify({ type: "auth", token })));
    ws.on("message", (raw) => {
      const m = JSON.parse(raw.toString());
      messages.push(m);
      if (m.type === "auth-ok" || m.type === "auth-error") resolve(m);
    });
    ws.on("error", reject);
  });
  return { ws, messages, ready };
}

function waitFor(messages, predicate, timeoutMs = 5000) {
  return new Promise((resolve, reject) => {
    const found = messages.find(predicate);
    if (found) return resolve(found);
    const timer = setTimeout(
      () => reject(new Error("等待消息超时，已收到: " + JSON.stringify(messages))),
      timeoutMs
    );
    const iv = setInterval(() => {
      const f = messages.find(predicate);
      if (f) {
        clearTimeout(timer);
        clearInterval(iv);
        resolve(f);
      }
    }, 50);
  });
}

test("WS 认领、在线广播与一键共享邀请闭环", async () => {
  const { proc, port } = await startServer();
  try {
    const a = await registerUser(port, "ws-a@example.com");
    const b = await registerUser(port, "ws-b@example.com");

    const req = await httpJson(port, "POST", "/friends/request", { friendCode: b.profile.friendCode }, a.token);
    assert.equal(req.status, 200);
    const pending = await httpJson(port, "GET", "/friends/requests", null, b.token);
    const requestId = pending.json[0].requestId;
    const accepted = await httpJson(port, "POST", "/friends/accept", { requestId }, b.token);
    assert.equal(accepted.status, 200);

    const A = connectWs(port, a.token);
    assert.equal((await A.ready).type, "auth-ok");
    const B = connectWs(port, b.token);
    assert.equal((await B.ready).type, "auth-ok");

    // B 上线 → 好友 A 收到 presence online
    await waitFor(A.messages, (m) => m.type === "presence" && m.userId === b.userId && m.online === true);

    // A 一键发起共享 → B 收到定向邀请
    A.ws.send(JSON.stringify({ type: "share-invite", toUserId: b.userId, code: "4321" }));
    const invite = await waitFor(B.messages, (m) => m.type === "share-invite" && m.code === "4321");
    assert.equal(invite.from.userId, a.userId);

    // B 接受 → A 收到结果
    B.ws.send(JSON.stringify({ type: "share-invite-accept", inviteId: invite.inviteId }));
    await waitFor(A.messages, (m) => m.type === "share-invite-result" && m.accepted === true);

    // 非好友邀请被拒
    const c = await registerUser(port, "ws-c@example.com");
    const C = connectWs(port, c.token);
    await C.ready;
    C.ws.send(JSON.stringify({ type: "share-invite", toUserId: b.userId, code: "9999" }));
    await waitFor(C.messages, (m) => m.type === "error");

    // A 断开 → B 收到 presence offline
    A.ws.close();
    await waitFor(B.messages, (m) => m.type === "presence" && m.userId === a.userId && m.online === false);
    B.ws.close();
    C.ws.close();
  } finally {
    proc.kill("SIGKILL");
  }
});
