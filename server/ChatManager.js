"use strict";

/**
 * 好友聊天管理：消息收发、历史分页、未读计数、已读回执、存储治理。
 * 依赖：messages 表（db.js 建表），seq 为服务端自增、全局有序，客户端按 seq 增量同步。
 *
 * 投递策略：
 *  - 接收方在线：WS chat-message（完整正文）
 *  - 接收方离线：JPush 提醒（仅摘要，正文落库，上线后 REST 拉取）
 *  - 发送方自己：chat-ack 确认入库；另投 chat-message(mine) 供其他设备同步
 *
 * 幂等：相同 id 的消息重复发送，返回首次分配的 seq（客户端重发/弱网重试安全）。
 */

const { AccountError } = require("./AccountManager");

const MAX_TEXT = 500; // 单条消息字数上限（需求 R8）
const SEND_LIMIT = 60; // 每分钟发送条数上限
const SEND_WINDOW_MS = 60 * 1000;
const HISTORY_LIMIT = 50; // 单次历史拉取默认条数
const MAX_HISTORY_LIMIT = 100;
const KEEP_PER_CONV = 1000; // 每会话保留最近消息条数（需求 R9 存储治理）

function normText(text) {
  return String(text ?? "").replace(/\s+/g, " ").trim();
}

class ChatManager {
  constructor({ db, friends, presence, rateLimiter, sendToUser, notifyUser, briefUser }) {
    this.db = db;
    this.friends = friends;
    this.presence = presence;
    this.rateLimiter = rateLimiter;
    this.sendToUser = sendToUser;
    this.notifyUser = notifyUser;
    this.briefUser = briefUser || ((userId) => ({ userId }));
  }

  /**
   * 发送一条消息。from 为服务端认证身份（调用方传入），客户端无法伪造。
   * 返回 { ok, seq, id }；失败抛 AccountError，由调用方回 chat-rejected。
   */
  send({ id, from, to, text, ts }) {
    const content = normText(text);
    if (!content) throw new AccountError("empty_text", "消息内容不能为空", 400);
    if (content.length > MAX_TEXT) throw new AccountError("text_too_long", `单条消息最多 ${MAX_TEXT} 字`, 400);

    const clientMsgId = String(id || "");
    if (!clientMsgId) throw new AccountError("invalid_id", "缺少消息 id", 400);
    const peerId = String(to || "");
    if (!peerId || peerId === from) throw new AccountError("invalid_target", "消息接收方无效", 400);

    if (!this.friends.areFriends(from, peerId)) {
      throw new AccountError("not_friends", "仅好友之间可以聊天", 403);
    }

    // v1.414: 重发幂等——先按 id 回查，已存在则直接返回首次分配的 seq，
    // 不再重复投递/推送，也不消耗限流（弱网重连补发同一条消息是常态：
    // 重复 sendToUser 会让对端收到重复消息 + 离线时重复推送）
    const existing = this.db.prepare("SELECT seq FROM messages WHERE id = ?").get(clientMsgId);
    if (existing) {
      return { ok: true, seq: existing.seq, id: clientMsgId };
    }

    if (!this.rateLimiter.hit(`chat:send:${from}`, SEND_LIMIT, SEND_WINDOW_MS)) {
      throw new AccountError("too_many_messages", "发送过于频繁，请稍后再试", 429);
    }

    const createdAt = Number(ts) || Date.now();

    let seq;
    // INSERT OR IGNORE 幂等：相同 id 冲突时不插入。冲突时 lastInsertRowid 的取值
    // 依实现而变（可能返回旧值、0 或已消耗的候选 rowid），且 AUTOINCREMENT 序列会被
    // 冲突消耗导致 seq 跳号——seq 仅作同步游标，跳号不影响正确性。统一按 id 回查，
    // 不依赖 lastInsertRowid，保证重发一定返回该消息真实 seq。
    try {
      this.db
        .prepare(`INSERT OR IGNORE INTO messages (id, from_user, to_user, text, created_at) VALUES (?, ?, ?, ?, ?)`)
        .run(clientMsgId, from, peerId, content, createdAt);
      const row = this.db.prepare(`SELECT seq FROM messages WHERE id = ?`).get(clientMsgId);
      if (!row) throw new Error("insert missing");
      seq = row.seq;
    } catch (e) {
      console.error("[chat] 入库失败:", e?.message || e);
      throw new AccountError("db_error", "消息保存失败", 500);
    }

    const base = { seq, id: clientMsgId, from, to: peerId, text: content, ts: createdAt };

    // 接收方：在线走 WS 完整正文；离线走推送提醒（notifyUser 内部判断离线才推）
    this.sendToUser(peerId, { type: "chat-message", ...base });
    if (!this.presence.isOnline(peerId) && this.notifyUser) {
      // 推送预览含正文截断（IM 标准做法），长度受限
      this.notifyUser(peerId, { type: "chat", from: this.briefUser(from), text: content.slice(0, 60) });
    }
    // 发送方：ack 供本机把"发送中"置为"已发送"；chat-message(mine) 供其他在线设备同步
    this.sendToUser(from, { type: "chat-ack", id: clientMsgId, seq });
    this.sendToUser(from, { type: "chat-message", ...base, mine: true });

    this._prune(from, peerId);
    return { ok: true, seq, id: clientMsgId };
  }

