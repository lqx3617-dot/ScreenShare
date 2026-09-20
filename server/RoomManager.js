/**
 * RoomManager.js —— V5：多客户端房间管理 + 加入需 Host 确认
 *
 * 房结构：1 host + N viewers（情侣模式实际 1 对 1）。
 *   room = { host: ws, viewers: Map<viewerId, ws>, pending: Map<viewerId, {ws, at}> }
 *
 * 加入流程（防撞房）：
 * - viewer 发 join → 进入 pending（不占用 viewer 槽位），通知 host 弹确认
 * - host accept → 正式加入 viewers；host reject → 通知 viewer 被拒
 * - pending 30s 未确认自动过期；pending viewer 断线自动移除
 *
 * 路由规则：
 * - host 的 relay 广播给指定 viewer
 * - viewer 的 relay 转发给 host
 * - 任一 viewer 离开：从 viewers 移除，通知 host；host 离开：整房销毁，通知所有 viewer
 */
"use strict";

// pending 加入请求超时（毫秒）
const PENDING_TIMEOUT = 30 * 1000;
// viewer 断开后的重连宽限期：期间保留 viewerId 槽位不结账、不通知 host，
// 同 userId 重连即恢复；超时才真正离开（结账 + 通知 host）
const RECONNECT_TIMEOUT = 60 * 1000;

class RoomManager {
  /**
   * @param {function} onReconnectExpired 宽限期超时回调 (code, viewerId, userId)，
   *        由 server.js 注册：通知 host viewer-left 并结账共享会话
   * @param {number} reconnectTimeout 测试可覆盖宽限期
   */
  constructor(onReconnectExpired, reconnectTimeout = RECONNECT_TIMEOUT) {
    /** code -> room */
    this.rooms = new Map();
    /** 自增 viewerId，区分同一 host 下的多个 viewer */
    this.viewerSeq = 0;
    this.onReconnectExpired = onReconnectExpired || (() => {});
    this.reconnectTimeout = reconnectTimeout;
  }

  /** 校验会议号格式（4 位数字） */
  isValidCode(code) {
    return /^[0-9]{4}$/.test(code);
  }

  /** host 创建房间。返回空串表示成功，否则返回错误文案 */
  create(code, hostWs, hostUserId) {
    const existing = this.rooms.get(code);
    if (existing) {
      // host 连接已关闭（close 事件与房间清理的时序竞态、或网络黑洞后被 terminate）
      // 时回收死房间，否则真 host 重连会被自己的房间号锁死最多 45s
      if (existing.host.readyState === 1) return "会议号已被占用，请重试";
      this.rooms.delete(code);
    }
    this.rooms.set(code, { host: hostWs, hostUserId, viewers: new Map(), pending: new Map(), reconnecting: new Map() });
    return "";
  }

  /**
   * viewer 加入房间（直接进入 viewers，无需 host 确认）。
   * 返回 { ok:true, viewerId } 或 { ok:false, error }。
   * 情侣模式：已有 viewer 时拒绝后续加入。
   */
  requestJoin(code, viewerWs) {
    const room = this.rooms.get(code);
    if (!room) return { ok: false, error: "会议号不存在或会议已结束" };
    // host 已断开（close 清理时序竞态）时当作会议已结束，避免 viewer 加入空房间白等
    if (room.host.readyState !== 1) {
      this.rooms.delete(code);
      return { ok: false, error: "会议号不存在或会议已结束" };
    }
    if (room.viewers.size > 0 || room.reconnecting.size > 0) {
      // 清理已断开但尚未走完 close 清理的僵尸 viewer，避免新 viewer 被死连接挡住。
      // 除 readyState 外加 lastSeen 判据：客户端每 10s ping，15s 无消息即为半开死连接
      // （TCP 半开时 readyState 仍为 OPEN，只有心跳能判定），缩短重连被拒的窗口期。
      const now = Date.now();
      const purged = [];
      for (const [vid, vws] of room.viewers) {
        if (vws.readyState !== 1 || now - (vws.lastSeen || 0) > 15 * 1000) {
          room.viewers.delete(vid);
          // 返回给调用方：须通知 host viewer-left（否则 host 侧 PC 永不回收）并
          // terminate 僵尸 socket（否则 close 事件要等心跳扫描才触发，落入未知分支不结账）
          purged.push({ viewerId: vid, ws: vws });
        }
      }
      // 宽限期已过期但定时器尚未触发的兜底清理
      for (const [vid, e] of room.reconnecting) {
        if (now - e.at > this.reconnectTimeout + 5000) {
          clearTimeout(e.timer);
          room.reconnecting.delete(vid);
        }
      }
      if (room.viewers.size > 0 || room.reconnecting.size > 0) {
        // 槽位被占：正在共享或对方正在重连，拒绝第三个加入者
        return { ok: false, error: room.reconnecting.size > 0 ? "对方正在重新连接，请稍后再试" : "该会议已被对方加入，仅支持 1 对 1 共享", purged };
      }
      if (purged.length) {
        const vid = ++this.viewerSeq;
        room.viewers.set(vid, viewerWs);
        return { ok: true, viewerId: vid, purged };
      }
    }
    const viewerId = ++this.viewerSeq;
    room.viewers.set(viewerId, viewerWs);
    return { ok: true, viewerId };
  }

