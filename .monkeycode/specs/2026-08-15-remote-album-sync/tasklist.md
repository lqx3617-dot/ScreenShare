# Task List — 远程相册同步

## 服务器端

- [ ] 相册服务器（8096）：sessions 表加 device 列（含迁移）；create 接口接受 device 参数
- [ ] 相册服务器：新增 GET /api/devices（有照片的设备列表）
- [ ] 相册服务器：GET /api/albums 支持 ?device= 过滤；/all 按设备分组
- [ ] 中继（8095 信令服务器扩展）：relay 通道，deviceCode→ws 注册映射 + 指令转发 + 离线判定

## 共享方（主 App）

- [ ] ScreenSyncService 前台服务（dataSync 类型 + 常驻通知，显示设备码/IP/进度）
- [ ] 内置 HTTP 服务（8686）：GET /status、POST /sync/start
- [ ] 中继 WebSocket 长连接注册设备码 + 收指令
- [ ] 相册同步执行器：首次全量 + MediaStore id 增量去重 + 断点续传
- [ ] 相册权限校验与授权引导
- [ ] 设备码生成与展示

## 观看方（相册 APP）

- [ ] 聚合页「连接设备」入口：输入设备码 → 经中继发开启同步指令
- [ ] 同步进度轮询展示
- [ ] 按设备查看：/api/devices 列表 + /api/albums?device 加载

## 验证

- [ ] 局域网端到端：输入设备码远程触发 → 相册 APP 看到照片 → 增量刷新
- [ ] 公网端到端：跨网段经中继触发
- [ ] 发布 + md5 校验 + MEMORY 记录
