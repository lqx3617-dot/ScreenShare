# User Instruction Memory

This file records user instructions, preferences, and teachings for reference in future interactions.

## Format

### User Instruction Entry
User instruction entries should follow this format:

[User Instruction Summary]
- Date: [YYYY-MM-DD]
- Context: [Mentioned scenario or time]
- Instructions:
  - [Content of user teaching or instruction, described line by line]

### Project Knowledge Entry
Entries discovered by the Agent during task execution should follow this format:

[Project Knowledge Summary]
- Date: [YYYY-MM-DD]
- Context: Discovered by Agent while performing [specific task description]
- Category: [Operations & Deployment|Build Methods|Testing Methods|Troubleshooting & Debugging|Workflow & Collaboration|Environment Configuration]
- Instructions:
  - [Specific knowledge points, described line by line]

## Deduplication Strategy
- Before adding a new entry, check for similar or identical instructions.
- If a duplicate is found, skip the new entry or merge it with the existing one.
- When merging, update the context or date information.
- This helps avoid redundant entries and keeps the memory file tidy.

## Entries

[User Instruction Summary]
- Date: 2026-08-03
- Context: 用户要求每次修复 bug 后更新 APK 版本号
- Instructions:
  - 每次修复 bug 并发布新 APK 时，必须同步递增版本号（versionCode + versionName）后再构建签名

[Project Knowledge Summary]
- Date: 2026-08-03
- Context: Discovered by Agent while performing ScreenShare Android 项目构建
- Category: Build Methods
- Instructions:
  - Android 项目构建：export ANDROID_HOME=/opt/android-sdk，运行 ./gradlew assembleDebug
  - SDK 组件已装于 /opt/android-sdk（platforms;android-34、build-tools/34.0.0、platform-tools）
  - 签名命令：zipalign + apksigner，使用 /workspace/signing/release.keystore，密码 screenshare123，alias screenshare
  - 每次签名后需 apksigner verify 验证 v2/v3 通过
  - 全架构版（含全部 ABI）约 54MB；仅 arm64 约 19MB

[Project Knowledge Summary]
- Date: 2026-08-03
- Context: Discovered by Agent while debugging P2P 卡在 CHECKING / ICE 候选为 0 问题
- Category: Troubleshooting & Debugging
- Instructions:
  - ScreenShare WebRTC 候选不生成的根因与修复（v1.8）：
    - 症状：服务器日志 relay 的 offer/answer 显示 sdp a=candidate:0 且 ice[] 全 0，P2P 卡在连接
    - 根因 1：PeerConnectionFactory.builder() 必须显式 setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBaseContext,true,true)) + setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBaseContext))，否则 ICE 不收集候选（DefaultVideoEncoderFactory/DefaultVideoDecoderFactory 存在于 SDK，之前编译失败只是缺 import）
    - 根因 2：WebRTC 所有 PeerConnection 操作必须在同一线程（主线程），createOffer 从后台 executor 改为主线程 postDelayed(2000ms) 后候选才正常生成
    - 验证：本地用 node ws 客户端做端到端 relay 测试服务器转发可靠；服务器日志统计 ice[] host/srflx/relay 类型计数验证无误
    - 诊断手段：MainActivity 屏幕显示"本机候选: N个"实时计数 + 服务器 relay 日志统计候选类型，双端交叉确认

[Project Knowledge Summary]
- Date: 2026-08-03
- Context: Discovered by Agent while enabling 异地共享（不同网络）
- Category: Operations & Deployment
- Instructions:
  - 异地共享（不同 Wi-Fi/4G）配置（v1.10）：
    - STUN_URLS 用 3 个公共节点（stun.l.google.com:19302、stun1.l.google.com:19302、stun.cloudflare.com:3478），供两端生成 srflx 公网映射候选，覆盖大多数家用/移动网络场景（已验证 host=2 srflx=2/3 跨网直连成功）
    - TURN 自建 coturn：/etc/turnserver.conf，监听 3478（UDP+TCP），long-term credential，静态用户 screenshare/screenshare123，realm=6d639d2de20eb686.monkeycode-ai.online
    - 关键：TURN URL 用 ?transport=tcp（turn:6d639d2de20eb686.monkeycode-ai.online:3478?transport=tcp），因为环境反代 UDP 透传不可靠、TCP 控制面已验证 ALLOCATE/REFRESH 成功
    - allowed-peer-ip 语法是 ip[-ip] 范围（不支持 CIDR），需显式放行 127.0.0.1 和内网段
    - 环境限制：公网大文件下载偶发截断（整文件 curl 哈希不一致），但 Range 分块下载稳定（6 块全 206 且合并哈希一致）；手机浏览器下载器可正确断点续传
    - coturn 进程也可能被环境回收，用户异地测试前需确认 3478 端口在监听（ss -tlnp | grep 3478）

