"use strict";

/**
 * 情侣绑定与情侣空间：邀请→唯一绑定→共享相册（照片+视频）→位置共享→解绑。
 * couples 表对称双写两行；解绑后内容保留只读，新绑定清空旧空间。
 * 媒体以 base64 上传（与现有相册链路一致），落盘到 data/couple_photos/。
 */

const crypto = require("crypto");
const fs = require("fs");
const path = require("path");
const { AccountError } = require("./AccountManager");

const FRIEND_CODE_RE = /^[A-Z0-9]{6}$/;
const DATA_DIR = path.join(__dirname, "data", "couple_photos");
const TMP_DIR = path.join(DATA_DIR, "tmp");
const MAX_PHOTO_BYTES = 10 * 1024 * 1024;
const MAX_VIDEO_BYTES = 100 * 1024 * 1024;
const MAX_THUMB_BYTES = 1024 * 1024;
const ALLOWED_PHOTO_MIMES = new Set(["image/jpeg", "image/png", "image/webp"]);
const CHUNK_BYTES = 3 * 1024 * 1024;
const VIDEO_SESSION_TTL_MS = 30 * 60 * 1000;

/** 今日日期（Asia/Shanghai，用户都在国内） */
function todayShanghai() {
  return new Date(Date.now() + 8 * 3600_000).toISOString().slice(0, 10);
}
/** ISO 日期增减天数（在 +8 时区下计算，避免 UTC 跨日错位） */
function shiftDay(iso, delta) {
  const t = new Date(iso + "T00:00:00+08:00").getTime() + delta * 86400_000;
  return new Date(t + 8 * 3600_000).toISOString().slice(0, 10);
}

class CoupleManager {
  constructor(db, presence) {
    this.db = db;
    this.presence = presence;
    this.videoSessions = new Map(); // videoId -> {coupleId, uploader, size, received, hasThumb, durationMs, createdAt}
    for (const d of [DATA_DIR, TMP_DIR]) {
      fs.mkdirSync(d, { recursive: true });
    }
    // 未完成的视频会话（客户端中断/放弃）默认永驻，加 TTL 定期回收内存与 .part 分片
    this._sweeper = setInterval(() => this._sweepSessions(), 10 * 60 * 1000);
    if (this._sweeper.unref) this._sweeper.unref();
  }

  _sweepSessions() {
    const now = Date.now();
    for (const [vid, s] of this.videoSessions) {
      if (now - (s.createdAt || 0) > VIDEO_SESSION_TTL_MS) {
        this.videoSessions.delete(vid);
        fs.rm(path.join(TMP_DIR, `${vid}.part`), () => {});
        fs.rm(path.join(DATA_DIR, `${vid}_thumb.jpg`), () => {});
      }
    }
  }

  invite(fromUserId, friendCode) {
    const code = String(friendCode || "").trim().toUpperCase();
    if (!FRIEND_CODE_RE.test(code)) throw new AccountError("invalid_code", "好友码无效", 400);
    const target = this.db.prepare(`SELECT id FROM users WHERE friend_code = ?`).get(code);
    if (!target) throw new AccountError("invalid_code", "好友码无效", 400);
    if (target.id === fromUserId) throw new AccountError("self_request", "不能绑定自己", 400);
    if (this._activeCoupleOf(fromUserId)) {
      throw new AccountError("already_bound", "你已绑定，请先解绑", 409);
    }
    if (this._activeCoupleOf(target.id)) {
      throw new AccountError("already_bound", "对方已绑定", 409);
    }

    const reverse = this.db
      .prepare(`SELECT id FROM couple_invitations WHERE from_user = ? AND to_user = ? AND status = 'pending'`)
      .get(target.id, fromUserId);
    if (reverse) {
      // 反向待处理邀请存在：直接建立绑定（对方向我发起的情侣邀请，我 invite 对方视为接受）
      this._bindBoth(fromUserId, target.id, reverse.id);
      return { invitationId: reverse.id, accepted: true, to: target.id };
    }

    const existing = this.db
      .prepare(`SELECT id FROM couple_invitations WHERE from_user = ? AND to_user = ? AND status = 'pending'`)
      .get(fromUserId, target.id);
    if (existing) return { invitationId: existing.id, accepted: false, to: target.id };

    const id = crypto.randomUUID();
    this.db
      .prepare(`INSERT INTO couple_invitations (id, from_user, to_user, status, created_at) VALUES (?, ?, ?, ?, ?)`)
      .run(id, fromUserId, target.id, "pending", Date.now());
    return { invitationId: id, accepted: false, to: target.id };
  }

