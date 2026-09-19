"use strict";

/**
 * 共享会话记录：好友邀请被接受时开账，任意一方断开时结账。
 * 只记录邀请路径的会话（双方都是账号用户），4 位口令随机加入不记。
 * 服务重启时未闭合的会话按启动时刻强制结账，避免永久挂起。
 */

const crypto = require("crypto");

const SCHEMA = `
CREATE TABLE IF NOT EXISTS share_sessions (
  id          TEXT PRIMARY KEY,
  room_code   TEXT NOT NULL,
  host_user   TEXT NOT NULL,
  viewer_user TEXT NOT NULL,
  started_at  INTEGER NOT NULL,
  ended_at    INTEGER
);
CREATE INDEX IF NOT EXISTS idx_shares_host ON share_sessions(host_user, started_at);
CREATE INDEX IF NOT EXISTS idx_shares_viewer ON share_sessions(viewer_user, started_at);
`;

class ShareHistory {
  constructor(db) {
    this.db = db;
    db.exec(SCHEMA);
    // 重启前未闭合的会话：以启动时刻结账（进程已死，无法知道真实结束时间）
    this.db.prepare(`UPDATE share_sessions SET ended_at = started_at WHERE ended_at IS NULL`).run();
    // room+viewer -> 进行中的会话 id
    this.open = new Map();
  }

  /** 邀请被接受：开账。同一 room+viewer 已有进行中会话则复用，防止重复记录 */
  onStart(roomCode, hostUserId, viewerUserId) {
    const key = `${roomCode}|${viewerUserId}`;
    const existing = this.open.get(key);
    if (existing) return existing;
    const id = crypto.randomUUID();
    const now = Date.now();
    this.db
      .prepare(
        `INSERT INTO share_sessions (id, room_code, host_user, viewer_user, started_at) VALUES (?, ?, ?, ?, ?)`
      )
      .run(id, roomCode, hostUserId, viewerUserId, now);
    this.open.set(key, id);
    return id;
  }

  /** 观看方断开：结账 */
  onViewerLeft(roomCode, viewerUserId) {
    const key = `${roomCode}|${viewerUserId}`;
    const id = this.open.get(key);
    if (!id) return false;
    this.db.prepare(`UPDATE share_sessions SET ended_at = ? WHERE id = ?`).run(Date.now(), id);
    this.open.delete(key);
    return true;
  }

  /** 共享方断开：该房间所有进行中会话结账 */
  onHostLeft(roomCode) {
    let n = 0;
    for (const key of [...this.open.keys()]) {
      // key 形如 roomCode|viewerUserId，按分隔符精确取 roomCode 比对，
      // 避免 "1234|" 误匹配 "12340|xxx" 之类的前缀碰撞
      if (key.split("|", 1)[0] === roomCode) {
        const id = this.open.get(key);
        this.db.prepare(`UPDATE share_sessions SET ended_at = ? WHERE id = ?`).run(Date.now(), id);
        this.open.delete(key);
        n++;
      }
    }
    return n;
  }

  /** 我的最近共享（作为 host 或 viewer），含对方资料与时长 */
  recent(userId, limit = 20) {
    const rows = this.db
      .prepare(
        `SELECT s.id, s.room_code, s.host_user, s.viewer_user, s.started_at, s.ended_at,
                (SELECT nickname FROM users WHERE id = CASE WHEN s.host_user = ? THEN s.viewer_user ELSE s.host_user END) AS peer_nickname,
                (SELECT avatar FROM users WHERE id = CASE WHEN s.host_user = ? THEN s.viewer_user ELSE s.host_user END) AS peer_avatar
         FROM share_sessions s
         WHERE s.host_user = ? OR s.viewer_user = ?
         ORDER BY s.started_at DESC, s.rowid DESC
         LIMIT ?`
      )
      .all(userId, userId, userId, userId, limit);
    return rows.map((r) => ({
      sessionId: r.id,
      roomCode: r.room_code,
      peerId: r.host_user === userId ? r.viewer_user : r.host_user,
      peerNickname: r.peer_nickname || "",
      peerAvatar: r.peer_avatar || "0",
      // 我是共享方还是观看方
      role: r.host_user === userId ? "host" : "viewer",
      startedAt: r.started_at,
      endedAt: r.ended_at,
      durationMs: r.ended_at != null ? r.ended_at - r.started_at : null,
    }));
  }
}

module.exports = { ShareHistory };