[Project Knowledge Summary]
- Date: 2026-08-03
- Context: Discovered by Agent while debugging 反复"无法连接信令服务器"问题
- Category: Troubleshooting & Debugging
- Instructions:
  - 信令服务器反复离线、App 报"无法连接"的根因（v1.15 修复）：
    - 主因：background_terminal_create 的 timeout 参数单位是毫秒，误传 10000/15000 导致服务器运行 10/15 秒后被自动杀掉，不是环境回收进程！长驻服务必须传 timeout=0（无超时），且禁止用 bash 的 & 启动（会话结束后被杀）
    - 环境公网代理对部分端口间歇性返回 530（8080/8081 已失效），需逐一探测可用端口（8090、8095-8101 已验证可用），信令地址用可用端口
    - 信令服务器现运行于 PORT=8095，公网地址 wss://8095-6d639d2de20eb686.monkeycode-ai.online/ws
    - 守护脚本 /workspace/server/daemon-signaling.sh（检测 8095 无监听则重启）需用 timeout=0 的后台终端运行，否则守护进程本身也会被杀
    - 排查命令：ss -tlnp | grep 端口 确认本地监听；node ws 客户端连 wss 公网地址发 {type:'create'} 验证全链路；公网代理不可用端口返回 HTTP 530

[Project Knowledge Summary]
- Date: 2026-08-04
- Context: Discovered by Agent while fixing 异地黑屏 + 实现云更新 + 会议号改 4 位（v1.16）
- Category: Operations & Deployment
- Instructions:
  - 异地"已连接但黑屏"根因（v1.16 修复）：coturn 的 allowed-peer-ip 只放行内网段（127.0.0.1/192.168/10./172.16-31），观看方手机在公网时 TURN 中继数据面被拒绝 → relay 候选无法传数据 → 黑屏。修复：allowed-peer-ip=0.0.0.0-255.255.255.255（放开所有对端）
  - 诊断：服务器日志 offer/answer 的 ice[] 统计——若两端 relay=0 且 answer 无 srflx，跨网必黑屏；offer 有 srflx 是因为 STUN 有时效返回，answer 端 STUN 失败则无
  - 云更新：download-server.js 提供 /version.json（动态读 build.gradle.kts 的 versionCode/versionName + 计算 APK md5，APK mtime 变化自动失效缓存）；App 端 UpdateChecker.kt 启动时对比 BuildConfig.VERSION_CODE，新版弹窗→下载到 getExternalFilesDir→md5 校验→FileProvider 分发 ACTION_VIEW 安装；需 REQUEST_INSTALL_PACKAGES 权限 + res/xml/file_paths.xml（external-files-path）
  - 会议号 v1.16 起为 4 位数字（App generateMeetingCode 4 位 + validateSignalCode ^[0-9]{4}$；server.js 校验同步）

[Project Knowledge Summary]
- Date: 2026-08-04
- Context: Discovered by Agent while fixing 屏幕旋转中断共享（v1.17）
- Category: Troubleshooting & Debugging
- Instructions:
  - 屏幕旋转导致 Activity 重建，WebRTC 连接和 MediaProjection 全部销毁而中断共享。修复：MainActivity 加 configChanges="orientation|screenSize|screenLayout|keyboardHidden|uiMode" + screenOrientation="fullSensor"，旋转时 Activity 不重建
  - WebRTC 的 ScreenCapturerAndroid 不自动跟随屏幕方向，需在 onConfigurationChanged 里用 resources.displayMetrics 的宽高调用 capturer.changeCaptureFormat(新宽,新高,30) 更新采集方向
  - onConfigurationChanged 里仅当 isHost && hostSessionActive && peer!=null 时才更新采集方向（观看端无需处理）