  invitations(userId) {
    const rows = this.db
      .prepare(
        `SELECT r.id, r.created_at, u.id AS uid, u.nickname, u.avatar
         FROM couple_invitations r JOIN users u ON u.id = r.from_user
         WHERE r.to_user = ? AND r.status = 'pending' ORDER BY r.created_at DESC`
      )
      .all(userId);
    return rows.map((r) => ({
      invitationId: r.id,
      from: { userId: r.uid, nickname: r.nickname, avatar: r.avatar },
      createdAt: r.created_at,
    }));
  }

  accept(userId, invitationId) {
    const req = this._pendingFor(userId, invitationId);
    this._bindBoth(req.to_user, req.from_user, invitationId);
    return { coupleId: this._activeCoupleOf(req.to_user)?.coupleId, boundAt: Date.now(), partnerId: req.from_user };
  }

  /** 校验唯一性 → 清旧空间 → 建立双向绑定 + 自动加好友 → 邀请标记 accepted（单事务，失败回滚） */
  _bindBoth(fromUserId, targetId, invitationId) {
    if (this._activeCoupleOf(fromUserId)) {
      throw new AccountError("already_bound", "你已绑定，请先解绑", 409);
    }
    if (this._activeCoupleOf(targetId)) {
      throw new AccountError("already_bound", "对方已绑定", 409);
    }
    this.db.exec("BEGIN");
    try {
      this._clearSpace(fromUserId);
      this._clearSpace(targetId);
      const coupleId = crypto.randomUUID();
      const now = Date.now();
      this._makeCouple(fromUserId, targetId, coupleId, now);
      // 绑定成功后，双方其余遗留待处理邀请统一失效，避免僵尸邀请长期滞留
      this.db
        .prepare(
          `UPDATE couple_invitations SET status = 'rejected'
           WHERE status = 'pending' AND (from_user IN (?, ?) OR to_user IN (?, ?))`
        )
        .run(fromUserId, targetId, fromUserId, targetId);
      this.db.prepare(`UPDATE couple_invitations SET status = 'accepted' WHERE id = ?`).run(invitationId);
      this.db.exec("COMMIT");
    } catch (e) {
      try { this.db.exec("ROLLBACK"); } catch (_) {}
      throw e instanceof AccountError
        ? e
        : new AccountError("bind_failed", "绑定失败，请重试", 500);
    }
  }

  reject(userId, invitationId) {
    this._pendingFor(userId, invitationId);
    this.db.prepare(`UPDATE couple_invitations SET status = 'rejected' WHERE id = ?`).run(invitationId);
    return { ok: true };
  }

  space(userId) {
    const c = this._activeCoupleOf(userId);
    if (!c) return { bound: false };
    const partner = this.db
      .prepare(`SELECT id, nickname, avatar FROM users WHERE id = ?`)
      .get(c.partnerId);
    if (!partner) return { bound: false };
    const days = Math.max(0, Math.floor((Date.now() - c.boundAt) / 86_400_000));
    const photoCount =
      this.db.prepare(`SELECT COUNT(*) AS n FROM couple_photos WHERE couple_id = ?`).get(c.coupleId).n || 0;
    const loc = this._partnerLocation(c.coupleId, c.partnerId);
    return {
      bound: true,
      coupleId: c.coupleId,
      days,
      boundAt: c.boundAt,
      partner: {
        userId: partner.id,
        nickname: partner.nickname,
        avatar: partner.avatar,
        online: this.presence ? this.presence.isOnline(partner.id) : false,
      },
      location: loc,
      photoCount,
      anniversary: c.anniversary || null,
      checkin: this._checkinStatus(c.coupleId, userId, c.partnerId),
    };
  }

