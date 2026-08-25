# ScreenShare 代码审查修复日志

**日期**：2026-08-26
**方案**：兼容方案 A（REQUIRE_TOKEN 默认关闭，不破坏现有客户端）

## 已修改
- `app/build.gradle.kts`：新增 `secret()`，机密字段 `TURN_PASSWORD`、`ALBUM_KEY`、`DIAG_TOKEN` 改由 `local.properties` 或环境变量注入；公开地址仍读 `gradle.properties`
- `local.properties`：新增，git 忽略，保存 TURN 密码、相册密钥、诊断 token
- `gradle.properties`：删除三行明文机密
- `server/server.js`：接入 `AuthManager`，新增 `REQUIRE_TOKEN` 开关。开启后 `create` 签发房间 token 并随 `created` 返回、`join` 校验 token、房间销毁时释放 token；默认关闭保持兼容
- `ScreenShare_AuditFix_2026-08-26.md`：本日志

## 服务器端 token 认证已完整接入（2026-08-26 补充）
- `create`：签发 8 位 token（去易混淆字符）随 `created` 返回 host，签发前 `releaseTokens` 防残留
- `join`：`REQUIRE_TOKEN=1` 时校验 `msg.token`，不匹配返回"加入口令无效"
- `host` 离开：整房销毁时 `releaseTokens(roomCode)` 释放 token
- `REQUIRE_TOKEN=0`（默认）：create 仍签发 token 但 join 不校验，旧客户端完全兼容
- 测试全通过：create 返回 token / 错误 token 拒绝 / 正确 token 接受 / host 离开后重建 token 不同 / 默认关闭时无 token join 正常

## 尚未完成
- 客户端 token 输入/分享 UI 未接入；在 `REQUIRE_TOKEN=1` 正式启用前需完成，否则新版客户端无法加入开启校验的房间
- 历史提交中仍包含明文密钥与 GitHub PAT，建议后续轮换

## 验证
- `node --check server/server.js` 通过
- 服务器端 ws 全链路测试通过（token 签发/校验/释放/兼容）
- 未执行完整 Gradle 构建
