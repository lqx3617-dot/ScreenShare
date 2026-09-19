"use strict";

/**
 * FCM HTTP v1 推送：用服务账号私钥签 RS256 JWT 换 OAuth2 access token，
 * 再调 messages:send。纯 Node 22 内置（fetch/crypto），无外部依赖。
 *
 * 安全约定：私钥只在内存与请求头中出现，绝不写入日志或响应。
 * 推送失败只记录错误原因，不抛出——推送是邀请链路的旁路，不能影响主流程。
 */

const crypto = require("crypto");
const fs = require("fs");

const SCOPE = "https://www.googleapis.com/auth/firebase.messaging";
const TOKEN_URL = "https://oauth2.googleapis.com/token";

class FcmPusher {
  /**
   * @param {string} serviceAccountPath 服务账号私钥 JSON 路径；为空时整体禁用
   */
  constructor(serviceAccountPath) {
    this.enabled = false;
    if (!serviceAccountPath || !fs.existsSync(serviceAccountPath)) return;
    try {
      const sa = JSON.parse(fs.readFileSync(serviceAccountPath, "utf8"));
      if (sa.type !== "service_account" || !sa.private_key || !sa.project_id || !sa.client_email) {
        throw new Error("私钥文件缺少 type/private_key/project_id/client_email");
      }
      this.projectId = sa.project_id;
      this.clientEmail = sa.client_email;
      this.privateKey = sa.private_key;
      this.accessToken = null;
      this.expiresAt = 0;
      this.enabled = true;
    } catch (e) {
      console.log(`[fcm] 初始化失败，推送禁用：${e.message}`);
    }
  }

  /** 换访问令牌，复用未过期的 */
  async ensureToken() {
    if (this.accessToken && this.expiresAt > Date.now() + 60_000) return this.accessToken;
    const now = Math.floor(Date.now() / 1000);
    const header = { alg: "RS256", typ: "JWT" };
    const payload = {
      iss: this.clientEmail,
      scope: SCOPE,
      aud: TOKEN_URL,
      iat: now,
      exp: now + 3600,
    };
    const b64 = (o) => Buffer.from(JSON.stringify(o)).toString("base64url");
    const data = `${b64(header)}.${b64(payload)}`;
    const sign = crypto.createSign("RSA-SHA256");
    sign.update(data);
    const assertion = `${data}.${sign.sign(this.privateKey, "base64url")}`;

    const res = await fetch(TOKEN_URL, {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({
        grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
        assertion,
      }),
    });
    const body = await res.json();
    if (!res.ok) throw new Error(`OAuth2 换票失败: ${body.error || res.status}`);
    this.accessToken = body.access_token;
    this.expiresAt = Date.now() + body.expires_in * 1000;
    return this.accessToken;
  }

  /**
   * 发推送。成功/失败只返回布尔并打日志，绝不抛异常。
   * @returns {Promise<boolean>} 是否投递成功
   */
  async send(token, title, body, data = {}) {
    if (!this.enabled) return false;
    if (!token) return false;
    try {
      const accessToken = await this.ensureToken();
      const res = await fetch(
        `https://fcm.googleapis.com/v1/projects/${this.projectId}/messages:send`,
        {
          method: "POST",
          headers: {
            Authorization: `Bearer ${accessToken}`,
            "Content-Type": "application/json",
          },
          body: JSON.stringify({
            message: {
              token,
              notification: { title, body },
              android: { priority: "high" },
              data,
            },
          }),
        }
      );
      const resp = await res.json();
      if (res.ok) {
        console.log(`[fcm] 推送成功 name=${resp.name}`);
        return true;
      }
      // 令牌失效（UNREGISTERED/INVALID_ARGUMENT）：调用方应清掉它
      const reason = (resp.error && resp.error.status) || res.status;
      console.log(`[fcm] 推送失败 status=${reason}`);
      return reason === "UNREGISTERED" || reason === "INVALID_ARGUMENT" ? "invalid_token" : false;
    } catch (e) {
      console.log(`[fcm] 推送异常：${e.message}`);
      return false;
    }
  }
}

module.exports = { FcmPusher };