  // ==================== 每日打卡 ====================

  /** 今日打卡（Asia/Shanghai 日期）；当天重复打卡抛 already_checkin */
  checkin(userId) {
    const c = this._requireBound(userId);
    const today = todayShanghai();
    const inserted = this.db
      .prepare(`INSERT OR IGNORE INTO couple_checkins (id, couple_id, user_id, date, created_at) VALUES (?, ?, ?, ?, ?)`)
      .run(crypto.randomUUID(), c.coupleId, userId, today, Date.now());
    if (inserted.changes === 0) {
      throw new AccountError("already_checkin", "今天已经打卡过了", 409);
    }
    return { today: true, streak: this._streakOf(userId, c.coupleId), partnerId: c.partnerId };
  }

  /** 某用户在当前情侣关系下的连续打卡天数（从今天往回数到第一个缺失） */
  _streakOf(userId, coupleId) {
    const rows = this.db
      .prepare(`SELECT date FROM couple_checkins WHERE couple_id = ? AND user_id = ? ORDER BY date DESC LIMIT 400`)
      .all(coupleId, userId);
    const set = new Set(rows.map((r) => r.date));
    let streak = 0;
    let cur = todayShanghai();
    while (set.has(cur)) {
      streak += 1;
      cur = shiftDay(cur, -1);
    }
    return streak;
  }

  _checkinStatus(coupleId, meId, partnerId) {
    const today = todayShanghai();
    const stmt = this.db
      .prepare(`SELECT 1 FROM couple_checkins WHERE couple_id = ? AND user_id = ? AND date = ?`);
    return {
      me: { today: !!stmt.get(coupleId, meId, today), streak: this._streakOf(meId, coupleId) },
      partner: { today: !!stmt.get(coupleId, partnerId, today), streak: this._streakOf(partnerId, coupleId) },
    };
  }

  /** 设置在一起纪念日（双方对称更新，格式 YYYY-MM-DD） */
  setAnniversary(userId, date) {
    const c = this._requireBound(userId);
    const iso = String(date || "").trim();
    // 格式与合法性校验：YYYY-MM-DD 且真实存在（防 2-31 之类）
    const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(iso);
    let valid = false;
    if (m) {
      const d = new Date(Number(m[1]), Number(m[2]) - 1, Number(m[3]));
      valid =
        d.getFullYear() === Number(m[1]) &&
        d.getMonth() === Number(m[2]) - 1 &&
        d.getDate() === Number(m[3]);
    }
    if (!valid) throw new AccountError("invalid_date", "日期格式需为 YYYY-MM-DD", 400);
    // 与「今天」按东八区日期字符串比较（YYYY-MM-DD 字典序即日期序）。
    // 不能用 new Date(iso+"T00:00:00")：那按服务器本地时区解析，服务器为 UTC 时
    // 用户在东八区当天设「今天」会被误判为未来而拒绝。
    if (iso > todayShanghai()) {
      throw new AccountError("invalid_date", "纪念日不能晚于今天", 400);
    }
    // 同一对称双写两行用同一 couple id，一次更新两行
    this.db
      .prepare(`UPDATE couples SET anniversary = ? WHERE id = ? AND status = 'active'`)
      .run(iso, c.coupleId);
    return { anniversary: iso };
  }

  uploadPhoto(userId, { photo, mime }) {
    const c = this._requireBound(userId);
    const m = String(mime || "image/jpeg").toLowerCase();
    if (!ALLOWED_PHOTO_MIMES.has(m)) throw new AccountError("invalid_file", "仅支持 JPEG/PNG/WebP", 400);
    const buf = Buffer.from(String(photo || ""), "base64");
    if (buf.length === 0 || buf.length > MAX_PHOTO_BYTES) {
      throw new AccountError("invalid_file", "照片大小需在 10MB 以内", 400);
    }
    const id = crypto.randomUUID();
    const ext = m === "image/png" ? "png" : m === "image/webp" ? "webp" : "jpg";
    const file = path.join(DATA_DIR, `${id}.${ext}`);
    fs.writeFileSync(file, buf);
    this.db
      .prepare(
        `INSERT INTO couple_photos (id, couple_id, uploader, media_type, url, created_at) VALUES (?, ?, ?, 'photo', ?, ?)`
      )
      .run(id, c.coupleId, userId, `/couple_media/${id}.${ext}`, Date.now());
    return { mediaId: id };
  }

