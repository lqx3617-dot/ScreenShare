# Album Server — 相册照片后端服务器

共享方 App 上传相册照片的后端服务：**缩略图先行 + 按需原图**，几千张照片也能秒开浏览。

## 技术栈

- Node.js (≥22) + Express 4
- SQLite（内置 `node:sqlite` 模块，零原生依赖）
- 磁盘存储：缩略图与按需原图

## 启动

```bash
npm install
PORT=8096 node index.js
```

环境变量：

| 变量 | 默认 | 说明 |
|------|------|------|
| `PORT` | `8096` | 监听端口 |
| `ALBUM_ROOT` | `/workspace/albums` | 照片存储根目录 |

SQLite 数据库自动创建于 `data/albums.db`。

## 接口

### POST /api/upload（JSON body）

| action | body | 返回 |
|--------|------|------|
| `create` | `{}` | `{token}` 创建上传会话 |
| `upload` | `{token, index, data}` | `{ok}` 上传缩略图（base64） |
| `original` | `{token, index, data}` | `{ok}` 上传原图（按需） |
| `finish` | `{token}` | `{ok, url}` 结束会话 |

### GET

| 路径 | 返回 |
|------|------|
| `/api/status?token=<t>` | `{total, received, done, originals}` |
| `/api/original?token=<t>&index=N` | 原图图片流，未上传时标记 pending 并返回 `{status:"pending"}` |
| `/api/pending?token=<t>` | `{pending:[index...]}` 共享方 App 轮询用 |
| `/<token>/` | 相册网页（上传中自动刷新、点击按需加载原图） |
| `/<token>/xxxx.jpg` | 单张缩略图 |

## 数据流

1. App `create` → 获得 token（链接立即可用，网页边传边看）
2. App 并发上传缩略图（300px）→ 网页逐张出现
3. 网页点某张 → `/api/original` 标记 pending → App 轮询 `/api/pending` 实时压缩上传该张原图（1280px）→ 网页加载
4. App `finish` → 会话完成

会话 24h 后过期自动清理（目录 + DB 行）。

## 兼容

接口与旧版单文件 `server/album-server.js` 完全兼容，App 端无需改动；旧 `meta.json` 会话会自动迁移入库。
