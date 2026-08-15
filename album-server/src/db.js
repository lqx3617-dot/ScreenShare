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
  return db;
}

function createSession(token, createdAt) {
  getDb()
    .prepare("INSERT INTO sessions(token, created_at) VALUES (?, ?)")
    .run(token, createdAt);
}

function loadSession(token) {
  const row = getDb().prepare("SELECT * FROM sessions WHERE token = ?").get(token);
  if (!row) return null;
  return {
    token: row.token,
    createdAt: row.created_at,
    total: row.total,
    done: !!row.done,
    received: new Set(JSON.parse(row.received || "[]")),
    originals: new Set(JSON.parse(row.originals || "[]")),
  };
}

function saveSession(s) {
  getDb()
    .prepare(
      "INSERT OR REPLACE INTO sessions(token, created_at, total, done, received, originals) VALUES (?, ?, ?, ?, ?, ?)"
    )
    .run(
      s.token,
      s.createdAt,
      s.total,
      s.done ? 1 : 0,
      JSON.stringify(Array.from(s.received)),
      JSON.stringify(Array.from(s.originals))
    );
}

function deleteSession(token) {
  getDb().prepare("DELETE FROM sessions WHERE token = ?").run(token);
}

/** 全部会话（按创建时间倒序），供聚合相册页汇总所有照片 */
function listAll() {
  return getDb()
    .prepare("SELECT * FROM sessions WHERE total > 0 ORDER BY created_at DESC")
    .all()
    .map((r) => ({
      token: r.token,
      createdAt: r.created_at,
      total: r.total,
      done: !!r.done,
      received: JSON.parse(r.received || "[]"),
      originals: JSON.parse(r.originals || "[]"),
    }));
}

function listExpired(now, ttlMs) {
  return getDb()
    .prepare("SELECT token FROM sessions WHERE created_at < ?")
    .all(now - ttlMs)
    .map((r) => r.token);
}

module.exports = { createSession, loadSession, saveSession, deleteSession, listAll, listExpired };