  /**
   * 会话历史（双向分页）：
   *  - afterSeq：拉取 seq 之后的新消息（升序），用于进入页面/长连接恢复时增量同步
   *  - beforeSeq：拉取 seq 之前的旧消息（取出来逆序后升序返回），用于上滑翻页
   *  - 两者皆空：最新一屏
   * 返回 { messages: [升序], hasMore }
   */
  history(userId, peerId, { afterSeq, beforeSeq, limit } = {}) {
    if (!this.friends.areFriends(userId, peerId)) {
      throw new AccountError("not_friends", "仅好友之间可以聊天", 403);
    }
    const cap = Math.min(Math.max(Number(limit) || HISTORY_LIMIT, 1), MAX_HISTORY_LIMIT);
    const conv = `(from_user = ? AND to_user = ?) OR (from_user = ? AND to_user = ?)`;
    const baseParams = [userId, peerId, peerId, userId];
    let rows;

    if (afterSeq != null && afterSeq !== "") {
      rows = this.db
        .prepare(
          `SELECT seq, id, from_user, to_user, text, created_at, read_at FROM messages
           WHERE (${conv}) AND seq > ? ORDER BY seq ASC LIMIT ?`
        )
        .all(...baseParams, Number(afterSeq), cap);
    } else if (beforeSeq != null && beforeSeq !== "") {
      rows = this.db
        .prepare(
          `SELECT seq, id, from_user, to_user, text, created_at, read_at FROM messages
           WHERE (${conv}) AND seq < ? ORDER BY seq DESC LIMIT ?`
        )
        .all(...baseParams, Number(beforeSeq), cap);
      rows.reverse(); // 翻页结果是逆序，转回升序方便客户端前插
    } else {
      rows = this.db
        .prepare(
          `SELECT seq, id, from_user, to_user, text, created_at, read_at FROM messages
           WHERE (${conv}) ORDER BY seq DESC LIMIT ?`
        )
        .all(...baseParams, cap);
      rows.reverse();
    }

    const messages = rows.map((r) => ({
      seq: r.seq,
      id: r.id,
      from: r.from_user,
      to: r.to_user,
      text: r.text,
      ts: r.created_at,
      read: r.read_at != null,
    }));
    return { messages, hasMore: messages.length >= cap };
  }

  /** 未读数汇总：{ peerId: count }，供好友列表角标与聊天页外未读提醒 */
  unread(userId) {
    const rows = this.db
      .prepare(`SELECT from_user AS peer, COUNT(*) AS cnt FROM messages WHERE to_user = ? AND read_at IS NULL GROUP BY from_user`)
      .all(userId);
    const map = {};
    for (const r of rows) map[r.peer] = r.cnt;
    return { unread: map };
  }

  /** 标记某会话全部已读，并向对方发送已读回执 */
  markRead(userId, peerId) {
    if (!this.friends.areFriends(userId, peerId)) {
      throw new AccountError("not_friends", "仅好友之间可以聊天", 403);
    }
    const info = this.db
      .prepare(`UPDATE messages SET read_at = ? WHERE to_user = ? AND from_user = ? AND read_at IS NULL`)
      .run(Date.now(), userId, peerId);
    // 通知对方"你的消息已被读"，供其把气泡由"已送达"转为"已读"
    this.sendToUser(peerId, { type: "chat-read", from: userId, to: peerId });
    return { ok: true, count: info.changes };
  }

  /** 用户上线时推送各会话未读数，驱动好友列表角标（REST /chat/unread 的 WS 版本） */
  pushUnread(userId) {
    const { unread } = this.unread(userId);
    for (const peer of Object.keys(unread)) {
      this.sendToUser(userId, { type: "chat-unread", peerId: peer, count: unread[peer] });
    }
  }

  /** 存储治理：每会话仅保留最近 KEEP_PER_CONV 条，防止库无限膨胀 */
  _prune(a, b) {
    try {
      this.db
        .prepare(
          `DELETE FROM messages WHERE seq IN (
             SELECT seq FROM messages
             WHERE ((from_user = ? AND to_user = ?) OR (from_user = ? AND to_user = ?))
             ORDER BY seq DESC LIMIT -1 OFFSET ?
           )`
        )
        .run(a, b, b, a, KEEP_PER_CONV);
    } catch (e) {
      console.error("[chat] 存储治理失败:", e?.message || e);
    }
  }
}

module.exports = { ChatManager, MAX_TEXT, KEEP_PER_CONV };
