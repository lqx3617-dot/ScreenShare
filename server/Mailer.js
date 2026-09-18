"use strict";

/**
 * 验证码发送。
 * 开发模式（默认）：不真正发信，验证码写入服务端日志便于自测，接口照常返回成功。
 * 上线前通过 MAIL_DEV_MODE=0 并提供 transport（SMTP / 第三方邮件 API）切换到真实发送。
 */

class Mailer {
  constructor({ devMode = process.env.MAIL_DEV_MODE !== "0", transport = null } = {}) {
    this.devMode = devMode;
    this.transport = transport;
  }

  async sendCode(email, code, purpose) {
    if (this.devMode || typeof this.transport !== "function") {
      console.log(`[mail:dev] ${purpose} code for ${email}: ${code}`);
      return { ok: true, dev: true };
    }
    return this.transport(email, code, purpose);
  }
}

module.exports = { Mailer };
