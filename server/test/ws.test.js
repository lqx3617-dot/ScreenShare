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

const PASSWORD = "Passw0rd!";

function startServer(extraEnv = {}) {
  return new Promise((resolve, reject) => {
    const proc = spawn(process.execPath, [path.join(__dirname, "..", "server.js")], {
      env: {
        ...process.env,
        PORT: "0",
        ACCOUNT_DB: ":memory:",
        ...extraEnv,
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

async function registerUser(port, nickname) {
  const r = await httpJson(port, "POST", "/account/register", {
    nickname,
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
    const a = await registerUser(port, "测试甲");
    const b = await registerUser(port, "测试乙");

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

    // 未建房直接邀请：服务端要求先进入房间
    A.ws.send(JSON.stringify({ type: "share-invite", toUserId: b.userId, code: "4321" }));
    const needRoom = await waitFor(A.messages, (m) => m.type === "error");
    assert.match(needRoom.message, /请先进入共享房间/);

    // A 建房后再邀请 → B 收到定向邀请
    A.ws.send(JSON.stringify({ type: "create", code: "4321" }));
    await waitFor(A.messages, (m) => m.type === "created");
    A.ws.send(JSON.stringify({ type: "share-invite", toUserId: b.userId, code: "4321" }));
    const invite = await waitFor(B.messages, (m) => m.type === "share-invite" && m.code === "4321");
    assert.equal(invite.from.userId, a.userId);

    // B 接受 → A 收到结果
    B.ws.send(JSON.stringify({ type: "share-invite-accept", inviteId: invite.inviteId }));
    await waitFor(A.messages, (m) => m.type === "share-invite-result" && m.accepted === true);

    // 非好友邀请被拒
    const c = await registerUser(port, "测试丙");
    const C = connectWs(port, c.token);
    await C.ready;
    C.ws.send(JSON.stringify({ type: "share-invite", toUserId: b.userId, code: "9999" }));
    await waitFor(C.messages, (m) => m.type === "error");

    // 非房间 host 邀请好友进别人房间：被拒（C 未建 9999 房间）
    C.ws.send(JSON.stringify({ type: "create", code: "8888" }));
    await waitFor(C.messages, (m) => m.type === "created");
    C.ws.send(JSON.stringify({ type: "share-invite", toUserId: b.userId, code: "4321" }));
    const notHost = await waitFor(C.messages, (m) => m.type === "error" && m.message !== "请先登录");
    assert.match(notHost.message, /请先进入共享房间/);

    // A 断开 → B 收到 presence offline
    A.ws.close();
    await waitFor(B.messages, (m) => m.type === "presence" && m.userId === a.userId && m.online === false);
    B.ws.close();
    C.ws.close();
  } finally {
    proc.kill("SIGKILL");
  }
});

test("邀请归属校验：建房连接与发邀请连接不同时仍允许（按 userId 比对）", async () => {
  // 客户端实际有两条独立 WS：SignalWS 负责 create，PresenceClient 负责发邀请。
  // 按连接比对会导致 host 永远被拒，必须按账号身份比对。
  const { proc, port } = await startServer();
  try {
    const a = await registerUser(port, "房主甲");
    const b = await registerUser(port, "观众乙");
    const req = await httpJson(port, "POST", "/friends/request", { friendCode: b.profile.friendCode }, a.token);
    assert.equal(req.status, 200);
    const pending = await httpJson(port, "GET", "/friends/requests", null, b.token);
    const accepted = await httpJson(port, "POST", "/friends/accept", { requestId: pending.json[0].requestId }, b.token);
    assert.equal(accepted.status, 200);

    // SignalWS：只建房
    const signal = connectWs(port, a.token);
    assert.equal((await signal.ready).type, "auth-ok");
    signal.ws.send(JSON.stringify({ type: "create", code: "6677" }));
    await waitFor(signal.messages, (m) => m.type === "created");

    // PresenceClient：另一条连接，同账号，发邀请
    const presence = connectWs(port, a.token);
    assert.equal((await presence.ready).type, "auth-ok");
    const B = connectWs(port, b.token);
    assert.equal((await B.ready).type, "auth-ok");
    presence.ws.send(JSON.stringify({ type: "share-invite", toUserId: b.userId, code: "6677" }));

    // B 应收到邀请（不能是 error）
    const invite = await waitFor(B.messages, (m) => m.type === "share-invite" && m.code === "6677");
    assert.equal(invite.from.userId, a.userId);

    // 发邀请连接上不应有错误
    const errs = presence.messages.filter((m) => m.type === "error");
    assert.equal(errs.length, 0, `发邀请连接收到错误: ${JSON.stringify(errs)}`);

    signal.ws.close();
    presence.ws.close();
    B.ws.close();
  } finally {
    proc.kill("SIGKILL");
  }
});

test("邀请接受时房间已关闭则不计会话", async () => {
  const { proc, port } = await startServer();
  try {
    const a = await registerUser(port, "房东甲");
    const b = await registerUser(port, "房客乙");
    const req = await httpJson(port, "POST", "/friends/request", { friendCode: b.profile.friendCode }, a.token);
    assert.equal(req.status, 200);
    const pending = await httpJson(port, "GET", "/friends/requests", null, b.token);
    const accepted = await httpJson(port, "POST", "/friends/accept", { requestId: pending.json[0].requestId }, b.token);
    assert.equal(accepted.status, 200);

    const A = connectWs(port, a.token);
    await A.ready;
    const B = connectWs(port, b.token);
    await B.ready;

    A.ws.send(JSON.stringify({ type: "create", code: "5555" }));
    await waitFor(A.messages, (m) => m.type === "created");
    A.ws.send(JSON.stringify({ type: "share-invite", toUserId: b.userId, code: "5555" }));
    const invite = await waitFor(B.messages, (m) => m.type === "share-invite" && m.code === "5555");

    // host 先退出，房间关闭，B 才接受（须等 close 在服务端处理完，避免竞态）
    A.ws.close();
    await waitFor(B.messages, (m) => m.type === "presence" && m.userId === a.userId && m.online === false);

    // 邀请已被作废，accept 被拒，最近共享里不出现这条幽灵会话
    B.ws.send(JSON.stringify({ type: "share-invite-accept", inviteId: invite.inviteId }));
    const roomGone = await waitFor(B.messages, (m) => m.type === "error");
    assert.match(roomGone.message, /邀请不存在或已过期|会议已结束/);

    const recent = await httpJson(port, "GET", "/shares/recent", null, b.token);
    assert.equal(recent.status, 200);
    assert.equal(recent.json.length, 0);
    B.ws.close();
  } finally {
    proc.kill("SIGKILL");
  }
});

test("host 结束会议后作废暂存邀请并通知被邀请方", async () => {
  const { proc, port } = await startServer();
  try {
    const a = await registerUser(port, "房东甲");
    const b = await registerUser(port, "房客乙");
    const req = await httpJson(port, "POST", "/friends/request", { friendCode: b.profile.friendCode }, a.token);
    assert.equal(req.status, 200);
    const pending = await httpJson(port, "GET", "/friends/requests", null, b.token);
    const accepted = await httpJson(port, "POST", "/friends/accept", { requestId: pending.json[0].requestId }, b.token);
    assert.equal(accepted.status, 200);

    const A = connectWs(port, a.token);
    await A.ready;
    const B = connectWs(port, b.token);
    await B.ready;

    A.ws.send(JSON.stringify({ type: "create", code: "3322" }));
    await waitFor(A.messages, (m) => m.type === "created");
    A.ws.send(JSON.stringify({ type: "share-invite", toUserId: b.userId, code: "3322" }));
    await waitFor(B.messages, (m) => m.type === "share-invite" && m.code === "3322");

    // host 退出 → 邀请作废，B 收到 invite-cancelled（而不是等到 5 分钟超时）
    A.ws.close();
    const cancelled = await waitFor(B.messages, (m) => m.type === "invite-cancelled" && m.code === "3322");
    assert.ok(cancelled.inviteId);

    // 作废后 B 再 accept 应被拒绝（邀请已删除）
    B.ws.send(JSON.stringify({ type: "share-invite-accept", inviteId: cancelled.inviteId }));
    const gone = await waitFor(B.messages, (m) => m.type === "error");
    assert.match(gone.message, /邀请不存在或已过期/);
    B.ws.close();
  } finally {
    proc.kill("SIGKILL");
  }
});

test("viewer 断开后重连恢复：会话不结账，host 收 viewer-joined(reconnected)", async () => {
  const { proc, port } = await startServer({ RECONNECT_TIMEOUT_MS: "2000" });
  try {
    const a = await registerUser(port, "房东甲");
    const b = await registerUser(port, "房客乙");
    const req = await httpJson(port, "POST", "/friends/request", { friendCode: b.profile.friendCode }, a.token);
    assert.equal(req.status, 200);
    const pending = await httpJson(port, "GET", "/friends/requests", null, b.token);
    assert.equal((await httpJson(port, "POST", "/friends/accept", { requestId: pending.json[0].requestId }, b.token)).status, 200);

    const A = connectWs(port, a.token);
    await A.ready;
    const B = connectWs(port, b.token);
    await B.ready;

    A.ws.send(JSON.stringify({ type: "create", code: "7788" }));
    await waitFor(A.messages, (m) => m.type === "created");
    // 邀请被接受才开账共享会话（shareHistory.onStart）
    A.ws.send(JSON.stringify({ type: "share-invite", toUserId: b.userId, code: "7788" }));
    const invite5 = await waitFor(B.messages, (m) => m.type === "share-invite" && m.code === "7788");
    B.ws.send(JSON.stringify({ type: "share-invite-accept", inviteId: invite5.inviteId }));
    await waitFor(A.messages, (m) => m.type === "share-invite-result" && m.accepted === true);
    B.ws.send(JSON.stringify({ type: "join", code: "7788" }));
await waitFor(B.messages, (m) => m.type === "joined" && m.code === "7788");
    await waitFor(A.messages, (m) => m.type === "viewer-joined" && m.reconnected === undefined);
    const firstVid = A.messages.find((m) => m.type === "viewer-joined").viewerId;

    // viewer 断开：进入宽限期，host 不应收到 viewer-left
    B.ws.close();
    // 等 close 在服务端处理完（A 收到 B 下线广播后再宽限 reconnecting 已落地）
    await waitFor(A.messages, (m) => m.type === "presence" && m.userId === b.userId && m.online === false);
    await new Promise((r) => setTimeout(r, 300));
    assert.ok(!A.messages.some((m) => m.type === "viewer-left"), "宽限期内 host 不应收到 viewer-left");

    // viewer 用同身份重连：恢复原 viewerId，host 收到 reconnected=true 的 viewer-joined
    const B2 = connectWs(port, b.token);
    await B2.ready;
    B2.ws.send(JSON.stringify({ type: "join", code: "7788" }));
    const rejoined = await waitFor(B2.messages, (m) => m.type === "joined" && m.code === "7788");
    assert.equal(rejoined.viewerId, firstVid, "恢复后 viewerId 不变");
    const reMsg = await waitFor(A.messages, (m) => m.type === "viewer-joined" && m.reconnected === true);
    assert.equal(reMsg.viewerId, firstVid);

    // 宽限期早已用完也不应再收到 viewer-left（已恢复）
    await new Promise((r) => setTimeout(r, 2500));
    assert.ok(!A.messages.some((m) => m.type === "viewer-left"), "恢复后不应再收到 viewer-left");

    // host 正常断开才结账：最近共享出现一条记录
    A.ws.close();
    B2.ws.close();
    await new Promise((r) => setTimeout(r, 400));
    const recent = await httpJson(port, "GET", "/shares/recent", null, b.token);
    assert.equal(recent.status, 200);
    assert.equal(recent.json.length, 1, "会话在 host 离开后结账一次");
  } finally {
    proc.kill("SIGKILL");
  }
});

test("viewer 断开后超时未重连：结账并通知 host viewer-left", async () => {
  const { proc, port } = await startServer({ RECONNECT_TIMEOUT_MS: "700" });
  try {
    const a = await registerUser(port, "房东甲");
    const b = await registerUser(port, "房客乙");
    const req = await httpJson(port, "POST", "/friends/request", { friendCode: b.profile.friendCode }, a.token);
    assert.equal(req.status, 200);
    const pending = await httpJson(port, "GET", "/friends/requests", null, b.token);
    assert.equal((await httpJson(port, "POST", "/friends/accept", { requestId: pending.json[0].requestId }, b.token)).status, 200);

    const A = connectWs(port, a.token);
    await A.ready;
    const B = connectWs(port, b.token);
    await B.ready;

    A.ws.send(JSON.stringify({ type: "create", code: "8899" }));
    await waitFor(A.messages, (m) => m.type === "created");
    A.ws.send(JSON.stringify({ type: "share-invite", toUserId: b.userId, code: "8899" }));
    const invite6 = await waitFor(B.messages, (m) => m.type === "share-invite" && m.code === "8899");
    B.ws.send(JSON.stringify({ type: "share-invite-accept", inviteId: invite6.inviteId }));
    await waitFor(A.messages, (m) => m.type === "share-invite-result" && m.accepted === true);
    B.ws.send(JSON.stringify({ type: "join", code: "8899" }));
    await waitFor(B.messages, (m) => m.type === "joined" && m.code === "8899");

    // viewer 断开后不重连，超时后 host 收到 viewer-left 且会话结账
    B.ws.close();
    const left = await waitFor(A.messages, (m) => m.type === "viewer-left", 4000);
    assert.ok(left, "宽限期超时后 host 应收到 viewer-left");

    A.ws.close();
    await new Promise((r) => setTimeout(r, 400));
    const recent = await httpJson(port, "GET", "/shares/recent", null, b.token);
    assert.equal(recent.status, 200);
    assert.equal(recent.json.length, 1, "超时后会话结账");
  } finally {
    proc.kill("SIGKILL");
  }
});

test("viewer 宽限期内第三个加入者被拒", async () => {
  const { proc, port } = await startServer({ RECONNECT_TIMEOUT_MS: "5000" });
  try {
    const a = await registerUser(port, "房东甲");
    const b = await registerUser(port, "房客乙");
    const c = await registerUser(port, "路人丙");
    for (const [t1, t2] of [[a, b], [a, c]]) {
      const req = await httpJson(port, "POST", "/friends/request", { friendCode: t2.profile.friendCode }, t1.token);
      assert.equal(req.status, 200);
      const pending = await httpJson(port, "GET", "/friends/requests", null, t2.token);
      assert.equal((await httpJson(port, "POST", "/friends/accept", { requestId: pending.json[0].requestId }, t2.token)).status, 200);
    }

    const A = connectWs(port, a.token);
    await A.ready;
    const B = connectWs(port, b.token);
    await B.ready;
    const C = connectWs(port, c.token);
    await C.ready;

    A.ws.send(JSON.stringify({ type: "create", code: "5151" }));
    await waitFor(A.messages, (m) => m.type === "created");
    B.ws.send(JSON.stringify({ type: "join", code: "5151" }));
    await waitFor(B.messages, (m) => m.type === "joined" && m.code === "5151");

    // viewer 断开进入宽限期：期间另一人加入应被拒
    B.ws.close();
    await new Promise((r) => setTimeout(r, 300));
    C.ws.send(JSON.stringify({ type: "join", code: "5151" }));
    const refused = await waitFor(C.messages, (m) => m.type === "error");
    assert.match(refused.message, /正在重新连接/);

    A.ws.close();
    C.ws.close();
  } finally {
    proc.kill("SIGKILL");
  }
});