[Project Knowledge Summary]
- Date: 2026-08-04
- Context: Discovered by Agent while 移除二维码连接 + 视频置顶 + 云更新不弹排查（v1.18）
- Category: Workflow & Collaboration
- Instructions:
  - 用户明确要求移除二维码配对，只保留会议号模式：删除 btnHost/btnJoin/btnConfirm/ivQRCode/ScanIntent 全部逻辑，布局只留快速会议+加入会议按钮；二维码相关依赖 zxing-android-embedded 已从 build.gradle 移除
  - 布局改为视频画面置顶（flRemoteVideo 放标题下方 weight=1，会议号按钮区放底部）
  - 云更新"不弹"排查结论：服务器 version.json 公网 6/6 稳定，根因是用户手机仍装旧版（无云更新功能）而非服务器问题；新增"检查更新"入口（标题栏右上角，UpdateChecker.check(context, manual=true)），手动检查时无新版会提示"已是最新版本"，网络失败会提示原因，便于用户自助排查
  - 云更新弹窗依赖 FileProvider 安装流程（AndroidManifest 已注册 ${applicationId}.fileprovider + res/xml/file_paths.xml）

[Project Knowledge Summary]
- Date: 2026-08-04
- Context: Discovered by Agent while 实现视频全屏横屏观看（v1.19）
- Category: Build Methods
- Instructions:
  - 全屏观看实现：视频区右上角 btnFullscreen 按钮 → enterFullscreen() 创建全屏 Dialog（Theme_Black_NoTitleBar_Fullscreen），复用同一 videoTrack 用独立 SurfaceViewRenderer + 独立 VideoSink addSink，强制横屏 requestedOrientation=SENSOR_LANDSCAPE，沉浸式隐藏系统栏（R+ 用 insetsController.hide(systemBars)，低版本用 systemUiVisibility IMMERSIVE_STICKY）
  - 退出全屏：dialog setOnDismissListener → exitFullscreen()：removeSink + renderer.release() + 恢复 requestedOrientation=FULL_SENSOR
  - 关键：每个 renderer 需独立 VideoSink（一个 track 可多 sink）；全屏按钮 setOnTouchListener 单击 dismiss 退出、双指缩放复用 applyVideoScale；resetUI/onDestroy 需清理 fullscreen 状态

[Project Knowledge Summary]
- Date: 2026-08-04
- Context: Discovered by Agent while 修复观看画面上下被裁切（v1.45~v1.52 多轮）
- Category: Troubleshooting & Debugging
- Instructions:
  - 观看画面"上下被裁切"的根因在采集端：MediaProjection 虚拟显示分辨率比例与屏幕实际比例不一致时，系统会把屏幕内容放大填满虚拟显示，导致上下内容被切掉。修复：captureSizeForScreen() 必须用 resources.displayMetrics 实际宽高并按屏幕比例计算（最大边 cap 2400），而非固定 1080x1920/1920x1080
  - SurfaceViewRenderer 在"容器宽>高 + 竖屏视频"时，内部 updateSurfaceSize 的 FIT 计算（frameHeight=measuredWidth/aspect）会导致 surface 超高、上下被裁。修复：不用 MATCH_PARENT+内部缩放，改为主渲染器手动 setLayoutParams 为视频等比适配容器后的尺寸 + Gravity.CENTER + scalingType=FIT；铺满模式才用 MATCH_PARENT+scalingType=FILL
  - 每帧强制 setScalingType 会在渲染线程调 View 方法导致闪退，不可用；改为首帧分辨率变化时（VideoSink 检测 rotatedWidth/rotatedHeight 变化）runOnUiThread 手动重算
  - 视频比例与播放器比例差异大时（手机 9:20 vs 平板竖屏 3:4），完整模式必然有大量黑边，属物理限制；建议用户平板横屏观看或共享方横屏共享

