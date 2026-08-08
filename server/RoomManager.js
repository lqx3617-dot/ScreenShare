/**
 * RoomManager.js —— V4：多客户端房间管理
 *
 * 房结构：1 host + N viewers（从 1对1 升级为 1对多）。
 *   room = { host: ws, viewers: Map<viewerId, ws> }
 *
 * 路由规则：
 * - host 的 relay 广播给指定 viewer
 * - viewer 的 relay 转发给 host
 * - 任一 viewer 离开：从 viewers 移除，通知 host；host 离开：整房销毁，通知所有 viewer
 */
"use strict";

class RoomManager {
  constructor() {
    /** code(lowercase) -> room */
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
    this.rooms.set(code, { host: hostWs, viewers: new Map() });
    return "";
  }

  /**
   * viewer 加入房间。返回 { ok:true, viewerId } 或 { ok:false, error }。
   * 情侣模式：每房间最多 1 个观看方，已有 viewer 时拒绝后续加入。
   */
  join(code, viewerWs) {
    const room = this.rooms.get(code);
    if (!room) return { ok: false, error: "会议号不存在或会议已结束" };
    if (room.viewers.size > 0) {
      return { ok: false, error: "该会议已被对方加入，仅支持 1 对 1 共享" };
    }
    const viewerId = ++this.viewerSeq;
    room.viewers.set(viewerId, viewerWs);
    return { ok: true, viewerId };
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
   * 若 host 离开：整房销毁，通知所有剩余 viewer。
   * 若 viewer 离开：仅移除该 viewer，通知 host。
   */
  onDisconnect(code, role, viewerId) {
    const room = this.rooms.get(code);
    if (!room) return { removedHost: false, roomClosed: false, peerLeftWs: null };
    if (role === "host") {
      this.rooms.delete(code);
      return { removedHost: true, roomClosed: true, peerLeftWs: room.host, remainingViewers: Array.from(room.viewers.values()) };
    }
    // viewer 离开
    room.viewers.delete(viewerId);
    if (room.viewers.size === 0) {
      // 无 viewer 时是否销毁？保留 host 房间等待新 viewer 加入，故不销毁
    }
    return { removedHost: false, roomClosed: false, peerLeftWs: room.host };
  }
}

module.exports = RoomManager;