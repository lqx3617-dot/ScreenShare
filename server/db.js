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
  email         TEXT,
  password_hash TEXT NOT NULL,
  salt          TEXT NOT NULL,
  nickname      TEXT NOT NULL UNIQUE,
  avatar        TEXT NOT NULL DEFAULT '0',
  friend_code   TEXT NOT NULL UNIQUE,
  push_token    TEXT,
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
  remark     TEXT NOT NULL DEFAULT '',
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
`;

/**
 * 历史库迁移：早期以邮箱为登录标识（email NOT NULL UNIQUE），改为昵称登录后需重建表。
 * 迁移保留旧账号与密码哈希，昵称重复时追加好友码前缀去重。
 */
function migrateToNicknameLogin(db) {
  const cols = db.prepare("PRAGMA table_info(users)").all();
  const emailCol = cols.find((c) => c.name === "email");
  if (!emailCol || emailCol.notnull === 0) return false;
  db.exec("BEGIN");
  try {
    db.exec(`
      CREATE TABLE users_new (
        id            TEXT PRIMARY KEY,
        email         TEXT,
        password_hash TEXT NOT NULL,
        salt          TEXT NOT NULL,
        nickname      TEXT NOT NULL UNIQUE,
        avatar        TEXT NOT NULL DEFAULT '0',
        friend_code   TEXT NOT NULL UNIQUE,
        created_at    INTEGER NOT NULL
      );
    `);
    const rows = db.prepare("SELECT * FROM users").all();
    const usedNicknames = new Set();
    const insert = db.prepare(
      `INSERT OR ROLLBACK INTO users_new (id, email, password_hash, salt, nickname, avatar, friend_code, created_at)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?)`
    );
    for (const r of rows) {
      let nick = String(r.nickname || r.email || "").split("@")[0] || `用户${String(r.friend_code || "").slice(0, 4)}`;
      if (!nick) nick = `用户${String(r.friend_code || "").slice(0, 4)}`;
      let unique = nick;
      let suffix = 1;
      while (usedNicknames.has(unique)) {
        unique = `${nick}${String(r.friend_code || "").slice(0, 2)}${suffix++}`;
      }
      usedNicknames.add(unique);
      insert.run(r.id, r.email || null, r.password_hash, r.salt, unique, r.avatar || "0", r.friend_code, r.created_at);
    }
    db.exec("DROP TABLE users");
    db.exec("ALTER TABLE users_new RENAME TO users");
    db.exec("DROP TABLE IF EXISTS verification_codes");
    db.exec("COMMIT");
    return true;
  } catch (e) {
    db.exec("ROLLBACK");
    throw e;
  }
}

/**
 * 历史库迁移：users 表补 push_token 列（v1.313 离线推送）。
 */
function migrateUsersPushToken(db) {
  const cols = db.prepare("PRAGMA table_info(users)").all();
  if (cols.some((c) => c.name === "push_token")) return false;
  db.exec(`ALTER TABLE users ADD COLUMN push_token TEXT`);
  return true;
}

function initSchema(db) {
  db.exec(SCHEMA);
}

/**
 * 历史库迁移：friends 表补 remark 列（v1.312 备注名）。
 * SQLite 的 ALTER TABLE ADD COLUMN 支持 NOT NULL DEFAULT，已有行自动填空串。
 */
function migrateFriendsRemark(db) {
  const cols = db.prepare("PRAGMA table_info(friends)").all();
  if (cols.some((c) => c.name === "remark")) return false;
  db.exec(`ALTER TABLE friends ADD COLUMN remark TEXT NOT NULL DEFAULT ''`);
  return true;
}

function openDb(filePath = DEFAULT_DB_PATH) {
  if (filePath !== ":memory:") {
    fs.mkdirSync(path.dirname(filePath), { recursive: true });
  }
  const db = new DatabaseSync(filePath);
  db.exec("PRAGMA journal_mode = WAL");
  db.exec("PRAGMA foreign_keys = ON");
  initSchema(db);
  if (filePath !== ":memory:") {
    migrateToNicknameLogin(db);
    migrateFriendsRemark(db);
    migrateUsersPushToken(db);
  }
  return db;
}

module.exports = {
  openDb,
  initSchema,
  migrateToNicknameLogin,
  migrateFriendsRemark,
  migrateUsersPushToken,
  DEFAULT_DB_PATH,
};
