"use strict";
/**
 * SQLite 会话存储（node:sqlite 内置模块，零原生依赖）。
 * 会话元数据：received/originals 以 JSON 文本列存储；pending（网页点大图的按需队列）为内存态。
 */
const { DatabaseSync } = require("node:sqlite");
const fs = require("fs");
const path = require("path");

const DATA_DIR = path.join(__dirname, "..", "data");
const DB_PATH = path.join(DATA_DIR, "albums.db");

let db = null;

/** 安全解析 JSON 数组列：损坏行降级为空集合，绝不因单条坏数据拖垮整个服务 */
function parseJsonArray(json) {
  try {
    const arr = JSON.parse(json || "[]");
    return Array.isArray(arr) ? arr : [];
  } catch (e) {
    return [];
  }
}

function getDb() {
  if (db) return db;
  fs.mkdirSync(DATA_DIR, { recursive: true });
  db = new DatabaseSync(DB_PATH);
  db.exec(`
    CREATE TABLE IF NOT EXISTS sessions (
      token      TEXT PRIMARY KEY,
      created_at INTEGER NOT NULL,
      total      INTEGER NOT NULL DEFAULT 0,
      done       INTEGER NOT NULL DEFAULT 0,
      received   TEXT NOT NULL DEFAULT '[]',
      originals  TEXT NOT NULL DEFAULT '[]'
    )
  `);
  // V2 迁移：远程相册同步按设备分组，新增 device 列（历史会话默认空=未分组）
  const cols = db.prepare("PRAGMA table_info(sessions)").all().map((c) => c.name);
  if (!cols.includes("device")) {
    db.exec("ALTER TABLE sessions ADD COLUMN device TEXT DEFAULT ''");
  }
  // V3 迁移：视频上传，新增 videos 列（哪些 index 是视频，缩略图统一存 pad.jpg）
  if (!cols.includes("videos")) {
    db.exec("ALTER TABLE sessions ADD COLUMN videos TEXT DEFAULT '[]'");
  }
  return db;
}

function createSession(token, createdAt, device) {
  getDb()
    .prepare("INSERT INTO sessions(token, created_at, device) VALUES (?, ?, ?)")
    .run(token, createdAt, device || "");
}

function loadSession(token) {
  const row = getDb().prepare("SELECT * FROM sessions WHERE token = ?").get(token);
  if (!row) return null;
  return {
    token: row.token,
    createdAt: row.created_at,
    device: row.device || "",
    total: row.total,
    done: !!row.done,
    received: new Set(parseJsonArray(row.received)),
    originals: new Set(parseJsonArray(row.originals)),
    videos: new Set(parseJsonArray(row.videos)),
  };
}

function saveSession(s) {
  // created_at 语义为「最后活跃时间」：每次保存（上传/查看/删除等会话活动）都刷新，
  // 避免会话创建后仅因固定 24h 到期就被整体清理，导致活跃使用中的照片被误删。
  s.createdAt = Date.now();
  getDb()
    .prepare(
      "INSERT OR REPLACE INTO sessions(token, created_at, total, done, received, originals, videos, device) VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
    )
    .run(
      s.token,
      s.createdAt,
      s.total,
      s.done ? 1 : 0,
      JSON.stringify(Array.from(s.received)),
      JSON.stringify(Array.from(s.originals)),
      JSON.stringify(Array.from(s.videos || [])),
      s.device || ""
    );
}

function deleteSession(token) {
  getDb().prepare("DELETE FROM sessions WHERE token = ?").run(token);
}

/**
 * 原子标记方法：取代 load → 内存改 → save 的 read-modify-write 模式。
 * 原模式下多个请求并发上传同一会话时，后写的快照会覆盖先写的索引，
 * 导致「上传成功却不在相册里」。以下方法用单条 SQL 原子完成，
 * json_group_array 重建时顺带去重（同 index 重传不重复入库）。
 */
function markPhoto(token, index) {
  getDb()
    .prepare(
      `UPDATE sessions SET
         received = json_insert((SELECT json_group_array(value) FROM json_each(received) WHERE value <> ?), '$[#]', ?),
         total = MAX(total, ?)
       WHERE token = ?`
    )
    .run(index, index, index, token);
}

function markVideo(token, index) {
  getDb()
    .prepare(
      `UPDATE sessions SET
         received = json_insert((SELECT json_group_array(value) FROM json_each(received) WHERE value <> ?), '$[#]', ?),
         videos  = json_insert((SELECT json_group_array(value) FROM json_each(videos)  WHERE value <> ?), '$[#]', ?),
         total = MAX(total, ?)
       WHERE token = ?`
    )
    .run(index, index, index, index, index, token);
}

function markOriginal(token, index) {
  getDb()
    .prepare(
      `UPDATE sessions SET
         originals = json_insert((SELECT json_group_array(value) FROM json_each(originals) WHERE value <> ?), '$[#]', ?)
       WHERE token = ?`
    )
    .run(index, index, token);
}

/** 从 received/originals/videos 移除序号。查询与更新同步执行、中间无 await，整段原子。 */
function unmarkMedia(token, index) {
  const db = getDb();
  const row = db.prepare("SELECT received, originals, videos FROM sessions WHERE token = ?").get(token);
  if (!row) return null;
  const recv = parseJsonArray(row.received).filter((i) => i !== index);
  const orig = parseJsonArray(row.originals).filter((i) => i !== index);
  const vid = parseJsonArray(row.videos).filter((i) => i !== index);
  db.prepare("UPDATE sessions SET received = ?, originals = ?, videos = ? WHERE token = ?")
    .run(JSON.stringify(recv), JSON.stringify(orig), JSON.stringify(vid), token);
  return { receivedCount: recv.length };
}

/** 全部会话（按创建时间倒序），供聚合相册页汇总所有照片 */
function listAll() {
  return getDb()
    .prepare("SELECT * FROM sessions WHERE total > 0 ORDER BY created_at DESC")
    .all()
    .map((r) => ({
      token: r.token,
      createdAt: r.created_at,
      device: r.device || "",
      total: r.total,
      done: !!r.done,
      received: parseJsonArray(r.received),
      originals: parseJsonArray(r.originals),
      videos: parseJsonArray(r.videos),
    }));
}

/** 有照片的设备列表（按媒体数倒序），供观看方按设备查看；照片数 = 非视频项数 */
function listDevices() {
  return getDb()
    .prepare(
      "SELECT device, COUNT(*) AS sessions, SUM(total) AS total, GROUP_CONCAT(token) AS tokens FROM sessions WHERE total > 0 AND device != '' GROUP BY device"
    )
    .all()
    .map((r) => {
      // 精确统计每个设备收到的非视频媒体数
      let photos = 0;
      const tokens = (r.tokens || "").split(",").filter(Boolean);
      for (const t of tokens) {
        const s = loadSession(t);
        if (!s) continue;
        for (const idx of s.received) {
          if (!s.videos.has(idx)) photos++;
        }
      }
      return {
        device: (r.device || "").replace(/\s+/g, ""),
        sessions: r.sessions,
        photos,
      };
    })
    .sort((a, b) => b.photos - a.photos);
}

function listExpired(now, ttlMs) {
  return getDb()
    .prepare("SELECT token FROM sessions WHERE created_at < ?")
    .all(now - ttlMs)
    .map((r) => r.token);
}

module.exports = { createSession, loadSession, saveSession, deleteSession, listAll, listDevices, listExpired, markPhoto, markVideo, markOriginal, unmarkMedia };
