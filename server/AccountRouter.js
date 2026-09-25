"use strict";

/**
 * 账号/好友 REST 路由。
 * 挂载方式：server.js 的 http 回调开头调用 handle(req, res)，返回 true 表示已接管。
 * 鉴权：Authorization: Bearer <token>。
 */

const { AccountError } = require("./AccountManager");
const geo = require("./GeoCoder");
const fs = require("fs");
const path = require("path");

const API_WINDOW_MS = 60 * 1000;
const API_MAX_REQUESTS = 60;
const MAX_BODY = 64 * 1024;
const BODY_PHOTO = 16 * 1024 * 1024; // 照片 base64（10MB 原图 ≈ 13.4MB base64）
const BODY_CHUNK = 8 * 1024 * 1024; // 视频分块 base64（3MB 原始 ≈ 4MB base64）

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
  constructor({ accountManager, friendManager, coupleManager, rateLimiter, presence, shareHistory, notifyUser }) {
    this.accounts = accountManager;
    this.friends = friendManager;
    this.couples = coupleManager;
    this.rateLimiter = rateLimiter;
    this.presence = presence;
    this.shareHistory = shareHistory;
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
          this.accounts.login(body.nickname, body.password, req.headers["user-agent"] || "", remoteIp(req)),
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
          this.accounts.updateProfile(ctx.userId, {
            nickname: body.nickname,
            avatar: body.avatar,
            friendCode: body.friendCode,
          }),
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
      {
        method: "PATCH",
        pattern: /^\/friends\/([A-Za-z0-9-]+)\/remark$/,
        auth: true,
        handler: (req, body, ctx, match) =>
          this.friends.setRemark(ctx.userId, match[1], body.remark),
      },
      {
        method: "GET",
        pattern: /^\/shares\/recent$/,
        auth: true,
        handler: (req, body, ctx) => this.shareHistory.recent(ctx.userId, 5),
      },
      {
        method: "POST",
        pattern: /^\/account\/push-token$/,
        auth: true,
        handler: (req, body, ctx) => {
          if (body.clear) return this.accounts.clearPushToken(ctx.userId);
          return this.accounts.setPushToken(ctx.userId, body.token);
        },
      },
      {
        method: "POST",
        pattern: /^\/couple\/invite$/,
        auth: true,
        handler: (req, body, ctx) => {
          const r = this.couples.invite(ctx.userId, body.friendCode);
          const me = this.accounts.getProfile(ctx.userId);
          if (this.notifyUser && me) {
            const brief = { userId: me.userId, nickname: me.nickname, avatar: me.avatar };
            this.notifyUser(r.to, {
              type: r.accepted ? "couple-bound" : "couple-invite",
              invitationId: r.invitationId,
              from: brief,
              partner: brief,
            });
          }
          return r;
        },
      },
      {
        method: "GET",
        pattern: /^\/couple\/invitations$/,
        auth: true,
        handler: (req, body, ctx) => this.couples.invitations(ctx.userId),
      },
      {
        method: "POST",
        pattern: /^\/couple\/accept$/,
        auth: true,
        handler: (req, body, ctx) => {
          const r = this.couples.accept(ctx.userId, body.invitationId);
          const me = this.accounts.getProfile(ctx.userId);
          if (this.notifyUser && me) {
            this.notifyUser(r.partnerId, {
              type: "couple-bound",
              partner: { userId: me.userId, nickname: me.nickname, avatar: me.avatar },
            });
          }
          return r;
        },
      },
      {
        method: "POST",
        pattern: /^\/couple\/reject$/,
        auth: true,
        handler: (req, body, ctx) => this.couples.reject(ctx.userId, body.invitationId),
      },
      {
        method: "GET",
        pattern: /^\/couple$/,
        auth: true,
        // 空间页 60s 轮询：伴侣位置解码为省市区街道地址（未配置高德 Key 时降级经纬度）
        handler: async (req, body, ctx) => {
          const space = this.couples.space(ctx.userId);
          if (space.location) {
            const g = await geo.reverse(space.location.lat, space.location.lng);
            space.location.address = g ? g.address : null;
            // 顺带查 TA 所在地天气：复用同一 adcode，Key 未配置或失败时为 null
            space.weather = g && g.adcode ? await geo.weather(g.adcode) : null;
          }
          return space;
        },
      },
      {
        method: "PATCH",
        pattern: /^\/couple\/anniversary$/,
        auth: true,
        handler: (req, body, ctx) => this.couples.setAnniversary(ctx.userId, body.anniversary),
      },
      {
        method: "POST",
        pattern: /^\/couple\/checkin$/,
        auth: true,
        handler: (req, body, ctx) => {
          const r = this.couples.checkin(ctx.userId);
          const me = this.accounts.getProfile(ctx.userId);
          if (this.notifyUser && me) {
            this.notifyUser(r.partnerId, {
              type: "couple-checkin",
              from: { userId: me.userId, nickname: me.nickname, avatar: me.avatar },
            });
          }
          return { today: r.today, streak: r.streak };
        },
      },
      {
        method: "POST",
        pattern: /^\/couple\/photos$/,
        auth: true,
        bodyLimit: BODY_PHOTO,
        handler: (req, body, ctx) => this.couples.uploadPhoto(ctx.userId, body),
      },
      {
        method: "POST",
        pattern: /^\/couple\/videos$/,
        auth: true,
        bodyLimit: BODY_CHUNK,
        handler: (req, body, ctx) => this.couples.createVideo(ctx.userId, body),
      },
      {
        method: "POST",
        pattern: /^\/couple\/videos\/([A-Za-z0-9-]+)\/chunks$/,
        auth: true,
        bodyLimit: BODY_CHUNK,
        handler: (req, body, ctx, match) =>
          this.couples.uploadVideoChunk(ctx.userId, match[1], body),
      },
      {
        method: "POST",
        pattern: /^\/couple\/videos\/([A-Za-z0-9-]+)\/finish$/,
        auth: true,
        handler: (req, body, ctx, match) => this.couples.finishVideo(ctx.userId, match[1]),
      },
      {
        method: "GET",
        pattern: /^\/couple\/wishes$/,
        auth: true,
        handler: (req, body, ctx) => this.couples.listWishes(ctx.userId),
      },
      {
        method: "POST",
        pattern: /^\/couple\/wishes$/,
        auth: true,
        handler: (req, body, ctx) => this.couples.addWish(ctx.userId, body.text),
      },
      {
        method: "PATCH",
        pattern: /^\/couple\/wishes\/([A-Za-z0-9-]+)$/,
        auth: true,
        handler: (req, body, ctx, match) =>
          this.couples.toggleWish(ctx.userId, match[1], !!body.done),
      },
      {
        method: "DELETE",
        pattern: /^\/couple\/wishes\/([A-Za-z0-9-]+)$/,
        auth: true,
        handler: (req, body, ctx, match) =>
          this.couples.deleteWish(ctx.userId, match[1]),
      },
      {
        method: "GET",
        pattern: /^\/couple\/photos$/,
        auth: true,
        handler: (req, body, ctx) => this.couples.listPhotos(ctx.userId),
      },
      {
        method: "DELETE",
        pattern: /^\/couple\/photos\/([A-Za-z0-9-]+)$/,
        auth: true,
        handler: (req, body, ctx, match) => this.couples.deletePhoto(ctx.userId, match[1]),
      },
      {
        method: "POST",
        pattern: /^\/couple\/location$/,
        auth: true,
        handler: (req, body, ctx) => this.couples.reportLocation(ctx.userId, body),
      },
      {
        method: "POST",
        pattern: /^\/couple\/dissolve$/,
        auth: true,
        handler: (req, body, ctx) => {
          const r = this.couples.dissolve(ctx.userId);
          if (this.notifyUser && r.partnerId) {
            this.notifyUser(r.partnerId, { type: "couple-dissolved" });
          }
          return { ok: true };
        },
      },
      {
        method: "GET",
        pattern: /^\/couple_media\/([A-Za-z0-9_-]+\.(?:jpg|png|webp|mp4))$/,
        auth: true,
        handler: (req, body, ctx, match) => {
          const file = match[1];
          const mediaId = file.replace(/_thumb\.(?:jpg|png|webp)$/, "").replace(/\.(?:jpg|png|webp|mp4)$/, "");
          const ok = this.couples.canViewMedia(ctx.userId, mediaId);
          if (!ok) throw new AccountError("forbidden", "无权访问该媒体", 403);
          const mime =
            /\.png$/.test(file) ? "image/png" :
            /\.webp$/.test(file) ? "image/webp" :
            /\.mp4$/.test(file) ? "video/mp4" : "image/jpeg";
          return { __file: path.join(__dirname, "data", "couple_photos", file), __mime: mime };
        },
      },
    ];
  }

  handle(req, res) {
    const path = new URL(req.url, "http://localhost").pathname;
    if (!path.startsWith("/account") && !path.startsWith("/friends") && !path.startsWith("/shares") && !path.startsWith("/couple")) return false;
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

    const body = req.method === "GET" ? {} : await readJson(req, route.bodyLimit || MAX_BODY);
    const ctx = { ip };
    if (route.auth) {
      const url = new URL(req.url, "http://localhost");
      ctx.token = this._bearer(req) || url.searchParams.get("token") || "";
      ctx.userId = this.accounts.authenticate(ctx.token);
    }
    const result = await route.handler(req, body, ctx, route.pattern.exec(path));
    if (result && result.__file) {
      const stream = fs.createReadStream(result.__file);
      // 先等 open 再写响应头：文件缺失时 headersSent 仍为 false，可正常返回 404 而非挂起连接
      stream.once("open", () => {
        if (res.headersSent) return;
        res.writeHead(200, {
          "Content-Type": result.__mime,
          "Cache-Control": "public, max-age=86400",
          "X-Content-Type-Options": "nosniff",
        });
        stream.pipe(res);
      });
      stream.once("error", () => {
        if (!res.headersSent) {
          this._fail(res, new AccountError("not_found", "文件缺失", 404));
        } else {
          res.destroy();
        }
      });
      // 客户端中断时销毁读流，避免文件句柄滞留
      res.once("close", () => stream.destroy());
      return;
    }
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