  createVideo(userId, { size, durationMs, thumb }) {
    const c = this._requireBound(userId);
    const n = Number(size || 0);
    if (!Number.isInteger(n) || n <= 0 || n > MAX_VIDEO_BYTES) {
      throw new AccountError("invalid_video", "视频大小需在 100MB 以内", 400);
    }
    const id = crypto.randomUUID();
    const thumbBuf = Buffer.from(String(thumb || ""), "base64");
    const hasThumb = thumbBuf.length > 0 && thumbBuf.length <= MAX_THUMB_BYTES;
    if (hasThumb) fs.writeFileSync(path.join(DATA_DIR, `${id}_thumb.jpg`), thumbBuf);
    const dur = Number(durationMs);
    this.videoSessions.set(id, {
      coupleId: c.coupleId,
      uploader: userId,
      size: n,
      received: 0,
      hasThumb,
      durationMs: Number.isFinite(dur) && dur > 0 ? Math.min(dur, 24 * 3600 * 1000) : 0,
      createdAt: Date.now(),
    });
    return { videoId: id, chunkSize: CHUNK_BYTES };
  }

  uploadVideoChunk(userId, videoId, { offset, chunk }) {
    const c = this._requireBound(userId);
    const s = this.videoSessions.get(String(videoId || ""));
    if (!s) throw new AccountError("not_found", "视频会话不存在或已过期", 404);
    if (s.uploader !== userId || s.coupleId !== c.coupleId) {
      throw new AccountError("forbidden", "无权上传该视频", 403);
    }
    if (typeof offset !== "number" || !Number.isInteger(offset) || offset < 0 || offset !== s.received) {
      throw new AccountError("invalid_video", "分块顺序错误", 400);
    }
    const buf = Buffer.from(String(chunk || ""), "base64");
    if (buf.length === 0) throw new AccountError("invalid_video", "空分块", 400);
    const part = path.join(TMP_DIR, `${videoId}.part`);
    if (s.received + buf.length > s.size) {
      // 超出声明大小：丢弃会话与分片，防止无限写盘
      fs.rmSync(part, { force: true });
      this.videoSessions.delete(String(videoId));
      throw new AccountError("invalid_video", "分块超出声明大小", 400);
    }
    fs.appendFileSync(part, buf);
    s.received += buf.length;
    return { received: s.received };
  }

  finishVideo(userId, videoId) {
    const c = this._requireBound(userId);
    const s = this.videoSessions.get(String(videoId || ""));
    if (!s) throw new AccountError("not_found", "视频会话不存在或已过期", 404);
    if (s.uploader !== userId || s.coupleId !== c.coupleId) {
      throw new AccountError("forbidden", "无权完成该视频", 403);
    }
    if (s.received !== s.size) {
      throw new AccountError("invalid_video", `字节数不符（${s.received}/${s.size}）`, 400);
    }
    const part = path.join(TMP_DIR, `${videoId}.part`);
    const file = path.join(DATA_DIR, `${videoId}.mp4`);
    fs.renameSync(part, file);
    this.videoSessions.delete(videoId);
    this.db
      .prepare(
        `INSERT INTO couple_photos (id, couple_id, uploader, media_type, url, thumb_url, duration_ms, created_at)
         VALUES (?, ?, ?, 'video', ?, ?, ?, ?)`
      )
      .run(
        videoId,
        s.coupleId,
        userId,
        `/couple_media/${videoId}.mp4`,
        s.hasThumb ? `/couple_media/${videoId}_thumb.jpg` : null,
        Number(s.durationMs || 0) || null,
        Date.now()
      );
    return { mediaId: videoId };
  }

