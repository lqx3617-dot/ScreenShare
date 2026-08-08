// V4 信令协议集成测试（事件队列版，无竞态）
"use strict";
const WebSocket = require("ws");
const URL = process.env.TEST_URL || "ws://localhost:8081/ws";
let pass = 0, fail = 0;
function check(name, cond) { if (cond) { pass++; console.log(`  PASS  ${name}`); } else { fail++; console.log(`  FAIL  ${name}`); } }

class Client {
  constructor(name) {
    this.name = name;
    this.queue = [];
    this.waiters = [];
    this.ws = new WebSocket(URL);
    this.ws.on("message", (raw) => {
      const m = JSON.parse(raw.toString());
      const w = this.waiters.shift();
      if (w) w(m); else this.queue.push(m);
    });
  }
  open() { return new Promise((r) => this.ws.on("open", r)); }
  send(o) { this.ws.send(JSON.stringify(o)); }
  next() { if (this.queue.length) return Promise.resolve(this.queue.shift()); return new Promise((r) => this.waiters.push(r)); }
  nextType(t, timeoutMs = 3000) {
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error(`${this.name} 等待 ${t} 超时`)), timeoutMs);
      const poll = () => {
        const idx = this.queue.findIndex((m) => m.type === t);
        if (idx >= 0) { clearTimeout(timer); resolve(this.queue.splice(idx, 1)[0]); }
        else { this.waiters.push((m) => { if (m.type === t) { clearTimeout(timer); resolve(m); } else this.queue.push(m); }); }
      };
      poll();
    });
  }
  close() { try { this.ws.close(); } catch (e) {} }
}
const wait = (ms) => new Promise((r) => setTimeout(r, ms));

async function main() {
  console.log("== V4 信令协议测试 ==");

  const host = new Client("host");
  await host.open();
  host.send({ type: "create", code: "1234" });
  const created = await host.nextType("created");
  check("host 创建成功", created.code === "1234");

  // viewer1 加入
  const v1 = new Client("v1");
  await v1.open();
  v1.send({ type: "join", code: "1234" });
  const joined1 = await v1.nextType("joined");
  check("viewer1 加入成功", joined1.viewerId > 0);

  // viewer2 加入
  const v2 = new Client("v2");
  await v2.open();
  v2.send({ type: "join", code: "1234" });
  const joined2 = await v2.nextType("joined");
  check("viewer2 加入成功", joined2.viewerId > 0 && joined2.viewerId !== joined1.viewerId);

  // host 收到两个 viewer-joined
  const hj1 = await host.nextType("viewer-joined");
  const hj2 = await host.nextType("viewer-joined");
  check("host 收到 2 个 viewer-joined", hj1.viewerId !== hj2.viewerId);

  // host -> viewer1 定向 relay
  v1.send({ type: "relay", data: "answer-v1" });
  const hGot = await host.nextType("relay");
  check("viewer1->host relay 成功", hGot.data === "answer-v1" && hGot.viewerId === hj1.viewerId);

  host.send({ type: "relay", data: "hello-v1", viewerId: hj1.viewerId });
  const v1Got = await v1.nextType("relay");
  check("host->viewer1 定向 relay 成功", v1Got.data === "hello-v1" && v1Got.viewerId === hj1.viewerId);

  // viewer2 不应收到 host 定向给 v1 的消息（用空队列确认）
  await wait(300);
  check("viewer2 未收到定向给 viewer1 的消息", v2.queue.length === 0);

  // viewer1 离开 -> host 收 viewer-left
  v1.close();
  const vl = await host.nextType("viewer-left");
  check("viewer1 离开 host 收到 viewer-left", vl.viewerId === hj1.viewerId);

  // host 离开 -> viewer2 收 host-left
  host.close();
  const hl = await v2.nextType("host-left");
  check("host 离开 viewer2 收到 host-left", hl.type === "host-left");
  v2.close();

  // 房间销毁后新 viewer 加入被拒
  const late = new Client("late");
  await late.open();
  late.send({ type: "join", code: "1234" });
  const lateErr = await late.nextType("error");
  check("房间销毁后加入被拒", lateErr.message.includes("不存在") || lateErr.message.includes("口令"));
  late.close();

  console.log(`\n== 结果: ${pass} passed, ${fail} failed ==`);
  process.exit(fail > 0 ? 1 : 0);
}
main().catch((e) => { console.error("测试异常:", e.message); process.exit(1); });
