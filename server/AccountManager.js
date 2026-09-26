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

// scrypt 必须用异步版本：scryptSync 单次约数十毫秒且完全阻塞事件循环，
// 并发登录/注册请求会互相放大成实际 DoS（同步执行期间无法处理任何其他连接）
function hashPassword(password, salt) {
  return new Promise((resolve, reject) => {
    crypto.scrypt(password, salt, 64, (err, b) => {
      if (err) reject(err);
      else resolve(b.toString("hex"));
    });
  });
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
        .run(userId, await hashPassword(password, salt), salt, name, DEFAULT_AVATAR, friendCode, Date.now());
    } catch (e) {
      if (String(e.message).includes("UNIQUE")) {
        throw new AccountError("nickname_taken", "该昵称已被占用，请换一个", 409);
      }
      throw e;
    }

    const token = this._issueSession(userId, deviceInfo);
    return { userId, token, profile: this.getProfile(userId) };
  }

  async login(nickname, password, deviceInfo = "", ip = "") {
    const name = this._normalizeNickname(nickname);
    this._assertNickname(name);
    if (typeof password !== "string" || password.length === 0) {
      throw new AccountError("invalid_credentials", "昵称或密码错误", 401);
    }
    // 锁定 key 含 IP：纯按昵称锁定时，攻击者可对任意昵称故意失败 5 次将其锁死 15 分钟
    // （账号锁定 DoS）。含 IP 后只能锁自己 IP 的尝试。暴力破解由同 IP 的 5 次上限覆盖。
    const lockKey = `login:${ip || "?"}:${name}`;
    if (!this.loginLimiter.hit(lockKey, LOGIN_MAX_FAILURES, LOGIN_LOCK_WINDOW_MS)) {
      throw new AccountError("too_many_attempts", "失败次数过多，请 15 分钟后再试", 429);
    }

    const user = this._findByNickname(name);
    const ok = user ? await this._verifyPassword(password, user.salt, user.password_hash) : false;
    if (!ok) {
      throw new AccountError("invalid_credentials", "昵称或密码错误", 401);
    }
    this.loginLimiter.reset(lockKey);
    const token = this._issueSession(user.id, deviceInfo);
    return { userId: user.id, token, profile: this.getProfile(user.id) };
  }

  /** 定期清理过期的登录失败记录，防止 loginLimiter 无界堆积 */
  sweepLoginLimiter() {
    this.loginLimiter.sweep();
  }

  /** 清理所有过期会话：authenticate 只在命中时删自己那一条，
   * 长期不登录的会话会一直留在 sessions 表无界增长 */
  sweepExpiredSessions() {
    this.db.prepare(`DELETE FROM sessions WHERE expires_at < ?`).run(Date.now());
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

  /** 保存本设备的 FCM 推送令牌（令牌可能轮换，每次上报覆盖旧值） */
  setPushToken(userId, pushToken) {
    const token = String(pushToken || "").trim();
    if (!token) throw new AccountError("invalid_token", "推送令牌不能为空", 400);
    const r = this.db.prepare(`UPDATE users SET push_token = ? WHERE id = ?`).run(token, userId);
    if (r.changes === 0) throw new AccountError("not_found", "账号不存在", 404);
    return { ok: true };
  }

  getPushToken(userId) {
    const r = this.db.prepare(`SELECT push_token FROM users WHERE id = ?`).get(userId);
    return r ? r.push_token || "" : "";
  }

  /** 清空推送令牌：客户端报告令牌失效时调用 */
  clearPushToken(userId) {
    this.db.prepare(`UPDATE users SET push_token = NULL WHERE id = ?`).run(userId);
    return { ok: true };
  }

  updateProfile(userId, patch = {}) {
    const user = this.getProfile(userId);
    if (!user) throw new AccountError("not_found", "账号不存在", 404);

    if (patch.nickname !== undefined) {
      // 显式拒绝 null/非字符串：String(null) 会得到 "null" 字符串并被当合法昵称存入
      if (typeof patch.nickname !== "string") {
        throw new AccountError("invalid_nickname", "昵称格式无效", 400);
      }
      const nickname = this._normalizeNickname(patch.nickname);
      // 与注册统一走 _assertNickname：原先只查 length<1 允许了 1 字符昵称，
      // 与 NICKNAME_MIN=2 的注册约束不一致
      this._assertNickname(nickname);
      if (nickname !== user.nickname) {
        const taken = this._findByNickname(this._normalizeNickname(nickname));
        if (taken && taken.id !== userId) {
          throw new AccountError("nickname_taken", "该昵称已被占用，请换一个", 409);
        }
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
    if (patch.friendCode !== undefined) {
      // 好友码可随时修改：校验格式 + 全局唯一。改码后旧码查无此人，
      // 他人用旧码发起的邀请/申请会直接失败；同时作废自己名下待处理的邀请与申请
      const code = this._normalizeFriendCode(patch.friendCode);
      if (code !== user.friendCode) {
        const taken = this.db
          .prepare(`SELECT 1 FROM users WHERE friend_code = ? AND id != ?`)
          .get(code, userId);
        if (taken) throw new AccountError("friend_code_taken", "该好友码已被占用，请换一个", 409);
        // 改码 + 作废待处理邀请/申请须同生共死：任一 UPDATE 失败时整体回滚，
        // 避免出现新码已生效但旧邀请仍滞留 pending 的中间态
        this.db.exec("BEGIN");
        try {
          this.db.prepare(`UPDATE users SET friend_code = ? WHERE id = ?`).run(code, userId);
          this.db
            .prepare(`UPDATE couple_invitations SET status = 'cancelled' WHERE to_user = ? AND status = 'pending'`)
            .run(userId);
          this.db
            .prepare(`UPDATE friend_requests SET status = 'cancelled' WHERE to_user = ? AND status = 'pending'`)
            .run(userId);
          this.db.exec("COMMIT");
        } catch (e) {
          try { this.db.exec("ROLLBACK"); } catch (_) {}
          throw e instanceof AccountError
            ? e
            : new AccountError("friend_code_update_failed", "好友码修改失败，请重试", 500);
        }
      }
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
    return hashPassword(password, salt).then((hex) => {
      const actual = Buffer.from(hex, "hex");
      const expected = Buffer.from(expectedHex, "hex");
      if (actual.length !== expected.length) return false;
      return crypto.timingSafeEqual(actual, expected);
    });
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

  /** 好友码规整与校验：6 位大写字母数字，剔除易混的 I/O/0/1 */
  _normalizeFriendCode(raw) {
    const code = String(raw ?? "").trim().toUpperCase();
    const ok =
      code.length === FRIEND_CODE_LENGTH &&
      [...code].every((c) => FRIEND_CODE_ALPHABET.includes(c));
    if (!ok) {
      throw new AccountError("invalid_friend_code", "好友码需为 6 位字母或数字（不含 I、O、0、1）", 400);
    }
    return code;
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
