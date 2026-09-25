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

CREATE TABLE IF NOT EXISTS couples (
  id          TEXT NOT NULL,
  user_a      TEXT NOT NULL,
  user_b      TEXT NOT NULL,
  bound_at    INTEGER NOT NULL,
  anniversary TEXT,
  status      TEXT NOT NULL DEFAULT 'active',
  PRIMARY KEY (id, user_a)
);
CREATE INDEX IF NOT EXISTS idx_couples_user ON couples(user_a, status);
CREATE INDEX IF NOT EXISTS idx_couples_user_b ON couples(user_b, status);
-- 每人唯一绑定：DB 层兜底（仅对生效关系，解绑后可重新绑定）
CREATE UNIQUE INDEX IF NOT EXISTS uq_couples_active_a ON couples(user_a) WHERE status = 'active';
CREATE UNIQUE INDEX IF NOT EXISTS uq_couples_active_b ON couples(user_b) WHERE status = 'active';

CREATE TABLE IF NOT EXISTS couple_invitations (
  id         TEXT PRIMARY KEY,
  from_user  TEXT NOT NULL,
  to_user    TEXT NOT NULL,
  status     TEXT NOT NULL,
  created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_couple_inv_to ON couple_invitations(to_user, status);
CREATE INDEX IF NOT EXISTS idx_couple_inv_pair ON couple_invitations(from_user, to_user, status);

-- 每日打卡：每对情侣每人每天一条；解绑后保留历史，新绑定在新 couple_id 下重新开始
CREATE TABLE IF NOT EXISTS couple_checkins (
  id         TEXT PRIMARY KEY,
  couple_id  TEXT NOT NULL,
  user_id    TEXT NOT NULL,
  date       TEXT NOT NULL,
  created_at INTEGER NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_couple_checkin ON couple_checkins(couple_id, user_id, date);
CREATE INDEX IF NOT EXISTS idx_couple_checkin_user ON couple_checkins(user_id, date);

CREATE TABLE IF NOT EXISTS couple_photos (
  id          TEXT PRIMARY KEY,
  couple_id   TEXT NOT NULL,
  uploader    TEXT NOT NULL,
  media_type  TEXT NOT NULL,
  url         TEXT NOT NULL,
  thumb_url   TEXT,
  duration_ms INTEGER,
  created_at  INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_couple_photos_couple ON couple_photos(couple_id, created_at);

CREATE TABLE IF NOT EXISTS couple_locations (
  id          TEXT PRIMARY KEY,
  couple_id   TEXT NOT NULL,
  reporter    TEXT NOT NULL,
  lat         REAL NOT NULL,
  lng         REAL NOT NULL,
  reported_at INTEGER NOT NULL
);

-- 共同愿望清单：双方共享，任一方可勾选/删除，完成记录操作人
CREATE TABLE IF NOT EXISTS couple_wishes (
  id         TEXT PRIMARY KEY,
  couple_id  TEXT NOT NULL,
  user_id    TEXT NOT NULL,
  text       TEXT NOT NULL,
  done       INTEGER NOT NULL DEFAULT 0,
  done_by    TEXT,
  done_at    INTEGER,
  created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_couple_wishes ON couple_wishes(couple_id, created_at);
CREATE INDEX IF NOT EXISTS idx_couple_locations_couple ON couple_locations(couple_id, reporter, reported_at);
`;

/**
 * 历史库迁移：早期以邮箱为登录标识（email NOT NULL UNIQUE），改为昵称登录后需重建表。
 * 迁移保留旧账号与密码哈希，昵称重复时追加好友码前缀去重。
 */
function migrateToNicknameLogin(db) {
  const cols = db.prepare("PRAGMA table_info(users)").all();
  const emailCol = cols.find((c) => c.name === "email");
  if (!emailCol || emailCol.notnull === 0) return false;
  // 旧表可能已有 push_token（v1.313 之后的库又落到迁移前的表结构），
  // users_new 须同步带上该列并拷贝原值，否则迁移后 migrateUsersPushToken
  // 只会补一个空列，老用户的离线推送 token 被静默丢弃
  const hasPushToken = cols.some((c) => c.name === "push_token");
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
        ${hasPushToken ? "push_token    TEXT," : ""}
        created_at    INTEGER NOT NULL
      );
    `);
    const rows = db.prepare("SELECT * FROM users").all();
    const usedNicknames = new Set();
    const insert = db.prepare(
      `INSERT OR ROLLBACK INTO users_new (id, email, password_hash, salt, nickname, avatar, friend_code${hasPushToken ? ", push_token" : ""}, created_at)
       VALUES (?, ?, ?, ?, ?, ?, ?${hasPushToken ? ", ?" : ""}, ?)`
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
      if (hasPushToken) {
        insert.run(r.id, r.email || null, r.password_hash, r.salt, unique, r.avatar || "0", r.friend_code, r.push_token || null, r.created_at);
      } else {
        insert.run(r.id, r.email || null, r.password_hash, r.salt, unique, r.avatar || "0", r.friend_code, r.created_at);
      }
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

/**
 * 历史库迁移：couples 表补 anniversary 列（在一起纪念日，YYYY-MM-DD，可空）。
 */
function migrateCouplesAnniversary(db) {
  const cols = db.prepare("PRAGMA table_info(couples)").all();
  if (cols.some((c) => c.name === "anniversary")) return false;
  db.exec(`ALTER TABLE couples ADD COLUMN anniversary TEXT`);
  return true;
}

/**
 * 历史库迁移：couples 表补 last_memory_push 列（「一年前的今天」推送去重，
 * 存最近一次推送的上海日期 YYYY-MM-DD，同日不重复推）。
 */
function migrateCouplesMemoryPush(db) {
  const cols = db.prepare("PRAGMA table_info(couples)").all();
  if (cols.some((c) => c.name === "last_memory_push")) return false;
  db.exec(`ALTER TABLE couples ADD COLUMN last_memory_push TEXT`);
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
    migrateCouplesAnniversary(db);
    migrateCouplesMemoryPush(db);
  }
  return db;
}

module.exports = {
  openDb,
  initSchema,
  migrateToNicknameLogin,
  migrateFriendsRemark,
  migrateUsersPushToken,
  migrateCouplesAnniversary,
  migrateCouplesMemoryPush,
  DEFAULT_DB_PATH,
};
