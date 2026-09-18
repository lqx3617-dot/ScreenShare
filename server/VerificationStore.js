"use strict";

/**
 * 邮箱验证码签发与校验。
 * 同一 (email, purpose) 只保留最新一条，签发即覆盖旧码；校验通过后立即删除，防重放。
 * 连续校验失败达到上限即作废该码，需重新获取。
 */

const CODE_TTL_MS = 10 * 60 * 1000;
const MAX_ATTEMPTS = 5;

class VerificationStore {
  constructor(db) {
    this.db = db;
  }

  issue(email, purpose) {
    // 测试/自测可固定验证码（ACCOUNT_FIXED_CODE），生产留空走随机码
    const code = process.env.ACCOUNT_FIXED_CODE || String(require("crypto").randomInt(100000, 1000000));
    const now = Date.now();
    this.db
      .prepare(
        `INSERT INTO verification_codes (email, purpose, code, expires_at, attempts)
         VALUES (?, ?, ?, ?, 0)
         ON CONFLICT(email, purpose) DO UPDATE SET
           code = excluded.code, expires_at = excluded.expires_at, attempts = 0`
      )
      .run(email, purpose, code, now + CODE_TTL_MS);
    return code;
  }

  verify(email, purpose, code) {
    const row = this.db
      .prepare(`SELECT code, expires_at, attempts FROM verification_codes WHERE email = ? AND purpose = ?`)
      .get(email, purpose);
    if (!row) return { ok: false, reason: "expired" };

    if (Date.now() > row.expires_at) {
      this._drop(email, purpose);
      return { ok: false, reason: "expired" };
    }
    if (row.attempts >= MAX_ATTEMPTS) {
      this._drop(email, purpose);
      return { ok: false, reason: "too_many" };
    }
    if (String(code) !== String(row.code)) {
      this.db
        .prepare(`UPDATE verification_codes SET attempts = attempts + 1 WHERE email = ? AND purpose = ?`)
        .run(email, purpose);
      return { ok: false, reason: "mismatch" };
    }
    this._drop(email, purpose);
    return { ok: true };
  }

  _drop(email, purpose) {
    this.db.prepare(`DELETE FROM verification_codes WHERE email = ? AND purpose = ?`).run(email, purpose);
  }
}

module.exports = { VerificationStore, CODE_TTL_MS, MAX_ATTEMPTS };
