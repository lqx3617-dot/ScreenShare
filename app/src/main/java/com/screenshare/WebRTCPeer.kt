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
        /** 观看端掉帧反馈最长有效期：超时未收到恢复消息则自动解除，避免编码降档锁死（M9） */
        private const val STALL_TIMEOUT_MS = 30_000L
        /** ICE DISCONNECTED 后等待自动恢复的最长时间，超时则强制 restart（M10） */
        private const val ICE_RECOVERY_TIMEOUT_MS = 15_000L
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

    // 统计/自适应线程读、主线程写，@Volatile 保证可见性（M2 修复）
    @Volatile private var peerConnection: PeerConnection? = null
    private var localVideoTrack: VideoTrack? = null
    private var localAudioTrack: AudioTrack? = null
    private var videoCapturer: VideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    // 屏幕采集 VideoSource：存成员引用以便 disconnect 统一释放
    //（PC.dispose 不负责独立的 VideoSource，漏释放则每次开启共享泄漏一个 native 源）
    private var videoSource: VideoSource? = null
    // 关键帧请求短时防抖：多 viewer 同时恢复时 requestKeyFrame 可能连续触发，
    // changeCaptureFormat 会重启采集器造成画面闪断，因此 500ms 内只允许触发一次。
    private val lastKeyFrameAt = java.util.concurrent.atomic.AtomicLong(0)
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

    // v1.294: ICE 恢复看门狗——DISCONNECTED 后超时未恢复则强制 restart，避免永久"重连中"（M10）
    private val iceRecoveryWatchdog = Runnable {
        if (connectionStatus == ConnectionStatus.RECONNECTING) {
            AppLogger.network("ICE ${ICE_RECOVERY_TIMEOUT_MS}ms 未恢复，强制 restart")
            connectionStatus = ConnectionStatus.FAILED
            listener.onDisconnected()
            restartConnection()
        }
    }
    private val viewerMaxRestarts = 5
    // 编码负载自适应（v1.120）：开视频软件等动态画面时硬编跟不上，主动降采集分辨率保帧率。
    // 与弱网档位(curAdaptLevel)独立，最终采集档位取两者的较大值
    @Volatile private var encLoadDown = false
    @Volatile private var encLoadSamples = 0       // 编码瓶颈持续采样计数（触发降质）
    // 观看端掉帧反馈（stream-stall）：观看端检测到接收管线持续掉帧时经控制通道通知共享方，
    // 共享方按编码瓶颈同路径立即降档（MAINTAIN_FRAMERATE + 降分辨率），反馈恢复后才允许回升。
    // 解决共享方出向统计看不到的"接收端解码/渲染瓶颈"与"帧到达过晚被丢"两类掉帧。
    @Volatile private var viewerStallActive = false
    @Volatile private var encRecoverSamples = 0    // 编码恢复持续采样计数（回升 1080p）
    // 编码帧率统计缺失连续采样计数（v1.240）：部分机型 outbound-rtp.framesPerSecond 恒不上报，
    // 恢复判定无法依赖帧率证据，改用"连续无瓶颈证据"缓慢恢复，防止统计缺失永久锁死降档
    @Volatile private var encNoStatSamples = 0
    // v1.333: 编码器实测帧率上限（棘轮）。一加/OPPO 硬编在 1080p(1920x1329) 下实测
    // 只能稳定输出 ~24fps，highMotionFpsCap=48 时 encodedFps 恒低于 target*0.78=37.4
    // → 降 720p@24 → 轻松达标 4 次采样 → 回升 1080p@48 → 再次超载，形成 7~9s 周期的
    // 1920@48 ↔ 1280@24 反复切换。每次 changeCaptureFormat 都重启采集管线并触发关键帧，
    // 观看端冻结持续累加、抖动缓冲棘轮式攀升（实测 3 分钟冻结 20 次、缓冲 19→127ms）。
    // 降档时把「编码器在该分辨率下实际能达到的帧率」记下来，回升目标夹到此值，
    // 与弱网 minAdaptLevel 同理消除周期震荡。
    @Volatile private var encFpsCeiling = 0      // 编码器实测上限（fps），0=未学习
    private var encFpsLearnMs = 0L               // 最近一次学习时间，用于按证据逐级放开
    // v1.333: 上限放开节奏——持续无编码瓶颈证据 30s 后上限 +6fps（24→30→36→42→48），
    // 内容静止/设备降温等真实改善时可逐步回到高帧率；持续超载时学习事件刷新计时器，
    // 放开永不触发（与弱网 congestionForgetMs 同构）
    private val encFpsReleaseMs = 30_000L
    private val encFpsReleaseStep = 6

    // 系统音频 DataChannel（观看方接收）
    private var systemAudioListener: ((ByteArray) -> Unit)? = null
    // v1.132：观看方已注册的音频接收通道引用（收到新音频通道时先释放旧的，防止多通道交错播放导致电流声）
    private var audioReceiveChannel: DataChannel? = null

    // 控制 DataChannel（观看方 → 共享方下发指令，如切换帧率）
    private var controlChannel: DataChannel? = null
    private var controlListener: ((String) -> Unit)? = null

    // 视频发送器（切换帧率时更新编码参数）
    @Volatile private var videoSender: org.webrtc.RtpSender? = null

    // 麦克风语音（会议内双向对讲）：标准 WebRTC 音频轨道
    private var micAudioSource: AudioSource? = null
    @Volatile private var micSender: org.webrtc.RtpSender? = null

    // 视频通话摄像头（camera_track）：前端摄像头实时采集，人脸画面。与屏幕轨（screen_track）并存，
    // host 端挂到每个 viewer 连接、viewer 端挂到主连接；对端按 track id 区分渲染到 PIP 小窗
    private var cameraCapturer: CameraVideoCapturer? = null
    private var cameraVideoSource: VideoSource? = null
    private var cameraVideoTrack: VideoTrack? = null
    private var cameraSurfaceTextureHelper: SurfaceTextureHelper? = null
    // 当前摄像头设备名（用于前后切换判断）
    private var cameraDeviceName: String? = null
    // host 端：每个 viewer 连接的摄像头发送器（同摄像头轨可 addTrack 到多条连接）
    private val cameraViewerSenders = java.util.concurrent.ConcurrentHashMap<Int, org.webrtc.RtpSender>()
    // viewer 端：主连接的摄像头发送器
    @Volatile private var cameraSender: org.webrtc.RtpSender? = null
    // 摄像头弱网自适应：最近一次码率/帧率上限（防重复设置）。
    // 写入在 WebRTC 回调线程，重置在主线程 disconnect，需保证可见性
    @Volatile private var lastCameraBitrateCap = 0
    @Volatile private var lastCameraFpsCap = 0

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
                    mainHandler.removeCallbacks(iceRecoveryWatchdog)
                    listener.onConnected()
                }
                PeerConnection.IceConnectionState.DISCONNECTED -> {
                    connectionStatus = ConnectionStatus.RECONNECTING
                    listener.onDisconnected()
                    // V3.1: 断网自动恢复——发起 ICE restart 重新建立数据通道
                    // 注意: DISCONNECTED 时 WebRTC 会先自行尝试恢复，收到 FAILED 再强制 restart
                    AppLogger.network("ICE DISCONNECTED, awaiting auto recovery")
                    // v1.294: 部分机型 DISCONNECTED 后永不转 FAILED，UI 会永久卡在"重连中"；
                    // 超时未恢复则强制 restart（M10）
                    mainHandler.removeCallbacks(iceRecoveryWatchdog)
                    mainHandler.postDelayed(iceRecoveryWatchdog, ICE_RECOVERY_TIMEOUT_MS)
                }
                PeerConnection.IceConnectionState.FAILED -> {
                    connectionStatus = ConnectionStatus.FAILED
                    mainHandler.removeCallbacks(iceRecoveryWatchdog)
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
            // v1.259: 音频轨（对端麦克风）单独捕获以控制对讲音量
            handleRemoteAudioTrack(receiver)
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
            // v1.259: onTrack 路径同样可能交付音频轨
            handleRemoteAudioTrack(track?.receiver)
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

    // v1.259: 远端音频轨（对端麦克风，1 对 1 场景只有一路）。到达时应用当前对讲音量
    @Volatile private var remoteAudioTrack: AudioTrack? = null
    // v1.259: 对讲音量 0~1（远端音轨到达前缓存，到达后立即应用）
    @Volatile private var talkVolume: Double = 1.0

    /**
     * v1.259: 设置对讲音量（本端听到的对端说话声）。
     * @param v 0~1，远端音轨到达前缓存，到达时应用
     */
    fun setTalkVolume(v: Float) {
        talkVolume = v.toDouble().coerceIn(0.0, 1.0)
        val t = remoteAudioTrack ?: return
        try { t.setVolume(talkVolume) } catch (_: Throwable) {}
    }

    /** v1.259: 捕获远端音频轨并应用当前对讲音量（host 端 viewer 连接 / viewer 端主连接共用） */
    private fun handleRemoteAudioTrack(receiver: RtpReceiver?) {
        val at = receiver?.track() as? AudioTrack ?: return
        remoteAudioTrack = at
        try { at.setVolume(talkVolume) } catch (_: Throwable) {}
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
                    // CHECKING 阶段（首个数据包到达前）receiving 正常为 false，此时重建会打断
                    // 正在建立的连接，弱网/TURN 中继下可能 5 次重连耗尽后被放弃（H3 修复）
                    val st = viewerConnections[viewerId]?.pc?.iceConnectionState()
                    AppLogger.network("viewer#$viewerId receiving stopped (ice=$st)")
                    if (st == PeerConnection.IceConnectionState.CONNECTED ||
                        st == PeerConnection.IceConnectionState.COMPLETED) {
                        restartViewer(viewerId)
                    }
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
                // v1.259: viewer 的麦克风音轨（host 端听到 viewer 说话）
                handleRemoteAudioTrack(receiver)
                val track = receiver?.track() as? VideoTrack
                if (track != null) {
                    // host 端 viewer 连接收的远端视频轨即 viewer 的摄像头画面
                    listener.onViewerCameraTrack(viewerId, track)
                }
            }
            override fun onTrack(transceiver: RtpTransceiver?) {
                handleRemoteAudioTrack(transceiver?.receiver)
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
        // 挂载共享视频轨道（host 采集已启动才有轨道；若本连接先于采集就绪创建，
        // 则由 startScreenCapture 末尾的 attachScreenTrackToViewers 补挂，避免该连接全程无视频/无法自适应）
        attachScreenTrack(viewerId, conn)
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

    /**
     * 把共享视频轨挂到指定 viewer 连接并设置初始码率/退让策略。
     * 采集就绪前创建的连接（localVideoTrack 为空）会跳过，由 [attachScreenTrackToViewers] 在采集就绪后补挂。
     */
    private fun attachScreenTrack(viewerId: Int, conn: ViewerConnection) {
        val track = localVideoTrack ?: return
        val rtp = conn.pc.addTrack(track)
        if (rtp == null) {
            Log.e(TAG, "viewer#$viewerId addTrack 失败")
            return
        }
        conn.videoSender = rtp
        // v1.292: 新连接必须继承当前自适应档位，不能固定用高档初始值。
        // 此前固定 4M/48fps，弱网期间（档位6=800k/15fps）新 viewer 一接入就把
        // 该连接的 maxBitrateBps/maxFramerate 抬回 4M/48；而 applyNetworkAdaptation
        // 因 targetFps==captureFps 且 profile 未变跳过整个切换块、不会纠正，
        // 导致档位6 名义上限 800k、实发却 1~1.9Mbps、编码 48fps（真机日志实测）。
        val cap = minOf(adaptBitrateCaps[curAdaptLevel], maxBitrateCap)
        // 编码负载降档期间实际采集是 720p@24 + MAINTAIN_FRAMERATE，新连接必须继承同一状态，
        // 否则新 viewer 拿到 48fps + MAINTAIN_RESOLUTION，与采集器实况矛盾（M1）
        val baseFps = captureFpsForLevel(curAdaptLevel)
        val fps = if (encLoadDown) minOf(baseFps, 24) else baseFps
        val params = rtp.parameters
        params.encodings?.firstOrNull()?.let { enc ->
            // v1.243: 初始上限 12M→9M、下限 1M→600k——降低开局带宽冲动，
            // 高动态画面/弱网下拥塞控制起步更平缓，减少开头几秒的积压掉帧
            // v1.249: 低端机进一步截到 6M（maxBitrateCap）
            // v1.251: 初始上限再降到 4M——与初始档位 2 一致，弱 WiFi 开局不再瞬间打满空口队列
            enc.maxBitrateBps = cap
            enc.minBitrateBps = minOf(60_000, cap)
            // v1.246: 与 host 侧 highMotionFpsCap 保持一致
            enc.maxFramerate = fps
            enc.networkPriority = 4
            enc.bitratePriority = 4.0
        }
        try {
            params.degradationPreference = if (curAdaptLevel > 0 || encLoadDown) {
                RtpParameters.DegradationPreference.MAINTAIN_FRAMERATE
            } else {
                RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION
            }
        } catch (t: Throwable) {}
        rtp.parameters = params
        // 初始带宽按当前档位（与 applyNetworkAdaptation 的下发值一致）
        try {
            conn.pc.setBitrate(minOf(60_000, cap), (cap * 0.7).toInt(), cap)
            lastAdaptBitrateCap = cap
            lastEncoderTargetBps = (cap * 0.7).toInt()
            Log.d(TAG, "viewer#$viewerId 初始带宽 ${minOf(60_000, cap) / 1000}/${(cap * 0.7).toInt() / 1000}/${cap / 1000} kbps (档位$curAdaptLevel)")
        } catch (t: Throwable) {
            Log.w(TAG, "viewer#$viewerId setBitrate 失败: ${t.message}")
        }
    }

    /**
     * 采集就绪后补挂共享视频轨：viewer 可能先于采集启动加入（handleViewerJoined 立即建连），
     * 此时 localVideoTrack 尚为空，若不补挂该连接将全程无视频，且 videoSender 为空导致弱网自适应空转。
     */
    fun attachScreenTrackToViewers() {
        if (disposed) return
        viewerConnections.forEach { (vid, conn) ->
            if (conn.videoSender == null) attachScreenTrack(vid, conn)
        }
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
        // 超时兜底：WebRTC 内部竞态可能导致 createOffer/setLocalDescription 的回调
        // 从不触发（既无 onSuccess 也无 onFailure），negotiating 永久为 true 后该
        // viewer 再也无法重新协商（画面卡死只能重建连接）。15s 后强制释放锁。
        mainHandler.postDelayed({
            if (conn.negotiating.compareAndSet(true, false)) {
                Log.w(TAG, "viewer#$viewerId 协商超时，强制释放 negotiating 锁")
            }
        }, 15000)
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
        // 候选必须等远端描述设置成功才能 add：setRemoteDescription 异步未完成时
        // AddIceCandidate 返回 INVALID_STATE 静默丢弃，批量候选会永久丢失导致建连失败（H2）
        if (candidates.isNotEmpty()) {
            synchronized(pendingViewerCandidates) {
                pendingViewerCandidates.getOrPut(viewerId) { mutableListOf() }.addAll(candidates)
            }
        }
        pc.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                // 与 addViewerIce 写端共用同一把锁，避免遍历/移除时的并发修改（M1）
                synchronized(pendingViewerCandidates) {
                    pendingViewerCandidates[viewerId]?.forEach { pc.addIceCandidate(it) }
                    pendingViewerCandidates.remove(viewerId)
                }
                AppLogger.webrtc("viewer#$viewerId answer applied")
                // 新 viewer 完成 Answer 后立即触发关键帧，让观看端尽快拿到 I 帧出画面
                requestKeyFrame()
            }
            override fun onSetFailure(error: String?) {
                synchronized(pendingViewerCandidates) { pendingViewerCandidates.remove(viewerId) }
                Log.e(TAG, "viewer#$viewerId setRemoteDescription 失败: $error")
            }
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onCreateFailure(p0: String?) {}
        }, sdp)
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
        // 候选同样暂存到 onSetSuccess 后应用（H2，与 handleViewerAnswer 同因）
        if (candidates.isNotEmpty()) {
            synchronized(pendingViewerCandidates) {
                pendingViewerCandidates.getOrPut(viewerId) { mutableListOf() }.addAll(candidates)
            }
        }
        pc.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                // 应用暂存候选（H2：remoteDescription 就绪后方可 add）
                synchronized(pendingViewerCandidates) {
                    pendingViewerCandidates[viewerId]?.forEach { pc.addIceCandidate(it) }
                    pendingViewerCandidates.remove(viewerId)
                }
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
                }, constraints)
            }
            override fun onSetFailure(error: String?) {
                synchronized(pendingViewerCandidates) { pendingViewerCandidates.remove(viewerId) }
                Log.e(TAG, "viewer#$viewerId 重协商 setRemoteDescription 失败: $error")
            }
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onCreateFailure(p0: String?) {}
        }, sdp)
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
        // 与 handleViewerAnswer/handleViewerOffer 的写入用同一把锁，避免迭代时 CME（S5）
        synchronized(pendingViewerCandidates) { pendingViewerCandidates.remove(viewerId) }
        viewerRestartCounts.remove(viewerId)
        // 摄像头发送器同随连接移除，否则统计线程会遍历到已 dispose 的 RtpSender（H4）
        cameraViewerSenders.remove(viewerId)
        // 无 viewer 连接时解除掉帧锁定，避免残留状态影响后续会话（M9）
        if (viewerConnections.isEmpty() && viewerStallActive) {
            mainHandler.post { if (viewerStallActive) setViewerStall(false) }
        }
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
        // 由 ICE 回调（信令线程）触发，PC 创建/协商必须回到主线程：
        // 与主线程的 createOffer 混用线程会导致候选不生成（历史已定位的坑）
        mainHandler.post {
            if (disposed) return@post
            if (viewerConnections[viewerId] == null) return@post
            val count = viewerRestartCounts.getOrPut(viewerId) { 0 } + 1
            if (count > viewerMaxRestarts) {
                AppLogger.webrtc("viewer#$viewerId 重连超过${viewerMaxRestarts}次，放弃")
                removeViewer(viewerId)
                // 与主连接 restartConnection 双回调一致：只调 onConnectionFailed 时
                // MainActivity 只置标志+Toast，不触发结束会议/导航，host 侧界面会
                // 永久卡在"已连接"状态无法自愈或退出
                listener.onDisconnected()
                listener.onConnectionFailed()
                return@post
            }
            viewerRestartCounts[viewerId] = count
            AppLogger.network("viewer#$viewerId restarting connection ($count/$viewerMaxRestarts)")
            removeViewer(viewerId)
            val conn = createViewerConnection(viewerId)
            if (conn != null) {
                listener.onViewerRestarted(viewerId)
            } else {
                // 重建失败必须通知 UI，否则界面仍显示已连接（M8）
                AppLogger.webrtc("viewer#$viewerId 重连失败，通知 UI")
                listener.onConnectionFailed()
            }
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
        // 任一连接 removeTrack 失败就不能 dispose 轨道：track 仍挂在 PC 上，
        // dispose 悬空的 native 引用会崩溃（M12）
        var anyRemoveFailed = false
        micSender?.let { s ->
            try { pc?.removeTrack(s) } catch (t: Throwable) {
                anyRemoveFailed = true
                Log.w(TAG, "移除麦克风轨道失败: ${t.message}")
            }
        }
        micSender = null
        viewerConnections.forEach { (vid, conn) ->
            conn.micSender?.let { s ->
                try { conn.pc.removeTrack(s) } catch (t: Throwable) {
                    anyRemoveFailed = true
                    Log.w(TAG, "viewer#$vid 移除麦克风轨道失败: ${t.message}")
                }
            }
            conn.micSender = null
            if (negotiate) createOfferFor(vid)
        }
        // 释放顺序：先 track 后 source（track 持有对 native AudioSource 的引用，反序会悬空崩溃）
        if (!anyRemoveFailed) {
            localAudioTrack?.dispose()
        } else {
            Log.w(TAG, "存在移轨失败的连接，跳过 track dispose 避免悬空 native 引用")
        }
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
        // 由 ICE 回调（信令线程）触发，PC 操作回到主线程，避免与主线程 createOffer 混用（S6）
        mainHandler.post {
            if (disposed) return@post
            // 上一次 ICE restart 的 offer 尚未返回时，DISCONNECTED/FAILED/receiving-stopped
            // 可能接连触发；此时不重复发起、不重复计数，否则计数被空转耗尽过早放弃连接（H5）
            if (restartInFlight) return@post
            // 先判空再自增：pc 为 null 时不应消耗重连计数（M11）
            val pc = peerConnection ?: return@post
            if (reconnectCount >= maxReconnectAttempts) {
                AppLogger.webrtc("Reconnect failed (超过${maxReconnectAttempts}次)")
                connectionStatus = ConnectionStatus.FAILED
                listener.onConnectionFailed()
                listener.onDisconnected()
                return@post
            }
            reconnectCount++
            AppLogger.webrtc("Restart ICE $reconnectCount/$maxReconnectAttempts")
            try {
                pc.restartIce()
            } catch (t: Throwable) {
                Log.w(TAG, "restartIce() 异常，走重协商兜底: ${t.message}")
            }
            doIceRestart()
        }
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
     * 低端老设备统一判断（v1.248）：依据 ActivityManager.isLowRamDevice 与
     * largeMemoryClass 双重判断，结果惰性缓存（避免反复调 getSystemService）。
     * 低端机的硬编能力有限，CHANGELOG v1.231/v1.234/v1.235 均记载过提帧率/提档位
     * 会导致「帧率塌陷、整体观感更卡」，因此帧率上限与起始档位都要按此分流。
     */
    private val isLowEndDevice: Boolean by lazy {
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.isLowRamDevice || am.largeMemoryClass <= 256
        } catch (t: Throwable) {
            Log.w(TAG, "设备能力探测失败，按中高端设备处理: ${t.message}")
            false
        }
    }

    /**
     * 共享方：切换采集/编码帧率（观看方下发指令触发）。
     * v1.247: 不再直接改动 captureFps，而是记录 manualFpsOverride 交给自适应逻辑统一裁决：
     * - 档位0（网络良好）：手动值覆盖 captureFpsForLevel 的默认值
     * - 档位>=1（弱网）：忽略手动值，仍走弱网降档曲线保连续性
     * 这样既让按钮真正生效，又不会与弱网自适应互相打架反复重启采集器。
     */
    fun setFramerate(fps: Int) {
        if (disposed) return
        val target = when {
            fps <= 0 -> 0                       // 0 = 清除手动覆盖，回到自适应
            fps <= 30 -> 30                     // 「标准」档
            else -> highMotionFpsCap            // 「高帧率」档（48）
        }
        manualFpsOverride = target
        val wantFps = if (curAdaptLevel <= 0) {
            if (target > 0) target else captureFpsForLevel(0)
        } else {
            captureFpsForLevel(curAdaptLevel)
        }
        applyCaptureFps(wantFps, "手动切换")
    }

    /** 统一的采集帧率落地：同步采集器与编码器上限（避免两处值不一致） */
    private fun applyCaptureFps(fps: Int, tag: String) {
        if (fps <= 0 || fps == captureFps) return
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
            AppLogger.capture("$tag: 帧率 ${fps}fps (${capW}x${capH})")
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

            // v1.251: 起始采集与当前自适应档位对齐（初始档位 2 → 720p@28），避免开局
            // 1080p@48 瞬间打满弱 WiFi 空口队列（RTT 飙到秒级、丢包 60%+），随后又立刻
            // 降分辨率造成两次抖动。网络良好时自适应会在 ~12s 内逐级回升到 1080p。
            val initialProfile = captureProfileForLevel(curAdaptLevel)
            val (capW, capH) = captureSizeForLevel(initialProfile)
            captureFps = captureFpsForLevel(curAdaptLevel)
            lastCaptureProfile = initialProfile
            reportProgress("③c 启动采集 ${capW}x${capH}@${captureFps}（起始档位$curAdaptLevel）...")
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
                        // v1.249: 上限按设备分流——低端机 6M 防硬编热降频，中高端 12M 保持高清。
                        enc.maxBitrateBps = maxBitrateCap
                        enc.minBitrateBps = 1_000_000
                        // v1.246: 编码器帧率上限与采集一致（highMotionFpsCap），
                        // 避免编码上限 30 卡住 48fps 采集
                        enc.maxFramerate = highMotionFpsCap
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
                        peerConnection?.setBitrate(1_000_000, 4_000_000, maxBitrateCap)
                        Log.d(TAG, "已设置初始带宽 1/4/${maxBitrateCap / 1_000_000} Mbps")
                    } catch (t: Throwable) {
                        Log.w(TAG, "setBitrate 失败: ${t.message}")
                    }
                }
            } ?: run {
                Log.e(TAG, "localVideoTrack 为空，未添加视频轨道")
            }

            // 采集就绪：补挂视频轨到先于采集启动就加入的 viewer 连接（否则这些连接无视频且无法弱网自适应）
            attachScreenTrackToViewers()

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
        // 由 viewer ICE CONNECTED / answer onSetSuccess（信令线程）触发，回到主线程：
        // changeCaptureFormat 与主线程的 startScreenCapture/applyCaptureFps 并发会竞态重启采集器
        mainHandler.post {
            val capturer = videoCapturer ?: return@post
            val now = SystemClock.elapsedRealtime()
            if (now - lastKeyFrameAt.get() < 500L) return@post
            lastKeyFrameAt.set(now)
            try {
                val (capW, capH) = captureSizeForLevel(lastCaptureProfile)
                capturer.changeCaptureFormat(capW, capH, captureFps)
                AppLogger.capture("已请求关键帧 ${capW}x${capH}@${captureFps}")
            } catch (t: Throwable) {
                Log.w(TAG, "请求关键帧失败: ${t.message}")
            }
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
                        // v1.252: 观看端接收延迟构成。抖动缓冲/解码耗时在 SDK 中均为累计值，
                        // 必须连同分母（已发射帧数/已解码帧数）一起采集，才能换算成均值 ms。
                        var jbDelay = 0.0        // jitterBufferDelay（秒，累计）
                        var jbEmitted = 0.0      // jitterBufferEmittedCount（累计帧数）
                        var jbMinDelay = 0.0     // jitterBufferMinimumDelay（秒，累计）
                        var decodeTime = 0.0     // totalDecodeTime（秒，累计）
                        var decFrames = 0.0      // framesDecoded（累计，作为解码耗时分母）
                        var freezeCount = 0L     // freezeCount（累计冻结次数）
                        var freezeDuration = 0.0 // totalFreezesDuration（秒，累计冻结时长）
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
                        // v1.248: 选中的候选对 id（transport.selectedCandidatePairId）；
                        // 以及 nominated 兜底值（旧 SDK 不报 selectedCandidatePairId 时使用）
                        var selectedPairId = ""
                        var fbRtt = -1.0
                        var fbSelPath = ""
                        var fbPathType = ""
                        val stats = report.statsMap
                        // 第一遍：索引各 candidate 的 id -> 地址与类型（本地/远端）；并取选中候选对 id
                        val candAddr = HashMap<String, String>()
                        for ((_, s) in stats) {
                            val members = s.members ?: continue
                            if (s.type == "local-candidate" || s.type == "remote-candidate") {
                                val ip = (members["ip"] as? String) ?: ""
                                val port = (members["port"] as? Number)?.toInt() ?: 0
                                val ctype = (members["candidateType"] as? String) ?: "?"
                                candAddr[s.id] = "$ctype $ip:$port"
                            } else if (s.type == "transport") {
                                val sel = members["selectedCandidatePairId"] as? String
                                if (!sel.isNullOrEmpty()) selectedPairId = sel
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
                                    // v1.252: 抖动缓冲与解码耗时仅统计视频流（音频流缓冲语义不同，混入会失真）
                                    val inMedia = (s.members["mediaType"] as? String) ?: ""
                                    if (inMedia.isEmpty() || inMedia == "video") {
                                        jbDelay += (s.members["jitterBufferDelay"] as? Number)?.toDouble() ?: 0.0
                                        jbEmitted += (s.members["jitterBufferEmittedCount"] as? Number)?.toDouble() ?: 0.0
                                        jbMinDelay += (s.members["jitterBufferMinimumDelay"] as? Number)?.toDouble() ?: 0.0
                                        decodeTime += (s.members["totalDecodeTime"] as? Number)?.toDouble() ?: 0.0
                                        decFrames += (s.members["framesDecoded"] as? Number)?.toDouble() ?: 0.0
                                        freezeCount += (s.members["freezeCount"] as? Number)?.toLong() ?: 0L
                                        freezeDuration += (s.members["totalFreezesDuration"] as? Number)?.toDouble() ?: 0.0
                                    }
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
                                    // v1.253: fractionLost 只采纳视频流回报。本 SDK 会为音频/RTX 的
                                    // SSRC 也各给一条 remote-inbound-rtp，旧逻辑对全部条目 last-wins，
                                    // 会读到音频/RTX 的 8bit fractionLost。日志实测出现「rtt=25ms、
                                    // 丢包 89.5%/95.7%」这种自相矛盾的读数，直接触发 lossLevel=6 深降档。
                                    val rmKind = (s.members["mediaType"] as? String)
                                        ?: (s.members["kind"] as? String) ?: ""
                                    if (rmKind.isEmpty() || rmKind == "video") {
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
                                }
                                "candidate-pair" -> {
                                    val members = s.members
                                    if (members != null) {
                                        val nominated = members["nominated"] == true || members["nominated"]?.toString() == "true"
                                        val state = (members["state"] as? String) ?: ""
                                        val r = (members["currentRoundTripTime"] as? Number)?.toDouble()
                                        // 记录选中路径：local:host 192.168.x → remote:relay 1.2.x（local→remote 方向）
                                        val loc = (members["localCandidateId"] as? String) ?: ""
                                        val rem = (members["remoteCandidateId"] as? String) ?: ""
                                        val locStr = candAddr[loc] ?: "?"
                                        val remStr = candAddr[rem] ?: "?"
                                        val p = "$locStr → $remStr"
                                        val pt = locStr.substringBefore(" ") + "→" + remStr.substringBefore(" ")
                                        // v1.248: 只采纳真正生效的候选对。旧逻辑对任意 nominated=true 的对
                                        // last-wins，ICE 重连/多次提名后可能命中历史遗留对，读到过期或偏高
                                        // 的 RTT（实测 LAN 直连却报 713ms），触发 rttLevel 误降档把码率压死。
                                        // 优先 transport.selectedCandidatePairId；缺失时退回 nominated 且 succeeded。
                                        if (selectedPairId.isNotEmpty() && s.id == selectedPairId) {
                                            if (r != null && r > 0) rtt = r * 1000.0
                                            selPath = p
                                            pathType = pt
                                        } else if (nominated && (state == "succeeded" || state.isEmpty())) {
                                            if (fbRtt <= 0 && r != null && r > 0) fbRtt = r * 1000.0
                                            if (fbSelPath.isEmpty()) { fbSelPath = p; fbPathType = pt }
                                        }
                                    }
                                }
                                else -> {}
                            }
                        }
                        // v1.248: 无有效 selectedCandidatePairId 时退回 nominated+succeeded 的对
                        if (rtt <= 0 && fbRtt > 0) {
                            rtt = fbRtt
                            selPath = fbSelPath
                            pathType = fbPathType
                        }
                        // v1.251: 记录本次采样路径，供 NETWORK 日志输出（识别是否走了 relay 中继）
                        lastStatsPathType = pathType
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
                            // v1.252: 观看端延迟构成（累计值，调用方自行换算均值）
                            put("jbDelay", jbDelay)
                            put("jbEmitted", jbEmitted)
                            put("jbMinDelay", jbMinDelay)
                            put("decodeTime", decodeTime)
                            put("decFrames", decFrames)
                            put("freezeCount", freezeCount)
                            put("freezeDuration", freezeDuration)
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
    // v1.251: 初始档位由 0（9M）下调到 2（4M）——实测弱 WiFi 开局按 9M 出帧会瞬间打满
    // 空口队列，RTT 飙到 1.5~3s、丢包 60%+，自适应要几十秒才收敛（用户观感「开头很卡」）。
    // 从 4M 起步再按需回升，开局更平滑。
    @Volatile private var curAdaptLevel = 2
    @Volatile private var recoverTimer = 0
    // v1.251: 拥塞记忆——记录「最近一次拥塞降档发生时的质量档位」，恢复时不允许越过其下一档，
    // 防止「回升到 9M → 再次拥塞 → 崩到 800k」的周期震荡；长时间无拥塞后逐级松弛。
    private var minAdaptLevel = 0
    private var lastCongestionMs = 0L
    // v1.253: 60s 过长——档位被恢复上限锁住时画质长时间停在最差档。20s 无拥塞即逐级放宽，
    // 既保留"不立刻冲回高码率"的抑制，又让链路转好时能较快恢复清晰度。
    private val congestionForgetMs = 20_000L
    // v1.251: 最近一次统计到的候选对路径类型（host→srflx / relay→relay 等），写入 NETWORK 日志辅助定位
    @Volatile private var lastStatsPathType = ""
    // v1.241: 扩到 7 档——蜂窝上行普遍仅 1~2Mbps，编码器无降档余地时帧率被拥塞控制硬压到个位数
    // v1.243: 整体下移（9M 起步/800k 底档）与初始 9Mbps 一致，蜂窝链路 1M 档比 800k 档更平滑，
    // 避免网络状态机恢复时重新放宽到旧的 12Mbps 高位
    private val adaptBitrateCaps = intArrayOf(9_000_000, 6_000_000, 4_000_000, 2_800_000, 1_800_000, 1_200_000, 800_000)
    // 对端音频电平 0~32768（collectStatsFor 从 inbound audio 统计更新，供对讲状态指示）
    @Volatile private var remoteAudioLevel = 0.0

    /** 读取最近一次统计到的对端音频电平（0~32768，越大越响） */
    fun remoteAudioLevel(): Double = remoteAudioLevel
    // 增量丢包统计兜底（fractionLost 未上报时用累计值做差估算）
    // 由 WebRTC 统计回调线程写入，可能被主线程 disconnect 重置
    @Volatile private var lastOutSentCum = 0L
    @Volatile private var lastOutLostCum = 0L
    @Volatile private var lastAdaptBitrateCap = 0
    // v1.241: 实际发送码率 EMA 平滑值（带宽匹配档位用；EMA 无界递增风险：码率上限 12M，Double 无溢出）
    private var bwSmooth = 0.0
    // v1.248: 最近一次下发给编码器的目标码率（setBitrate 的 desired 值）。用于区分
    // 「链路受限」与「内容静止/编码输出少」——仅当实测码率远低于该目标且伴随拥塞迹象时
    // 才按实测带宽降档，避免自我降档死循环（低档→低目标→实测更低→继续降档）。
    private var lastEncoderTargetBps = 0
    // V3.1: 动态采集分辨率
    private var captureFps = 30
    private var lastCaptureProfile = 0
    // V1.187: 弱网自适应降帧率后的实际采集帧率（用于判断档位变化是否需要再次调整）
    private var lastCaptureFps = 30
    // V3.2: 采集防抖——切换分辨率后 4s 冷却，防止临界抖动导致 1080/720/480 来回跳
    private var lastCaptureSwitchMs = 0L
    // v1.257: 崩塌→恢复边缘检测。老设备 WiFi 周期性故障的恢复是瞬时的
    // （实测 rtt 2436ms→23ms 仅一个采样周期），好窗口仅 ~17s。
    @Volatile private var lastAdaptRttMs = 0
    // v1.295: 静态保持连续采样计数（配合 staticHoldBps 去抖动，见 applyNetworkAdaptation）
    private var staticHoldSamples = 0
    // v1.257: 恢复边缘待发的关键帧。崩塌期观看端抖动缓冲累积 200~290ms 陈旧帧、
    // 缓冲最小目标被棘轮抬高（实测恢复后 60s 仍残留 231ms），需要 I 帧让接收端
    // 丢弃全部待解码帧重新同步。与采集格式切换解耦：即使格式未变也补一个关键帧。
    @Volatile private var pendingRecoveryKeyFrame = false
    // v1.257: 实发低于此值视为「链路无容量证据」——屏幕静止（实发≈0）或链路
    // 已濒死（连 80k 都扛不住）时，回升分辨率既无意义（静态画面观看端已有帧）
    // 又危险（内容恢复运动往往立刻重新崩塌，实测 21:45:25 静态期回升 480p，
    // 21:45:29 内容一动 8 秒后链路再次死亡）。
    private val staticHoldBps = 80_000
    // v1.249: captureSwitchCooldownMs 已改为按设备分流的 get() 属性（见上方 highMotionFpsCap 附近）
    // v1.246 实验：高动态内容（视频播放）采集帧率上限。30fps 采集与 30fps 内容帧
    // 存在相位差导致系统性丢帧，提到 48fps 减少丢帧。仅档位0（网络良好）生效；
    // 设为 30 即可一键回退到旧行为。
    // v1.248: 低端老设备维持 30fps——硬编扛不住 48fps（CHANGELOG v1.231/v1.234/v1.235
    // 记载过低端机提帧率会帧率塌陷、观感更卡）。
    private val highMotionFpsCap: Int get() = if (isLowEndDevice) 30 else 48
    // v1.249: 低端机码率上限同步下调——硬编在高码率下更易触发热降频（v1.231 帧率
    // 塌陷的成因之一）。低端机顶档 6M，中高端保持 12M。
    private val maxBitrateCap: Int get() = if (isLowEndDevice) 6_000_000 else 12_000_000
    // v1.249: 低端机采集格式切换冷却期延长——v1.234 记载低端机持续降档会反复触发
    // changeCaptureFormat，负反馈循环加剧掉帧，需更长冷却抑制震荡。
    private val captureSwitchCooldownMs: Long get() = if (isLowEndDevice) 12_000L else 8_000L
    // v1.253: 降分辨率最短间隔。原先降档不受冷却约束，档位在 0↔6 间震荡时采集格式每 1.5s
    // 被重建一次（1080p→360p→720p…），每次都会重启采集器并触发关键帧，画面表现为持续卡顿。
    private val captureDowngradeCooldownMs = 5_000L
    // v1.247: 观看方手动帧率选择（0=未覆盖，走自适应；>0=档位0下覆盖自适应值）。
    // 弱网档位（>=1）下始终让位于弱网降档，避免手动值把帧率顶回高位导致卡顿。
    @Volatile private var manualFpsOverride = 0

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
     * v1.246: 档位0（网络良好）改用 highMotionFpsCap=48，缓解播放视频时与
     * 30fps 内容帧的相位差丢帧；弱网档位不变（带宽不足时提帧率只会更糟）。
     */
    private fun captureFpsForLevel(level: Int): Int {
        return when {
            level >= 6 -> 15 // 蜂窝超弱网：1M 带宽下 15fps 保每帧数据量（v1.241）
            level >= 5 -> 18
            level >= 4 -> 20
            level >= 3 -> 24
            level >= 2 -> 28
            // v1.247: 档位0 优先观看方手动值（未手动则用 highMotionFpsCap=48）
            else -> if (manualFpsOverride > 0) manualFpsOverride else highMotionFpsCap
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
            // v1.243: 严重掉帧（编码帧率<目标60%）/cpu瓶颈/观看端反馈立即降档；
            // 轻度持续掉帧仍需 2 次采样确认，避免统计瞬时波动误触发
            if (isEncLag) {
                encLoadSamples++
                encRecoverSamples = 0
                if ((encodedFps > 0 && encodedFps < target * 0.60) || cpuBottleneck || viewerStallActive || encLoadSamples >= 2) {
                    // v1.333: 降档时学习编码器实测帧率上限。仅编码侧证据可学习：有 fps 统计、
                    // 非观看端接收瓶颈（接收侧卡顿不代表编码能力差）、且 qualityLimit 非 bandwidth
                    // （带宽受限时 fps 被拥塞控制压低，非编码器能力上限，学了会误夹）。
                    // 取本次实测值并取下限 15（与最深弱网档帧率一致）：既能随热降频/内容
                    // 变化向下追踪真实能力，也由 encFpsReleaseMs 计时器在无瓶颈证据后逐级上调。
                    // 相同值也刷新计时器：周期性超载期间放开不应生效
                    if (encodedFps > 0 && !viewerStallActive && qualityLimit != "bandwidth") {
                        val learned = encodedFps.coerceAtLeast(15)
                        if (learned != encFpsCeiling) {
                            AppLogger.capture("编码帧率上限学习: $encFpsCeiling->$learned fps (目标$target)")
                            encFpsCeiling = learned
                        }
                        encFpsLearnMs = System.currentTimeMillis()
                    }
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
        // v1.333: 上限棘轮放开。要求当前编码帧率已达标（达标才说明有余量试探更高目标），
        // 且距最近一次学习已超 encFpsReleaseMs 无瓶颈证据。统计缺失时不放开（无余量证据）
        if (encFpsCeiling > 0 && encFpsCeiling < highMotionFpsCap &&
            (encodedFps <= 0 || encodedFps >= target * 0.9) &&
            System.currentTimeMillis() - encFpsLearnMs >= encFpsReleaseMs) {
            val released = minOf(highMotionFpsCap, encFpsCeiling + encFpsReleaseStep)
            AppLogger.capture("编码帧率上限放开: $encFpsCeiling->$released fps (无瓶颈证据${encFpsReleaseMs / 1000}s)")
            encFpsCeiling = released
            encFpsLearnMs = System.currentTimeMillis()
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
            // applyEncoderLoadProfile 操作 sender 参数，须在主线程与 startCameraVideo 等同步（S3）
            mainHandler.post { applyEncoderLoadProfile(true) }
        }
        // 超时兜底：观看方崩溃/退出后不再发恢复消息时，避免 encLoadDown 锁死到会话结束（M9）
        mainHandler.removeCallbacks(stallTimeoutRunnable)
        if (active) mainHandler.postDelayed(stallTimeoutRunnable, STALL_TIMEOUT_MS)
    }

    private val stallTimeoutRunnable = Runnable {
        if (viewerStallActive) {
            AppLogger.capture("观看端掉帧反馈超时未恢复，自动解除锁定")
            setViewerStall(false)
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
        // v1.333: 目标帧率夹到编码器实测上限（encFpsCeiling，0=未学习则不夹）。
        // 降档与回升都取 min，回升不再回到编码器扛不住的 highMotionFpsCap，
        // 消除 1920@48 ↔ 1280@24 的周期翻转
        val ceiling = if (encFpsCeiling > 0) encFpsCeiling else Int.MAX_VALUE
        val targetFps = if (down) minOf(baseFps, 24, ceiling) else minOf(baseFps, ceiling)
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
        val profileChanged = effective != lastCaptureProfile
        if (profileChanged || targetFps != captureFps) {
            val now = System.currentTimeMillis()
            val isDowngrade = effective > lastCaptureProfile || targetFps < captureFps
            val cooldownOk = now - lastCaptureSwitchMs >= captureSwitchCooldownMs
            val downgradeOk = now - lastCaptureSwitchMs >= captureDowngradeCooldownMs
            // v1.253: 降质也受冷却约束（原先立即执行），避免档位抖动时反复重建采集格式
            if ((isDowngrade && downgradeOk) || (!isDowngrade && cooldownOk)) {
                lastCaptureProfile = effective
                lastCaptureFps = targetFps
                captureFps = targetFps
                lastCaptureSwitchMs = now
                try {
                    // v1.254: 仅帧率变化不重启采集器（同 applyNetworkAdaptation）
                    if (profileChanged) {
                        val capturer = videoCapturer
                        if (capturer != null) {
                            val (capW, capH) = captureSizeForLevel(effective)
                            capturer.changeCaptureFormat(capW, capH, targetFps)
                            AppLogger.capture("编码负载分辨率: ${capW}x${capH}@${targetFps} 档位$effective")
                        }
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
    fun adaptToNetwork(fractionLossPct: Double, outSentCum: Long, outLostCum: Long, rttMs: Int, actualBitrateBps: Int, qualityLimit: String, encodedFps: Int) {
        val pc = peerConnection ?: return
        if (disposed) return
        val sender = videoSender ?: return
        applyNetworkAdaptation(pc, sender, "主连接", fractionLossPct, outSentCum, outLostCum, rttMs, actualBitrateBps, qualityLimit, encodedFps)
    }

    /**
     * V4 host 端：对指定 viewer 连接执行弱网自适应（1 对 1 模式的实际视频承载连接）。
     * 与 adaptToNetwork 共用档位状态机，但作用于该 viewer 的 pc 与 videoSender，
     * 让 host 的 1 对 1 连接也能在弱网时自动降码率/降分辨率保流畅。
     */
    fun adaptViewerNetwork(viewerId: Int, fractionLossPct: Double, outSentCum: Long, outLostCum: Long, rttMs: Int, actualBitrateBps: Int, qualityLimit: String, encodedFps: Int) {
        if (disposed) return
        val conn = viewerConnections[viewerId] ?: return
        val sender = conn.videoSender ?: return
        applyNetworkAdaptation(conn.pc, sender, "viewer#$viewerId", fractionLossPct, outSentCum, outLostCum, rttMs, actualBitrateBps, qualityLimit, encodedFps)
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
        actualBitrateBps: Int,
        qualityLimit: String,
        encodedFps: Int
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
        // v1.255: rtt>=900 视为链路崩塌（实测 realme WiFi 周期性掉到 ~0-150kbps），
        // 直接降到最深档 6，比"先 5 后 6"少一个采样周期的 1200k 上限。
        val rttLevel = when {
            rttMs >= 900 -> 6
            rttMs >= 600 -> 4
            rttMs >= 350 -> 2
            rttMs >= 200 -> 1
            else -> 0
        }
        // v1.255: 崩塌标志——rtt 跳变到 900ms+ 时采集格式切换绕过冷却立即执行（见下方切换块）。
        // 本机编码器中途改 maxBitrateBps 不生效，必须 changeCaptureFormat 重配才遵守新码率；
        // 若切换被 5s 冷却阻塞，1080p 采集器会以 2-4Mbps（单关键帧 300-800KB）灌入死链路，
        // 队列堆积数 MB → rtt 顶在 2.3s 长达 14s、观看端 0 帧（v1.254 实测）。
        // v1.256: 补上高丢包判据。老设备崩塌常以丢包先行（实测 77% 丢包时 rtt 仍 10ms），
        // 只看 rtt 会晚 1~2 个采样周期，届时档位/cap 已稳定、切换块被"cap 未变"跳过。
        val collapse = rttMs >= 900 || sendLossPct >= 30.0
        // v1.241: 带宽匹配档位——链路带宽不足时（如蜂窝上行 1M 而档位上限 4M），编码器无降档余地、
        // 帧率被硬压；按实测带宽从高到低选第一个码率上限 ≤ 实测带宽×1.6 的档位参与取大，
        // 让采集分辨率/帧率主动降到与链路匹配，帧率优先保连续。带宽波动由"降档立即、回升 6s/档"兜底。
        // v1.248 修正：实测码率受内容/编码影响，不能直接等同链路带宽（见下方 linkShortfall 判定）。
        var bwLevel = 0
        if (actualBitrateBps > 0) {
            // EMA 平滑（α=0.5）：蜂窝带宽 1.5s 窗口波动大，防瞬时毛刺误降档
            bwSmooth = if (bwSmooth <= 0) actualBitrateBps.toDouble()
            else bwSmooth * 0.5 + actualBitrateBps * 0.5
            // v1.248: 只在「链路确实交付不出我们下发的目标码率」且伴随拥塞迹象时才按实测码率降档。
            // 旧逻辑直接拿实测码率当带宽估计，会与档位形成死循环——档位越低→下发目标越低→实测
            // 越低→判定带宽越差→继续降档，最终自锁在最低档（实测画面卡成 2fps 即此因：LAN 直连
            // 无带宽瓶颈，但老设备编码输出少被误判成链路受限）。以「目标码率」为参照可区分：
            // 实测≈目标 说明链路能满足需求（码率低是内容/编码所致），实测远低于目标才是链路受限。
            val targetBps = if (lastEncoderTargetBps > 0) lastEncoderTargetBps else bwSmooth.toInt()
            val linkShortfall = actualBitrateBps < targetBps * 0.55
            // 是否真的被链路带宽限制：优先用编码器自报的 qualityLimitationReason（bandwidth=编码想发
            // 更多却发不出，是链路受限的权威信号）；机型不报该字段时退回丢包/RTT 拥塞迹象兜底。
            val bwLimited = qualityLimit.equals("bandwidth", ignoreCase = true)
            val congestion = sendLossPct >= 1.0 || rttMs >= 250
            val bwEvidence = if (qualityLimit.isNotEmpty()) bwLimited else congestion
            if (linkShortfall && bwEvidence) {
                // 默认最高档兜底（带宽低于最低档阈值 1M/1.6≈625kbps 时仍深降，不停留在高档）
                bwLevel = adaptBitrateCaps.size - 1
                for (i in adaptBitrateCaps.indices) {
                    if (adaptBitrateCaps[i] <= bwSmooth * 1.6) {
                        bwLevel = i
                        break
                    }
                }
            }
        }
        val level = maxOf(lossLevel, rttLevel, bwLevel)
        // v1.253: 仍处于（或重于）当前档位的拥塞时刷新遗忘计时器。否则持续丢包/高 RTT
        // 期间 minAdaptLevel 会被误判为"已平静"而逐级放开，回升后再次拥塞形成长期震荡。
        // v1.257: 链路崩塌→恢复边缘检测。rtt 从 >=900 骤降到 <200 说明老设备
        // WiFi 周期性故障已解除（实测 2436ms→23ms 一个采样周期，且故障与码率无关）。
        // 该边缘上：强制关键帧排空观看端残留缓冲、放开静态抑制允许立即回升。
        // v1.295: 阈值放宽到 <350。真机实测链路恢复常是渐进的（1706→1168→346ms），
        // 原阈值要求瞬时掉到 <200 才触发，RTT 缓慢回落时永远命中不了，
        // 关键帧排空（解观看端缓冲膨胀）与立即回升全部错过，档位 6 锁死 3.5 分钟。
        // RTT 从崩塌降到 350ms 已足以证明链路脱离濒死、可用。
        val linkRecovered = lastAdaptRttMs >= 900 && rttMs < 350
        lastAdaptRttMs = rttMs
        if (linkRecovered) {
            pendingRecoveryKeyFrame = true
            // 丢一次恢复等待，让本次采样即可回升（rtt 已证明链路可用，无需再等 6s）
            recoverTimer = 4
        }
        if (level > 0 && level >= curAdaptLevel) {
            lastCongestionMs = System.currentTimeMillis()
        }
        if (level > curAdaptLevel) {
            // v1.253: 记录"拥塞发生前正在工作的档位"作为恢复上限。v1.251 用 curAdaptLevel+1，
            // 当仅降一档（如 5→6）时上限=6，恢复判定 6-1>=6 恒为假 → 档位 6 被锁死直到
            // congestionForget（60s）到期；日志实测 319x640@15 持续 47~60s，即"画质特别差"主因。
            // 0 档（9M）过于激进，任何一次拥塞后不再盲目回到 0，最低记到 1（6M）。
            minAdaptLevel = maxOf(minAdaptLevel, curAdaptLevel.coerceAtLeast(1))
            lastCongestionMs = System.currentTimeMillis()
            curAdaptLevel = level
            recoverTimer = 0
        } else if (level < curAdaptLevel) {
            // 网络好转：连续 4 次（约 6s）回升一档，避免抖动。
            // v1.240 由 8 次（12s）提速——蜂窝/跨网场景从最高档回满约 24s（原 48s），
            // 弱网缓解后画质恢复更及时；6s 窗口仍足以滤除蜂窝 RTT 瞬时波动
            // v1.295: 静态抑制——实发 <= staticHoldBps 时暂停回升计数。屏幕静止时
            // 实发≈0 是内容所致而非链路改善的证据，此时回升只是白白触发
            // changeCaptureFormat（重启采集器+关键帧，撑高刚排空的队列）；
            // 且老设备内容恢复运动后往往立刻重新崩塌（实测静态期回升 480p，
            // 8 秒后链路再死）。链路恢复边缘已在上文把 recoverTimer 预置为 4，
            // 若此刻内容正在运动则本周期即可回升；内容静止则继续被抑制，
            // 等内容动起来再按常规节奏回升。
            // v1.295: 改为连续 3 次极低才抑制。弱档位下实发随内容动静在 50~250k 波动，
            // 单次跌破 80k 就归零会让 recoverTimer 永远到不了阈值（真机实测档位 6 锁死
            // 3.5 分钟，期间 RTT 早已回落到 346ms，OPPO 端冻结 30+ 次、缓冲膨胀 644ms）
            // v1.295: 同时校验 RTT 未在恶化——局域网正常 RTT <50ms，涨到 100ms+ 已是
            // 早期拥塞信号，但 rttLevel 阈值 200 偏高捕捉不到。真机实测开局 4M→6M
            // 回升时 rtt 已 116ms 仍照回不误，实发冲到 1980k 随即 RTT 尖峰 1706ms 崩到档位 6。
            // 健康门槛随档位深而放宽（深档位链路本身 RTT 就高）
            val staticHold = actualBitrateBps in 0..staticHoldBps
            val healthyRtt = when {
                curAdaptLevel >= 5 -> 400
                curAdaptLevel >= 3 -> 250
                else -> 150
            }
            if (staticHold || rttMs > healthyRtt) {
                if (staticHold) {
                    if (staticHoldSamples < 3) staticHoldSamples++
                    if (staticHoldSamples >= 3) recoverTimer = 0
                } else {
                    staticHoldSamples = 0
                }
            } else {
                staticHoldSamples = 0
                recoverTimer++
            }
            // v1.295: 深档位（5/6，濒死档）加速回升——画质已极差，早回升一档收益大；
            // 2 次采样（约 3s）仍足以滤除 RTT 瞬时毛刺
            val recoverNeeded = if (curAdaptLevel >= 5) 2 else 4
            if (recoverTimer >= recoverNeeded) {
                // v1.251: 拥塞记忆抑制回升——不越过最近一次被迫降档的档位，
                // 避免回升到 9M 后再次拥塞形成周期震荡
                if (curAdaptLevel - 1 >= minAdaptLevel) curAdaptLevel--
                recoverTimer = 0
            }
        }
        // v1.251/v1.253: 无拥塞降档事件持续 20s 后逐级放开恢复上限，重新具备试探更高质量的余地
        if (minAdaptLevel > 0 &&
            System.currentTimeMillis() - lastCongestionMs >= congestionForgetMs) {
            minAdaptLevel--
            lastCongestionMs = System.currentTimeMillis()
        }
        // v1.249: 低端机顶档截到 maxBitrateCap，避免硬编在高码率下热降频
        val cap = minOf(adaptBitrateCaps[curAdaptLevel], maxBitrateCap)
        // 摄像头通话轨随档位同步自适应（码率/帧率上限），弱网时降低人脸画面数据量
        applyCameraAdaptation()
        // 仅档位变化时调码率/策略，避免周期重置影响拥塞控制收敛
        if (lastAdaptBitrateCap != cap) {
            lastAdaptBitrateCap = cap
            try {
                // v1.243: 下限随档位下调（min(500k, cap)），深档（800k 底档）时 min 不再硬卡 1M，
                // 避免 min>max 的不一致区间干扰拥塞控制收敛
                // v1.253: 下限进一步降到 150k。日志显示深档时 BWE 被 min=500k 托住，而链路
                // 瞬时可能只有 300~450k，队列无法排空（rtt 稳定停在 1.5~1.8s、丢包却为 0）。
                // v1.254: 再降到 60k。v1.253 实测链路容量约 147kbps（rtt 以 ~2.5kbps 的净堆积
                // 速率缓慢爬升，正好是 150k 下限与 147k 链路的差值），min=150k 仍把 BWE 托在
                // 链路之上 → 队列只增不减、rtt 长期 1~2s。下限必须低于链路最差状态才能排空积压。
                pc.setBitrate(minOf(60_000, cap), (cap * 0.7).toInt(), cap)
                // v1.248: 记录当前下发的目标码率，作为带宽匹配的参照基准（见 bwLevel）
                lastEncoderTargetBps = (cap * 0.7).toInt()
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
                // v1.251: 同步收紧编码器码率上限。此前只调 pc.setBitrate（BWE 目标），编码器
                // maxBitrateBps 仍停在初始 9M，弱网降档后编码器继续按高码率出帧 → 发送队列积压
                // → 实测码率长期高于档位上限、RTT 被撑到 1.5~3s、丢包 60%+。现让编码器本身遵守 cap。
                params.encodings?.firstOrNull()?.let { enc ->
                    enc.maxBitrateBps = cap
                    // v1.254: 与 pc.setBitrate 下限一致，放开到 60k 才能在 ~147kbps 的
                    // 极弱链路上排空积压（见下限说明）
                    enc.minBitrateBps = minOf(60_000, cap)
                }
                sender.parameters = params
                val capTxt = if (cap >= 1_000_000) "${cap / 1_000_000}M" else "${cap / 1000}k"
                Log.d(TAG, "$tag 弱网自适应: 丢包${"%.1f".format(sendLossPct)}% rtt=${rttMs}ms 实发${actualBitrateBps / 1000}k 档位${curAdaptLevel} 码率上限$capTxt 策略=$degradation")
            } catch (t: Throwable) {
                Log.w(TAG, "$tag 自适应切分辨率策略失败: ${t.message}")
            }
        }
        // V3.1: 采集侧降分辨率——弱网档位>=2 降720p、>=3 降480p，减轻采集+编码双端负载；
        // 恢复档位0 回升 1080p（v1.243: 顶档码率上限 9M）
        // V3.2: 防抖——降质立即执行；回升需冷却 4s，避免 1080/720/480 临界来回跳
        // V1.120: 与编码负载自适应档位取较大值（编码瓶颈时即使网络好也保持降档）
        // v1.256: 本块从 cap 变化块内提出，改为每个采样周期都评估。原先档位不变时
        // cap 不变 → 切换块整段跳过，一旦某次切换被冷却挡住就再也不会重试，采集格式
        // 卡在 1080p 长达 19s（老设备实测：丢包 77% 降档到 6 时被挡，之后 rtt 升到
        // 2800ms 但档位/cap 未变，1080p 采集器持续灌死链路）。
        val weakProfile = captureProfileForLevel(curAdaptLevel)
        val targetProfile = if (encLoadDown) maxOf(weakProfile, 1) else weakProfile
        // V1.187: 采集侧同步降帧率——档位>=2 时 30→28→24→20，异地/中继高 RTT 下
        // 单帧数据量变大、拥塞控制收敛慢，降帧率能显著缓解积压掉帧，观感更连续
        // v1.294: 帧率上限须与 applyEncoderLoadProfile 用同一公式（编码负载降档时夹到 24），
        // 否则 level 0 + 编码降档时此处把 captureFps 抬回 48，而采集器仍是 24fps，
        // adaptToEncoderLoad 用 target=48 判定编码滞后又降回 24，24↔48 振荡且永久无法恢复（M2）
        val baseFps = captureFpsForLevel(curAdaptLevel)
        val targetFps = if (encLoadDown) minOf(baseFps, 24) else baseFps
        val profileChanged = targetProfile != lastCaptureProfile
        if (profileChanged || targetFps != captureFps) {
            val now = System.currentTimeMillis()
            val isDowngrade = targetProfile > lastCaptureProfile || targetFps < captureFps
            val cooldownOk = now - lastCaptureSwitchMs >= captureSwitchCooldownMs
            val downgradeOk = now - lastCaptureSwitchMs >= captureDowngradeCooldownMs
            // v1.253: 降质也受冷却约束（原先立即执行），避免档位抖动时反复重建采集格式
            // v1.255: 崩塌时绕过冷却立即降采集格式。冷却本意是抑制 4↔5↔6
            // 单档抖动，但崩塌时每多等 1.5s 就多灌 ~5MB 进死链路，代价完全不对称。
            // v1.256: 崩塌判据补上高丢包——老设备崩塌常以丢包先行（实测 77% 丢包时
            // rtt 仍 10ms，rtt 判据要晚 1~2 个采样周期才触发，届时切换块已被 cap 未变
            // 跳过）。
            if (collapse || (isDowngrade && downgradeOk) || (!isDowngrade && cooldownOk)) {
                lastCaptureProfile = targetProfile
                lastCaptureFps = targetFps
                captureFps = targetFps
                lastCaptureSwitchMs = now
                try {
                    // v1.254: 仅帧率变化时只改编码器上限、不重启采集器；只有分辨率档位
                    // 变化才走 changeCaptureFormat。后者每次都会重建采集管线并触发关键帧，
                    // 是弱网档位在 4↔5↔6 间抖动时画面一卡一卡的直接来源。
                    if (profileChanged) {
                        val capturer = videoCapturer
                        if (capturer != null) {
                            val (capW, capH) = captureSizeForLevel(targetProfile)
                            capturer.changeCaptureFormat(capW, capH, targetFps)
                            // v1.257: changeCaptureFormat 已产生关键帧，无需恢复补帧
                            pendingRecoveryKeyFrame = false
                            AppLogger.capture("动态分辨率: ${capW}x${capH}@${targetFps} ($tag 档位$curAdaptLevel)")
                        }
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
        // v1.257: 链路恢复后补发关键帧。崩塌期观看端抖动缓冲累积 200~290ms 陈旧帧、
        // 缓冲最小目标被棘轮式抬高（实测恢复后 60s 仍残留 231ms、每 2s 仅排空 3~8ms）。
        // 采集格式若已切换，changeCaptureFormat 本身已产生关键帧，标志位在切换块内
        // 清掉；此处只处理"格式未变但仍需重同步"的情形。requestKeyFrame 内部有
        // 500ms 防抖，且只在采集器存在时生效。
        if (pendingRecoveryKeyFrame) {
            pendingRecoveryKeyFrame = false
            requestKeyFrame()
        }
        // v1.249: 每次自适应采样落盘一行摘要（不再受全屏限制），现场导出日志即可看到
        // 档位/实测码率/RTT/丢包/编码瓶颈，无需用户进入全屏复现。
        // v1.254: 追加编码器实际输出帧率与"内容受限"标记。实发码率远低于目标码率时，
        // 低码率来自画面静止/编码器输出少（内容受限），而非链路带宽——此时降档只会
        // 反复重建采集格式，无法降低实发，是"画质差却仍卡"的判别依据。
        val contentLimited = actualBitrateBps > 0 && lastEncoderTargetBps > 0 &&
            actualBitrateBps < lastEncoderTargetBps * 0.6
        AppLogger.network(
            "$tag 档位${curAdaptLevel} 上限${cap / 1000}k 目标${lastEncoderTargetBps / 1000}k " +
                "实发${actualBitrateBps / 1000}k 编码${encodedFps}fps" +
                (if (contentLimited) " 内容受限" else "") +
                " 丢包${"%.1f".format(sendLossPct)}% rtt=${rttMs}ms " +
                "瓶颈=${qualityLimit.ifEmpty { "-" }} 路径=${lastStatsPathType.ifEmpty { "-" }}"
        )
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
        // v1.251: 与字段初值一致，新会话从档位 2（4M）保守起步
        curAdaptLevel = 2
        recoverTimer = 0
        minAdaptLevel = 0
        lastCongestionMs = 0L
        lastStatsPathType = ""
        lastOutSentCum = 0L
        lastOutLostCum = 0L
        lastAdaptBitrateCap = 0
        staticHoldSamples = 0
        bwSmooth = 0.0
        lastEncoderTargetBps = 0
        encLoadDown = false
        encLoadSamples = 0
        encRecoverSamples = 0
        encNoStatSamples = 0
        encFpsCeiling = 0
        encFpsLearnMs = 0L
        viewerStallActive = false
        lastCaptureFps = 30
        manualFpsOverride = 0
        lastCameraBitrateCap = 0
        lastCameraFpsCap = 0
    }

    fun disconnect() {
        if (disposed) return
        disposed = true
        mainHandler.removeCallbacks(iceRecoveryWatchdog)
        mainHandler.removeCallbacks(stallTimeoutRunnable)
        resetAdaptiveState()
        restartInFlight = false
        reconnectCount = 0
        // V4: 先同步关闭所有 PC：close() 使 PC 进入 CLOSED 状态、停止媒体流与
        // native 回调。必须先于轨道释放执行——否则 PC 仍引用已 dispose 的
        // track/source，native 层 use-after-free 随机崩溃（removeViewer 的 close
        // 在 post 里异步，无法保证顺序）
        viewerConnections.values.forEach { conn ->
            try { conn.pc.close() } catch (_: Throwable) {}
        }
        peerConnection?.let { try { it.close() } catch (_: Throwable) {} }
        // 清理所有 viewer 连接（PC dispose 统一 post 主线程，close 已同步完成，dispose 无回调风险）
        viewerConnections.keys.toList().forEach { removeViewer(it) }
        synchronized(pendingViewerCandidates) { pendingViewerCandidates.clear() }
        // 屏幕采集：按 stopCapture→capturer→track→source→helper 顺序统一释放
        releaseScreenCapture()
        stopCameraVideo()
        // 麦克风：先 track 后 source（track 持有 native AudioSource 引用，反序悬空崩溃）
        try { localAudioTrack?.dispose() } catch (_: Throwable) {}
        // v1.259: 远端音轨由 PeerConnection 持有，只需摘引用避免 dispose 后误用
        remoteAudioTrack = null
        localAudioTrack = null
        try { micAudioSource?.dispose() } catch (_: Throwable) {}
        micAudioSource = null
        systemAudioListener = null
        try { controlChannel?.dispose() } catch (_: Throwable) {}
        controlChannel = null
        controlListener = null
        videoSender = null
        micSender = null
        // v1.294: 主连接释放也统一 post 主线程，与 viewer PC 一致，
        // 避免 dispose 与信令线程的回调交叉操作同一 native 对象（M14）
        val pc = peerConnection
        peerConnection = null
        mainHandler.post {
            try { pc?.close() } catch (_: Throwable) {}
            try { pc?.dispose() } catch (_: Throwable) {}
        }
        Log.d(TAG, "WebRTC 已断开并清理")
    }
}
