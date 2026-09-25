"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");

const { openDb, migrateCouplesMemoryPush } = require("../db");
const { RateLimiter } = require("../RateLimiter");
const { AccountManager } = require("../AccountManager");
const { FriendManager } = require("../FriendManager");
const { CoupleManager } = require("../CoupleManager");
const { PresenceManager } = require("../PresenceManager");

const PASSWORD = "Passw0rd!";

function makeEnv() {
  const db = openDb(":memory:");
  migrateCouplesMemoryPush(db);
  const presence = new PresenceManager();
  return {
    db,
    presence,
    rateLimiter: new RateLimiter(),
    accounts: new AccountManager(db),
    friends: new FriendManager(db),
    couples: new CoupleManager(db, presence),
  };
}

async function register(env, nickname) {
  return env.accounts.register(nickname, PASSWORD);
}

test("情侣邀请经接受后双向绑定并自动加好友", async () => {
  const env = makeEnv();
  const a = await register(env, "小南");
  const b = await register(env, "阿远");

  const r = env.couples.invite(a.userId, b.profile.friendCode);
  assert.equal(r.accepted, false);
  assert.equal(env.couples.invitations(b.userId).length, 1);

  const acc = env.couples.accept(b.userId, r.invitationId);
  assert.equal(acc.partnerId, a.userId);

  // 双向好友自动建立
  assert.deepEqual(env.friends.list(a.userId).map((f) => f.userId), [b.userId]);
  assert.deepEqual(env.friends.list(b.userId).map((f) => f.userId), [a.userId]);

  // 空间首页双方都可见
  const sa = env.couples.space(a.userId);
  const sb = env.couples.space(b.userId);
  assert.equal(sa.bound, true);
  assert.equal(sa.partner.userId, b.userId);
  assert.equal(sa.days, 0);
  assert.equal(sb.partner.userId, a.userId);
});

test("每日打卡：当天一次，重复被拒，双方状态与连续天数", async () => {
  const env = makeEnv();
  const a = await register(env, "小南");
  const b = await register(env, "阿远");
  const r = env.couples.accept(b.userId, env.couples.invite(a.userId, b.profile.friendCode).invitationId);

  // 初始双方均未打卡
  const s0 = env.couples.space(a.userId);
  assert.equal(s0.checkin.me.today, false);
  assert.equal(s0.checkin.partner.today, false);
  assert.equal(s0.checkin.me.streak, 0);

  // A 打卡成功，连续 1 天
  const ck = env.couples.checkin(a.userId);
  assert.equal(ck.today, true);
  assert.equal(ck.streak, 1);

  // 当天重复被拒
  assert.throws(() => env.couples.checkin(a.userId), (e) => e.code === "already_checkin");

  // B 视角：A 已打卡、自己未打卡
  const s1 = env.couples.space(b.userId);
  assert.equal(s1.checkin.partner.today, true);
  assert.equal(s1.checkin.me.today, false);
  assert.equal(s1.checkin.partner.streak, 1);

  // 连续天数：补昨天和前天的记录，streak 应为 3
  const today = new Date(Date.now() + 8 * 3600_000).toISOString().slice(0, 10);
  const prev = (n) => new Date(Date.now() + 8 * 3600_000 - n * 86400_000).toISOString().slice(0, 10);
  const ins = env.db.prepare(
    `INSERT OR IGNORE INTO couple_checkins (id, couple_id, user_id, date, created_at) VALUES (?, ?, ?, ?, ?)`
  );
  ins.run("c1", r.coupleId, a.userId, prev(1), 0);
  ins.run("c2", r.coupleId, a.userId, prev(2), 0);
  assert.equal(env.couples.space(a.userId).checkin.me.streak, 3);

  // 断签验证：只保留今天和前天（缺昨天），streak 应为 1
  env.db.prepare(`DELETE FROM couple_checkins WHERE date = ?`).run(prev(1));
  assert.equal(env.couples.space(a.userId).checkin.me.streak, 1);

  // 未绑定不能打卡
  assert.throws(() => env.couples.checkin("nobody"), (e) => e.code === "not_bound");
});

