"use strict";

/**
 * 账号核心逻辑：昵称注册/登录/登出、资料与好友码。
 *
 * 设计：无邮箱、无验证码——昵称即登录标识（唯一），密码保护账号。
 * 安全约定：
 * - 密码仅存 scrypt 哈希与随机盐，任何接口响应不含哈希与盐；
 * - 登录令牌只存 SHA-256 摘要，明文仅在签发响应中出现一次；
 * - 连续密码校验失败按昵称锁定。
 * - 无邮箱即无自助找回密码：忘记密码需联系服务器管理员重置。
 */

const crypto = require("crypto");
const { RateLimiter } = require("./RateLimiter");

const SESSION_TTL_MS = 30 * 24 * 60 * 60 * 1000;
const LOGIN_LOCK_WINDOW_MS = 15 * 60 * 1000;
const LOGIN_MAX_FAILURES = 5;
const NICKNAME_MIN = 2;
const NICKNAME_MAX = 20;
const DEFAULT_AVATAR = "0";
const AVATAR_VALUES = new Set(["0", "1", "2", "3", "4", "5"]);
const FRIEND_CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
const FRIEND_CODE_LENGTH = 6;

class AccountError extends Error {
  constructor(code, message, status = 400) {
    super(message);
    this.name = "AccountError";
    this.code = code;
    this.status = status;
  }
}

function sha256(value) {
  return crypto.createHash("sha256").update(value).digest("hex");
}

function hashPassword(password, salt) {
  return crypto.scryptSync(password, salt, 64).toString("hex");
}

class AccountManager {
  constructor(db) {
    this.db = db;
    // 登录失败锁定按昵称独立计数（不与 API 限流混用）
    this.loginLimiter = new RateLimiter();
  }

  async register(nickname, password, deviceInfo = "") {
    const name = this._normalizeNickname(nickname);
    this._assertNickname(name);
    if (this._findByNickname(name)) {
      throw new AccountError("nickname_taken", "该昵称已被占用，请换一个", 409);
    }
    this._assertPassword(password);

    const userId = crypto.randomUUID();
    const friendCode = this._generateFriendCode();
    const salt = crypto.randomBytes(16).toString("hex");
    try {
      this.db
        .prepare(
          `INSERT INTO users (id, email, password_hash, salt, nickname, avatar, friend_code, created_at)
           VALUES (?, NULL, ?, ?, ?, ?, ?, ?)`
        )
        .run(userId, hashPassword(password, salt), salt, name, DEFAULT_AVATAR, friendCode, Date.now());
    } catch (e) {
      if (String(e.message).includes("UNIQUE")) {
        throw new AccountError("nickname_taken", "该昵称已被占用，请换一个", 409);
      }
      throw e;
    }

    const token = this._issueSession(userId, deviceInfo);
    return { userId, token, profile: this.getProfile(userId) };
  }

  async login(nickname, password, deviceInfo = "") {
    const name = this._normalizeNickname(nickname);
    this._assertNickname(name);
    if (typeof password !== "string" || password.length === 0) {
      throw new AccountError("invalid_credentials", "昵称或密码错误", 401);
    }
    const lockKey = `login:${name}`;
    if (!this.loginLimiter.hit(lockKey, LOGIN_MAX_FAILURES, LOGIN_LOCK_WINDOW_MS)) {
      throw new AccountError("too_many_attempts", "失败次数过多，请 15 分钟后再试", 429);
    }

    const user = this._findByNickname(name);
    const ok = user ? this._verifyPassword(password, user.salt, user.password_hash) : false;
    if (!ok) {
      throw new AccountError("invalid_credentials", "昵称或密码错误", 401);
    }
    this.loginLimiter.reset(lockKey);
    const token = this._issueSession(user.id, deviceInfo);
    return { userId: user.id, token, profile: this.getProfile(user.id) };
  }

  logout(token) {
    if (!token) return { ok: true };
    this.db.prepare(`DELETE FROM sessions WHERE token_hash = ?`).run(sha256(token));
    return { ok: true };
  }

