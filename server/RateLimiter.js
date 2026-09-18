"use strict";

/**
 * 内存滑动窗口限流器：hit() 计数，超过 limit 返回 false。
 * 用于登录失败锁定、验证码请求频率与接口调用频率。单进程内存实现，重启即清空。
 */

class RateLimiter {
  constructor() {
    this.buckets = new Map();
  }

  hit(key, limit, windowMs) {
    const now = Date.now();
    const entry = this.buckets.get(key);
    if (!entry || now >= entry.resetAt) {
      this.buckets.set(key, { count: 1, resetAt: now + windowMs });
      return true;
    }
    entry.count += 1;
    return entry.count <= limit;
  }

  reset(key) {
    this.buckets.delete(key);
  }

  sweep() {
    const now = Date.now();
    for (const [key, entry] of this.buckets) {
      if (now >= entry.resetAt) this.buckets.delete(key);
    }
  }
}

module.exports = { RateLimiter };