[Project Knowledge Summary]
- Date: 2026-08-05
- Context: Discovered by Agent while 排查平板端报"Software caused connection abort"
- Category: Troubleshooting & Debugging
- Instructions:
  - 平板端报"无法连接信令服务器: Software caused connection abort"（SocketException）是设备侧网络层连接被中断（WiFi 弱/网络切换），信令服务器公网 wss 本身正常（node ws 客户端连公网地址 create 往返通过），优先排查设备网络而非服务器
  - SignalClient 已加自动重连（v1.57）：onFailure 与 onClosed 都走 scheduleRetry，最多 4 次（1s/2s/3s/4s 间隔），重连成功重置 attempt；Listener 新增 onRetrying 回调，MainActivity 显示"信令连接异常，X 秒后自动重试"并重置 signalPeerReady/signalSdpSent，但不清理 peer（onError 才 cleanupPeer+resetUI）
  - 测试角色确认技巧：平板端报"正在启动屏幕共享"是 onPeerReady 的 Host 提示，若授权未完成 startHostSession 从未执行会一直停在此提示；onPeerReady 里用 ScreenCapturerFactory.hasPermission() 判断未授权则重新 requestPermission + 明确提示（v1.56）
  - 会议号弹窗（showMeetingCodeDialog）会遮挡系统授权框，导致用户漏点"立即开始"；改为创建会议后先弹授权框，授权成功（onActivityResult）后才显示会议号弹窗（v1.55）

[Project Knowledge Summary]
- Date: 2026-08-05
- Context: Discovered by Agent while 排查平板端共享失败（创建会议后 offer 始终未发到服务器）
- Category: Troubleshooting & Debugging
- Instructions:
  - 平板端共享失败两个根因及修复（v1.61/v1.62）：
    - ① 采集卡死（③c startCapture 阻塞，日志房间无 relay）：同一 MediaProjection 投影 token 被 getMediaProjection 二次获取——ScreenCapturerFactory.createScreenCapturer 预取一个实例缓存给音频内录用，ScreenCapturerAndroid.startCapture 内部又用同一 Intent 取一次，部分平板（尤其竖屏）在 createVirtualDisplay 卡死。修复：投影只由 ScreenCapturerAndroid 内部获取唯一一次，SystemAudioBridge.startCapture 改传 p.mediaProjection()（ScreenCapturerAndroid.getMediaProjection() 反射/公共方法获取同一实例）
    - ② "对端尚未加入"错误：onIceCandidate 发送候选只检查 signalSdpSent 不检查 signalPeerReady，对端未加入房间时候选被服务器拒发丢失。修复：对端未加入时候选缓存到 signalPendingCandidates，onPeerReady 补发时连同缓存的 signalPendingOfferData 一起发送
  - 无效尝试（未解决采集卡死，勿重复）：采集分辨率上限 2400→1920、帧率 60→30 都不能解除 ③c 卡死，根因是二次 getMediaProjection
  - 诊断手段：startSessionCore/startScreenCapture 增加 ①~⑤ + ③a~③e 逐步进度显示到 tvScanResult（独立区域，不被 updateUI 状态栏覆盖），用户直接反馈停在哪一步即可定位
  - onOfferReady 会把 SDP 诊断（视频轨道/候选数）显示到 tvScanResult；授权成功后正常流程：①启动服务→②创建PC→③采集→④音频通道→⑤生成SDP
  - 延迟优化实测结论（v1.63/v1.64）：60fps 采集比 30fps 端到端延迟更高——高分辨率 60fps 下硬件编码器每帧处理排队更久。固定用 30fps 采集 + 编码 maxFramerate 30。保留的低延迟手段：enc.networkPriority=4 + bitratePriority=4.0（视频流高优先级减少拥塞抑制）、peerConnection.setBitrate(6M, 12M, 25M)（初始带宽给足跳过码率爬坡期）、MAINTAIN_RESOLUTION 保清晰度