  listPhotos(userId) {
    const c = this._anyCoupleOf(userId);
    if (!c) return [];
    const rows = this.db
      .prepare(
        `SELECT id, uploader, media_type, url, thumb_url, duration_ms, created_at
         FROM couple_photos WHERE couple_id = ? ORDER BY created_at DESC`
      )
      .all(c.coupleId);
    return rows.map((r) => ({
      mediaId: r.id,
      mediaType: r.media_type,
      url: r.url,
      thumbUrl: r.thumb_url,
      durationMs: r.duration_ms,
      uploader: r.uploader,
      createdAt: r.created_at,
    }));
  }

  /**
   * 「一年前的今天」候选：去年同月同日有相册照片、且今日尚未推送的情侣。
   * 返回 [{ coupleId, partnerIds: [userA, userB], count }] 供定时任务推送。
   * 依据 created_at（上传时间）而非拍摄日期：历史照片无 EXIF，上传日≈值得纪念的日子。
   * 去重用 couples.last_memory_push（上海日期），服务重启不会同日重复推。
   */
  listMemoryCandidates() {
    const today = todayShanghai();
    const parts = today.split("-");
    const lastYear = String(Number(parts[0]) - 1);
    const startMs = Date.parse(`${lastYear}-${parts[1]}-${parts[2]}T00:00:00+08:00`);
    if (Number.isNaN(startMs)) return [];
    const endMs = startMs + 86400_000;
    const rows = this.db
      .prepare(
        `SELECT cp.couple_id AS coupleId, c.user_a AS userA, c.user_b AS userB,
                COUNT(*) AS count, c.last_memory_push AS pushed
         FROM couple_photos cp JOIN couples c ON c.id = cp.couple_id
         WHERE c.status = 'active' AND c.user_a < c.user_b
           AND cp.created_at >= ? AND cp.created_at < ?
         GROUP BY cp.couple_id`
      )
      .all(startMs, endMs);
    return rows
      .filter((r) => r.pushed !== today)
      .map((r) => ({ coupleId: r.coupleId, count: r.count, partnerIds: [r.userA, r.userB] }));
  }

  /** 标记今日已推送「一年前的今天」，避免同日重复（含服务重启后） */
  markMemoryPushed(coupleId) {
    this.db
      .prepare(`UPDATE couples SET last_memory_push = ? WHERE id = ?`)
      .run(todayShanghai(), coupleId);
  }

  deletePhoto(userId, mediaId) {
    const c = this._activeCoupleOf(userId);
    if (!c) throw new AccountError("not_bound", "未绑定", 403);
    const row = this.db.prepare(`SELECT couple_id, url, thumb_url FROM couple_photos WHERE id = ?`).get(String(mediaId || ""));
    if (!row) throw new AccountError("not_found", "媒体不存在", 404);
    if (row.couple_id !== c.coupleId) throw new AccountError("forbidden", "无权删除", 403);
    this.db.prepare(`DELETE FROM couple_photos WHERE id = ?`).run(String(mediaId));
    for (const rel of [row.url, row.thumb_url]) {
      if (!rel) continue;
      const f = path.join(DATA_DIR, path.basename(rel));
      fs.rm(f, (err) => {
        if (err && err.code !== "ENOENT") console.warn("[couple] 删除媒体文件失败:", f, err.message);
      });
    }
    return { ok: true };
  }

  reportLocation(userId, { lat, lng }) {
    const c = this._requireBound(userId);
    const la = Number(lat);
    const ln = Number(lng);
    if (!Number.isFinite(la) || !Number.isFinite(ln) || la < -90 || la > 90 || ln < -180 || ln > 180) {
      throw new AccountError("invalid_location", "位置坐标非法", 400);
    }
    const now = Date.now();
    const existing = this.db
      .prepare(`SELECT id FROM couple_locations WHERE couple_id = ? AND reporter = ?`)
      .get(c.coupleId, userId);
    if (existing) {
      // 每个用户在每段关系内只保留最新位置，避免 60s 上报无限堆积
      this.db
        .prepare(`UPDATE couple_locations SET lat = ?, lng = ?, reported_at = ? WHERE id = ?`)
        .run(la, ln, now, existing.id);
    } else {
      this.db
        .prepare(
          `INSERT INTO couple_locations (id, couple_id, reporter, lat, lng, reported_at) VALUES (?, ?, ?, ?, ?, ?)`
        )
        .run(crypto.randomUUID(), c.coupleId, userId, la, ln, now);
    }
    return { ok: true };
  }

