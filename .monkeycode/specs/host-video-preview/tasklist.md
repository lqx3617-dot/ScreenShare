# 需求实施计划

- [x] 1. WebRTCPeer 暴露本地视频轨道（design.md Components 1）
  - [x] 1.1 新增 getLocalVideoTrack()
    - WebRTCPeer.kt 新增 `fun getLocalVideoTrack(): VideoTrack?` 返回私有字段 localVideoTrack
    - 仅共享方调用，观看方不受影响

- [x] 2. 抽取通用视频预览渲染方法（design.md Components 2）
  - [x] 2.1 新建 setupVideoPreview(track: VideoTrack)
    - onRemoteVideoTrack 渲染逻辑抽取为通用方法：renderer 创建/销毁、sink 绑定（含旧轨道解绑修复）、lastFrameW/H 更新、缩放/双击复位、完整铺满按钮、容器尺寸监听、prepareFullscreenRenderer、remoteVideoTrack = track
  - [x] 2.2 onRemoteVideoTrack 改为调用 setupVideoPreview(track)
    - 观看方路径行为不变

- [x] 3. 共享方连接后显示本地预览（design.md Components 2/3）
  - [x] 3.1 onConnected 增加 isHost 分支
    - isHost 时调用 peer?.getLocalVideoTrack()?.let { setupVideoPreview(it) }
    - null 时静默跳过（采集未就绪不阻断连接）
    - 观看方分支保持原样（btnFpsToggle 仅观看方显示）
  - [x] 3.2 清理路径复用
    - releaseFullscreenRenderer/resetUI 现有逻辑自动解绑本地预览（remoteVideoTrack 已指向本地轨道），无新增清理代码

- [x] 4. 检查点 - 编译通过
  - assembleDebug 编译通过

- [x] 5. 构建签名发布 v1.66
  - versionCode=67/versionName="1.66"，assembleRelease + zipalign + apksigner 签名（md5 11d863d91d8d0b316791c3caad306ad5）
  - 更新 download-server.js note 并重启下载服务（/version.json 已验证）
  - 更新 tasklist 完成状态
