"use strict";

/**
 * 账号核心逻辑：邮箱注册/登录/登出、密码找回、资料与好友码。
 *
 * 安全约定：
 * - 密码仅存 scrypt 哈希与随机盐，任何接口响应不含哈希与盐；
 * - 登录令牌只存 SHA-256 摘要，明文仅在签发响应中出现一次；
 * - 连续密码校验失败按邮箱锁定；重置密码使该用户全部会话失效。
 */

const crypto = require("crypto");

const SESSION_TTL_MS = 30 * 24 * 60 * 60 * 1000;
const LOGIN_LOCK_WINDOW_MS = 15 * 60 * 1000;
const LOGIN_MAX_FAILURES = 5;
const CODE_REQUEST_WINDOW_MS = 60 * 1000;
const CODE_REQUEST_MAX = 3;
const FRIEND_CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
const FRIEND_CODE_LENGTH = 6;
const NICKNAME_MAX = 20;
const DEFAULT_AVATAR = "0";
const AVATAR_VALUES = new Set(["0", "1", "2", "3", "4", "5"]);
const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

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
  constructor(db, { verificationStore, rateLimiter, mailer } = {}) {
    this.db = db;
    this.verification = verificationStore;
    this.rateLimiter = rateLimiter;
    this.mailer = mailer;
  }

  async requestRegisterCode(email, deviceInfo = "") {
    const mail = this._normalizeEmail(email);
    this._assertEmail(mail);
    if (this._findByEmail(mail)) {
      throw new AccountError("email_taken", "该邮箱已注册，请直接登录", 409);
    }
    this._assertCodeRequestAllowed(mail);
    const code = this.verification.issue(mail, "register");
    await this.mailer.sendCode(mail, code, "register");
    return { ok: true };
  }

  async register(email, code, password, deviceInfo = "") {
    const mail = this._normalizeEmail(email);
    this._assertEmail(mail);
    if (this._findByEmail(mail)) {
      throw new AccountError("email_taken", "该邮箱已注册，请直接登录", 409);
    }
    this._assertPassword(password);
    const verified = this.verification.verify(mail, "register", code);
    if (!verified.ok) {
      throw new AccountError("invalid_code", "验证码无效或已过期", verified.reason === "too_many" ? 429 : 400);
    }

    const userId = crypto.randomUUID();
    const friendCode = this._generateFriendCode();
    const salt = crypto.randomBytes(16).toString("hex");
    const nickname = this._defaultNickname(mail, friendCode);
    try {
      this.db
        .prepare(
          `INSERT INTO users (id, email, password_hash, salt, nickname, avatar, friend_code, created_at)
           VALUES (?, ?, ?, ?, ?, ?, ?, ?)`
        )
        .run(userId, mail, hashPassword(password, salt), salt, nickname, DEFAULT_AVATAR, friendCode, Date.now());
    } catch (e) {
      if (String(e.message).includes("UNIQUE")) {
        throw new AccountError("email_taken", "该邮箱已注册，请直接登录", 409);
      }
      throw e;
    }

    const token = this._issueSession(userId, deviceInfo);
    return { userId, token, profile: this.getProfile(userId) };
  }

  async requestResetCode(email) {
    const mail = this._normalizeEmail(email);
    this._assertEmail(mail);
    const user = this._findByEmail(mail);
    if (user) {
      this._assertCodeRequestAllowed(mail);
      const code = this.verification.issue(mail, "reset");
      await this.mailer.sendCode(mail, code, "reset");
    }
    return { ok: true };
  }

  async resetPassword(email, code, newPassword) {
    const mail = this._normalizeEmail(email);
    this._assertEmail(mail);
    this._assertPassword(newPassword);
    const user = this._findByEmail(mail);
    if (!user) {
      throw new AccountError("invalid_code", "验证码无效或已过期", 400);
    }
    const verified = this.verification.verify(mail, "reset", code);
    if (!verified.ok) {
      throw new AccountError("invalid_code", "验证码无效或已过期", verified.reason === "too_many" ? 429 : 400);
    }
    const salt = crypto.randomBytes(16).toString("hex");
    this.db
      .prepare(`UPDATE users SET password_hash = ?, salt = ? WHERE id = ?`)
      .run(hashPassword(newPassword, salt), salt, user.id);
    this.db.prepare(`DELETE FROM sessions WHERE user_id = ?`).run(user.id);
    return { ok: true };
  }

  async login(email, password, deviceInfo = "") {
    const mail = this._normalizeEmail(email);
    this._assertEmail(mail);
    if (typeof password !== "string" || password.length === 0) {
      throw new AccountError("invalid_credentials", "邮箱或密码错误", 401);
    }
    const lockKey = `login:${mail}`;
    if (!this.rateLimiter.hit(lockKey, LOGIN_MAX_FAILURES, LOGIN_LOCK_WINDOW_MS)) {
      throw new AccountError("too_many_attempts", "失败次数过多，请 15 分钟后再试", 429);
    }

    const user = this._findByEmail(mail);
    const ok = user ? this._verifyPassword(password, user.salt, user.password_hash) : false;
    if (!ok) {
      throw new AccountError("invalid_credentials", "邮箱或密码错误", 401);
    }
    this.rateLimiter.reset(lockKey);
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
      .prepare(`SELECT id, email, nickname, avatar, friend_code, created_at FROM users WHERE id = ?`)
      .get(userId);
    if (!u) return null;
    return {
      userId: u.id,
      email: u.email,
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

  _findByEmail(email) {
    return this.db.prepare(`SELECT id, email, password_hash, salt FROM users WHERE email = ?`).get(email) || null;
  }

  _normalizeEmail(email) {
    return String(email || "").trim().toLowerCase();
  }

  _assertEmail(email) {
    if (!EMAIL_RE.test(email)) {
      throw new AccountError("invalid_email", "邮箱格式不正确", 400);
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

  _assertCodeRequestAllowed(email) {
    if (!this.rateLimiter.hit(`codereq:${email}`, CODE_REQUEST_MAX, CODE_REQUEST_WINDOW_MS)) {
      throw new AccountError("too_many_requests", "请求过于频繁，请稍后再试", 429);
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

  _defaultNickname(email, friendCode) {
    const prefix = email.split("@")[0].replace(/[^\w\u4e00-\u9fa5]/g, "");
    const name = prefix || `用户${friendCode.slice(0, 4)}`;
    return name.slice(0, NICKNAME_MAX);
  }
}

module.exports = { AccountManager, AccountError };