[Project Knowledge Summary]
- Date: 2026-08-07
- Context: Discovered by Agent while 排查观看画面滞后（v1.100）
- Category: Troubleshooting & Debugging
- Instructions:
  - WebRTC SDK 144 源码在 /tmp/webrtc_src（m144_release 分支，已稀疏检出 modules/call/media，本地 rg 可查 field trials），勿用 git grep（blob:none 需网络）
  - SDK 144 中**已不存在**的 field trials（加了静默无效）：WebRTC-MinimizeResamplingOnMobileVideoBitrateChange、WebRTC-VideoHwDecoding、WebRTC-FrameDropper、WebRTC-JitterBufferTargetDelay、WebRTC-LowLatencyRenderer、WebRTC-BweLossBasedControl、WebRTC-BweLatencySmoothing
  - SDK 144 中真实存在：WebRTC-JitterEstimatorConfig（参数 key:value 逗号分隔，如 nack_limit:15,nack_count_timeout:5s）、WebRTC-ZeroPlayoutDelay（min_pacing:8ms，需低延迟渲染路径激活才生效）、WebRTC-Bwe-LossBasedBweV2（丢包拥塞控制，Enabled 默认 true 无需配置）
  - 观看端画面滞后根因：丢包重传(nack_count>=3)触发 RTT 惩罚放大接收端 jitter 缓冲估计 → target delay 增大 → 播放缓冲膨胀滞后。v1.100 通过 JitterEstimatorConfig 把 nack_limit 3→15、nack_count_timeout 60s→5s 抑制缓冲膨胀
  - Java SDK 144 无任何视频 playout delay 设置 API（RtpParameters/MediaConstraints/PeerConnection 均无）；UseLowLatencyRendering 需 max_playout_delay<=500ms（默认 10s），仅由 playout-delay RTP header extension 驱动，Java 层无法激活

[User Instruction Summary]
- Date: 2026-08-08
- Context: 用户要求去掉多人共享，改为情侣之间的 1 对 1 共享
- Instructions:
  - 共享功能为情侣 1 对 1 模式：每房间最多 1 个观看方，第 2 个加入被拒（"该会议已被对方加入，仅支持 1 对 1 共享"）
  - 界面文案情侣化："观众 #N 已加入"改为"对方已加入"、"观众 #N 已离开"改为"对方已离开"
  - 采用服务器限制方案（RoomManager.join 检查 viewers.size>0 拒绝），保留 V4 客户端多连接框架不动，降低回归风险

[Project Knowledge Summary]
- Date: 2026-08-08
- Context: Discovered by Agent while 排查"视频连不上 + 控制通道未就绪"（v1.116/v1.117）
- Category: Troubleshooting & Debugging
- Instructions:
  - 症状：同 WiFi 下视频"连接中/连接超时"、观看端远程控制提示"控制通道未就绪"。根因：共享方（host）手机开着 VPN，WebRTC 只收集到 VPN 虚拟网卡候选（如 172.19.0.1），无真实 WiFi 局域网 IP、无 srflx 公网映射 → viewer 无法可达 → P2P 建立失败，DataChannel 也随 ICE 失败。用户关闭 VPN 后重测即正常（host 候选变为 192.168.x.x + srflx）
  - 服务器 DIAG 增强定位：server.js relay 日志对 type=candidate 消息解析 candidate 字段打印候选类型（cand:host/srflx/relay + IP 片段）。判断网络问题依据：候选全是虚拟网段地址(172.19.0.1)且无 srflx → host 开 VPN；有真实局域网 IP + srflx → 正常
  - 环境事实：本次排查时 TURN 3478 公网反代已失效（https://3478-<domain>/ 返回 HTTP 530），仅 8090/8095 反代可用，request_preview 返回 URL 但反代不生效；但同 WiFi + srflx 场景无需 TURN 也能连，TURN 只影响跨网/复杂网络兜底

[Project Knowledge Summary]
- Date: 2026-08-09
- Context: Discovered by Agent while 软件全面优化（v1.118）
- Category: Troubleshooting & Debugging
- Instructions:
  - V4 host 端弱网自适应缺口：adaptToNetwork 只作用于主连接（peerConnection），而 1 对 1 模式实际视频承载在 viewerConnections，导致弱网降质完全未生效。修复：新增 collectViewerStats(viewerId)/adaptViewerNetwork(viewerId,...)，MainActivity 统计线程 host 端优先取 viewer 连接数据并调用 adaptViewerNetwork（用 firstViewerId() 取活跃 viewer）
  - 地址参数化：分享链接 base 从 BuildConfig.UPDATE_URL 派生（去 /version.json）；download-server.js getVersion(host) 的 url 改为随请求 Host 动态生成（https://${host}/...），换服务器无需改源码
  - 连接失败诊断：Listener 新增 onConnectionFailed()（restartConnection 超限时触发），MainActivity 按本机候选类型给出可操作提示（全是内网候选→不同网络；有 srflx 无 relay→中继不可用；无任何候选→检查 VPN/飞行模式）
  - viewer 连接初始带宽补 setBitrate(1.5/5/12M) 保守起步，与主连接一致，避免 viewer 刚加入即冲击带宽
  - 环境限制复述：公网大文件下载整包 curl 超时/截断（25MB 约需 >25s），但 Range 分块下载稳定（每块 10MB 完整），手机端下载器断点续传可正常完成；验证 APK 完整性用 Range 分块而非整包

