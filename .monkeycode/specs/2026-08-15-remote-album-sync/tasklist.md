# Task List — 远程相册同步

## 服务器端

- [x] 相册服务器（8096）：sessions 表加 device 列（含迁移）；create 接口接受 device 参数
- [x] 相册服务器：新增 GET /api/devices（有照片的设备列表）
- [x] 相册服务器：GET /api/albums 支持 ?device= 过滤（/all 聚合页未按设备分组，可选增强）
- [x] 中继：新增独立 relay-server.js（8097）deviceCode→ws 注册映射 + 指令转发 + 离线判定（设计选择独立端口，不污染 8095 会议信令）
- [x] 相册服务器：sessions 表新增 videos 列（V3 迁移）；/api/upload 支持 video-thumb/video-finish action；POST /api/video/upload 二进制分块上传（3MB/块、offset 严格顺序校验、409 重传、reset 清空）；GET /api/video 流式播放（Range/206/416）
- [x] 相册服务器网页：单会话页与 /all 聚合页视频网格角标 + 内嵌 <video> 播放；/api/albums 返回 videos 集合；/api/devices 精确统计非视频项

## 共享方（主 App）

- [x] ScreenSyncService 前台服务（dataSync 类型 + 常驻通知，显示设备码/IP/进度）
- [x] 内置 HTTP 服务（8686）：GET /status、POST /sync/start
- [x] 中继 WebSocket 长连接注册设备码 + 收指令
- [x] 相册同步执行器：首次全量 + MediaStore id 增量去重 + 断点续传
- [x] 相册权限校验（无权限时通知提示；权限请求沿用 MainActivity 相册上传授权流程）
- [x] 设备码生成（8 位 XXXX XXXX，避免易混淆字符 O/0/I/1）与通知展示
- [x] 视频同步：README_MEDIA_VIDEO 权限、VideoTranscoder（MediaCodec surface 硬编解码 + EGL 缩放、720p/2Mbps/AAC 96k）、AlbumUploader 视频缩略图/分块上传、ScreenSyncService 视频扫描增量（VIDEO_INDEX_BASE=1000000 避免 index 冲突）

## 观看方（相册 APP）

- [x] 输入页「连接设备」入口：输入设备码 → 经中继发开启同步指令
- [x] 同步进度轮询展示（设备相册 5s 轮询自动刷新，上传中照片陆续出现）
- [x] 按设备查看：/api/devices 列表 + /api/albums?device 加载
- [x] 视频查看：AlbumApi 解析 videos 集合（isVideo 字段）、网格 ▶ 角标、点击全屏 VideoView 直接播放

## 验证

- [x] 中继：设备码注册/指令转发/离线判定（node 模拟 host+viewer 双客户端测试通过）
- [x] 相册服务器：device 分组、/api/devices、/api/albums?device 过滤、历史无 device 会话兼容
- [x] 端到端链路：共享方注册 → 观看方发 start → 触发上传（device 标记）→ 按设备查看（测试数据已清理）
- [x] 发布：主 App 1.188/191（md5 51e5ad18e1acb26f2f73c22cbdabcd03）、相册 APP 1.188/7（md5 2450c6af569bf13af6c203341c45ec60），公网下载 md5 一致
- [x] 视频同步服务器端验证：分块上传 5MB 随机文件 offset 逐块递增、finish 后 videos=[index]、Range 206/100B 与全量 200 与源文件一致、聚合页含视频角标/播放元素（e2e 测试会话已清理）
- [x] 发布：主 App 1.191/194（md5 97b608593a8af3188dcc19de38dc7066）、相册 APP 1.189/8（md5 095c0ee6592b1adcb0a3286c0767ccb5），公网下载 md5 一致
- [ ] 真机端到端（双端安装实测触发流程；本环境无法执行，待用户真机验证）
- [ ] 真机验证 VideoTranscoder 转码输出（无模拟器/真机，仅编译+代码审查，待用户真机验证）

## 备注

- 中继端口采用独立 8097（design.md 写"复用/扩展 8095"，实施时自主决策独立端口避免污染会议信令）
- 共享方同步状态展示在主 App 通知 + 内置 HTTP /status（局域网直连诊断用）
- 增量去重持久化：SharedPreferences 存已同步 MediaStore id 集合（首次全量后记录，后续增量跳过）
- 观看方触发后中继回执有 error 则提示离线；无回执 15s 超时视为已触发（防 ack 丢失）
