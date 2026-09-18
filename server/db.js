"use strict";

/**
 * 账号库 SQLite 层（node:sqlite，Node 22+ 内置，无第三方依赖）。
 * 表：users / sessions / friends / friend_requests / verification_codes
 * 单文件默认 server/data/account.db，可用 ACCOUNT_DB 环境变量覆盖（测试用 :memory:）。
 */

const fs = require("fs");
const path = require("path");
const { DatabaseSync } = require("node:sqlite");

const DEFAULT_DB_PATH = process.env.ACCOUNT_DB || path.join(__dirname, "data", "account.db");

const SCHEMA = `
CREATE TABLE IF NOT EXISTS users (
  id            TEXT PRIMARY KEY,
  email         TEXT NOT NULL UNIQUE,
  password_hash TEXT NOT NULL,
  salt          TEXT NOT NULL,
  nickname      TEXT NOT NULL,
  avatar        TEXT NOT NULL DEFAULT '0',
  friend_code   TEXT NOT NULL UNIQUE,
  created_at    INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS sessions (
  token_hash  TEXT PRIMARY KEY,
  user_id     TEXT NOT NULL,
  device_info TEXT NOT NULL DEFAULT '',
  created_at  INTEGER NOT NULL,
  last_seen   INTEGER NOT NULL,
  expires_at  INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_sessions_user ON sessions(user_id);

CREATE TABLE IF NOT EXISTS friends (
  user_id    TEXT NOT NULL,
  friend_id  TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  PRIMARY KEY (user_id, friend_id)
);
CREATE INDEX IF NOT EXISTS idx_friends_user ON friends(user_id);

CREATE TABLE IF NOT EXISTS friend_requests (
  id         TEXT PRIMARY KEY,
  from_user  TEXT NOT NULL,
  to_user    TEXT NOT NULL,
  status     TEXT NOT NULL,
  created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_friend_requests_to ON friend_requests(to_user, status);
CREATE INDEX IF NOT EXISTS idx_friend_requests_pair ON friend_requests(from_user, to_user);

CREATE TABLE IF NOT EXISTS verification_codes (
  email      TEXT NOT NULL,
  purpose    TEXT NOT NULL,
  code       TEXT NOT NULL,
  expires_at INTEGER NOT NULL,
  attempts   INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (email, purpose)
);
`;

function initSchema(db) {
  db.exec(SCHEMA);
}

function openDb(filePath = DEFAULT_DB_PATH) {
  if (filePath !== ":memory:") {
    fs.mkdirSync(path.dirname(filePath), { recursive: true });
  }
  const db = new DatabaseSync(filePath);
  db.exec("PRAGMA journal_mode = WAL");
  db.exec("PRAGMA foreign_keys = ON");
  initSchema(db);
  return db;
}

module.exports = { openDb, initSchema, DEFAULT_DB_PATH };