[Project Knowledge Summary]
- Date: 2026-08-11
- Context: Discovered by Agent while 根治电流声 v1.128~v1.132 排查 + v1.133 重写
- Category: Troubleshooting & Debugging
- Instructions:
  - 电流声排查已排除的环节（勿重复验证）：帧字节错位（正常 1920→484）、乱序/重传（ordered=true）、丢帧（帧序号验证）、双端版本错配（0x01/0x02/0x03 帧标志）、编解码不对称（Python 复刻闭环 SNR 44.2dB）、播放采样率（双端 48000）、多通道交错（v1.132 已做音频 DataChannel 去重，WebRTCPeer.audioReceiveChannel 只保留最新通道）
  - v1.133 起音频链路重写：**彻底删除 IMA ADPCM 编解码**，系统音频改为原始 PCM16 直传（48kHz 单声道 768kbps，DataChannel ordered=true），观看方 writePcm 直接写 AudioTrack，无编解码无状态，杜绝压缩/状态恢复失真。若后续电流声仍在，方向转向 DataChannel 传输层/多通道，而非编解码
  - libwebrtc Java API 的 createAudioSource 只能采集麦克风，无法注入系统内录 PCM 到标准音视频轨（无自定义 PCM AudioSource 公开 API），系统音频必须走 DataChannel 通道，此架构决策勿再尝试改为标准轨
  - 音频诊断双端上报（/diag）：host captureStats()（capFrames/sent/peak/rms/snap）、viewer playbackStats()（playFrames/decoded/dropped/pcmBytes/peak/rms/snap/track），每 5 秒由 MainActivity startAudioDiag 上报
  - 下载服务器必须用 DOWNLOAD_BASE 环境变量启动（`DOWNLOAD_BASE=<端口>-<env域名>.monkeycode-ai.online node download-server.js`）：反代会把 Host 改写为 localhost，导致 version.json 的 url 字段返回 `https://localhost:8090/...`，手机 App 用该 url 下载时 localhost 指向手机自身必然失败；App 下载 APK 用的是 version.json 的 url 字段（UpdateChecker.downloadAndInstall），修复后需重启下载服务器生效

[Project Knowledge Summary]
- Date: 2026-08-11
- Context: Discovered by Agent while 完成 v1.134 全量优化（审查 18 项 + 发布）
- Category: Operations & Deployment
- Instructions:
  - v1.134 信令服务器必须带 DIAG_TOKEN 启动：`DIAG=1 PORT=8095 DIAG_TOKEN=<token> node server.js`，token 与 App 构建参数 `screenshare.diag.token`（在 /workspace/local.properties，git 忽略）保持一致；无 token 时 server.js 对 /diag /crash 全部返回 403（客户端上报会失败），务必双端同步配置
  - 已删除音频诊断双端上报（v1.134 删 startAudioDiag/stopAudioDiag/振幅/波形快照），/diag 路由保留但客户端不再上报；diag.log 是 v1.130-132 历史数据，勿再依赖其格式
  - 构建配置 v1.134 结论：okhttp（4.12.0）、constraintlayout、coroutines 是 material/lifecycle/WebRTC 的传递依赖，直接声明以固定版本，**不可移除**（移除后离线构建解析传递版本失败）；R8 未开启（native .so 占体积大头、WebRTC 反射风险高，收益低）
  - 候选统计已改累计计数（candCountHost/Srflx/Relay），onConnectionFailed 里 groupingBy 单次计算保留
  - v1.134 签名 APK md5=911a38ccb01e5481e51b179e61230d73，versionCode=137，commit 660450d 已推送