test("纪念日：设置后双方可见，非法日期被拒", async () => {
  const env = makeEnv();
  const a = await register(env, "小南");
  const b = await register(env, "阿远");
  env.couples.accept(b.userId, env.couples.invite(a.userId, b.profile.friendCode).invitationId);

  // 未设时为 null
  assert.equal(env.couples.space(a.userId).anniversary, null);

  // A 设置，B 也能看到（对称双写两行同步）
  env.couples.setAnniversary(a.userId, "2024-05-20");
  assert.equal(env.couples.space(a.userId).anniversary, "2024-05-20");
  assert.equal(env.couples.space(b.userId).anniversary, "2024-05-20");

  // 格式非法
  assert.throws(() => env.couples.setAnniversary(a.userId, "2024/05/20"), (e) => e.code === "invalid_date");
  // 不存在的日期（2 月 31 日）
  assert.throws(() => env.couples.setAnniversary(a.userId, "2024-02-31"), (e) => e.code === "invalid_date");
  // 未来日期
  assert.throws(() => env.couples.setAnniversary(a.userId, "2099-01-01"), (e) => e.code === "invalid_date");
  // 时区回归：服务器为 UTC 时，东八区当天的「今天」不能被误判为未来而拒绝
  const todaySh = new Date(Date.now() + 8 * 3600_000).toISOString().slice(0, 10);
  assert.equal(env.couples.setAnniversary(a.userId, todaySh).anniversary, todaySh);

  // 未绑定不能设
  assert.throws(() => env.couples.setAnniversary("nobody", "2024-05-20"), (e) => e.code === "not_bound");
});

test("唯一绑定：已绑定再邀请被拒", async () => {
  const env = makeEnv();
  const a = await register(env, "小南");
  const b = await register(env, "阿远");
  const c = await register(env, "小三");

  const r1 = env.couples.invite(a.userId, b.profile.friendCode);
  env.couples.accept(b.userId, r1.invitationId);

  // a 已绑定，再邀请 c 被拒
  assert.throws(
    () => env.couples.invite(a.userId, c.profile.friendCode),
    (e) => e.code === "already_bound" && e.status === 409
  );
  // c 未绑定但邀请已绑定的 a 也被拒
  assert.throws(
    () => env.couples.invite(c.userId, a.profile.friendCode),
    (e) => e.code === "already_bound"
  );
});

test("反向邀请自动接受；自己邀请自己被拒；无效好友码被拒", async () => {
  const env = makeEnv();
  const a = await register(env, "小南");
  const b = await register(env, "阿远");

  assert.throws(
    () => env.couples.invite(a.userId, a.profile.friendCode),
    (e) => e.code === "self_request"
  );
  assert.throws(
    () => env.couples.invite(a.userId, "ZZZZZZ"),
    (e) => e.code === "invalid_code"
  );

  env.couples.invite(a.userId, b.profile.friendCode);
  // b 反向邀请 a：存在 pending 反向，直接接受
  const r2 = env.couples.invite(b.userId, a.profile.friendCode);
  assert.equal(r2.accepted, true);
  assert.equal(env.couples.space(b.userId).bound, true);
});

test("照片上传、列表与删除", async () => {
  const env = makeEnv();
  const a = await register(env, "小南");
  const b = await register(env, "阿远");
  const r = env.couples.invite(a.userId, b.profile.friendCode);
  env.couples.accept(b.userId, r.invitationId);

  const png1x1 =
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==";
  const up = env.couples.uploadPhoto(a.userId, { photo: png1x1, mime: "image/png" });
  assert.ok(up.mediaId);

  const list = env.couples.listPhotos(b.userId);
  assert.equal(list.length, 1);
  assert.equal(list[0].mediaType, "photo");
  assert.equal(env.couples.canViewMedia(b.userId, up.mediaId), true);

  // 非绑定用户无权访问
  const c = await register(env, "小三");
  assert.equal(env.couples.canViewMedia(c.userId, up.mediaId), false);

  env.couples.deletePhoto(b.userId, up.mediaId);
  assert.equal(env.couples.listPhotos(a.userId).length, 0);
});

