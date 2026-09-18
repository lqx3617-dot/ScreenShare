"use strict";

/**
 * 好友关系：通过好友码发起申请、接受/拒绝、列表、删除。
 * 好友关系在 friends 表双向各存一行，保证对称；反向待处理申请被接受时直接建立双向关系。
 */

const crypto = require("crypto");
const { AccountError } = require("./AccountManager");

const FRIEND_CODE_RE = /^[A-Z0-9]{6}$/;

class FriendManager {
  constructor(db) {
    this.db = db;
  }

  request(fromUserId, friendCode) {
    const code = String(friendCode || "").trim().toUpperCase();
    if (!FRIEND_CODE_RE.test(code)) throw new AccountError("invalid_code", "好友码无效", 400);
    const target = this.db.prepare(`SELECT id, nickname, avatar FROM users WHERE friend_code = ?`).get(code);
    if (!target) throw new AccountError("invalid_code", "好友码无效", 400);
    if (target.id === fromUserId) throw new AccountError("self_request", "不能添加自己为好友", 400);
    if (this._areFriends(fromUserId, target.id)) {
      throw new AccountError("already_friends", "对方已是你的好友", 409);
    }

    const reverse = this.db
      .prepare(`SELECT id FROM friend_requests WHERE from_user = ? AND to_user = ? AND status = 'pending'`)
      .get(target.id, fromUserId);
    if (reverse) {
      this._makeFriends(fromUserId, target.id);
      this.db.prepare(`UPDATE friend_requests SET status = 'accepted' WHERE id = ?`).run(reverse.id);
      return { requestId: reverse.id, accepted: true, friend: this._brief(target) };
    }

    const existing = this.db
      .prepare(`SELECT id FROM friend_requests WHERE from_user = ? AND to_user = ? AND status = 'pending'`)
      .get(fromUserId, target.id);
    if (existing) return { requestId: existing.id, accepted: false, friend: this._brief(target) };

    const id = crypto.randomUUID();
    this.db
      .prepare(`INSERT INTO friend_requests (id, from_user, to_user, status, created_at) VALUES (?, ?, ?, ?, ?)`)
      .run(id, fromUserId, target.id, "pending", Date.now());
    return { requestId: id, accepted: false, friend: this._brief(target) };
  }

  accept(userId, requestId) {
    const req = this._pendingFor(userId, requestId);
    this._makeFriends(req.from_user, req.to_user);
    this.db.prepare(`UPDATE friend_requests SET status = 'accepted' WHERE id = ?`).run(requestId);
    const from = this.db.prepare(`SELECT id, nickname, avatar FROM users WHERE id = ?`).get(req.from_user);
    return { friend: this._brief(from) };
  }

  reject(userId, requestId) {
    this._pendingFor(userId, requestId);
    this.db.prepare(`UPDATE friend_requests SET status = 'rejected' WHERE id = ?`).run(requestId);
    return { ok: true };
  }

  remove(userId, friendId) {
    if (!this._areFriends(userId, friendId)) {
      throw new AccountError("not_found", "好友关系不存在", 404);
    }
    this.db.prepare(`DELETE FROM friends WHERE user_id = ? AND friend_id = ?`).run(userId, friendId);
    this.db.prepare(`DELETE FROM friends WHERE user_id = ? AND friend_id = ?`).run(friendId, userId);
    return { ok: true };
  }

  list(userId) {
    const rows = this.db
      .prepare(
        `SELECT u.id, u.nickname, u.avatar, f.created_at
         FROM friends f JOIN users u ON u.id = f.friend_id
         WHERE f.user_id = ? ORDER BY f.created_at DESC`
      )
      .all(userId);
    return rows.map((r) => ({ userId: r.id, nickname: r.nickname, avatar: r.avatar, since: r.created_at }));
  }

  pendingRequests(userId) {
    const rows = this.db
      .prepare(
        `SELECT r.id, r.created_at, u.id AS uid, u.nickname, u.avatar
         FROM friend_requests r JOIN users u ON u.id = r.from_user
         WHERE r.to_user = ? AND r.status = 'pending' ORDER BY r.created_at DESC`
      )
      .all(userId);
    return rows.map((r) => ({
      requestId: r.id,
      from: { userId: r.uid, nickname: r.nickname, avatar: r.avatar },
      createdAt: r.created_at,
    }));
  }

  _pendingFor(userId, requestId) {
    const req = this.db.prepare(`SELECT * FROM friend_requests WHERE id = ?`).get(String(requestId || ""));
    if (!req || req.to_user !== userId) throw new AccountError("not_found", "好友申请不存在", 404);
    if (req.status !== "pending") throw new AccountError("invalid_state", "该申请已处理", 409);
    return req;
  }

  _areFriends(a, b) {
    return !!this.db.prepare(`SELECT 1 FROM friends WHERE user_id = ? AND friend_id = ?`).get(a, b);
  }

  _makeFriends(a, b) {
    const now = Date.now();
    const stmt = this.db.prepare(`INSERT OR IGNORE INTO friends (user_id, friend_id, created_at) VALUES (?, ?, ?)`);
    stmt.run(a, b, now);
    stmt.run(b, a, now);
  }

  _brief(u) {
    return { userId: u.id, nickname: u.nickname, avatar: u.avatar };
  }
}

module.exports = { FriendManager };