  /** host 同意加入：pending -> viewers。返回该 viewer 的 ws 或 null */
  acceptJoin(code, viewerId) {
    const room = this.rooms.get(code);
    if (!room) return null;
    const p = room.pending.get(viewerId);
    if (!p) return null;
    room.pending.delete(viewerId);
    room.viewers.set(viewerId, p.ws);
    return p.ws;
  }

  /** host 拒绝加入：移除 pending。返回被拒 viewer 的 ws 或 null */
  rejectJoin(code, viewerId) {
    const room = this.rooms.get(code);
    if (!room) return null;
    const p = room.pending.get(viewerId);
    if (!p) return null;
    room.pending.delete(viewerId);
    return p.ws;
  }

  /** 取消过期 pending 请求；返回被取消的 [{viewerId, ws}] */
  expirePending(code) {
    const room = this.rooms.get(code);
    if (!room || room.pending.size === 0) return [];
    const now = Date.now();
    const expired = [];
    for (const [vid, p] of room.pending) {
      if (now - p.at > PENDING_TIMEOUT) {
        room.pending.delete(vid);
        expired.push({ viewerId: vid, ws: p.ws });
      }
    }
    return expired;
  }

  /** 全部房间过期 pending 清理；返回 [{roomCode, viewerId, ws}] */
  expirePendingAll() {
    const all = [];
    for (const [code] of this.rooms) {
      for (const e of this.expirePending(code)) {
        all.push({ roomCode: code, viewerId: e.viewerId, ws: e.ws });
      }
    }
    return all;
  }

  /** 获取房间 */
  getRoom(code) {
    return this.rooms.get(code);
  }

  /** 房间内 host */
  getHost(code) {
    const room = this.rooms.get(code);
    return room ? room.host : null;
  }

  /**
   * 调用方是否为该房间的 host（按 userId 比对）。
   * 客户端有两条独立 WS：PresenceClient（发邀请）与 SignalWS（create 建房），
   * 按连接比对会永远失败，必须按建房的账号身份比对。
   */
  isHostOf(code, userId) {
    const room = this.rooms.get(code);
    return !!room && room.hostUserId === userId && room.host.readyState === 1;
  }

  /** 房间内指定 viewer */
  getViewer(code, viewerId) {
    const room = this.rooms.get(code);
    if (!room) return null;
    return room.viewers.get(viewerId) || null;
  }

  /** 房间内所有 viewer */
  getAllViewers(code) {
    const room = this.rooms.get(code);
    return room ? Array.from(room.viewers.values()) : [];
  }

  /** 房间内 viewer 数 */
  viewerCount(code) {
    const room = this.rooms.get(code);
    return room ? room.viewers.size : 0;
  }

  /**
   * 信令转发。host->viewer 指定 viewerId；viewer->host 转发给 host。
   * @returns 目标 ws 或 null
   */
  route(code, fromRole, viewerId) {
    if (fromRole === "host") {
      return this.getViewer(code, viewerId);
    }
    return this.getHost(code);
  }