  authenticate(token) {
    if (!token) throw new AccountError("unauthenticated", "请先登录", 401);
    const row = this.db
      .prepare(`SELECT user_id, expires_at FROM sessions WHERE token_hash = ?`)
      .get(sha256(token));
    if (!row) throw new AccountError("unauthenticated", "登录已失效，请重新登录", 401);
    if (Date.now() > row.expires_at) {
      this.db.prepare(`DELETE FROM sessions WHERE token_hash = ?`).run(sha256(token));
      throw new AccountError("unauthenticated", "登录已过期，请重新登录", 401);
    }
    this.db.prepare(`UPDATE sessions SET last_seen = ? WHERE token_hash = ?`).run(Date.now(), sha256(token));
    return row.user_id;
  }

  getProfile(userId) {
    const u = this.db
      .prepare(`SELECT id, nickname, avatar, friend_code, created_at FROM users WHERE id = ?`)
      .get(userId);
    if (!u) return null;
    return {
      userId: u.id,
      nickname: u.nickname,
      avatar: u.avatar,
      friendCode: u.friend_code,
      createdAt: u.created_at,
    };
  }

  updateProfile(userId, patch = {}) {
    const user = this.getProfile(userId);
    if (!user) throw new AccountError("not_found", "账号不存在", 404);

    if (patch.nickname !== undefined) {
      const nickname = String(patch.nickname).trim();
      if (nickname.length < 1) throw new AccountError("invalid_nickname", "昵称不能为空", 400);
      if (nickname.length > NICKNAME_MAX) {
        throw new AccountError("invalid_nickname", `昵称最多 ${NICKNAME_MAX} 个字符`, 400);
      }
      this.db.prepare(`UPDATE users SET nickname = ? WHERE id = ?`).run(nickname, userId);
    }
    if (patch.avatar !== undefined) {
      const avatar = String(patch.avatar);
      if (!AVATAR_VALUES.has(avatar)) {
        throw new AccountError("invalid_avatar", "头像档位无效", 400);
      }
      this.db.prepare(`UPDATE users SET avatar = ? WHERE id = ?`).run(avatar, userId);
    }
    return this.getProfile(userId);
  }

  _issueSession(userId, deviceInfo) {
    const token = crypto.randomBytes(32).toString("hex");
    const now = Date.now();
    this.db
      .prepare(
        `INSERT INTO sessions (token_hash, user_id, device_info, created_at, last_seen, expires_at)
         VALUES (?, ?, ?, ?, ?, ?)`
      )
      .run(sha256(token), userId, String(deviceInfo || "").slice(0, 200), now, now, now + SESSION_TTL_MS);
    return token;
  }

  _verifyPassword(password, salt, expectedHex) {
    const actual = Buffer.from(hashPassword(password, salt), "hex");
    const expected = Buffer.from(expectedHex, "hex");
    if (actual.length !== expected.length) return false;
    return crypto.timingSafeEqual(actual, expected);
  }

  _findByNickname(nickname) {
    return this.db.prepare(`SELECT id, password_hash, salt FROM users WHERE nickname = ?`).get(nickname) || null;
  }

  _normalizeNickname(nickname) {
    return String(nickname || "").trim();
  }

  _assertNickname(nickname) {
    if (nickname.length < NICKNAME_MIN || nickname.length > NICKNAME_MAX) {
      throw new AccountError("invalid_nickname", `昵称需为 ${NICKNAME_MIN}-${NICKNAME_MAX} 个字符`, 400);
    }
  }

  _assertPassword(password) {
    if (typeof password !== "string" || password.length < 8) {
      throw new AccountError("weak_password", "密码至少 8 位", 400);
    }
    const kinds = [/[a-z]/, /[A-Z]/, /[0-9]/, /[^a-zA-Z0-9]/].filter((re) => re.test(password)).length;
    if (kinds < 2) {
      throw new AccountError("weak_password", "密码需包含字母、数字或符号中的至少两类", 400);
    }
  }

  _generateFriendCode() {
    for (let i = 0; i < 32; i++) {
      let code = "";
      for (let j = 0; j < FRIEND_CODE_LENGTH; j++) {
        code += FRIEND_CODE_ALPHABET[crypto.randomInt(FRIEND_CODE_ALPHABET.length)];
      }
      const exists = this.db.prepare(`SELECT 1 FROM users WHERE friend_code = ?`).get(code);
      if (!exists) return code;
    }
    throw new AccountError("server_busy", "好友码生成失败，请稍后重试", 500);
  }
}

module.exports = { AccountManager, AccountError };