test("未绑定时操作相册/位置被拒", async () => {
  const env = makeEnv();
  const a = await register(env, "小南");
  assert.throws(
    () => env.couples.uploadPhoto(a.userId, { photo: "x", mime: "image/jpeg" }),
    (e) => e.code === "not_bound"
  );
  assert.throws(
    () => env.couples.reportLocation(a.userId, { lat: 30, lng: 120 }),
    (e) => e.code === "not_bound"
  );
});

test("视频分块上传：字节数不符拒绝入库", async () => {
  const env = makeEnv();
  const a = await register(env, "小南");
  const b = await register(env, "阿远");
  const r = env.couples.invite(a.userId, b.profile.friendCode);
  env.couples.accept(b.userId, r.invitationId);

  const v = env.couples.createVideo(a.userId, { size: 10, durationMs: 5000, thumb: "" });
  assert.ok(v.videoId);
  env.couples.uploadVideoChunk(a.userId, v.videoId, { offset: 0, chunk: Buffer.from("12345").toString("base64") });
  env.couples.uploadVideoChunk(a.userId, v.videoId, { offset: 5, chunk: Buffer.from("678").toString("base64") });
  // 累计 8 < 声明 10
  assert.throws(
    () => env.couples.finishVideo(a.userId, v.videoId),
    (e) => e.code === "invalid_video"
  );
  // 分块乱序拒绝
  assert.throws(
    () => env.couples.uploadVideoChunk(a.userId, v.videoId, { offset: 99, chunk: Buffer.from("x").toString("base64") }),
    (e) => e.code === "invalid_video"
  );
});

test("位置上报与伴侣最近位置", async () => {
  const env = makeEnv();
  const a = await register(env, "小南");
  const b = await register(env, "阿远");
  const r = env.couples.invite(a.userId, b.profile.friendCode);
  env.couples.accept(b.userId, r.invitationId);

  assert.throws(
    () => env.couples.reportLocation(a.userId, { lat: 200, lng: 120 }),
    (e) => e.code === "invalid_location"
  );
  env.couples.reportLocation(b.userId, { lat: 31.23, lng: 121.47 });
  const sa = env.couples.space(a.userId);
  assert.deepEqual(sa.location, { lat: 31.23, lng: 121.47, reportedAt: sa.location.reportedAt });
  assert.ok(sa.location.reportedAt > 0);
});

test("解绑保留内容；新绑定清空旧空间", async () => {
  const env = makeEnv();
  const a = await register(env, "小南");
  const b = await register(env, "阿远");
  const c = await register(env, "小三");

  const r1 = env.couples.invite(a.userId, b.profile.friendCode);
  env.couples.accept(b.userId, r1.invitationId);
  const png1x1 =
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==";
  const photo = env.couples.uploadPhoto(a.userId, { photo: png1x1, mime: "image/png" });
  env.couples.reportLocation(a.userId, { lat: 30, lng: 120 });

  // 解绑
  env.couples.dissolve(a.userId);
  assert.equal(env.couples.space(a.userId).bound, false);
  assert.equal(env.couples.space(b.userId).bound, false);
  // 内容仍对原双方可见
  assert.equal(env.couples.listPhotos(b.userId).length, 1);

  // a 绑定新伴侣 c：旧空间清空
  const r2 = env.couples.invite(c.userId, a.profile.friendCode);
  env.couples.accept(a.userId, r2.invitationId);
  assert.equal(env.couples.listPhotos(a.userId).length, 0);
  assert.equal(env.couples.space(c.userId).location, null);
});