  /**
   * viewer 重连恢复：reconnecting 宽限期内的同 userId viewer 重新绑定新连接。
   * @returns { ok:true, viewerId } 恢复成功（原 viewerId 保留）；null 表示无待恢复记录
   */
  resumeViewer(code, userId, viewerWs) {
    const room = this.rooms.get(code);
    if (!room) return null;
    // host 已断开（close 清理时序竞态，房间尚未销毁）时不可恢复：
    // 恢复成功后 host 侧连接已死，viewer 会卡在空房间白等
    if (room.host.readyState !== 1) {
      this.rooms.delete(code);
      return null;
    }
    for (const [vid, entry] of room.reconnecting) {
      // 未登录 viewer（entry.userId 为 null）也允许重连：
      // 1 对 1 场景同房间最多一条未登录记录，按 null 匹配即可，
      // 否则游客断线后宽限期内无法恢复，只能被当新加入者拒之门外
      if (entry.userId === userId || (!entry.userId && !userId)) {
        clearTimeout(entry.timer);
        room.reconnecting.delete(vid);
        room.viewers.set(vid, viewerWs);
        return { ok: true, viewerId: vid };
      }
    }
    return null;
  }

  /**
   * 成员断开。返回 { removedHost, roomClosed, peerLeft } 便于通知。
   * - pending 中的 viewer 断开：从 pending 移除，通知 host（join-cancelled）
   * - host 离开：整房销毁，通知所有剩余 viewer；同时清理未过期的重连定时器
   * - viewer 离开：进入 reconnecting 宽限期（不立即移除/结账/通知），返回 reconnecting=true
   */
  onDisconnect(code, role, viewerId, userId) {
    const room = this.rooms.get(code);
    if (!room) return { removedHost: false, roomClosed: false, peerLeftWs: null, pendingRemoved: null };
    if (role === "host") {
      for (const [, e] of room.reconnecting) clearTimeout(e.timer);
      room.reconnecting.clear();
      this.rooms.delete(code);
      // pending 中的请求者不在 remainingViewers 内，单独返回让上层通知 join-cancelled，
      // 否则请求者只能干等 30s 超时才知道 host 已离开
      const pendingWs = Array.from(room.pending.values()).filter((ws) => ws && ws.readyState === 1);
      return { removedHost: true, roomClosed: true, peerLeftWs: room.host, remainingViewers: Array.from(room.viewers.values()), pendingRemoved: null, pendingWs };
    }
    // viewer 断开：可能是 pending 中的请求者，也可能是已加入的 viewer
    if (room.pending.has(viewerId)) {
      room.pending.delete(viewerId);
      return { removedHost: false, roomClosed: false, peerLeftWs: room.host, pendingRemoved: viewerId };
    }
    if (room.viewers.has(viewerId)) {
      // 已加入 viewer 断开：进入重连宽限期，保留 viewerId 槽位，超时后才真正离开
      room.viewers.delete(viewerId);
      const entry = { userId: userId || null, at: Date.now(), timer: null };
      entry.timer = setTimeout(() => {
        const r = this.rooms.get(code);
        // 房间已销毁或已恢复/已清理：静默退出（host 断开时定时器已被清）
        if (!r || !r.reconnecting.has(viewerId)) return;
        r.reconnecting.delete(viewerId);
        this.onReconnectExpired(code, viewerId, entry.userId);
      }, this.reconnectTimeout);
      room.reconnecting.set(viewerId, entry);
      return { removedHost: false, roomClosed: false, peerLeftWs: null, pendingRemoved: null, reconnecting: true, viewerId, userId: entry.userId };
    }
    // 未知 viewer（如 join 被拒后断开）：无需处理
    return { removedHost: false, roomClosed: false, peerLeftWs: null, pendingRemoved: null };
  }
}

module.exports = RoomManager;
module.exports.PENDING_TIMEOUT = PENDING_TIMEOUT;
module.exports.RECONNECT_TIMEOUT = RECONNECT_TIMEOUT;