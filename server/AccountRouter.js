"use strict";

/**
 * 账号/好友 REST 路由。
 * 挂载方式：server.js 的 http 回调开头调用 handle(req, res)，返回 true 表示已接管。
 * 鉴权：Authorization: Bearer <token>。
 */

const { AccountError } = require("./AccountManager");

const API_WINDOW_MS = 60 * 1000;
const API_MAX_REQUESTS = 60;
const MAX_BODY = 64 * 1024;

function remoteIp(req) {
  if (process.env.TRUST_PROXY === "1") {
    const xff = String(req.headers["x-forwarded-for"] || "");
    const first = xff.split(",")[0].trim();
    if (first) return first.replace(/^::ffff:/, "");
  }
  const sock = req.socket || req.connection;
  return String((sock && sock.remoteAddress) || "").replace(/^::ffff:/, "") || "unknown";
}

function sendJson(res, status, obj) {
  const body = JSON.stringify(obj);
  res.writeHead(status, { "Content-Type": "application/json; charset=utf-8", "Cache-Control": "no-store" });
  res.end(body);
}

function readJson(req, limit = MAX_BODY) {
  return new Promise((resolve, reject) => {
    let size = 0;
    const chunks = [];
    req.on("data", (c) => {
      size += c.length;
      if (size > limit) {
        reject(new AccountError("payload_too_large", "请求体过大", 413));
        req.destroy();
        return;
      }
      chunks.push(c);
    });
    req.on("end", () => {
      const raw = Buffer.concat(chunks).toString("utf8");
      if (!raw) return resolve({});
      try {
        const parsed = JSON.parse(raw);
        if (parsed === null || typeof parsed !== "object" || Array.isArray(parsed)) {
          reject(new AccountError("invalid_json", "请求格式错误", 400));
          return;
        }
        resolve(parsed);
      } catch (e) {
        reject(new AccountError("invalid_json", "请求格式错误", 400));
      }
    });
    req.on("error", reject);
  });
}

class AccountRouter {
  constructor({ accountManager, friendManager, rateLimiter, presence, notifyUser }) {
    this.accounts = accountManager;
    this.friends = friendManager;
    this.rateLimiter = rateLimiter;
    this.presence = presence;
    this.notifyUser = notifyUser;
    this.routes = [
      {
        method: "POST",
        pattern: /^\/account\/register$/,
        auth: false,
        handler: (req, body) =>
          this.accounts.register(body.nickname, body.password, req.headers["user-agent"] || ""),
      },
      {
        method: "POST",
        pattern: /^\/account\/login$/,
        auth: false,
        handler: (req, body) =>
          this.accounts.login(body.nickname, body.password, req.headers["user-agent"] || ""),
      },
      {
        method: "POST",
        pattern: /^\/account\/logout$/,
        auth: true,
        handler: (req, body, ctx) => this.accounts.logout(ctx.token),
      },
      {
        method: "GET",
        pattern: /^\/account\/me$/,
        auth: true,
        handler: (req, body, ctx) => this.accounts.getProfile(ctx.userId),
      },
      {
        method: "PATCH",
        pattern: /^\/account\/profile$/,
        auth: true,
        handler: (req, body, ctx) =>
          this.accounts.updateProfile(ctx.userId, { nickname: body.nickname, avatar: body.avatar }),
      },
      {
        method: "GET",
        pattern: /^\/friends$/,
        auth: true,
        handler: (req, body, ctx) => this._withPresence(this.friends.list(ctx.userId)),
      },
      {
        method: "GET",
        pattern: /^\/friends\/requests$/,
        auth: true,
        handler: (req, body, ctx) => this.friends.pendingRequests(ctx.userId),
      },
      {
        method: "POST",
        pattern: /^\/friends\/request$/,
        auth: true,
        handler: (req, body, ctx) => {
          const r = this.friends.request(ctx.userId, body.friendCode);
          const me = this.accounts.getProfile(ctx.userId);
          if (this.notifyUser && me) {
            const brief = { userId: me.userId, nickname: me.nickname, avatar: me.avatar };
            if (r.accepted) {
              this.notifyUser(r.friend.userId, { type: "friend-accepted", friend: brief, requestId: r.requestId });
            } else {
              this.notifyUser(r.friend.userId, { type: "friend-request", requestId: r.requestId, from: brief });
            }
          }
          return r;
        },
      },
      {
        method: "POST",
        pattern: /^\/friends\/accept$/,
        auth: true,
        handler: (req, body, ctx) => {
          const r = this.friends.accept(ctx.userId, body.requestId);
          const me = this.accounts.getProfile(ctx.userId);
          if (this.notifyUser && me) {
            this.notifyUser(r.friend.userId, {
              type: "friend-accepted",
              friend: { userId: me.userId, nickname: me.nickname, avatar: me.avatar },
              requestId: body.requestId,
            });
          }
          return r;
        },
      },
      {
        method: "POST",
        pattern: /^\/friends\/reject$/,
        auth: true,
        handler: (req, body, ctx) => this.friends.reject(ctx.userId, body.requestId),
      },
      {
        method: "DELETE",
        pattern: /^\/friends\/([A-Za-z0-9-]+)$/,
        auth: true,
        handler: (req, body, ctx, match) => this.friends.remove(ctx.userId, match[1]),
      },
    ];
  }

  handle(req, res) {
    const path = new URL(req.url, "http://localhost").pathname;
    if (!path.startsWith("/account") && !path.startsWith("/friends")) return false;
    this._run(req, res, path).catch((e) => this._fail(res, e));
    return true;
  }

  async _run(req, res, path) {
    const ip = remoteIp(req);
    if (!this.rateLimiter.hit(`api:${ip}`, API_MAX_REQUESTS, API_WINDOW_MS)) {
      throw new AccountError("too_many_requests", "请求过于频繁，请稍后再试", 429);
    }
    const route = this.routes.find((r) => r.method === req.method && r.pattern.test(path));
    if (!route) throw new AccountError("not_found", "接口不存在", 404);

    const body = req.method === "GET" ? {} : await readJson(req);
    const ctx = { ip };
    if (route.auth) {
      ctx.token = this._bearer(req);
      ctx.userId = this.accounts.authenticate(ctx.token);
    }
    const result = await route.handler(req, body, ctx, route.pattern.exec(path));
    sendJson(res, 200, result);
  }

  _withPresence(friends) {
    return friends.map((f) => ({ ...f, online: this.presence ? this.presence.isOnline(f.userId) : false }));
  }

  _bearer(req) {
    const m = /^Bearer\s+(.+)$/i.exec(String(req.headers["authorization"] || "").trim());
    return m ? m[1].trim() : "";
  }

  _fail(res, err) {
    if (res.headersSent) return;
    if (err instanceof AccountError) {
      sendJson(res, err.status, { error: err.code, message: err.message });
      return;
    }
    console.error("[account] 未处理异常:", err);
    sendJson(res, 500, { error: "internal", message: "服务内部错误" });
  }
}

module.exports = { AccountRouter, remoteIp };