test("解绑后双方仍可访问历史媒体（只读）", async () => {
  const env = makeEnv();
  const a = await register(env, "小南");
  const b = await register(env, "阿远");
  const c = await register(env, "小三");
  const r = env.couples.invite(a.userId, b.profile.friendCode);
  env.couples.accept(b.userId, r.invitationId);
  const png1x1 =
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==";
  const photo = env.couples.uploadPhoto(a.userId, { photo: png1x1, mime: "image/png" });

  env.couples.dissolve(a.userId);

  // 列表仍可见，且媒体访问鉴权不因解绑而失效（否则图片全 403）
  assert.equal(env.couples.listPhotos(b.userId).length, 1);
  assert.equal(env.couples.canViewMedia(a.userId, photo.mediaId), true);
  assert.equal(env.couples.canViewMedia(b.userId, photo.mediaId), true);
  assert.equal(env.couples.canViewMedia(c.userId, photo.mediaId), false);
  // 解绑后只读：不可再删除
  assert.throws(() => env.couples.deletePhoto(a.userId, photo.mediaId), (e) => e.code === "not_bound");
});

test("视频分块超出声明大小被拒并回收会话", async () => {
  const env = makeEnv();
  const a = await register(env, "小南");
  const b = await register(env, "阿远");
  const r = env.couples.invite(a.userId, b.profile.friendCode);
  env.couples.accept(b.userId, r.invitationId);
  const v = env.couples.createVideo(a.userId, { size: 4, durationMs: 1000, thumb: "" });
  assert.throws(
    () => env.couples.uploadVideoChunk(a.userId, v.videoId, { offset: 0, chunk: Buffer.from("12345").toString("base64") }),
    (e) => e.code === "invalid_video"
  );
  // 超传后会话与分片已回收
  assert.throws(
    () => env.couples.uploadVideoChunk(a.userId, v.videoId, { offset: 0, chunk: Buffer.from("1").toString("base64") }),
    (e) => e.code === "not_found"
  );
});

test("视频大小必须为正整数；offset 缺失或非数字被拒", async () => {
  const env = makeEnv();
  const a = await register(env, "小南");
  const b = await register(env, "阿远");
  const r = env.couples.invite(a.userId, b.profile.friendCode);
  env.couples.accept(b.userId, r.invitationId);
  assert.throws(() => env.couples.createVideo(a.userId, { size: 10.5, thumb: "" }), (e) => e.code === "invalid_video");
  const v = env.couples.createVideo(a.userId, { size: 2, thumb: "" });
  assert.throws(
    () => env.couples.uploadVideoChunk(a.userId, v.videoId, { chunk: Buffer.from("12").toString("base64") }),
    (e) => e.code === "invalid_video"
  );
  assert.throws(
    () => env.couples.uploadVideoChunk(a.userId, v.videoId, { offset: "0", chunk: Buffer.from("12").toString("base64") }),
    (e) => e.code === "invalid_video"
  );
});

test("绑定后清理双方遗留待处理邀请", async () => {
  const env = makeEnv();
  const a = await register(env, "小南");
  const b = await register(env, "阿远");
  const c = await register(env, "小三");
  env.couples.invite(c.userId, b.profile.friendCode);
  const r = env.couples.invite(a.userId, b.profile.friendCode);
  env.couples.accept(b.userId, r.invitationId);
  assert.equal(env.couples.invitations(b.userId).length, 0);
});

test("位置重复上报只保留最新一条", async () => {
  const env = makeEnv();
  const a = await register(env, "小南");
  const b = await register(env, "阿远");
  const r = env.couples.invite(a.userId, b.profile.friendCode);
  env.couples.accept(b.userId, r.invitationId);
  env.couples.reportLocation(b.userId, { lat: 30, lng: 120 });
  env.couples.reportLocation(b.userId, { lat: 31, lng: 121 });
  const loc = env.couples.space(a.userId).location;
  assert.equal(loc.lat, 31);
  assert.equal(loc.lng, 121);
  assert.equal(env.db.prepare("SELECT COUNT(*) AS n FROM couple_locations").get().n, 1);
});

