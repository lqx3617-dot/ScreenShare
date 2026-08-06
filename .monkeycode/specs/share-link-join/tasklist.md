# 实施任务列表

- [x] 需求文档 + 设计文档（share-link-join）
- [x] 1. download-server.js 新增 `/j` 路由（网页兜底页：会议号 + 打开 App + 下载 App）
- [x] 2. AndroidManifest.xml 为 MainActivity 注册 `screenshare://join` VIEW intent-filter
- [x] 3. MainActivity.showMeetingCodeDialog 新增「分享链接」按钮（系统分享面板）
- [x] 4. MainActivity 新增 handleShareLink（onCreate + onNewIntent 解析 scheme 自动加入）
- [x] 5. 构建 + 单测 + 签名 + 重启下载服务器 + 验证 version.json
- [x] 6. 提交推送（含 spec 文档）
