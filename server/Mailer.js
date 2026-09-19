"use strict";

const nodemailer = require("nodemailer");

/**
 * 验证码发送。
 * 开发模式（MAIL_DEV_MODE != 0）：不真正发信，验证码写入服务端日志便于自测，接口照常返回成功。
 * 生产模式（MAIL_DEV_MODE=0 且配置 SMTP）：用 nodemailer 真实发送。
 *   MAIL_HOST / MAIL_PORT / MAIL_USER / MAIL_PASS / MAIL_FROM
 *   MAIL_PASS 是邮箱授权码（如 QQ 邮箱），不是登录密码。
 */

class Mailer {
  constructor({ devMode = process.env.MAIL_DEV_MODE !== "0", transport = null } = {}) {
    this.devMode = devMode;
    this.transport = transport;
    this.smtp = null;
    if (!devMode && !transport && process.env.MAIL_HOST && process.env.MAIL_USER) {
      this.smtp = nodemailer.createTransport({
        host: process.env.MAIL_HOST,
        port: Number(process.env.MAIL_PORT || 465),
        secure: (process.env.MAIL_SECURE || "1") === "1",
        auth: { user: process.env.MAIL_USER, pass: process.env.MAIL_PASS || "" }
      });
      this.from = process.env.MAIL_FROM || `"ScreenShare" <${process.env.MAIL_USER}>`;
    } else if (!devMode) {
      console.warn("[mail] 生产模式但 SMTP 未配置（缺 MAIL_HOST/MAIL_USER），验证码将只写日志");
    }
  }

  async sendCode(email, code, purpose) {
    if (this.devMode || (typeof this.transport !== "function" && !this.smtp)) {
      console.log(`[mail:dev] ${purpose} code for ${email}: ${code}`);
      return { ok: true, dev: true };
    }
    if (this.smtp) {
      const subject = purpose === "reset" ? "重置 ScreenShare 密码" : "ScreenShare 注册验证码";
      try {
        await this.smtp.sendMail({
          from: this.from,
          to: email,
          subject,
          text: `你的验证码是 ${code}，10 分钟内有效。如非本人操作请忽略此邮件。`
        });
        return { ok: true };
      } catch (e) {
        // 发信失败不得阻断注册流程：降级写日志（验证码仍可用），便于人工兜底
        console.error(`[mail:fail] ${purpose} for ${email}: ${e.code || e.message}`);
        console.log(`[mail:fallback] ${purpose} code for ${email}: ${code}`);
        return { ok: true, fallback: true };
      }
    }
    return this.transport(email, code, purpose);
  }
}

module.exports = { Mailer };
