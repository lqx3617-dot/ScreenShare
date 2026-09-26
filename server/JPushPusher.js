"use strict";

/**
 * 极光推送 REST API v3：用 AppKey + Master Secret 做 Basic Auth，
 * 调 POST https://api.jpush.cn/v3/push。纯 Node 22 内置（fetch/crypto），无外部依赖。
 *
 * 安全约定：Master Secret 只在内存与请求头（base64）中出现，绝不写入日志或响应。
 * 推送失败只记录错误原因，不抛出——推送是邀请链路的旁路，不能影响主流程。
 *
 * registrationID 是极光维度的设备标识，客户端通过 onRegister 回调上报，
 * 与原 FCM token 占用同一个 push_token 字段。
 */

const PUSH_URL = "https://api.jpush.cn/v3/push";

class JPushPusher {
  /**
   * @param {string} appKey 极光应用 AppKey
   * @param {string} masterSecret 极光应用 Master Secret
   */
  constructor(appKey, masterSecret) {
    this.enabled = false;
    if (!appKey || !masterSecret) return;
    // Basic Auth 凭据只算一次复用；不放日志
    this.authHeader = "Basic " + Buffer.from(`${appKey}:${masterSecret}`).toString("base64");
    this.enabled = true;
  }

  /**
   * 发推送（按 registrationID 单推）。
   * @param {string} registrationId 客户端上报的极光标识
   * @param {string} title 通知标题
   * @param {string} body 通知内容
   * @param {object} extras 额外字段（透传给 App）
   * @returns {Promise<"ok"|"invalid_token"|"failed">} 投递结果
   */
  async send(registrationId, title, body, extras = {}) {
    if (!this.enabled) return "failed";
    if (!registrationId) return "failed";
    try {
      const res = await fetch(PUSH_URL, {
        method: "POST",
        // 极光接口卡住时及时放弃，否则推送 Promise 永久挂起
        signal: AbortSignal.timeout(8000),
        headers: {
          Authorization: this.authHeader,
          "Content-Type": "application/json",
        },
        body: JSON.stringify({
          platform: "android",
          audience: { registration_id: [registrationId] },
          notification: {
            android: {
              alert: body,
              title,
              // 高优先级：离线邀请需要及时送达
              priority: 2,
              category: "high",
              // 通知点击不打开 Activity，由 App 自行处理（与原 FCM data-only 行为对齐）
              extras,
            },
          },
          options: {
            // 透传+通知：App 在前台时也能收到自定义消息
            third_party_channel: { jpush: { distribution: "osp" } },
          },
        }),
      });
      const resp = await res.json();
      if (res.ok) {
        console.log(`[jpush] 推送成功 msg_id=${resp.msg_id}`);
        return "ok";
      }
      // 1011 = 目标设备不存在/未注册；1010 = registrationID 非法
      // 1004 = appkey 不存在（配错或未创建）；1003 = appkey 与 master secret 不匹配
      const code = resp && resp.code;
      console.log(`[jpush] 推送失败 code=${code}`);
      return code === 1011 || code === 1010 ? "invalid_token" : "failed";
    } catch (e) {
      console.log(`[jpush] 推送异常：${e.message}`);
      return "failed";
    }
  }
}

module.exports = { JPushPusher };
