/**
 * AuthManager.js —— V4：房间 Token 认证
 *
 * 用途：防止"撞房"与未授权观看。
 * - host 创建房间时生成随机 Token，仅返回给 host
 * - viewer join 时必须携带正确 Token，否则拒绝
 * - Token 由 host 通过口令告知合法 viewer（App 端展示给共享发起人线下转发）
 */
"use strict";

const crypto = require("crypto");

// token -> { roomCode } 简单索引（token 与房间绑定，房销毁即失效）
const tokenIndex = new Map();

class AuthManager {
  /**
   * 为房间生成新 Token（8 位大写字母数字，防暴力也便于 human 输入）。
   * @returns {string}
   */
  static issueToken(roomCode) {
    // 8 字符，去掉易混淆字符 0/O/1/I
    const alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    let token = "";
    for (let i = 0; i < 8; i++) {
      token += alphabet[crypto.randomInt(0, alphabet.length)];
    }
    tokenIndex.set(token, roomCode);
    return token;
  }

  /**
   * 校验 token 是否属于该房间。
   * @param {string} roomCode 归属房间号
   * @param {string} token 待校验 token
   * @returns {boolean}
   */
  static verify(roomCode, token) {
    if (!token) return false;
    return tokenIndex.get(String(token).trim().toUpperCase()) === roomCode;
  }

  /** 房间销毁/重建时释放其 token，避免占用 */
  static releaseTokens(roomCode) {
    for (const [tok, code] of tokenIndex) {
      if (code === roomCode) tokenIndex.delete(tok);
    }
  }
}

module.exports = AuthManager;