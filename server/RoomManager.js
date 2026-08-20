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

class RoomManager {
  constructor() {
    /** code -> room */
    this.rooms = new Map();
    /** 自增 viewerId，区分同一 host 下的多个 viewer */
    this.viewerSeq = 0;
  }

  /** 校验会议号格式（4 位数字） */
  isValidCode(code) {
    return /^[0-9]{4}$/.test(code);
  }

  /** host 创建房间。返回空串表示成功，否则返回错误文案 */
  create(code, hostWs) {
    if (this.rooms.has(code)) return "会议号已被占用，请重试";
    this.rooms.set(code, { host: hostWs, viewers: new Map(), pending: new Map() });
    return "";
  }

  /**
   * viewer 请求加入房间（进入 pending，等待 host 确认）。
   * 返回 { ok:true, viewerId } 或 { ok:false, error }。
   * 情侣模式：已有 viewer 或已有 pending 请求时拒绝后续加入。
   */
  requestJoin(code, viewerWs) {
    const room = this.rooms.get(code);
    if (!room) return { ok: false, error: "会议号不存在或会议已结束" };
    if (room.viewers.size > 0) {
      return { ok: false, error: "该会议已被对方加入，仅支持 1 对 1 共享" };
    }
    if (room.pending.size > 0) {
      return { ok: false, error: "已有加入请求等待确认，请稍后再试" };
    }
    const viewerId = ++this.viewerSeq;
    room.pending.set(viewerId, { ws: viewerWs, at: Date.now() });
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
   * 成员断开。返回 { removedHost, roomClosed, peerLeft } 便于通知。
   * - pending 中的 viewer 断开：从 pending 移除，通知 host（join-cancelled）
   * - host 离开：整房销毁，通知所有剩余 viewer
   * - viewer 离开：仅移除该 viewer，通知 host
   */
  onDisconnect(code, role, viewerId) {
    const room = this.rooms.get(code);
    if (!room) return { removedHost: false, roomClosed: false, peerLeftWs: null, pendingRemoved: null };
    if (role === "host") {
      this.rooms.delete(code);
      return { removedHost: true, roomClosed: true, peerLeftWs: room.host, remainingViewers: Array.from(room.viewers.values()), pendingRemoved: null };
    }
    // viewer 断开：可能是 pending 中的请求者，也可能是已加入的 viewer
    if (room.pending.has(viewerId)) {
      room.pending.delete(viewerId);
      return { removedHost: false, roomClosed: false, peerLeftWs: room.host, pendingRemoved: viewerId };
    }
    room.viewers.delete(viewerId);
    return { removedHost: false, roomClosed: false, peerLeftWs: room.host, pendingRemoved: null };
  }
}

module.exports = RoomManager;
module.exports.PENDING_TIMEOUT = PENDING_TIMEOUT;