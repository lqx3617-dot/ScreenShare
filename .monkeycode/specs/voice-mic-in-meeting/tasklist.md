# 需求实施计划

- [x] 1. 实现 WebRTCPeer 麦克风音频轨道（design.md Components 1）
  - [x] 1.1 实现 startMicAudio
    - 创建 AudioSource（MediaConstraints：googEchoCancellation:true、googNoiseSuppression:true、googAutoGainControl:true）与 org.webrtc.AudioTrack
    - peerConnection.addTrack 添加音频轨道，保存 micSender
    - 失败返回 false 并记录日志
  - [x] 1.2 实现 stopMicAudio 与 setMicMuted
    - stopMicAudio：removeTrack + dispose AudioSource/AudioTrack
    - setMicMuted：track.setEnabled(!muted)（不触发重协商）
  - [x] 1.3 实现 renegotiate
    - 复用 createOffer 流程重新生成 Offer（携带音频轨道），由调用方触发
    - 保证主线程调用（与 createOffer 同一线程约束）

- [x] 2. 支持观看端重协商（design.md Components 2，MainActivity.handleSignalRelay）
  - [x] 2.1 修改 OFFER 分支复用已有 PeerConnection
    - MainActivity.kt OFFER 分支改为 peer==null 时才创建（isNewPeer）
    - 已存在则直接 setRemoteDescription（支持连接建立后的重协商 Offer），且不重复调用 createPeerConnection

- [x] 3. 集成麦克风 UI 与权限（design.md Components 2）
  - [x] 3.1 布局新增麦克风按钮
    - activity_main.xml llStatus 状态栏新增 btnMic（对共享方/观看方均可见，onConnected 时显示）
    - 三种状态文案：麦克风（未开启）/ 对讲中（绿色）/ 已静音（红色）
  - [x] 3.2 实现按钮点击逻辑
    - 未开启：请求 RECORD_AUDIO 运行时权限（PERM_REQUEST_MIC=101）→ 授权成功调 peer.startMicAudio()+renegotiate()，UI 显示已开启；拒绝则提示
    - 已开启：peer.setMicMuted 切换静音，UI 更新静音状态（不重协商）
  - [x] 3.3 cleanupPeer 与会话结束清理
    - cleanupPeer 重置麦克风 UI 状态并隐藏按钮（peer.disconnect() 内部释放麦克风资源）
    - onDisconnected/onPeerLeft/resetUI 路径同样重置

- [x] 4. 检查点 - 编译通过
  - assembleDebug 编译通过（AudioConstraints→MediaConstraints 修正后成功）

- [x] 5. 构建签名发布 v1.65
  - versionCode=66/versionName="1.65"，assembleRelease + zipalign + apksigner 签名（md5 2a2f2e9f52138e0c9d671141dcda82ed）
  - 更新 download-server.js note 并重启下载服务（/version.json 已验证）
  - 更新 tasklist 完成状态
