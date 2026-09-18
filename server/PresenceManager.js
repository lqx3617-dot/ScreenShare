"use strict";

/**
 * 在线状态：一个用户可有多条信令连接（多设备），任一连接在线即在线。
 * 纯内存，进程重启即清空；attach/detach 返回是否发生「离线→在线」或「在线→离线」翻转，
 * 由调用方据此决定是否向好友广播。
 */

class PresenceManager {
  constructor() {
    this.byUser = new Map();
  }

  attach(userId, ws) {
    let set = this.byUser.get(userId);
    const cameOnline = !set || set.size === 0;
    if (!set) {
      set = new Set();
      this.byUser.set(userId, set);
    }
    set.add(ws);
    return cameOnline;
  }

  detach(userId, ws) {
    const set = this.byUser.get(userId);
    if (!set) return false;
    set.delete(ws);
    if (set.size === 0) {
      this.byUser.delete(userId);
      return true;
    }
    return false;
  }

  isOnline(userId) {
    const set = this.byUser.get(userId);
    return !!set && set.size > 0;
  }

  socketsOf(userId) {
    const set = this.byUser.get(userId);
    return set ? [...set] : [];
  }
}

module.exports = { PresenceManager };