  dissolve(userId) {
    const c = this._activeCoupleOf(userId);
    if (!c) throw new AccountError("not_bound", "未绑定", 403);
    this.db
      .prepare(`UPDATE couples SET status = 'dissolved' WHERE id = ?`)
      .run(c.coupleId);
    // 解绑后该用户相关待处理邀请统一失效
    this.db
      .prepare(
        `UPDATE couple_invitations SET status = 'rejected' WHERE status = 'pending' AND (from_user = ? OR to_user = ?)`
      )
      .run(userId, userId);
    return { ok: true, partnerId: c.partnerId };
  }

  /** 媒体访问鉴权：调用者须为该媒体所属 couple 的当事人（含已解绑，保证解绑后内容仍只读可见） */
  canViewMedia(userId, mediaId) {
    const row = this.db
      .prepare(
        `SELECT p.couple_id FROM couple_photos p
         JOIN couples c ON c.id = p.couple_id
         WHERE p.id = ? AND (c.user_a = ? OR c.user_b = ?)`
      )
      .get(String(mediaId || ""), userId, userId);
    return !!row;
  }

  _pendingFor(userId, invitationId) {
    const req = this.db.prepare(`SELECT * FROM couple_invitations WHERE id = ?`).get(String(invitationId || ""));
    if (!req || req.to_user !== userId) throw new AccountError("not_found", "情侣邀请不存在", 404);
    if (req.status !== "pending") throw new AccountError("invalid_state", "该邀请已处理", 409);
    return req;
  }

  _activeCoupleOf(userId) {
    const row = this.db
      .prepare(`SELECT id, user_a, user_b, bound_at, anniversary FROM couples WHERE user_a = ? AND status = 'active'`)
      .get(userId);
    if (!row) return null;
    return { coupleId: row.id, partnerId: row.user_b, boundAt: row.bound_at, anniversary: row.anniversary };
  }

  _requireBound(userId) {
    const c = this._activeCoupleOf(userId);
    if (!c) throw new AccountError("not_bound", "请先绑定伴侣", 403);
    return c;
  }

  /** 最近一段情侣关系（含已解绑），供解绑后查看历史内容 */
  _anyCoupleOf(userId) {
    const row = this.db
      .prepare(`SELECT id, user_b, bound_at FROM couples WHERE user_a = ? ORDER BY bound_at DESC LIMIT 1`)
      .get(userId);
    if (!row) return null;
    return { coupleId: row.id, partnerId: row.user_b, boundAt: row.bound_at };
  }

  _makeCouple(a, b, coupleId, now) {
    const stmt = this.db.prepare(
      `INSERT OR IGNORE INTO couples (id, user_a, user_b, bound_at, status) VALUES (?, ?, ?, ?, 'active')`
    );
    stmt.run(coupleId, a, b, now);
    stmt.run(coupleId, b, a, now);
    const friend = this.db.prepare(
      `INSERT OR IGNORE INTO friends (user_id, friend_id, created_at) VALUES (?, ?, ?)`
    );
    friend.run(a, b, now);
    friend.run(b, a, now);
  }