test("重复邀请返回结构一致（含 to）", async () => {
  const env = makeEnv();
  const a = await register(env, "小南");
  const b = await register(env, "阿远");
  const r1 = env.couples.invite(a.userId, b.profile.friendCode);
  const r2 = env.couples.invite(a.userId, b.profile.friendCode);
  assert.equal(r2.invitationId, r1.invitationId);
  assert.equal(r2.to, b.userId);
});

test("愿望清单：增删改查 + 双方共享 + 越界拒绝", async () => {
  const env = makeEnv();
  const a = await register(env, "小南");
  const b = await register(env, "阿远");
  env.couples.accept(b.userId, env.couples.invite(a.userId, b.profile.friendCode).invitationId);

  // 空内容拒绝
  assert.throws(() => env.couples.addWish(a.userId, "  "), (e) => e.code === "invalid_text");
  // 超长拒绝
  assert.throws(() => env.couples.addWish(a.userId, "x".repeat(51)), (e) => e.code === "invalid_text");

  // A 添加两条
  const w1 = env.couples.addWish(a.userId, "一起看日出");
  const w2 = env.couples.addWish(a.userId, "去海边旅行");
  // B 视角能看到 A 添加的（双方共享）
  let list = env.couples.listWishes(b.userId);
  assert.equal(list.length, 2);
  assert.equal(list[0].text, "一起看日出");
  assert.equal(list[0].done, false);

  // B 勾选完成
  env.couples.toggleWish(b.userId, w1.wishId, true);
  list = env.couples.listWishes(a.userId);
  const done = list.find((x) => x.wishId === w1.wishId);
  assert.equal(done.done, true);
  assert.equal(done.doneBy, b.userId);

  // 取消勾选
  env.couples.toggleWish(a.userId, w1.wishId, false);
  assert.equal(env.couples.listWishes(a.userId).find((x) => x.wishId === w1.wishId).done, false);

  // 越界操作：别人 couple 的愿望 id（此处用一个不存在的 uuid 属于"其他关系"）
  assert.throws(() => env.couples.toggleWish(a.userId, "00000000-0000-0000-0000-000000000000", true), (e) => e.code === "not_found");

  // 删除
  env.couples.deleteWish(b.userId, w2.wishId);
  assert.equal(env.couples.listWishes(a.userId).length, 1);

  // 未绑定不能操作
  assert.throws(() => env.couples.listWishes("nobody"), (e) => e.code === "not_bound");
});

test("一年前的今天：去年同月同日照片命中推送，标记后同日不重复", async () => {
  const env = makeEnv();
  const a = await register(env, "小南");
  const b = await register(env, "阿远");
  env.couples.accept(b.userId, env.couples.invite(a.userId, b.profile.friendCode).invitationId);

  // 动态算去年今日（上海时区），保证测试长期有效
  const today = new Date(Date.now() + 8 * 3600_000).toISOString().slice(0, 10);
  const [y, m, d] = today.split("-");
  const lastYearToday = `${Number(y) - 1}-${m}-${d}`;
  const t = Date.parse(`${lastYearToday}T12:00:00+08:00`);
  const coupleId = env.couples.space(a.userId).coupleId;

  const ins = env.db.prepare(
    `INSERT INTO couple_photos (id, couple_id, uploader, media_type, url, created_at) VALUES (?, ?, ?, 'photo', ?, ?)`
  );
  ins.run("m1", coupleId, a.userId, "/m1.jpg", t);
  ins.run("m2", coupleId, b.userId, "/m2.jpg", t + 3600_000);
  ins.run("m3", coupleId, a.userId, "/m3.jpg", t - 2 * 86400_000); // 前天，不命中

  const cands = env.couples.listMemoryCandidates();
  assert.equal(cands.length, 1);
  assert.equal(cands[0].coupleId, coupleId);
  assert.equal(cands[0].count, 2);
  assert.deepEqual(cands[0].partnerIds.sort(), [a.userId, b.userId].sort());

  // 标记后同日不再命中（服务重启后去重）
  env.couples.markMemoryPushed(coupleId);
  assert.equal(env.couples.listMemoryCandidates().length, 0);
});
