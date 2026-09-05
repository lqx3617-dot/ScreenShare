package com.screenshare

import android.app.ActivityManager
import android.content.Context
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.DataChannel
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpParameters
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

/**
 * WebRTC 对等连接管理。
 *
 * 设计决策：
 * - STUN 用 Google 公共服务器（免费，不需要部署）
 * - TURN 用 Open Relay 免费节点（应对 NAT 穿透失败的情况）
 * - 编码用硬件 MediaCodec，720p 30fps，码率 2.5Mbps
 */
class WebRTCPeer(
    private val context: Context,
    private val eglBaseContext: EglBase.Context,
    private val listener: Listener
) {
    companion object {
        private const val TAG = "WebRTCPeer"
        const val SYSTEM_AUDIO_LABEL = "system-audio"
        const val CONTROL_LABEL = "control"
        const val CAMERA_TRACK_ID = "camera_track"

        // STUN：公共服务器，让两端通过公网地址映射直连（覆盖大多数家用/移动网络场景）
        private val STUN_URLS = listOf(
            "stun:stun.l.google.com:19302",
            "stun:stun1.l.google.com:19302",
            "stun:stun.cloudflare.com:3478"
        )

        // TURN 配置由 gradle.properties 注入（screenshare.turn.*），可自定义服务器
        private val TURN_URLS: List<String> by lazy {
            BuildConfig.TURN_URLS.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        }
        private const val TURN_USER = BuildConfig.TURN_USERNAME
        private const val TURN_PASS = BuildConfig.TURN_PASSWORD

        // 单例 factory：整个进程共用
        @Volatile private var singletonFactory: PeerConnectionFactory? = null

        @Volatile private var initialized = false
        @Synchronized fun ensureInitialized(appContext: Context) {
            if (initialized) return
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(appContext)
                    // 低延迟 field trials（对照 SDK 144 源码逐一核验，只保留真实存在的配置）：
                    .setFieldTrials(
                        // jitter buffer 目标延迟控制：丢包重传会让接收端 jitter 估计膨胀，
                        // 导致播放缓冲增大、画面滞后（"共享方动了观看方还卡住"）。
                        // nack_limit 默认 3、nack_count_timeout 默认 60s：短时间内重传 3 帧就
                        // 在 jitter 上加 RTT 惩罚放大缓冲。提高阈值（15/5s）让轻微丢包不放大缓冲，
                        // 严重丢包仍触发惩罚降速保流畅
                        "WebRTC-JitterEstimatorConfig/nack_limit:15,nack_count_timeout:5s," +
                            // max_frame_size_percentile: 视频播放（高动态）时 I 帧可达数百 KB，
                            // 非线性 max（kPsi=0.9999 几乎不衰减）会永久记住超大 I 帧，
                            // worst_case=max-avg 持续偏大 → jitter 估计长期偏高 → 播放缓冲 200-400ms。
                            // 0.90 百分位 max（窗口 300 帧）排除极端 I 帧与超大 P 帧（top 10%），
                            // 让 size-based jitter 项贴近真实到达抖动；弱网丢包由 nack_limit+RTT 加成兜底。
                            // 需显式声明才生效（默认 nullopt 走非线性 max）
                            "max_frame_size_percentile:0.90/" +
                            // 零播放延迟渲染：到达即渲染，配合低延迟渲染路径进一步压播放缓冲。
                            // min_pacing 为解码最小帧间隔，默认 8ms 足够，显式声明避免默认值漂移
                            "WebRTC-ZeroPlayoutDelay/min_pacing:8ms/" +
                            // 强制视频接收端 playout delay 上限（SDK144 key 经 so 字符串 min_playout_delay_ms 核验确实存在）。
                            // Java 层无法用 API 设置 playout delay，但该 field trial 可从全局覆盖 VCMTiming 的目标延迟上/下限：
                            //   UseLowLatencyRendering 激活条件是 max_playout_delay<=500ms（kLowLatencyStreamMaxPlayoutDelayThreshold），
                            //   Java 无 API 设 playout delay 导致低延迟渲染路径一直无法激活（此前 MEMORY 反复记录的瓶颈）。
                            // 强制 max=180ms 直接满足该条件 → render_time=Zero 立即渲染，播放缓冲被压到最低。
                            // max=180ms（而非 0）为解码/渲染排队留最小余量，避免瞬时抖动直接丢帧花屏；
                            // 弱网丢包仍由 nack_limit/RTT 加成 + 弱网自适应降档兜底。
                            "WebRTC-ForcePlayoutDelay/min_playout_delay_ms:0,max_playout_delay_ms:180/"
                    )
                    .createInitializationOptions()
            )
            initialized = true
        }
    }

    private fun getFactory(): PeerConnectionFactory {
        return singletonFactory ?: synchronized(this) {
            singletonFactory ?: PeerConnectionFactory.builder()
                // 屏幕共享：H264 硬件编码。v1.103 改 Baseline profile：部分中低端机型硬编不支持 High profile，
                // 若协商失败会静默回退软件编码(1080p30 软编极吃 CPU，表现为"不管几个人都一直卡")，
                // 用 Baseline 保证硬编可用；同画质码率略高但由弱网自适应补偿
                .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBaseContext, true, false))
                .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBaseContext))
                .createPeerConnectionFactory()
                .also { singletonFactory = it }
        }
    }

    interface Listener {
        fun onIceCandidate(candidate: IceCandidate)
        fun onOfferReady(sdp: SessionDescription)
        fun onAnswerReady(sdp: SessionDescription)
        fun onConnected()
        fun onDisconnected()
        /** 重连超过上限，连接彻底失败——用于 UI 给出可操作的诊断提示 */
        fun onConnectionFailed() {}
        fun onRemoteVideoTrack(videoTrack: VideoTrack)
        /** 摄像头视频轨（远端 camera_track，视频通话人脸）。与屏幕轨同连接到达，按 track id 区分 */
        fun onRemoteCameraTrack(videoTrack: VideoTrack) {}
        fun onIceGatheringComplete() {}
        /** ICE 状态变化（CHECKING/CONNECTED/FAILED...），用于 UI 显示诊断信息 */
        fun onIceState(state: String) {}
        /** V4: 多 viewer 回调（host 端） */
        fun onViewerIceCandidate(viewerId: Int, candidate: IceCandidate) {}
        fun onViewerOfferReady(viewerId: Int, sdp: SessionDescription) {}
        fun onViewerRestarted(viewerId: Int) {}
        /** viewer 主动重协商 Offer（开摄像头/麦克风时 viewer 发 Offer，host 需应答） */
        fun onViewerOfferIncoming(viewerId: Int, sdp: SessionDescription) {}
        /** host 端收到该 viewer 的远端摄像头视频轨 */
        fun onViewerCameraTrack(viewerId: Int, videoTrack: VideoTrack) {}
        /** DataChannel 事件诊断（label + 状态变化），viewer 端用于确认控制/音频通道是否建立 */
        fun onDataChannelInfo(info: String) {}
    }

    private var peerConnection: PeerConnection? = null
    private var localVideoTrack: VideoTrack? = null
    private var localAudioTrack: AudioTrack? = null
    private var videoCapturer: VideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    // 屏幕采集 VideoSource：存成员引用以便 disconnect 统一释放
    //（PC.dispose 不负责独立的 VideoSource，漏释放则每次开启共享泄漏一个 native 源）
    private var videoSource: VideoSource? = null
    // 关键帧请求短时防抖：多 viewer 同时恢复时 requestKeyFrame 可能连续触发，
    // changeCaptureFormat 会重启采集器造成画面闪断，因此 500ms 内只允许触发一次。
    private var lastKeyFrameAt = 0L
    private var disposed = false

    // V4: 多客户端——共享方(host)为每个 viewer 维护一条独立 PeerConnection。
    // 采集/视频源/音频源共享一份（localVideoTrack 可同时 addTrack 到多条连接），
    // 每条连接独立的 SDP/ICE 协商与 DataChannel。
    private data class ViewerConnection(
        val pc: PeerConnection,
        var videoSender: org.webrtc.RtpSender? = null,
        var micSender: org.webrtc.RtpSender? = null,
        var systemAudioChannel: DataChannel? = null,
        var controlChannel: DataChannel? = null,
        // 防重入：该连接上是否有未完成的 Offer 协商（避免并发 createOffer 竞态导致协商失败）
        val negotiating: AtomicBoolean = AtomicBoolean(false),
        // 该连接是否已请求过关键帧。首次 CONNECTED 后置位；后续 ICE 抖动/COMPLETED 不再触发，
        // 避免 changeCaptureFormat 反复重启采集器打断帧流（短剧等低动态场景尤其致命）。
        var keyFrameRequested: Boolean = false
    )
    private val viewerConnections = ConcurrentHashMap<Int, ViewerConnection>()
    // viewer 断线重建计数（防持续弱网下无限重建）与上限。
    // ICE/观察者回调在 WebRTC native 线程执行，重建/移除会被该线程触发，需并发安全。
    private val viewerRestartCounts = ConcurrentHashMap<Int, Int>()
    // 主线程 Handler：WebRTC 回调线程内触发的 PC close/dispose 与控制消息处理
    // 统一收敛到主线程，避免在回调线程释放正在回调的 native 对象导致 use-after-free 崩溃
    private val mainHandler = Handler(Looper.getMainLooper())
    private val viewerMaxRestarts = 5
    // 编码负载自适应（v1.120）：开视频软件等动态画面时硬编跟不上，主动降采集分辨率保帧率。
    // 与弱网档位(curAdaptLevel)独立，最终采集档位取两者的较大值
    private var encLoadDown = false
    private var encLoadSamples = 0       // 编码瓶颈持续采样计数（触发降质）
    // 观看端掉帧反馈（stream-stall）：观看端检测到接收管线持续掉帧时经控制通道通知共享方，
    // 共享方按编码瓶颈同路径立即降档（MAINTAIN_FRAMERATE + 降分辨率），反馈恢复后才允许回升。
    // 解决共享方出向统计看不到的"接收端解码/渲染瓶颈"与"帧到达过晚被丢"两类掉帧。
    @Volatile private var viewerStallActive = false
    private var encRecoverSamples = 0    // 编码恢复持续采样计数（回升 1080p）
    // 编码帧率统计缺失连续采样计数（v1.240）：部分机型 outbound-rtp.framesPerSecond 恒不上报，
    // 恢复判定无法依赖帧率证据，改用"连续无瓶颈证据"缓慢恢复，防止统计缺失永久锁死降档
    private var encNoStatSamples = 0

    // 系统音频 DataChannel（观看方接收）
    private var systemAudioListener: ((ByteArray) -> Unit)? = null
    // v1.132：观看方已注册的音频接收通道引用（收到新音频通道时先释放旧的，防止多通道交错播放导致电流声）
    private var audioReceiveChannel: DataChannel? = null

    // 控制 DataChannel（观看方 → 共享方下发指令，如切换帧率）
    private var controlChannel: DataChannel? = null
    private var controlListener: ((String) -> Unit)? = null

    // 视频发送器（切换帧率时更新编码参数）
    private var videoSender: org.webrtc.RtpSender? = null

    // 麦克风语音（会议内双向对讲）：标准 WebRTC 音频轨道
    private var micAudioSource: AudioSource? = null
    private var micSender: org.webrtc.RtpSender? = null

    // 视频通话摄像头（camera_track）：前端摄像头实时采集，人脸画面。与屏幕轨（screen_track）并存，
    // host 端挂到每个 viewer 连接、viewer 端挂到主连接；对端按 track id 区分渲染到 PIP 小窗
    private var cameraCapturer: CameraVideoCapturer? = null
    private var cameraVideoSource: VideoSource? = null
    private var cameraVideoTrack: VideoTrack? = null
    private var cameraSurfaceTextureHelper: SurfaceTextureHelper? = null
    // 当前摄像头设备名（用于前后切换判断）
    private var cameraDeviceName: String? = null
    // host 端：每个 viewer 连接的摄像头发送器（同摄像头轨可 addTrack 到多条连接）
    private val cameraViewerSenders = mutableMapOf<Int, org.webrtc.RtpSender>()
    // viewer 端：主连接的摄像头发送器
    private var cameraSender: org.webrtc.RtpSender? = null
    // 摄像头弱网自适应：最近一次码率/帧率上限（防重复设置）
    private var lastCameraBitrateCap = 0
    private var lastCameraFpsCap = 0

    // ===== V3.1: WebRTC 连接状态管理 =====
    enum class ConnectionStatus { CONNECTING, CONNECTED, RECONNECTING, FAILED }

    /** V3.2: 网络质量评分——由丢包率(loss 0~100%)与 RTT(ms) 综合得出 0~100 分 */
    data class NetworkQuality(val loss: Double, val rtt: Int, val score: Int)

    /**
     * V3.2: 计算网络质量评分。
     * 基准 100 分，丢包率每 1% 扣 5 分，RTT>200ms 额外扣 20 分，最低 0 分。
     */
    fun calculateQuality(loss: Double, rtt: Int): Int {
        var score = 100
        score -= (loss * 5).toInt()
        if (rtt > 200) score -= 20
        return score.coerceAtLeast(0)
    }

    /** 当前连接状态（观察方可用 getConnectionStatus() 读取，ICE 状态变化时更新） */
    @Volatile private var connectionStatus = ConnectionStatus.CONNECTING

    /** ICE restart 进行中标志，防止并发多次触发 */
    @Volatile private var restartInFlight = false

    // V3.2: 重连保护——最多尝试 5 次 ICE restart，防止弱网下无限重协商耗电
    @Volatile private var reconnectCount = 0
    private val maxReconnectAttempts = 5

    fun getConnectionStatus(): ConnectionStatus = connectionStatus

    init {
        ensureInitialized(context.applicationContext)
    }

    private val pendingCandidates = mutableListOf<IceCandidate>()
    // v1.53: Trickle ICE——SDP 就绪立即发送（不等 gathering），候选随后增量即时发送，
    // 由 MainActivity 负责增量转发；pendingCandidates 缓冲在 remoteDescription 就绪前到达的候选

    // 新版 API：ICE 服务器直接用 URL 列表（含 TURN 凭据用 ":user:pass" 或通过 url 携带）
    private val iceServers: List<PeerConnection.IceServer> by lazy {
        STUN_URLS.map { PeerConnection.IceServer.builder(it).createIceServer() } +
            TURN_URLS.map {
                val builder = PeerConnection.IceServer.builder(it)
                if (TURN_USER.isNotEmpty()) {
                    builder.setUsername(TURN_USER).setPassword(TURN_PASS)
                }
                builder.createIceServer()
            }
    }

    private val pcObserver = object : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState?) {
            Log.d(TAG, "Signaling: $state")
        }
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
            Log.d(TAG, "ICE Connection: $state")
            listener.onIceState("ICE: $state")
            when (state) {
                PeerConnection.IceConnectionState.CHECKING -> {
                    connectionStatus = ConnectionStatus.CONNECTING
                }
                PeerConnection.IceConnectionState.CONNECTED,
                PeerConnection.IceConnectionState.COMPLETED -> {
                    connectionStatus = ConnectionStatus.CONNECTED
                    // V3.2: 连接成功后重置重连计数
                    reconnectCount = 0
                    listener.onConnected()
                }
                PeerConnection.IceConnectionState.DISCONNECTED -> {
                    connectionStatus = ConnectionStatus.RECONNECTING
                    listener.onDisconnected()
                    // V3.1: 断网自动恢复——发起 ICE restart 重新建立数据通道
                    // 注意: DISCONNECTED 时 WebRTC 会先自行尝试恢复，收到 FAILED 再强制 restart
                    AppLogger.network("ICE DISCONNECTED, awaiting auto recovery")
                }
                PeerConnection.IceConnectionState.FAILED -> {
                    connectionStatus = ConnectionStatus.FAILED
                    listener.onDisconnected()
                    Log.w(TAG, "ICE FAILED，发起 ICE restart 尝试自动恢复")
                    restartConnection()
                }
                else -> {}
            }
        }
        override fun onIceConnectionReceivingChange(receiving: Boolean) {
            if (!receiving) {
                AppLogger.network("ICE receiving stopped, restarting")
                // 长时间收不到数据视为连接假死，尝试 ICE restart 恢复
                if (connectionStatus == ConnectionStatus.CONNECTED) {
                    connectionStatus = ConnectionStatus.RECONNECTING
                    restartConnection()
                }
            }
        }
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
            Log.d(TAG, "ICE Gathering: $state")
            listener.onIceState("Gathering: $state")
            if (state == PeerConnection.IceGatheringState.COMPLETE) {
                listener.onIceGatheringComplete()
            }
        }
        override fun onIceCandidate(candidate: IceCandidate) {
            // 诊断：记录候选类型（host/srflx/relay），用于定位 P2P 卡在哪个阶段
            val type = when {
                candidate.sdp.contains("typ host") -> "host(内网直连)"
                candidate.sdp.contains("typ srflx") -> "srflx(STUN公网映射)"
                candidate.sdp.contains("typ relay") -> "relay(TURN中继)"
                else -> "unknown"
            }
            Log.d(TAG, "onIceCandidate: mid=${candidate.sdpMid} index=${candidate.sdpMLineIndex} type=$type")
            listener.onIceCandidate(candidate)
        }
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}

        // 注意：不用废弃的 onAddStream——远端轨统一由下方 onAddTrack/onTrack 回调处理，
        // 两者都实现会导致同一轨道被通知两次（重复渲染/重复注册）
        // 但 SDK 的 PeerConnection.Observer.onAddStream 是抽象方法必须实现，此处留空（兼容旧接口签名）
        override fun onAddStream(stream: MediaStream?) {}
        override fun onRemoveStream(stream: MediaStream?) {}
        override fun onDataChannel(channel: org.webrtc.DataChannel?) {
            Log.d(TAG, "onDataChannel: ${channel?.label()}")
            if (channel?.label() == SYSTEM_AUDIO_LABEL) {
                // v1.132：多路音频通道去重——重复收到音频通道（重连/多连接残留）时先释放旧通道，
                // 只保留最新一路，避免多通道帧交错/重复播放导致电流声
                val old = audioReceiveChannel
                if (old != null && old != channel) {
                    try { old.dispose() } catch (_: Throwable) {}
                }
                audioReceiveChannel = channel
                channel.registerObserver(object : DataChannel.Observer {
                    override fun onBufferedAmountChange(previousAmount: Long) {}
                    override fun onStateChange() {
                        listener.onDataChannelInfo("音频通道: ${channel.state()}")
                    }
                    override fun onMessage(buffer: DataChannel.Buffer) {
                        if (buffer.binary) {
                            try {
                                val data = ByteArray(buffer.data.remaining())
                                buffer.data.get(data)
                                systemAudioListener?.invoke(data)
                            } catch (t: Throwable) {
                                // 音频解码异常不抛到 WebRTC 回调线程（native 线程异常会导致进程崩溃且不触发
                                // UncaughtExceptionHandler），丢弃该帧继续
                                Log.w(TAG, "系统音频处理异常: ${t.message}")
                            }
                        }
                    }
                })
                listener.onDataChannelInfo("收到音频通道 (${channel.state()})")
            } else if (channel?.label() == CONTROL_LABEL) {
                // 观看方侧：保存控制通道引用（用于向共享方发送指令），并接收可能的回应
                controlChannel = channel
                registerControlObserver(channel)
                listener.onDataChannelInfo("收到控制通道 (${channel.state()})")
            } else {
                listener.onDataChannelInfo("收到未知通道: ${channel?.label()}")
            }
        }
        override fun onRenegotiationNeeded() {}
        override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
            val track = receiver?.track() as? VideoTrack
            if (track != null) {
                if (track.id() == CAMERA_TRACK_ID) {
                    listener.onRemoteCameraTrack(track)
                } else {
                    listener.onRemoteVideoTrack(track)
                }
            }
        }
        override fun onTrack(track: RtpTransceiver?) {
            val vt = track?.receiver?.track() as? VideoTrack
            if (vt != null) {
                if (vt.id() == CAMERA_TRACK_ID) {
                    listener.onRemoteCameraTrack(vt)
                } else {
                    listener.onRemoteVideoTrack(vt)
                }
            }
        }
    }

    fun createPeerConnection(): PeerConnection? {
        val factory = getFactory()
        val config = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            keyType = PeerConnection.KeyType.ECDSA
        }
        peerConnection = factory.createPeerConnection(config, pcObserver)
        return peerConnection
    }

    // ==================== V4: 多 viewer 连接管理 ====================

    /** 当前活跃 viewer 的 id（1 对 1 模式下取唯一 viewer；无则返回 0） */
    fun firstViewerId(): Int = viewerConnections.keys.firstOrNull() ?: 0

    /**
     * 为指定 viewer 创建独立 PeerConnection 并挂载共享视频轨道。
     * host 收到 onViewerJoined(viewerId) 时调用。
     * @return 新建的 PeerConnection；失败返回 null
     */
    fun createViewerConnection(viewerId: Int): PeerConnection? {
        if (disposed) return null
        if (viewerConnections.containsKey(viewerId)) return viewerConnections[viewerId]?.pc
        val factory = getFactory()
        val config = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            keyType = PeerConnection.KeyType.ECDSA
        }
        val observer = object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onRenegotiationNeeded() {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                AppLogger.webrtc("viewer#$viewerId ICE: $state")
                when (state) {
                    PeerConnection.IceConnectionState.CONNECTED,
                    PeerConnection.IceConnectionState.COMPLETED -> {
                        AppLogger.webrtc("viewer#$viewerId connected")
                        // 每条连接只在首次连接建立后请求一次关键帧。后续 ICE 状态抖动/COMPLETED
                        // 不再触发，避免 changeCaptureFormat 反复重启采集器打断帧流（短剧等低动态画面尤其致命）。
                        val conn = viewerConnections[viewerId]
                        if (conn != null && !conn.keyFrameRequested) {
                            conn.keyFrameRequested = true
                            requestKeyFrame()
                        }
                    }
                    PeerConnection.IceConnectionState.FAILED -> {
                        AppLogger.network("viewer#$viewerId FAILED, restarting")
                        restartViewer(viewerId)
                    }
                    else -> {}
                }
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) {
                if (!receiving) {
                    AppLogger.network("viewer#$viewerId receiving stopped, restarting")
                    restartViewer(viewerId)
                }
            }
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidate(candidate: IceCandidate) {
                // 该 viewer 的候选：带 viewerId 转发给对端
                listener.onViewerIceCandidate(viewerId, candidate)
            }
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            @Deprecated("Deprecated in Java")
            override fun onAddStream(stream: MediaStream?) {}
            @Deprecated("Deprecated in Java")
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                val track = receiver?.track() as? VideoTrack
                if (track != null) {
                    // host 端 viewer 连接收的远端视频轨即 viewer 的摄像头画面
                    listener.onViewerCameraTrack(viewerId, track)
                }
            }
            override fun onTrack(transceiver: RtpTransceiver?) {
                val vt = transceiver?.receiver?.track() as? VideoTrack
                if (vt != null) {
                    listener.onViewerCameraTrack(viewerId, vt)
                }
            }
            override fun onDataChannel(channel: org.webrtc.DataChannel?) {
                handleViewerDataChannel(viewerId, channel)
            }
        }
        val pc = factory.createPeerConnection(config, observer) ?: return null
        val conn = ViewerConnection(pc)
        viewerConnections[viewerId] = conn
        // 挂载共享视频轨道（host 采集已启动才有轨道）
        localVideoTrack?.let { track ->
            val rtp = pc.addTrack(track)
            if (rtp == null) {
                Log.e(TAG, "viewer#$viewerId addTrack 失败")
            } else {
                conn.videoSender = rtp
                val params = rtp.parameters
                params.encodings?.firstOrNull()?.let { enc ->
                    enc.maxBitrateBps = 12_000_000
                    enc.minBitrateBps = 1_000_000
                    enc.maxFramerate = 30
                    enc.networkPriority = 4
                    enc.bitratePriority = 4.0
                }
                try {
                    params.degradationPreference = RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION
                } catch (t: Throwable) {}
                rtp.parameters = params
                // 初始带宽 4M 起步（同主连接），画面更快清晰；弱网由拥塞控制兜底降档
                try {
                    pc.setBitrate(1_000_000, 4_000_000, 12_000_000)
                    Log.d(TAG, "viewer#$viewerId 初始带宽 1/4/12 Mbps")
                } catch (t: Throwable) {
                    Log.w(TAG, "viewer#$viewerId setBitrate 失败: ${t.message}")
                }
            }
        }
        // 麦克风已开启时，新 viewer 连接同步挂载麦克风音频轨（V4 下必须挂到 viewer 连接才能协商到对端）
        localAudioTrack?.let { mic ->
            val ms = pc.addTrack(mic)
            if (ms == null) {
                Log.w(TAG, "viewer#$viewerId 挂载麦克风轨失败")
            } else {
                conn.micSender = ms
            }
        }
        // 视频通话摄像头已开启时，新 viewer 连接同步挂载摄像头轨
        cameraVideoTrack?.let { cam ->
            val cs = pc.addTrack(cam)
            if (cs == null) {
                Log.w(TAG, "viewer#$viewerId 挂载摄像头轨失败")
            } else {
                cameraViewerSenders[viewerId] = cs
            }
        }
        AppLogger.webrtc("viewer#$viewerId connection created")
        // V4: host 主连接仅作采集底座不参与协商，控制/音频 DataChannel 必须随每个
        // viewer 连接的 Offer 携带（createDataChannel 在 createOffer 前调用，随 SDP 协商），
        // 否则 viewer 端 onDataChannel 收不到通道，控制通道与系统音频均不可用。
        createViewerDataChannels(viewerId)
        return pc
    }

    /** host 端：为 viewer 连接创建控制 + 系统音频 DataChannel（offerer 侧，viewer 端 onDataChannel 接收） */
    private fun createViewerDataChannels(viewerId: Int) {
        val conn = viewerConnections[viewerId] ?: return
        val pc = conn.pc
        try {
            val ctrlInit = DataChannel.Init().apply {
                ordered = false
                maxRetransmits = 1
            }
            val ctrlDc = pc.createDataChannel(CONTROL_LABEL, ctrlInit)
            conn.controlChannel = ctrlDc
            ctrlDc.registerObserver(object : DataChannel.Observer {
                override fun onBufferedAmountChange(previousAmount: Long) {}
                override fun onStateChange() {}
                override fun onMessage(buffer: DataChannel.Buffer) {
                    if (!buffer.binary) {
                        val data = ByteArray(buffer.data.remaining())
                        buffer.data.get(data)
                        dispatchControlMessage(String(data))
                    }
                }
            })
        } catch (t: Throwable) {
            Log.e(TAG, "viewer#$viewerId 创建控制 DataChannel 失败: ${t.message}")
        }
        try {
            val audioInit = DataChannel.Init().apply {
                // v1.133 起为原始 PCM 直传（无状态），v1.134 改无序+重传1次：
                // 可靠有序在弱网丢包时会触发重传与队头阻塞，抬高音频延迟；无序最多重传 1 次，
                // 偶发 20ms 掉帧/乱序因无压缩状态不会产生失真
                ordered = false
                maxRetransmits = 1
            }
            val audioDc = pc.createDataChannel(SYSTEM_AUDIO_LABEL, audioInit)
            conn.systemAudioChannel = audioDc
        } catch (t: Throwable) {
            Log.e(TAG, "viewer#$viewerId 创建音频 DataChannel 失败: ${t.message}")
        }
    }

    /** 为该 viewer 创建 Offer 并发起协商（host 端） */
    fun createOfferFor(viewerId: Int) {
        val conn = viewerConnections[viewerId] ?: return
        // 防重入：该连接已有未完成的 Offer 协商时跳过本次（createOffer 并发调用会互相干扰导致协商失败）
        if (!conn.negotiating.compareAndSet(false, true)) {
            Log.d(TAG, "viewer#$viewerId 正在协商中，跳过重复 createOffer")
            return
        }
        val pc = conn.pc
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        }
        pc.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                pc.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        conn.negotiating.set(false)
                        val ld = pc.localDescription
                        listener.onViewerOfferReady(viewerId, ld ?: sdp)
                    }
                    override fun onSetFailure(error: String?) {
                        conn.negotiating.set(false)
                        Log.e(TAG, "viewer#$viewerId setLocalDescription 失败: $error")
                    }
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onCreateFailure(p0: String?) {}
                }, sdp)
            }
            override fun onCreateFailure(error: String?) {
                conn.negotiating.set(false)
                Log.e(TAG, "viewer#$viewerId 创建 Offer 失败: $error")
            }
            override fun onSetSuccess() {}
            override fun onSetFailure(error: String?) {}
        }, constraints)
    }

    /** 处理该 viewer 的 Answer（host 端收到后完成连接） */
    fun handleViewerAnswer(viewerId: Int, sdp: SessionDescription, candidates: List<IceCandidate>) {
        val conn = viewerConnections[viewerId] ?: return
        val pc = conn.pc
        pc.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                pendingViewerCandidates[viewerId]?.forEach { pc.addIceCandidate(it) }
                pendingViewerCandidates.remove(viewerId)
                AppLogger.webrtc("viewer#$viewerId answer applied")
                // 新 viewer 完成 Answer 后立即触发关键帧，让观看端尽快拿到 I 帧出画面
                requestKeyFrame()
            }
            override fun onSetFailure(error: String?) { Log.e(TAG, "viewer#$viewerId setRemoteDescription 失败: $error") }
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onCreateFailure(p0: String?) {}
        }, sdp)
        candidates.forEach { pc.addIceCandidate(it) }
    }

    /**
     * 处理 viewer 主动发起的重协商 Offer（viewer 开摄像头/麦克风时触发）。
     * host 端为该 viewer 连接应用远端描述并生成 Answer，交回 MainActivity 转发。
     */
    fun handleViewerOffer(viewerId: Int, sdp: SessionDescription, candidates: List<IceCandidate>) {
        val conn = viewerConnections[viewerId] ?: run {
            Log.e(TAG, "handleViewerOffer: viewer#$viewerId 连接不存在")
            return
        }
        val pc = conn.pc
        pc.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                // 应用远端描述后自动生成 Answer（Trickle ICE：SDP 先回，候选随后增量）
                val constraints = MediaConstraints().apply {
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
                }
                pc.createAnswer(object : SdpObserver {
                    override fun onCreateSuccess(answer: SessionDescription) {
                        pc.setLocalDescription(object : SdpObserver {
                            override fun onSetSuccess() {
                                val ld = pc.localDescription
                                listener.onViewerOfferIncoming(viewerId, ld ?: answer)
                            }
                            override fun onSetFailure(error: String?) { Log.e(TAG, "viewer#$viewerId answer setLocalDescription 失败: $error") }
                            override fun onCreateSuccess(p0: SessionDescription?) {}
                            override fun onCreateFailure(p0: String?) {}
                        }, answer)
                    }
                    override fun onCreateFailure(error: String?) { Log.e(TAG, "viewer#$viewerId createAnswer 失败: $error") }
                    override fun onSetSuccess() {}
                    override fun onSetFailure(error: String?) {}
                }, MediaConstraints())
            }
            override fun onSetFailure(error: String?) { Log.e(TAG, "viewer#$viewerId 重协商 setRemoteDescription 失败: $error") }
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onCreateFailure(p0: String?) {}
        }, sdp)
        candidates.forEach { pc.addIceCandidate(it) }
    }
    private val pendingViewerCandidates = mutableMapOf<Int, MutableList<IceCandidate>>()

    fun addViewerIce(viewerId: Int, candidate: IceCandidate) {
        val conn = viewerConnections[viewerId] ?: return
        val pc = conn.pc
        if (pc.remoteDescription == null) {
            synchronized(pendingViewerCandidates) {
                pendingViewerCandidates.getOrPut(viewerId) { mutableListOf() }.add(candidate)
            }
        } else {
            pc.addIceCandidate(candidate)
        }
    }

    /** 移除指定 viewer 连接（host 端，viewer 离开时调用）。
     * map 移除同步执行（保证后续 createViewerConnection 立即可重建同 viewerId），
     * PC close/dispose 延迟到主线程——本方法可能由 ICE 回调线程触发（restartViewer/FAILED），
     * 在回调线程内释放正在回调的 PC 会 use-after-free 崩溃。 */
    fun removeViewer(viewerId: Int) {
        val conn = viewerConnections.remove(viewerId) ?: return
        pendingViewerCandidates.remove(viewerId)
        viewerRestartCounts.remove(viewerId)
        mainHandler.post {
            try { conn.controlChannel?.dispose() } catch (_: Throwable) {}
            try { conn.systemAudioChannel?.dispose() } catch (_: Throwable) {}
            try {
                conn.pc.close()
                conn.pc.dispose()
            } catch (t: Throwable) { Log.w(TAG, "viewer#$viewerId 清理失败: ${t.message}") }
        }
        AppLogger.webrtc("viewer#$viewerId removed (dispose on main)")
    }

    /** 该 viewer 断线重连（ICE restart 简化版：直接重建连接，host 端）。
     * 带每 viewer 重连上限保护（同主连接 restartConnection 策略），
     * 避免持续弱网下无限重建连接耗尽电量、画面卡死。 */
    private fun restartViewer(viewerId: Int) {
        if (viewerConnections[viewerId] == null) return
        val count = viewerRestartCounts.getOrPut(viewerId) { 0 } + 1
        if (count > viewerMaxRestarts) {
            AppLogger.webrtc("viewer#$viewerId 重连超过${viewerMaxRestarts}次，放弃")
            removeViewer(viewerId)
            listener.onConnectionFailed()
            return
        }
        viewerRestartCounts[viewerId] = count
        AppLogger.network("viewer#$viewerId restarting connection ($count/$viewerMaxRestarts)")
        removeViewer(viewerId)
        if (createViewerConnection(viewerId) != null) {
            listener.onViewerRestarted(viewerId)
        }
    }

    private fun handleViewerDataChannel(viewerId: Int, channel: org.webrtc.DataChannel?) {
        if (channel == null) return
        val conn = viewerConnections[viewerId] ?: return
        when (channel.label()) {
            SYSTEM_AUDIO_LABEL -> {
                conn.systemAudioChannel = channel
                channel.registerObserver(object : DataChannel.Observer {
                    override fun onBufferedAmountChange(previousAmount: Long) {}
                    override fun onStateChange() {}
                    override fun onMessage(buffer: DataChannel.Buffer) {
                        if (buffer.binary) {
                            try {
                                val data = ByteArray(buffer.data.remaining())
                                buffer.data.get(data)
                                systemAudioListener?.invoke(data)
                            } catch (t: Throwable) {
                                // 音频解码异常不抛到 WebRTC 回调线程（native 线程异常会导致进程崩溃且不触发
                                // UncaughtExceptionHandler），丢弃该帧继续
                                Log.w(TAG, "viewer#$viewerId 系统音频处理异常: ${t.message}")
                            }
                        }
                    }
                })
            }
            CONTROL_LABEL -> {
                conn.controlChannel = channel
                channel.registerObserver(object : DataChannel.Observer {
                    override fun onBufferedAmountChange(previousAmount: Long) {}
                    override fun onStateChange() {}
                    override fun onMessage(buffer: DataChannel.Buffer) {
                        if (!buffer.binary) {
                            try {
                                val data = ByteArray(buffer.data.remaining())
                                buffer.data.get(data)
                                dispatchControlMessage(String(data))
                            } catch (t: Throwable) {
                                Log.w(TAG, "viewer#$viewerId 控制指令处理异常: ${t.message}")
                            }
                        }
                    }
                })
            }
        }
    }

    // ==================== 麦克风语音（会议内双向对讲） ====================

    /**
     * 开启麦克风：创建 WebRTC 标准音频轨道（Opus + AEC + 降噪）并加入连接。
     * 成功后调用方需触发 [renegotiate] 让对端收到含音频轨道的新 Offer。
     * @return true 表示轨道已添加；false 表示失败（调用方应提示用户）。
     */
    fun startMicAudio(negotiate: Boolean = true): Boolean {
        if (disposed) return false
        val pc = peerConnection ?: return false
        if (micAudioSource != null) return true
        return try {
            val factory = getFactory()
            val constraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            }
            val source = factory.createAudioSource(constraints)
            val track = factory.createAudioTrack("mic_track", source)
            val sender = pc.addTrack(track)
            if (sender == null) {
                source.dispose()
                track.dispose()
                Log.e(TAG, "addTrack 麦克风失败")
                return false
            }
            micAudioSource = source
            localAudioTrack = track
            micSender = sender
            track.setEnabled(true)
            // V4: host 主连接不协商 SDP，麦克风轨必须同时挂到每个 viewer 连接
            // （同一 AudioTrack 可 addTrack 到多个 PeerConnection）
            viewerConnections.forEach { (vid, conn) ->
                val vs = conn.pc.addTrack(track)
                if (vs != null) {
                    conn.micSender = vs
                    if (negotiate) createOfferFor(vid)
                } else {
                    Log.w(TAG, "viewer#$vid 挂载麦克风轨失败")
                }
            }
            Log.d(TAG, "麦克风音频轨道已添加")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "启动麦克风失败: ${t.message}")
            false
        }
    }

    /** 停止麦克风：从主连接与所有 viewer 连接移除轨道并释放音频源 */
    fun stopMicAudio(negotiate: Boolean = true) {
        if (disposed) return
        val pc = peerConnection
        micSender?.let { s ->
            try { pc?.removeTrack(s) } catch (t: Throwable) {
                Log.w(TAG, "移除麦克风轨道失败: ${t.message}")
            }
        }
        micSender = null
        viewerConnections.forEach { (vid, conn) ->
            conn.micSender?.let { s ->
                try { conn.pc.removeTrack(s) } catch (t: Throwable) {
                    Log.w(TAG, "viewer#$vid 移除麦克风轨道失败: ${t.message}")
                }
            }
            conn.micSender = null
            if (negotiate) createOfferFor(vid)
        }
        // 释放顺序：先 track 后 source（track 持有对 native AudioSource 的引用，反序会悬空崩溃）
        localAudioTrack?.dispose()
        localAudioTrack = null
        micAudioSource?.dispose()
        micAudioSource = null
        Log.d(TAG, "麦克风音频已停止")
    }

    /** 静音/取消静音（直接启用/停用轨道，不触发重协商） */
    fun setMicMuted(muted: Boolean) {
        localAudioTrack?.setEnabled(!muted)
        Log.d(TAG, "麦克风${if (muted) "静音" else "取消静音"}")
    }

    /** 是否已开启麦克风 */
    fun isMicOn(): Boolean = micAudioSource != null

    // ==================== 视频通话摄像头（实时人脸画面） ====================

    /**
     * 开启视频通话摄像头：用 Camera2 采集前端摄像头 → camera_track。
     * host 端挂到每个 viewer 连接；viewer 端挂到主连接。
     * @param negotiate true=挂载后立即对各 viewer 重协商（独立开启时用）；false=仅挂载，
     *   由调用方在摄像头+麦克风都挂好后统一触发一次重协商（避免多次 Offer 竞态导致对端协商失败）
     * @return true 表示摄像头已启动；false 表示失败（调用方应提示用户）
     */
    fun startCameraVideo(negotiate: Boolean = true): Boolean {
        if (disposed) return false
        if (cameraVideoTrack != null) return true
        return try {
            val factory = getFactory()
            val enumerator = Camera2Enumerator(context)
            // 视频通话默认用前置摄像头（对方面向自己）；无前置则退回第一个可用
            val deviceName = (enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) })
                ?: enumerator.deviceNames.firstOrNull()
                ?: return false
            cameraSurfaceTextureHelper = SurfaceTextureHelper.create("CameraVideo", eglBaseContext)
            val source = factory.createVideoSource(false)
            val capturer = enumerator.createCapturer(deviceName, null)
            capturer.initialize(cameraSurfaceTextureHelper, context, source.capturerObserver)
            // 640x480 足够人脸通话清晰度且带宽友好（屏幕共享已是 1080p 主码流）
            capturer.startCapture(640, 480, 30)
            val track = factory.createVideoTrack(CAMERA_TRACK_ID, source)
            track.setEnabled(true)
            cameraCapturer = capturer
            cameraVideoSource = source
            cameraVideoTrack = track
            cameraDeviceName = deviceName
            // host 端：摄像头轨挂到每个 viewer 连接（同一轨可挂多条连接）
            if (viewerConnections.isNotEmpty()) {
                viewerConnections.forEach { (vid, conn) ->
                    val sender = conn.pc.addTrack(track)
                    if (sender != null) {
                        cameraViewerSenders[vid] = sender
                        if (negotiate) createOfferFor(vid)
                    } else {
                        Log.w(TAG, "viewer#$vid 挂载摄像头轨失败")
                    }
                }
            } else {
                // viewer 端：摄像头轨挂到主连接，随后由调用方 renegotiate() 发起 Offer
                val pc = peerConnection
                val sender = pc?.addTrack(track)
                if (sender == null) {
                    Log.e(TAG, "addTrack 摄像头失败")
                    stopCameraVideo()
                    return false
                }
                cameraSender = sender
            }
            Log.d(TAG, "视频通话摄像头已启动: $deviceName 640x480@30")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "启动摄像头失败: ${t.message}")
            stopCameraVideo()
            false
        }
    }

    /** 停止视频通话摄像头：从所有连接移除轨道并释放采集资源 */
    fun stopCameraVideo() {
        cameraSender?.let { s ->
            try { peerConnection?.removeTrack(s) } catch (t: Throwable) {
                Log.w(TAG, "移除摄像头轨道失败: ${t.message}")
            }
        }
        cameraSender = null
        viewerConnections.forEach { (vid, conn) ->
            cameraViewerSenders.remove(vid)?.let { s ->
                try { conn.pc.removeTrack(s) } catch (t: Throwable) {
                    Log.w(TAG, "viewer#$vid 移除摄像头轨道失败: ${t.message}")
                }
            }
        }
        cameraViewerSenders.clear()
        // 释放顺序（libwebrtc 约定）：先停采集→dispose capturer→track→source→helper。
        // 原顺序（track/source 先于 stopCapture）会让采集器向已释放的 VideoSource 推帧，触发 use-after-free 崩溃
        try { cameraCapturer?.stopCapture() } catch (t: Throwable) {
            Log.w(TAG, "摄像头 stopCapture 失败: ${t.message}")
        }
        try { cameraCapturer?.dispose() } catch (t: Throwable) {
            Log.w(TAG, "摄像头 capturer dispose 失败: ${t.message}")
        }
        cameraCapturer = null
        try { cameraVideoTrack?.dispose() } catch (t: Throwable) {}
        cameraVideoTrack = null
        try { cameraVideoSource?.dispose() } catch (t: Throwable) {}
        cameraVideoSource = null
        cameraDeviceName = null
        try { cameraSurfaceTextureHelper?.dispose() } catch (t: Throwable) {}
        cameraSurfaceTextureHelper = null
        Log.d(TAG, "视频通话摄像头已停止")
    }

    /** 是否已开启视频通话摄像头 */
    fun isCameraOn(): Boolean = cameraVideoTrack != null

    /** 本端摄像头轨道（供 MainActivity 本地预览渲染；null=未开启） */
    fun cameraVideoTrack(): VideoTrack? = cameraVideoTrack

    /** 当前摄像头采集分辨率档位（0=480p, 1=720p） */
    private var cameraQualityLevel = 0

    /**
     * 切换视频通话画质档位：480p(640x480@30) 与 720p(1280x720@30) 之间切换。
     * changeCaptureFormat 热切换采集分辨率，不影响发送器与会话（无重协商）。
     * @return true=切到 720p, false=切回 480p
     */
    fun toggleCameraQuality(): Boolean {
        val capturer = cameraCapturer ?: return cameraQualityLevel == 1
        if (disposed) return cameraQualityLevel == 1
        return try {
            cameraQualityLevel = if (cameraQualityLevel == 0) 1 else 0
            if (cameraQualityLevel == 1) {
                capturer.changeCaptureFormat(1280, 720, 30)
                Log.d(TAG, "摄像头画质切换: 720p")
            } else {
                capturer.changeCaptureFormat(640, 480, 30)
                Log.d(TAG, "摄像头画质切换: 480p")
            }
            // 切档后重置弱网码率上限缓存，让 applyCameraAdaptation 按新档位重新设置
            lastCameraBitrateCap = 0
            applyCameraAdaptation()
            cameraQualityLevel == 1
        } catch (t: Throwable) {
            Log.e(TAG, "切换摄像头画质失败: ${t.message}")
            cameraQualityLevel = if (cameraQualityLevel == 0) 1 else 0
            false
        }
    }

    /** 当前摄像头画质档位是否为 720p */
    fun isCamera720p(): Boolean = cameraQualityLevel == 1

    /** 是否当前使用前置摄像头 */
    fun isUsingFrontCamera(): Boolean {
        if (cameraDeviceName == null) return true
        val enumerator = Camera2Enumerator(context)
        return enumerator.isFrontFacing(cameraDeviceName!!)
    }

    /**
     * 切换前后摄像头：Camera2 采集切换，无需重协商（画面本地翻转，编码流不变）。
     * 若无对应朝向的摄像头则返回 false（调用方可提示用户）。
     * @param front true=切到前置，false=切到后置
     */
    fun switchCamera(front: Boolean): Boolean {
        val capturer = cameraCapturer ?: return false
        if (disposed) return false
        val enumerator = Camera2Enumerator(context)
        val target = if (front) {
            enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
        } else {
            enumerator.deviceNames.firstOrNull { enumerator.isBackFacing(it) }
        } ?: return false
        if (target == cameraDeviceName) return true
        return try {
            // CameraVideoCapturer.switchCamera 在前后摄之间快速切换（不变更采集会话）
            if (capturer is CameraVideoCapturer) {
                capturer.switchCamera(null)
                cameraDeviceName = target
                Log.d(TAG, "视频通话摄像头已切换: ${if (front) "前置" else "后置"} $target")
                true
            } else {
                Log.w(TAG, "当前摄像头不支持快速切换，忽略")
                false
            }
        } catch (t: Throwable) {
            Log.e(TAG, "切换摄像头失败: ${t.message}")
            false
        }
    }

    /**
     * 重协商：基于当前连接状态重新生成 Offer 并发出（供开启/关闭麦克风后更新 SDP）。
     * 必须在主线程调用（与 createOffer 同一线程约束）。
     */
    fun renegotiate() {
        if (disposed) return
        if (peerConnection == null) return
        Log.d(TAG, "触发重协商 (renegotiate)")
        createOffer()
    }

    /**
     * 统一重协商：host 端对每个 viewer 连接发 Offer；viewer 端对主连接发 Offer。
     * 视频通话开/关时在摄像头轨与麦克风轨全部挂载/移除后只调用一次，
     * 避免多次 createOfferFor 竞态导致对端协商失败（v1.156 视频通话无画面根因）。
     */
    fun renegotiateVideoCall() {
        if (disposed) return
        if (viewerConnections.isNotEmpty()) {
            viewerConnections.keys.forEach { createOfferFor(it) }
        } else {
            renegotiate()
        }
    }

    /**
     * V3.2: 带保护的 ICE restart——重连上限保护，避免无限重协商耗电。
     * 断线 → 尝试恢复 → 最多 5 次 → 仍失败则提示放弃。
     */
    fun restartConnection() {
        if (disposed) return
        if (reconnectCount >= maxReconnectAttempts) {
            AppLogger.webrtc("Reconnect failed (超过${maxReconnectAttempts}次)")
            connectionStatus = ConnectionStatus.FAILED
            listener.onConnectionFailed()
            listener.onDisconnected()
            return
        }
        reconnectCount++
        AppLogger.webrtc("Restart ICE $reconnectCount/$maxReconnectAttempts")
        val pc = peerConnection ?: return
        try {
            pc.restartIce()
        } catch (t: Throwable) {
            Log.w(TAG, "restartIce() 异常，走重协商兜底: ${t.message}")
        }
        doIceRestart()
    }

    /** V3.1: ICE restart——重新生成 Offer（IceRestart=true）尝试在断网后自动恢复连接 */
    private fun doIceRestart() {
        if (disposed) return
        val pc = peerConnection ?: return
        // 防止并发多次 restart（DISCONNECTED/FAILED/receiving 停止可能同时触发）
        if (restartInFlight) return
        restartInFlight = true
        Log.w(TAG, "ICE restart 发起...")
        try {
            val constraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("IceRestart", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
            }
            pc.createOffer(object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription) {
                    pc.setLocalDescription(object : SdpObserver {
                        override fun onSetSuccess() {
                            restartInFlight = false
                            val ld = pc.localDescription
                            if (ld != null) listener.onOfferReady(ld) else listener.onOfferReady(sdp)
                            AppLogger.network("ICE restart offer sent")
                        }
                        override fun onSetFailure(error: String?) {
                            restartInFlight = false
                            Log.e(TAG, "restart setLocalDescription 失败: $error")
                        }
                        override fun onCreateSuccess(p0: SessionDescription?) {}
                        override fun onCreateFailure(p0: String?) {}
                    }, sdp)
                }
                override fun onCreateFailure(error: String?) {
                    restartInFlight = false
                    Log.e(TAG, "restart createOffer 失败: $error")
                }
                override fun onSetSuccess() {}
                override fun onSetFailure(error: String?) {}
            }, constraints)
        } catch (t: Throwable) {
            restartInFlight = false
            Log.e(TAG, "ICE restart 异常: ${t.message}")
        }
    }

    // ==================== 系统音频 DataChannel ====================

    /** 共享方：发送一段系统音频 PCM 数据（广播到所有 viewer 连接；V4 下主连接不协商，无主连接通道） */
    fun sendSystemAudio(data: ByteArray) {
        viewerConnections.values.forEach { conn ->
            val dc = conn.systemAudioChannel
            if (dc != null && dc.state() == DataChannel.State.OPEN) {
                try { dc.send(DataChannel.Buffer(java.nio.ByteBuffer.wrap(data), true)) } catch (t: Throwable) {}
            }
        }
    }

    /** 观看方：注册系统音频接收回调（收到的 PCM 交给播放器） */
    fun setSystemAudioListener(listener: (ByteArray) -> Unit) {
        systemAudioListener = listener
    }

    // ==================== 控制 DataChannel（帧率切换等） ====================

    /** 控制数据通道是否已打开（观看方启用控制模式前检查） */
    fun controlChannelOpen(): Boolean = controlChannel?.state() == DataChannel.State.OPEN

    /** 观看方：经控制通道向共享方发送指令（如 {"type":"fps","value":30}） */
    fun sendControl(message: String) {
        // 主连接（观看方视角）
        val dc = controlChannel
        if (dc != null && dc.state() == DataChannel.State.OPEN) {
            try {
                dc.send(DataChannel.Buffer(java.nio.ByteBuffer.wrap(message.toByteArray()), false))
            } catch (t: Throwable) {
                Log.w(TAG, "发送控制消息失败: ${t.message}")
            }
        }
        // host 回发消息到所有 viewer 连接
        viewerConnections.values.forEach { conn ->
            val c = conn.controlChannel
            if (c != null && c.state() == DataChannel.State.OPEN) {
                try { c.send(DataChannel.Buffer(java.nio.ByteBuffer.wrap(message.toByteArray()), false)) } catch (_: Throwable) {}
            }
        }
    }

    /** 共享方：注册控制指令接收回调 */
    fun setControlListener(listener: (String) -> Unit) {
        controlListener = listener
    }

    /** 控制消息统一投递主线程处理。
     * DataChannel onMessage 在 WebRTC native 线程回调，监听方（MainActivity）会操作
     * UI/无障碍服务/媒体控制，跨线程直接调用会崩溃或产生竞态。 */
    private fun dispatchControlMessage(msg: String) {
        mainHandler.post {
            try {
                controlListener?.invoke(msg)
            } catch (t: Throwable) {
                Log.w(TAG, "控制指令处理异常: ${t.message}")
            }
        }
    }

    private fun registerControlObserver(dc: DataChannel) {
        dc.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) {}
            override fun onStateChange() {
                listener.onDataChannelInfo("控制通道状态: ${dc.state()}")
            }
            override fun onMessage(buffer: DataChannel.Buffer) {
                if (!buffer.binary) {
                    try {
                        val data = ByteArray(buffer.data.remaining())
                        buffer.data.get(data)
                        Log.d(TAG, "收到控制指令: ${String(data)}")
                        dispatchControlMessage(String(data))
                    } catch (t: Throwable) {
                        Log.w(TAG, "控制指令处理异常: ${t.message}")
                    }
                }
            }
        })
    }

    /** 控制通道诊断信息（观看方）：通道是否建立、当前状态 */
    fun controlChannelDebug(): String {
        val c = controlChannel
        return if (c == null) "控制通道: 未建立" else "控制通道: ${c.state()}"
    }

    /** 采集分辨率匹配屏幕比例（虚拟显示比例与屏幕不一致时系统会放大裁切屏幕内容，导致上下被切）。
     *  最大边限制 1920（1080p）：保持原画面清晰度。 */
    private fun captureSizeForScreen(): Pair<Int, Int> {
        val dm = context.resources.displayMetrics
        val w = dm.widthPixels
        val h = dm.heightPixels
        val maxDim = 1920
        val scale = minOf(1f, maxDim.toFloat() / maxOf(w, h))
        val cw = (w * scale).toInt()
        val ch = (h * scale).toInt()
        return cw to ch
    }

    /**
     * 设备自适应起始采集档位：低端老设备开局直接 720p，不让其硬冲 1080p 硬编。
     * 旗舰/中端设备仍 1080p 起步保持清晰度；后续编码瓶颈自适应会继续降档。
     * 依据 ActivityManager.isLowRamDevice 与 largeMemoryClass 双重判断。
     */
    private fun initialCaptureProfile(): Int {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            if (am.isLowRamDevice || am.largeMemoryClass <= 256) 1 else 0
        } catch (t: Throwable) {
            Log.w(TAG, "设备能力探测失败，默认1080p: ${t.message}")
            0
        }
    }

    /** 共享方：切换采集/编码帧率（观看方下发指令触发） */
    fun setFramerate(fps: Int) {
        if (disposed) return
        val capturer = videoCapturer ?: return
        try {
            // 保持当前采集档位尺寸（弱网降质期间切帧率不应恢复 1080p）
            val (capW, capH) = captureSizeForLevel(lastCaptureProfile)
            captureFps = fps
            capturer.changeCaptureFormat(capW, capH, fps)
            videoSender?.let { sender ->
                val params = sender.parameters
                params.encodings?.firstOrNull()?.maxFramerate = fps
                sender.parameters = params
            }
            AppLogger.capture("帧率已切换为 ${fps}fps (${capW}x${capH})")
        } catch (t: Throwable) {
            Log.e(TAG, "切换帧率失败: ${t.message}")
        }
    }

    /**
     * 启动屏幕采集。
     * capturer 由 ScreenCapturerFactory 内部缓存提供（真源是授权后拿到的 MediaProjection Intent）。
     * @return true 表示采集器创建并启动成功；false 表示失败（调用方应给 UI 提示）。
     */
    fun startScreenCapture(): Boolean {
        reportProgress("③a 创建采集器...")
        val capturer = ScreenCapturerFactory.createScreenCapturer(context) ?: run {
            Log.e(TAG, "屏幕采集器创建失败，请确认已授权屏幕采集权限")
            return false
        }
        videoCapturer = capturer
        reportProgress("③b 初始化采集器...")
        try {
            surfaceTextureHelper = SurfaceTextureHelper.create("ScreenCapture", eglBaseContext)

            val factory = getFactory()
            // 新版：视频源从 factory 实例创建（存成员引用，disconnect/releaseScreenCapture 统一释放）
            val source = factory.createVideoSource(true)
            videoSource = source
            capturer.initialize(surfaceTextureHelper, context, source.capturerObserver)

            // 设备自适应起始采集：低端老设备开局直接 720p，避免硬冲 1080p 硬编导致卡顿；
            // 旗舰/中端设备仍 1080p 起步保持清晰度，后续编码瓶颈自适应会继续降档。
            val initialProfile = initialCaptureProfile()
            val (capW, capH) = captureSizeForLevel(initialProfile)
            // 采集帧率 30fps：实测 60fps 下硬件编码器处理每帧排队更久，端到端延迟反而更高；
            // 30fps 帧间隔 33ms，编码器负载低、延迟更小（屏幕共享流畅度也足够）
            captureFps = 30
            lastCaptureProfile = initialProfile
            reportProgress("③c 启动采集 ${capW}x${capH}@${captureFps}（设备档位$initialProfile）...")
            capturer.startCapture(capW, capH, captureFps)

            reportProgress("③d 挂载视频轨道...")
            localVideoTrack = factory.createVideoTrack("screen_track", source)
            localVideoTrack?.setEnabled(true)

            // 添加视频轨道（默认 transceiver 传音视频；仅视频）
            localVideoTrack?.let { track ->
                val rtp = peerConnection?.addTrack(track)
                if (rtp == null) {
                    Log.e(TAG, "addTrack 失败：未添加视频轨道")
                } else {
                    Log.d(TAG, "addTrack 成功，sender=${rtp.track()?.id()}")
                    videoSender = rtp
                    val params = rtp.parameters
                    params.encodings?.firstOrNull()?.let { enc ->
                        // 打开应用等画面剧烈变化场景，码率瞬间需求大；上限过高会导致瞬时拥塞丢包。
                        // 12M 上限 + 初始 2.5M：WiFi 保持高清，弱网/低端机降低卡顿与发热。
                        enc.maxBitrateBps = 12_000_000
                        enc.minBitrateBps = 1_000_000
                        enc.maxFramerate = 30
                        // 低延迟：屏幕共享视频流高优先级，避免拥塞控制过度平滑/抑制导致延迟升高
                        enc.networkPriority = 4
                        enc.bitratePriority = 4.0
                    }
                    // 码率不足时优先保分辨率（降帧率而非降清晰度），用户要求不降画质
                    try {
                        params.degradationPreference = RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION
                    } catch (t: Throwable) {
                        Log.w(TAG, "设置 degradationPreference 失败: ${t.message}")
                    }
                    rtp.parameters = params
                    // 初始带宽 4M 起步：低于 5M 峰值避免启动瞬间拥塞，高于 2.5M 让画面更快清晰
                    //（1080p30 屏幕共享 2.5M 起步爬坡期画面模糊，弱网由拥塞控制 + 弱网自适应兜底降档）
                    try {
                        peerConnection?.setBitrate(1_000_000, 4_000_000, 12_000_000)
                        Log.d(TAG, "已设置初始带宽 1/4/12 Mbps")
                    } catch (t: Throwable) {
                        Log.w(TAG, "setBitrate 失败: ${t.message}")
                    }
                }
            } ?: run {
                Log.e(TAG, "localVideoTrack 为空，未添加视频轨道")
            }

            // 系统音频改走 DataChannel（SystemAudioBridge），不再添加麦克风音频轨道
            Log.d(TAG, "屏幕采集已启动: ${capW}x${capH}@${captureFps}fps (码率上限12M)")
            reportProgress("③e 采集就绪")
            return true
        } catch (t: Throwable) {
            Log.e(TAG, "屏幕采集启动失败: ${t.message}")
            // 失败路径统一释放半初始化资源，避免 capturer/helper/source 泄漏
            releaseScreenCapture()
            return false
        }
    }

    /** 释放屏幕采集全部资源（按 stopCapture→capturer→track→source→helper 顺序） */
    private fun releaseScreenCapture() {
        try { videoCapturer?.stopCapture() } catch (_: Throwable) {}
        try { videoCapturer?.dispose() } catch (_: Throwable) {}
        videoCapturer = null
        try { localVideoTrack?.dispose() } catch (_: Throwable) {}
        localVideoTrack = null
        try { videoSource?.dispose() } catch (_: Throwable) {}
        videoSource = null
        try { surfaceTextureHelper?.dispose() } catch (_: Throwable) {}
        surfaceTextureHelper = null
    }

    /** 启动采集进度回调（用于屏幕逐步诊断，startSessionCore 里设置） */
    var progressListener: ((String) -> Unit)? = null

    private fun reportProgress(step: String) {
        try { progressListener?.invoke(step) } catch (t: Throwable) {
            Log.w(TAG, "进度回调异常: ${t.message}")
        }
    }

    /**
     * 获取本机屏幕采集的本地视频轨道（供共享方本地预览渲染）。
     * 仅共享方调用；观看方通过远程轨道回调获取画面，不受影响。
     */
    fun getLocalVideoTrack(): VideoTrack? {
        return localVideoTrack
    }

    /**
     * 获取采集使用的 MediaProjection 实例（供系统音频内录 AudioPlaybackCapture 复用）。
     * 该实例由 ScreenCapturerAndroid.startCapture 内部创建，全应用仅此一份，
     * 避免同一投影 token 被 getMediaProjection 重复获取导致部分设备 createVirtualDisplay 卡死。
     */
    fun mediaProjection(): MediaProjection? {
        return (videoCapturer as? org.webrtc.ScreenCapturerAndroid)?.getMediaProjection()
    }

    /**
     * 主动触发一次关键帧：新 viewer 连接建立后，共享编码器不会立刻吐 I 帧，
     * 导致观看端首帧延迟 2~5 秒。这里用 changeCaptureFormat 强制采集器重启，
     * 编码器会立即产出一个关键帧，所有连接（含刚建立的 viewer）马上出画面。
     */
    fun requestKeyFrame() {
        val capturer = videoCapturer ?: return
        val now = SystemClock.elapsedRealtime()
        if (now - lastKeyFrameAt < 500L) return
        lastKeyFrameAt = now
        try {
            val (capW, capH) = captureSizeForLevel(lastCaptureProfile)
            capturer.changeCaptureFormat(capW, capH, captureFps)
            AppLogger.capture("已请求关键帧 ${capW}x${capH}@${captureFps}")
        } catch (t: Throwable) {
            Log.w(TAG, "请求关键帧失败: ${t.message}")
        }
    }

    fun createOffer() {
        val pc = peerConnection ?: return
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        }
        pc.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                Log.d(TAG, "Offer 创建成功")
                pc.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        // Trickle ICE：不等 gathering，SDP 立即回传，候选随后由 onIceCandidate 增量发送
                        val ld = pc.localDescription
                        if (ld != null) listener.onOfferReady(ld) else listener.onOfferReady(sdp)
                    }
                    override fun onSetFailure(error: String?) { Log.e(TAG, "setLocalDescription 失败: $error") }
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onCreateFailure(p0: String?) {}
                }, sdp)
            }
            override fun onCreateFailure(error: String?) { Log.e(TAG, "创建 Offer 失败: $error") }
            override fun onSetSuccess() {}
            override fun onSetFailure(error: String?) {}
        }, constraints)
    }

    fun createAnswer() {
        val pc = peerConnection ?: return
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        }
        pc.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                Log.d(TAG, "Answer 创建成功")
                pc.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        // Trickle ICE：不等 gathering，Answer 立即回传，候选随后增量发送
                        val ld = pc.localDescription
                        listener.onAnswerReady(ld ?: sdp)
                    }
                    override fun onSetFailure(error: String?) { Log.e(TAG, "setLocalDescription 失败: $error") }
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onCreateFailure(p0: String?) {}
                }, sdp)
            }
            override fun onCreateFailure(error: String?) { Log.e(TAG, "创建 Answer 失败: $error") }
            override fun onSetSuccess() {}
            override fun onSetFailure(error: String?) {}
        }, constraints)
    }

    fun setRemoteDescription(sdp: SessionDescription) {
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                Log.d(TAG, "setRemoteDescription 成功: ${sdp.type}")
                if (sdp.type == SessionDescription.Type.OFFER) {
                    createAnswer()
                }
                synchronized(pendingCandidates) {
                    pendingCandidates.forEach { peerConnection?.addIceCandidate(it) }
                    pendingCandidates.clear()
                }
            }
            override fun onSetFailure(error: String?) { Log.e(TAG, "setRemoteDescription 失败: $error") }
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onCreateFailure(p0: String?) {}
        }, sdp)
    }

    fun addIceCandidate(candidate: IceCandidate) {
        val pc = peerConnection
        if (pc == null || pc.remoteDescription == null) {
            synchronized(pendingCandidates) { pendingCandidates.add(candidate) }
        } else {
            pc.addIceCandidate(candidate)
        }
    }

    /**
     * 屏幕旋转后更新采集分辨率（宽高互换）。
     * WebRTC 的 ScreenCapturerAndroid 不会自动跟随屏幕方向，需手动 changeCaptureFormat。
     */
    fun updateCaptureOrientation(width: Int, height: Int) {
        if (disposed) return
        // 虚拟显示比例跟随屏幕比例，避免旋转后内容被裁切
        val maxDim = 2400
        val scale = minOf(1f, maxDim.toFloat() / maxOf(width, height))
        val capW = (width * scale).toInt()
        val capH = (height * scale).toInt()
        videoCapturer?.changeCaptureFormat(capW, capH, captureFps)
        Log.d(TAG, "旋转后更新采集分辨率: ${capW}x${capH}@$captureFps")
    }

    /**
     * 拉取 WebRTC 实时统计（帧率/码率/往返延迟），供全屏悬浮信息条展示。
     * 返回 JSON 字符串摘要；失败返回 null。
     */
    fun collectStats(): String? {
        val pc = peerConnection ?: return null
        return collectStatsFor(pc)
    }

    /** 指定 viewer 连接的统计（V4 host 端弱网自适应用，取该连接的发送/丢包数据） */
    fun collectViewerStats(viewerId: Int): String? {
        val conn = viewerConnections[viewerId] ?: return null
        return collectStatsFor(conn.pc)
    }

    private fun collectStatsFor(pc: PeerConnection): String? {
        var result: String? = null
        val lock = Object()
        try {
            // 新版 API：RTCStatsCollectorCallback（webrtc 124+）；旧版 StatsObserver 已过时
            pc.getStats(object : org.webrtc.RTCStatsCollectorCallback {
                override fun onStatsDelivered(report: org.webrtc.RTCStatsReport) {
                    try {
                        var inFps = 0.0
                        var outFps = 0.0
                        var inBytes = 0.0
                        var outBytes = 0.0
                        var rtt = -1.0
                        var inW = 0
                        var inH = 0
                        var outW = 0
                        var outH = 0
                        var lost = 0L
                        var lostTotal = 0L
                        var nackCount = 0L
                        // 观看端接收管线掉帧诊断：framesDropped 为接收/解码/渲染队列丢弃的累计帧数，
                        // framesDecoded 为成功解码的累计帧数；两者做差分可算出采样窗口内的掉帧率
                        var inDropped = 0L
                        var inDecoded = 0L
                        // 共享方视角的发送丢包：SDK144 中 outbound-rtp 无 packetsLost 字段，
                        // 必须从 remote-inbound-rtp（RTCP receiver report 回传）读取
                        var outSent = 0L
                        var outLost = 0L
                        var outLossPct = -1.0 // fractionLost：远端直接报告的丢包率 0~1，未上报时为 -1
                        var outEncImpl = ""    // 编码器实现（判断软编/硬编：如 HWEncoder/H264 / SWEncoder）
                        var outQualityLimit = "" // 编码瓶颈：cpu/bandwidth/none
                        // 诊断：当前选中的候选对路径（host/srflx/relay + 地址），用于定位 P2P 直连失败问题
                        var selPath = ""
                        var pathType = "" // 仅用于诊断去重：host/srflx/relay 组合，不含 IP
                        val stats = report.statsMap
                        // 第一遍：索引各 candidate 的 id -> 地址与类型（本地/远端）
                        val candAddr = HashMap<String, String>()
                        for ((_, s) in stats) {
                            val members = s.members ?: continue
                            if (s.type == "local-candidate" || s.type == "remote-candidate") {
                                val ip = (members["ip"] as? String) ?: ""
                                val port = (members["port"] as? Number)?.toInt() ?: 0
                                val ctype = (members["candidateType"] as? String) ?: "?"
                                candAddr[s.id] = "$ctype $ip:$port"
                            }
                        }
                        for ((_, s) in stats) {
                            when (s.type) {
                                "inbound-rtp" -> {
                                    inFps = (s.members["framesPerSecond"] as? Number)?.toDouble() ?: 0.0
                                    inBytes += ((s.members["bytesReceived"] as? Number)?.toDouble() ?: 0.0)
                                    inW = (s.members["frameWidth"] as? Number)?.toInt() ?: 0
                                    inH = (s.members["frameHeight"] as? Number)?.toInt() ?: 0
                                    lost += (s.members["packetsLost"] as? Number)?.toLong() ?: 0L
                                    lostTotal += (s.members["packetsReceived"] as? Number)?.toLong() ?: 0L
                                    nackCount += (s.members["nackCount"] as? Number)?.toLong() ?: 0L
                                    inDropped += (s.members["framesDropped"] as? Number)?.toLong() ?: 0L
                                    inDecoded += (s.members["framesDecoded"] as? Number)?.toLong() ?: 0L
                                    // 对端音频电平（inbound audio，0~32768 反映对方说话音量）
                                    if ((s.members["mediaType"] as? String) == "audio") {
                                        val lv = (s.members["audioLevel"] as? Number)?.toDouble()
                                        if (lv != null) remoteAudioLevel = lv
                                    }
                                }
                                "outbound-rtp" -> {
                                    outFps = (s.members["framesPerSecond"] as? Number)?.toDouble() ?: 0.0
                                    outBytes += ((s.members["bytesSent"] as? Number)?.toDouble() ?: 0.0)
                                    outW = (s.members["frameWidth"] as? Number)?.toInt() ?: 0
                                    outH = (s.members["frameHeight"] as? Number)?.toInt() ?: 0
                                    outSent += (s.members["packetsSent"] as? Number)?.toLong() ?: 0L
                                    outEncImpl = (s.members["encoderImplementation"] as? String) ?: ""
                                    outQualityLimit = (s.members["qualityLimitationReason"] as? String) ?: ""
                                }
                                "remote-inbound-rtp" -> {
                                    // 接收方经 RTCP 回报的丢包：packetsLost 累计值 + fractionLost 比例
                                    outLost += (s.members["packetsLost"] as? Number)?.toLong() ?: 0L
                                    val frac = (s.members["fractionLost"] as? Number)?.toDouble()
                                    if (frac != null && frac >= 0) {
                                        // fractionLost 语义修正：本 SDK 上报的是 RTCP 8bit 原始字节
                                        //（0~255，每单位≈1/256），而 W3C 规范为 0~1 比例。此前直接
                                        // ×100 会把 ~1% 的轻微丢包误判成 285.7% 重度丢包，触发弱网
                                        // 自适应把码率一路压死（实测老设备 WiFi 轻微丢包→画面又糊又卡、
                                        // 延迟越积越远）。>1 时按字节值换算回真实比例再取百分数，并夹紧
                                        // 到 0~100 兜底防脏值。
                                        val ratio = if (frac <= 1.0) frac else frac / 256.0
                                        outLossPct = (ratio * 100.0).coerceIn(0.0, 100.0)
                                    }
                                }
                                "candidate-pair" -> {
                                    val members = s.members
                                    if (members != null) {
                                        val active = members["nominated"]
                                        if (active == true || active?.toString() == "true") {
                                            val r = (members["currentRoundTripTime"] as? Number)?.toDouble()
                                            if (r != null && r > 0) rtt = r * 1000.0
                                            // 记录选中路径：local:host 192.168.x → remote:relay 1.2.x（local→remote 方向）
                                            val loc = (members["localCandidateId"] as? String) ?: ""
                                            val rem = (members["remoteCandidateId"] as? String) ?: ""
                                            val locStr = candAddr[loc] ?: "?"
                                            val remStr = candAddr[rem] ?: "?"
                                            selPath = "${locStr} → ${remStr}"
                                            pathType = locStr.substringBefore(" ") + "→" + remStr.substringBefore(" ")
                                        }
                                    }
                                }
                                else -> {}
                            }
                        }
                        // bytesReceived/bytesSent 为累计值，由调用方结合采样间隔换算码率
                        result = org.json.JSONObject().apply {
                            put("inFps", inFps.toInt())
                            put("outFps", outFps.toInt())
                            put("rtt", rtt.toInt())
                            put("inBytes", inBytes.toLong())
                            put("outBytes", outBytes.toLong())
                            put("inW", inW)
                            put("inH", inH)
                            put("outW", outW)
                            put("outH", outH)
                            put("lost", lost)
                            put("lostTotal", lostTotal)
                            put("nack", nackCount)
                            put("inDropped", inDropped)
                            put("inDecoded", inDecoded)
                            put("outLost", outLost)
                            put("outSent", outSent)
                            put("outLossPct", outLossPct)
                            put("encImpl", outEncImpl)
                            put("qualityLimit", outQualityLimit)
                            put("path", selPath)
                            put("pathType", pathType)
                        }.toString()
                    } catch (t: Throwable) {
                        Log.w(TAG, "统计解析失败: ${t.message}")
                    } finally {
                        synchronized(lock) { lock.notifyAll() }
                    }
                }
            })
            synchronized(lock) { lock.wait(500) }
        } catch (t: Throwable) {
            Log.w(TAG, "getStats 不可用（可能 API 版本差异）: ${t.message}")
            return null
        }
        return result
    }

    // 腾讯会议式弱网自适应状态（v1.101）
    // collectStatsFor 统计线程读、主线程写，需 @Volatile 保证跨线程可见性
    @Volatile private var curAdaptLevel = 0
    @Volatile private var recoverTimer = 0
    // v1.241: 扩到 7 档——蜂窝上行普遍仅 1~2Mbps，原最低档 2.5M 仍超带宽，编码器无降档余地时
    // 帧率被拥塞控制硬压到个位数；最低档压到 1M 配合 360p@15 保画面连续
    private val adaptBitrateCaps = intArrayOf(12_000_000, 9_000_000, 6_000_000, 4_000_000, 2_500_000, 1_500_000, 1_000_000)
    // 对端音频电平 0~32768（collectStatsFor 从 inbound audio 统计更新，供对讲状态指示）
    @Volatile private var remoteAudioLevel = 0.0

    /** 读取最近一次统计到的对端音频电平（0~32768，越大越响） */
    fun remoteAudioLevel(): Double = remoteAudioLevel
    // 增量丢包统计兜底（fractionLost 未上报时用累计值做差估算）
    private var lastOutSentCum = 0L
    private var lastOutLostCum = 0L
    private var lastAdaptBitrateCap = 0
    // v1.241: 实际发送码率 EMA 平滑值（带宽匹配档位用；EMA 无界递增风险：码率上限 12M，Double 无溢出）
    private var bwSmooth = 0.0
    // V3.1: 动态采集分辨率
    private var captureFps = 30
    private var lastCaptureProfile = 0
    // V1.187: 弱网自适应降帧率后的实际采集帧率（用于判断档位变化是否需要再次调整）
    private var lastCaptureFps = 30
    // V3.2: 采集防抖——切换分辨率后 4s 冷却，防止临界抖动导致 1080/720/480 来回跳
    private var lastCaptureSwitchMs = 0L
    private val captureSwitchCooldownMs = 8000L

    /**
     * V3.1: 按弱网档位选择采集分辨率档位。
     * 0=1080p(网络好) 1=720p(轻度弱网) 2=480p(严重弱网) 3=360p(蜂窝/超弱网 v1.241)；
     * 降采集分辨率同时降低采集与编码负载，比仅降码率更彻底；
     * 深档位给编码器留降分辨率余地，带宽不足时优先降分辨率保帧率（避免帧率被硬压）。
     */
    private fun captureProfileForLevel(level: Int): Int {
        return when {
            level >= 5 -> 3 // 360p
            level >= 3 -> 2 // 480p
            level >= 2 -> 1 // 720p
            else -> 0      // 1080p
        }
    }

    /** 按档位换算实际采集分辨率尺寸 */
    private fun captureSizeForLevel(profile: Int): Pair<Int, Int> {
        val base = captureSizeForScreen() // 1080p 上限的屏幕比例尺寸
        return when (profile) {
            1 -> { // 720p：等比缩放到最长边1280
                val scale = 1280f / maxOf(base.first, base.second)
                ((base.first * scale).toInt() to (base.second * scale).toInt())
            }
            2 -> { // 480p：最长边854
                val scale = 854f / maxOf(base.first, base.second)
                ((base.first * scale).toInt() to (base.second * scale).toInt())
            }
            3 -> { // 360p：最长边640（v1.241 蜂窝超弱网档）
                val scale = 640f / maxOf(base.first, base.second)
                ((base.first * scale).toInt() to (base.second * scale).toInt())
            }
            else -> base
        }
    }

    /**
     * 按弱网档位选择采集帧率（v1.187）：异地/TURN 中继场景 RTT 高、带宽有限，
     * 30fps 下每帧数据量大且拥塞控制收敛慢，积压易导致接收端掉帧卡顿。
     * 弱网加重时同步降帧率（30→28→24→20），配合降码率/降分辨率进一步减轻链路负载，
     * 播放端观感反而更连续；档位恢复后回到 30fps。
     */
    private fun captureFpsForLevel(level: Int): Int {
        return when {
            level >= 6 -> 15 // 蜂窝超弱网：1M 带宽下 15fps 保每帧数据量（v1.241）
            level >= 5 -> 18
            level >= 4 -> 20
            level >= 3 -> 24
            level >= 2 -> 28
            else -> 30
        }
    }

    /**
     * 编码负载自适应（v1.120）：处理"开视频软件/动态画面时硬编跟不上"的卡顿。
     * 共享方打开视频播放类应用时，画面每帧都在变，硬件编码器负载升高（低端机发热降频），
     * 实际编码帧率(outbound-rtp.framesPerSecond)持续低于目标帧率，即使网络不丢包观看方也卡。
     * - 编码帧率持续 < 目标*0.78（约30fps目标时<23.4fps）或编码器报 cpu 瓶颈或观看端反馈掉帧：
     *   切 MAINTAIN_FRAMERATE 保帧率，采集降一档分辨率（1080→720），减轻编码负载
     * - 编码帧率恢复 >= 目标*0.85 持续若干次且观看端未反馈掉帧：回 MAINTAIN_RESOLUTION + 回升 1080p
     * v1.223 提速：触发采样 3→2 次（4.5s→3s）、阈值 0.70→0.78，动态画面更早介入少掉帧；
     * 恢复采样 3→4 次，降得快回得慢防来回震荡。
     * v1.240: outFps 统计缺失不再直接跳过——cpu 瓶颈/观看端掉帧反馈仍可介入降档；
     * 恢复路径增加"统计缺失但连续无瓶颈证据"的缓慢回升，防止永久锁死降档。
     * 由 MainActivity 统计线程周期调用（约 1.5s 一次）。
     *
     * @param encodedFps 实际编码帧率（outFps），0 表示暂无统计
     * @param qualityLimit 编码器报告的质量限制原因（cpu/bandwidth/none）
     */
    fun adaptToEncoderLoad(encodedFps: Int, qualityLimit: String) {
        val pc = peerConnection ?: return
        if (disposed) return
        val target = captureFps
        if (target <= 0) return
        val cpuBottleneck = qualityLimit == "cpu"
        // v1.240: outFps 统计缺失（部分机型 framesPerSecond 恒为 0）时原逻辑直接 return，
        // 编码瓶颈保护完全失效。改为：仅未降档且无任何瓶颈证据时跳过（避免无据误降）；
        // 已降档时不跳过，让下方恢复判定能走"连续无瓶颈证据缓慢回升"路径，防止永久锁死
        if (encodedFps <= 0) {
            if (encNoStatSamples < Int.MAX_VALUE) encNoStatSamples++
            if (!cpuBottleneck && !viewerStallActive && !encLoadDown) return
        } else {
            encNoStatSamples = 0
        }
        // 观看端反馈掉帧期间视为持续卡顿（抑制恢复），由观看端解除反馈后走常规回升采样
        val isEncLag = viewerStallActive || cpuBottleneck || encodedFps < target * 0.78
        val isEncOk = !viewerStallActive && !cpuBottleneck &&
            (encodedFps >= target * 0.85 || (encodedFps <= 0 && encNoStatSamples >= 8))
        if (!encLoadDown) {
            // 未降质：持续卡帧/CPU瓶颈触发降质（2 次采样约 3s）
            if (isEncLag) {
                encLoadSamples++
                encRecoverSamples = 0
                if (encLoadSamples >= 2) {
                    encLoadDown = true
                    encLoadSamples = 0
                    applyEncoderLoadProfile(true)
                }
            } else {
                encLoadSamples = 0
            }
        } else {
            // 已降质：编码帧率回升且观看端无掉帧反馈才恢复（4 次采样约 6s，防震荡）
            if (isEncOk) {
                encRecoverSamples++
                encLoadSamples = 0
                if (encRecoverSamples >= 4) {
                    encLoadDown = false
                    encRecoverSamples = 0
                    applyEncoderLoadProfile(false)
                }
            } else {
                encRecoverSamples = 0
            }
        }
    }

    /**
     * 观看端掉帧反馈（v1.223）：观看端检测到接收管线持续掉帧时调用。
     * active=true 立即降档（等价编码瓶颈路径），恢复由观看端解除反馈 + 常规回升采样完成。
     */
    fun setViewerStall(active: Boolean) {
        if (viewerStallActive == active) return
        viewerStallActive = active
        AppLogger.capture(if (active) "观看端反馈掉帧: 立即降档(保帧率+降分辨率)" else "观看端反馈恢复: 允许回升评估")
        if (active && !encLoadDown) {
            encLoadDown = true
            encLoadSamples = 0
            applyEncoderLoadProfile(true)
        }
    }

    /**
     * 应用编码负载档位：切换 degradationPreference + 采集分辨率。
     * 采集档位取编码负载档位与弱网档位(captureProfileForLevel)的较大值（更严格者生效），
     * 与弱网自适应共用 lastCaptureProfile/lastCaptureSwitchMs 防抖。
     */
    private fun applyEncoderLoadProfile(down: Boolean) {
        val targetProfile = if (down) 1 else 0
        val effective = maxOf(captureProfileForLevel(curAdaptLevel), targetProfile)
        // 编码瓶颈时除降分辨率外同步降采集帧率（30→24）。播放视频等高动态画面单靠降分辨率
        // 仍可能让 30fps 编不动，观看端一帧一帧跳；恢复时回弱网档位对应的基础帧率。
        val baseFps = captureFpsForLevel(curAdaptLevel)
        val targetFps = if (down) minOf(baseFps, 24) else baseFps
        // degradationPreference：编码瓶颈时保帧率降分辨率（动态画面流畅优先）
        val degradation = if (effective > 0) {
            RtpParameters.DegradationPreference.MAINTAIN_FRAMERATE
        } else {
            RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION
        }
        try {
            videoSender?.let { rtp ->
                val params = rtp.parameters
                params.degradationPreference = degradation
                params.encodings?.firstOrNull()?.maxFramerate = targetFps
                rtp.parameters = params
                Log.d(TAG, "编码负载自适应: ${if (down) "降720p@24" else "回升1080p@$targetFps"} 策略=$degradation")
            }
            // V4：1 对 1 模式视频实际承载在 viewer 连接，同步设置其 sender 的降级策略与帧率上限
            viewerConnections.values.forEach { conn ->
                conn.videoSender?.let { rtp ->
                    val params = rtp.parameters
                    params.degradationPreference = degradation
                    params.encodings?.firstOrNull()?.maxFramerate = targetFps
                    rtp.parameters = params
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "编码负载切策略失败: ${t.message}")
        }
        if (effective != lastCaptureProfile || targetFps != captureFps) {
            val now = System.currentTimeMillis()
            val isDowngrade = effective > lastCaptureProfile || targetFps < captureFps
            val cooldownOk = now - lastCaptureSwitchMs >= captureSwitchCooldownMs
            if (isDowngrade || cooldownOk) {
                lastCaptureProfile = effective
                lastCaptureFps = targetFps
                captureFps = targetFps
                lastCaptureSwitchMs = now
                try {
                    val capturer = videoCapturer
                    if (capturer != null) {
                        val (capW, capH) = captureSizeForLevel(effective)
                        capturer.changeCaptureFormat(capW, capH, targetFps)
                        AppLogger.capture("编码负载分辨率: ${capW}x${capH}@${targetFps} 档位$effective")
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "编码负载降分辨率失败: ${t.message}")
                }
            }
        }
    }

    /**
     * 腾讯会议式弱网自适应（v1.103）：按远端回报的发送丢包率动态调节码率与分辨率策略。
     * - 丢包高（>=3%）：逐级降码率 + 切 MAINTAIN_FRAMERATE（保帧率降分辨率，画面流畅不卡顿）
     * - 丢包恢复（<1% 持续）：缓步回升码率 + 回 MAINTAIN_RESOLUTION（恢复高清晰度）
     * 由 MainActivity 统计线程周期调用（约 1.5s 一次）。
     *
     * @param fractionLossPct remote-inbound-rtp.fractionLost 直接报告的丢包率 0~100，-1 表示未上报
     * @param outSentCum outbound-rtp packetsSent 累计值
     * @param outLostCum remote-inbound-rtp packetsLost 累计值
     * @param rttMs candidate-pair 当前往返时延（毫秒），RTT 高时主动降档保流畅
     */
    fun adaptToNetwork(fractionLossPct: Double, outSentCum: Long, outLostCum: Long, rttMs: Int, actualBitrateBps: Int) {
        val pc = peerConnection ?: return
        if (disposed) return
        val sender = videoSender ?: return
        applyNetworkAdaptation(pc, sender, "主连接", fractionLossPct, outSentCum, outLostCum, rttMs, actualBitrateBps)
    }

    /**
     * V4 host 端：对指定 viewer 连接执行弱网自适应（1 对 1 模式的实际视频承载连接）。
     * 与 adaptToNetwork 共用档位状态机，但作用于该 viewer 的 pc 与 videoSender，
     * 让 host 的 1 对 1 连接也能在弱网时自动降码率/降分辨率保流畅。
     */
    fun adaptViewerNetwork(viewerId: Int, fractionLossPct: Double, outSentCum: Long, outLostCum: Long, rttMs: Int, actualBitrateBps: Int) {
        if (disposed) return
        val conn = viewerConnections[viewerId] ?: return
        val sender = conn.videoSender ?: return
        applyNetworkAdaptation(conn.pc, sender, "viewer#$viewerId", fractionLossPct, outSentCum, outLostCum, rttMs, actualBitrateBps)
    }

    /** 弱网自适应公共实现：档位状态机 + 码率/降级策略 + 采集分辨率调整（主连接与 viewer 连接共用） */
    private fun applyNetworkAdaptation(
        pc: PeerConnection,
        sender: org.webrtc.RtpSender,
        tag: String,
        fractionLossPct: Double,
        outSentCum: Long,
        outLostCum: Long,
        rttMs: Int,
        actualBitrateBps: Int
    ) {
        // 丢包率：优先用 fractionLost（远端 RTCP 直接回报，实时准确）；未上报时用增量累计做差兜底
        var sendLossPct = if (fractionLossPct >= 0) fractionLossPct else 0.0
        if (fractionLossPct < 0 && lastOutSentCum > 0) {
            val dSent = outSentCum - lastOutSentCum
            if (dSent > 0) {
                // v1.240: 增量兜底防脏值——统计重置/连接重建窗口 lost 倒退 clamp 到 0，
                // 跨窗口重叠导致的超量丢包 clamp 到 dSent（丢包率上限 100%）
                val dLost = (outLostCum - lastOutLostCum).coerceIn(0L, dSent)
                sendLossPct = dLost * 100.0 / dSent
            }
        }
        lastOutSentCum = outSentCum
        lastOutLostCum = outLostCum
        // 丢包档位：丢包率 >=3% 视为弱网需降质，<1% 视为已恢复
        val lossLevel = when {
            sendLossPct >= 5.0 -> adaptBitrateCaps.size - 1 // 最高档降质
            sendLossPct >= 3.0 -> 2
            sendLossPct >= 1.5 -> 1
            else -> 0
        }
        // RTT 档位（异地/TURN 中继/蜂窝共享场景）：RTT 高即使丢包低也可能排队延迟，主动限制码率上限，
        // 避免拥塞控制在高 RTT 下收敛慢、码率估计偏高导致画面积压卡顿。
        // v1.241: 扩深档——蜂窝链路 600ms+ 常见，深档位（480p@20/360p@18）保帧率优先于清晰度
        val rttLevel = when {
            rttMs >= 900 -> 5
            rttMs >= 600 -> 4
            rttMs >= 350 -> 2
            rttMs >= 200 -> 1
            else -> 0
        }
        // v1.241: 带宽匹配档位——拥塞控制后的实际发送码率直接反映链路可用带宽。
        // 实际码率持续低于当前档位需求（如蜂窝上行 1M 而档位上限 4M）时，编码器无降档余地、
        // 帧率被硬压；按实际带宽从高到低选第一个码率上限 ≤ 实际带宽×1.6 的档位参与取大，
        // 让采集分辨率/帧率主动降到与链路匹配，帧率优先保连续。带宽波动由"降档立即、回升 6s/档"兜底。
        var bwLevel = 0
        if (actualBitrateBps > 0) {
            // EMA 平滑（α=0.5）：蜂窝带宽 1.5s 窗口波动大，防瞬时毛刺误降档
            bwSmooth = if (bwSmooth <= 0) actualBitrateBps.toDouble()
            else bwSmooth * 0.5 + actualBitrateBps * 0.5
            // 默认最高档兜底（带宽低于最低档阈值 1M/1.6≈625kbps 时仍深降，不停留在高档）
            bwLevel = adaptBitrateCaps.size - 1
            for (i in adaptBitrateCaps.indices) {
                if (adaptBitrateCaps[i] <= bwSmooth * 1.6) {
                    bwLevel = i
                    break
                }
            }
        }
        val level = maxOf(lossLevel, rttLevel, bwLevel)
        if (level > curAdaptLevel) {
            // 弱网加重：直接降到对应档位
            curAdaptLevel = level
            recoverTimer = 0
        } else if (level < curAdaptLevel) {
            // 网络好转：连续 4 次（约 6s）回升一档，避免抖动。
            // v1.240 由 8 次（12s）提速——蜂窝/跨网场景从最高档回满约 24s（原 48s），
            // 弱网缓解后画质恢复更及时；6s 窗口仍足以滤除蜂窝 RTT 瞬时波动
            recoverTimer++
            if (recoverTimer >= 4) {
                curAdaptLevel--
                recoverTimer = 0
            }
        }
        val cap = adaptBitrateCaps[curAdaptLevel]
        // 摄像头通话轨随档位同步自适应（码率/帧率上限），弱网时降低人脸画面数据量
        applyCameraAdaptation()
        // 仅档位变化时调码率/策略，避免周期重置影响拥塞控制收敛
        if (lastAdaptBitrateCap != cap) {
            lastAdaptBitrateCap = cap
            try {
                pc.setBitrate(1_000_000, (cap * 0.7).toInt(), cap)
            } catch (t: Throwable) {
                Log.w(TAG, "$tag 自适应调码率失败: ${t.message}")
            }
            // 弱网降分辨率保帧率（腾讯会议流畅优先），网络好恢复高清晰度
            val degradation = if (curAdaptLevel > 0) {
                RtpParameters.DegradationPreference.MAINTAIN_FRAMERATE
            } else {
                RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION
            }
            try {
                val params = sender.parameters
                params.degradationPreference = degradation
                sender.parameters = params
                Log.d(TAG, "$tag 弱网自适应: 丢包${"%.1f".format(sendLossPct)}% rtt=${rttMs}ms 实发${actualBitrateBps / 1000}k 档位${curAdaptLevel} 码率上限${cap / 1000000}M 策略=$degradation")
            } catch (t: Throwable) {
                Log.w(TAG, "$tag 自适应切分辨率策略失败: ${t.message}")
            }
            // V3.1: 采集侧降分辨率——弱网档位>=2(码率6M)降720p、>=3(4M)降480p，减轻采集+编码双端负载；
            // 恢复档位0(12M)回升 1080p
            // V3.2: 防抖——降质立即执行；回升需冷却 4s，避免 1080/720/480 临界来回跳
            // V1.120: 与编码负载自适应档位取较大值（编码瓶颈时即使网络好也保持降档）
            val weakProfile = captureProfileForLevel(curAdaptLevel)
            val targetProfile = if (encLoadDown) maxOf(weakProfile, 1) else weakProfile
            // V1.187: 采集侧同步降帧率——档位>=2 时 30→28→24→20，异地/中继高 RTT 下
            // 单帧数据量变大、拥塞控制收敛慢，降帧率能显著缓解积压掉帧，观感更连续
            val targetFps = captureFpsForLevel(curAdaptLevel)
            if (targetProfile != lastCaptureProfile || targetFps != captureFps) {
                val now = System.currentTimeMillis()
                val isDowngrade = targetProfile > lastCaptureProfile || targetFps < captureFps
                val cooldownOk = now - lastCaptureSwitchMs >= captureSwitchCooldownMs
                if (isDowngrade || cooldownOk) {
                    lastCaptureProfile = targetProfile
                    lastCaptureFps = targetFps
                    captureFps = targetFps
                    lastCaptureSwitchMs = now
                    try {
                        val capturer = videoCapturer
                        if (capturer != null) {
                            val (capW, capH) = captureSizeForLevel(targetProfile)
                            capturer.changeCaptureFormat(capW, capH, targetFps)
                            AppLogger.capture("动态分辨率: ${capW}x${capH}@${targetFps} ($tag 档位$curAdaptLevel)")
                        }
                        // 同步编码器帧率上限，避免编码端仍按 30fps 目标发包
                        val params = sender.parameters
                        params.encodings?.firstOrNull()?.maxFramerate = targetFps
                        sender.parameters = params
                    } catch (t: Throwable) {
                        Log.w(TAG, "采集降分辨率失败: ${t.message}")
                    }
                }
            }
        }
    }

    /**
     * 摄像头通话轨弱网自适应：随当前弱网档位调节摄像头发送器的码率/帧率上限。
     * 摄像头 640x480@30 是人脸小画面，弱网时优先降码率上限与帧率上限（不降采集分辨率，保持清晰度），
     * 屏幕共享主轨的自适应已由 applyNetworkAdaptation 处理，此处只作用 camera_track 的 sender。
     * host 端对每个 viewer 连接的 camera sender、viewer 端对主连接的 camera sender 均生效。
     */
    private fun applyCameraAdaptation() {
        val cameraSenders = buildList {
            cameraSender?.let { add(it) }
            cameraViewerSenders.values.forEach { add(it) }
        }
        if (cameraSenders.isEmpty()) return
        // v1.241: 档位 0~6（与主档位数组同步扩展）：码率 1200→…→120kbps，帧率 30→…→10
        val bitrateCaps = intArrayOf(1_200_000, 800_000, 500_000, 300_000, 200_000, 150_000, 120_000)
        val fpsCaps = intArrayOf(30, 28, 24, 20, 15, 12, 10)
        val level = curAdaptLevel.coerceIn(0, bitrateCaps.size - 1)
        val capBps = bitrateCaps[level]
        val capFps = fpsCaps[level]
        if (capBps == lastCameraBitrateCap && capFps == lastCameraFpsCap) return
        lastCameraBitrateCap = capBps
        lastCameraFpsCap = capFps
        for (sender in cameraSenders) {
            try {
                val params = sender.parameters
                val enc = params.encodings?.firstOrNull()
                if (enc != null) {
                    enc.maxBitrateBps = capBps
                    enc.maxFramerate = capFps
                }
                sender.parameters = params
                Log.d(TAG, "摄像头弱网自适应: 档位$level 码率上限${capBps / 1000}kbps 帧率$capFps")
            } catch (t: Throwable) {
                Log.w(TAG, "摄像头自适应调参失败: ${t.message}")
            }
        }
    }

    /** 重置弱网自适应状态（断开/重新连接时调用） */
    fun resetAdaptiveState() {
        curAdaptLevel = 0
        recoverTimer = 0
        lastOutSentCum = 0L
        lastOutLostCum = 0L
        lastAdaptBitrateCap = 0
        bwSmooth = 0.0
        encLoadDown = false
        encLoadSamples = 0
        encRecoverSamples = 0
        encNoStatSamples = 0
        viewerStallActive = false
        lastCaptureFps = 30
        lastCameraBitrateCap = 0
        lastCameraFpsCap = 0
    }

    fun disconnect() {
        if (disposed) return
        disposed = true
        resetAdaptiveState()
        restartInFlight = false
        reconnectCount = 0
        // V4: 清理所有 viewer 连接（PC 释放统一 post 主线程，见 removeViewer 注释）
        viewerConnections.keys.toList().forEach { removeViewer(it) }
        pendingViewerCandidates.clear()
        // 屏幕采集：按 stopCapture→capturer→track→source→helper 顺序统一释放
        releaseScreenCapture()
        stopCameraVideo()
        // 麦克风：先 track 后 source（track 持有 native AudioSource 引用，反序悬空崩溃）
        try { localAudioTrack?.dispose() } catch (_: Throwable) {}
        localAudioTrack = null
        try { micAudioSource?.dispose() } catch (_: Throwable) {}
        micAudioSource = null
        systemAudioListener = null
        try { controlChannel?.dispose() } catch (_: Throwable) {}
        controlChannel = null
        controlListener = null
        videoSender = null
        micSender = null
        peerConnection?.close()
        peerConnection?.dispose()
        peerConnection = null
        Log.d(TAG, "WebRTC 已断开并清理")
    }
}