  _clearSpace(userId) {
    const rows = this.db.prepare(`SELECT id FROM couples WHERE user_a = ?`).all(userId);
    for (const r of rows) {
      const medias = this.db.prepare(`SELECT url, thumb_url FROM couple_photos WHERE couple_id = ?`).all(r.id);
      for (const m of medias) {
        for (const rel of [m.url, m.thumb_url]) {
          if (!rel) continue;
          fs.rm(path.join(DATA_DIR, path.basename(rel)), (err) => {
            if (err && err.code !== "ENOENT") console.warn("[couple] 清理旧空间文件失败:", err.message);
          });
        }
      }
      // 该关系下未完成的视频会话与分片一并回收
      for (const [vid, s] of this.videoSessions) {
        if (s.coupleId !== r.id) continue;
        this.videoSessions.delete(vid);
        fs.rm(path.join(TMP_DIR, `${vid}.part`), () => {});
        fs.rm(path.join(DATA_DIR, `${vid}_thumb.jpg`), () => {});
      }
      this.db.prepare(`DELETE FROM couple_photos WHERE couple_id = ?`).run(r.id);
      this.db.prepare(`DELETE FROM couple_locations WHERE couple_id = ?`).run(r.id);
      this.db.prepare(`UPDATE couples SET status = 'dissolved' WHERE id = ?`).run(r.id);
    }
  }

  _partnerLocation(coupleId, partnerId) {
    const row = this.db
      .prepare(
        `SELECT lat, lng, reported_at FROM couple_locations
         WHERE couple_id = ? AND reporter = ? ORDER BY reported_at DESC LIMIT 1`
      )
      .get(coupleId, partnerId);
    if (!row) return null;
    return { lat: row.lat, lng: row.lng, reportedAt: row.reported_at };
  }

  // ==================== 共同愿望清单 ====================

  /** 添加愿望（双方共享，任一方可添加） */
  addWish(userId, text) {
    const c = this._requireBound(userId);
    const t = String(text || "").trim();
    if (!t) throw new AccountError("invalid_text", "愿望内容不能为空", 400);
    if (t.length > 50) throw new AccountError("invalid_text", "愿望最多 50 个字", 400);
    const id = crypto.randomUUID();
    this.db
      .prepare(
        `INSERT INTO couple_wishes (id, couple_id, user_id, text, done, created_at) VALUES (?, ?, ?, ?, 0, ?)`
      )
      .run(id, c.coupleId, userId, t, Date.now());
    return { wishId: id };
  }

  /** 愿望列表（按创建时间，未完成在前） */
  listWishes(userId) {
    const c = this._requireBound(userId);
    const rows = this.db
      .prepare(
        `SELECT w.id, w.text, w.done, w.done_by, w.done_at, w.created_at,
                (SELECT nickname FROM users WHERE id = w.user_id) AS author_nickname
         FROM couple_wishes w WHERE w.couple_id = ?
         ORDER BY w.done ASC, w.created_at ASC`
      )
      .all(c.coupleId);
    return rows.map((r) => ({
      wishId: r.id,
      text: r.text,
      done: r.done === 1,
      doneBy: r.done_by || "",
      doneAt: r.done_at || 0,
      createdAt: r.created_at,
      authorNickname: r.author_nickname || "",
    }));
  }

  /** 勾选/取消勾选（需属于当前情侣关系，防越界） */
  toggleWish(userId, wishId, done) {
    const c = this._requireBound(userId);
    const id = String(wishId || "");
    const row = this.db.prepare(`SELECT couple_id FROM couple_wishes WHERE id = ?`).get(id);
    if (!row || row.couple_id !== c.coupleId) {
      throw new AccountError("not_found", "愿望不存在或不属于你们", 404);
    }
    if (done) {
      this.db
        .prepare(`UPDATE couple_wishes SET done = 1, done_by = ?, done_at = ? WHERE id = ?`)
        .run(userId, Date.now(), id);
    } else {
      this.db
        .prepare(`UPDATE couple_wishes SET done = 0, done_by = NULL, done_at = NULL WHERE id = ?`)
        .run(id);
    }
    return { wishId: id, done: !!done };
  }

  deleteWish(userId, wishId) {
    const c = this._requireBound(userId);
    const id = String(wishId || "");
    const row = this.db.prepare(`SELECT couple_id FROM couple_wishes WHERE id = ?`).get(id);
    if (!row || row.couple_id !== c.coupleId) {
      throw new AccountError("not_found", "愿望不存在或不属于你们", 404);
    }
    this.db.prepare(`DELETE FROM couple_wishes WHERE id = ?`).run(id);
    return { wishId: id };
  }
}

module.exports = { CoupleManager };
