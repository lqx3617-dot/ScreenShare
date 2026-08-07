package com.screenshare

import android.content.Context
import android.media.projection.MediaProjection
import android.util.Log
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
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
                        "WebRTC-JitterEstimatorConfig/nack_limit:15,nack_count_timeout:5s/" +
                            // 零播放延迟渲染：到达即渲染，配合低延迟渲染路径进一步压播放缓冲。
                            // min_pacing 为解码最小帧间隔，默认 8ms 足够，显式声明避免默认值漂移
                            "WebRTC-ZeroPlayoutDelay/min_pacing:8ms/"
                    )
                    .createInitializationOptions()
            )
            initialized = true
        }
    }

    private fun getFactory(): PeerConnectionFactory {
        return singletonFactory ?: synchronized(this) {
            singletonFactory ?: PeerConnectionFactory.builder()
                // 屏幕共享：H264 High profile（同画质压缩率更高，跨网带宽占用更低，打开应用等动态画面更稳）。
                // Baseline 无 B 帧延迟最低，但压缩率低，跨网带宽受限时反而更易卡；High profile 的 B 帧延迟约 20-40ms，可接受
                .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBaseContext, true, true))
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
        fun onRemoteVideoTrack(videoTrack: VideoTrack)
        fun onIceGatheringComplete() {}
        /** ICE 状态变化（CHECKING/CONNECTED/FAILED...），用于 UI 显示诊断信息 */
        fun onIceState(state: String) {}
    }

    private var peerConnection: PeerConnection? = null
    private var localVideoTrack: VideoTrack? = null
    private var localAudioTrack: AudioTrack? = null
    private var videoCapturer: VideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var disposed = false

    // 系统音频 DataChannel（共享方发送 / 观看方接收）
    private var systemAudioChannel: DataChannel? = null
    private var systemAudioListener: ((ByteArray) -> Unit)? = null

    // 控制 DataChannel（观看方 → 共享方下发指令，如切换帧率）
    private var controlChannel: DataChannel? = null
    private var controlListener: ((String) -> Unit)? = null

    // 视频发送器（切换帧率时更新编码参数）
    private var videoSender: org.webrtc.RtpSender? = null

    // 麦克风语音（会议内双向对讲）：标准 WebRTC 音频轨道
    private var micAudioSource: AudioSource? = null
    private var micSender: org.webrtc.RtpSender? = null

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
                PeerConnection.IceConnectionState.CONNECTED -> listener.onConnected()
                PeerConnection.IceConnectionState.DISCONNECTED,
                PeerConnection.IceConnectionState.FAILED -> listener.onDisconnected()
                else -> {}
            }
        }
        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
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

        @Deprecated("Deprecated in Java")
        override fun onAddStream(stream: MediaStream?) {
            stream?.videoTracks?.firstOrNull()?.let { listener.onRemoteVideoTrack(it) }
        }
        @Deprecated("Deprecated in Java")
        override fun onRemoveStream(stream: MediaStream?) {}
        override fun onDataChannel(channel: org.webrtc.DataChannel?) {
            Log.d(TAG, "onDataChannel: ${channel?.label()}")
            if (channel?.label() == SYSTEM_AUDIO_LABEL) {
                channel.registerObserver(object : DataChannel.Observer {
                    override fun onBufferedAmountChange(previousAmount: Long) {}
                    override fun onStateChange() {}
                    override fun onMessage(buffer: DataChannel.Buffer) {
                        if (buffer.binary) {
                            val data = ByteArray(buffer.data.remaining())
                            buffer.data.get(data)
                            systemAudioListener?.invoke(data)
                        }
                    }
                })
            } else if (channel?.label() == CONTROL_LABEL) {
                // 观看方侧：保存控制通道引用（用于向共享方发送指令），并接收可能的回应
                controlChannel = channel
                registerControlObserver(channel)
            }
        }
        override fun onRenegotiationNeeded() {}
        override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
            val track = receiver?.track() as? VideoTrack
            if (track != null) {
                listener.onRemoteVideoTrack(track)
            }
        }
        override fun onTrack(track: RtpTransceiver?) {
            val vt = track?.receiver?.track() as? VideoTrack
            if (vt != null) listener.onRemoteVideoTrack(vt)
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

    // ==================== 麦克风语音（会议内双向对讲） ====================

    /**
     * 开启麦克风：创建 WebRTC 标准音频轨道（Opus + AEC + 降噪）并加入连接。
     * 成功后调用方需触发 [renegotiate] 让对端收到含音频轨道的新 Offer。
     * @return true 表示轨道已添加；false 表示失败（调用方应提示用户）。
     */
    fun startMicAudio(): Boolean {
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
            Log.d(TAG, "麦克风音频轨道已添加")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "启动麦克风失败: ${t.message}")
            false
        }
    }

    /** 停止麦克风：移除轨道并释放音频源（调用方随后应触发重协商） */
    fun stopMicAudio() {
        if (disposed) return
        val pc = peerConnection
        micSender?.let { s ->
            try { pc?.removeTrack(s) } catch (t: Throwable) {
                Log.w(TAG, "移除麦克风轨道失败: ${t.message}")
            }
        }
        micSender = null
        micAudioSource?.dispose()
        micAudioSource = null
        localAudioTrack?.dispose()
        localAudioTrack = null
        Log.d(TAG, "麦克风音频已停止")
    }

    /** 静音/取消静音（直接启用/停用轨道，不触发重协商） */
    fun setMicMuted(muted: Boolean) {
        localAudioTrack?.setEnabled(!muted)
        Log.d(TAG, "麦克风${if (muted) "静音" else "取消静音"}")
    }

    /** 是否已开启麦克风 */
    fun isMicOn(): Boolean = micAudioSource != null

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

    // ==================== 系统音频 DataChannel ====================

    /** 共享方：创建系统音频 DataChannel（低延迟：乱序、不重传） */
    fun createSystemAudioChannel(): DataChannel? {
        val pc = peerConnection ?: return null
        try {
            val init = DataChannel.Init().apply {
                ordered = false
                maxRetransmits = 0
            }
            val dc = pc.createDataChannel(SYSTEM_AUDIO_LABEL, init)
            systemAudioChannel = dc
            Log.d(TAG, "系统音频 DataChannel 已创建")
            return dc
        } catch (t: Throwable) {
            Log.e(TAG, "创建系统音频 DataChannel 失败: ${t.message}")
            return null
        }
    }

    /** 共享方：发送一段系统音频 PCM 数据 */
    fun sendSystemAudio(data: ByteArray) {
        val dc = systemAudioChannel ?: return
        if (dc.state() == DataChannel.State.OPEN) {
            try {
                dc.send(DataChannel.Buffer(java.nio.ByteBuffer.wrap(data), true))
            } catch (t: Throwable) {
                Log.w(TAG, "发送系统音频失败: ${t.message}")
            }
        }
    }

    /** 观看方：注册系统音频接收回调（收到的 PCM 交给播放器） */
    fun setSystemAudioListener(listener: (ByteArray) -> Unit) {
        systemAudioListener = listener
    }

    // ==================== 控制 DataChannel（帧率切换等） ====================

    /** 共享方：创建控制 DataChannel 并注册接收观看方指令 */
    fun createControlChannel(): DataChannel? {
        val pc = peerConnection ?: return null
        try {
            // 部分可靠：无序 + 最多重传1次。丢包不阻塞后续手势指令（swipe 为完整路径，丢失可由下一指令覆盖），
            // 网络抖动时依然保持跟手；tap/key 等低频指令丢失概率极低
            val init = DataChannel.Init().apply {
                ordered = false
                maxRetransmits = 1
            }
            val dc = pc.createDataChannel(CONTROL_LABEL, init)
            controlChannel = dc
            registerControlObserver(dc)
            Log.d(TAG, "控制 DataChannel 已创建")
            return dc
        } catch (t: Throwable) {
            Log.e(TAG, "创建控制 DataChannel 失败: ${t.message}")
            return null
        }
    }

    /** 控制数据通道是否已打开（观看方启用控制模式前检查） */
    fun controlChannelOpen(): Boolean = controlChannel?.state() == DataChannel.State.OPEN

    /** 观看方：经控制通道向共享方发送指令（如 {"type":"fps","value":30}） */
    fun sendControl(message: String) {        val dc = controlChannel ?: return
        if (dc.state() == DataChannel.State.OPEN) {
            try {
                dc.send(DataChannel.Buffer(java.nio.ByteBuffer.wrap(message.toByteArray()), false))
            } catch (t: Throwable) {
                Log.w(TAG, "发送控制消息失败: ${t.message}")
            }
        }
    }

    /** 共享方：注册控制指令接收回调 */
    fun setControlListener(listener: (String) -> Unit) {
        controlListener = listener
    }

    private fun registerControlObserver(dc: DataChannel) {
        dc.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) {}
            override fun onStateChange() {}
            override fun onMessage(buffer: DataChannel.Buffer) {
                if (!buffer.binary) {
                    val data = ByteArray(buffer.data.remaining())
                    buffer.data.get(data)
                    val msg = String(data)
                    Log.d(TAG, "收到控制指令: $msg")
                    controlListener?.invoke(msg)
                }
            }
        })
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

    /** 共享方：切换采集/编码帧率（观看方下发指令触发） */
    fun setFramerate(fps: Int) {
        if (disposed) return
        val capturer = videoCapturer ?: return
        try {
            val (capW, capH) = captureSizeForScreen()
            capturer.changeCaptureFormat(capW, capH, fps)
            videoSender?.let { sender ->
                val params = sender.parameters
                params.encodings?.firstOrNull()?.maxFramerate = fps
                sender.parameters = params
            }
            Log.d(TAG, "帧率已切换为 ${fps}fps")
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
        surfaceTextureHelper = SurfaceTextureHelper.create("ScreenCapture", eglBaseContext)

        val factory = getFactory()
        // 新版：视频源从 factory 实例创建
        val videoSource = factory.createVideoSource(true)
        capturer.initialize(surfaceTextureHelper, context, videoSource.capturerObserver)

        // 固定 1080p 采集（1080x1920 / 1920x1080），回归观看端合适的显示比例
        val (capW, capH) = captureSizeForScreen()
        // 采集帧率 30fps：实测 60fps 下硬件编码器处理每帧排队更久，端到端延迟反而更高；
        // 30fps 帧间隔 33ms，编码器负载低、延迟更小（屏幕共享流畅度也足够）
        val captureFps = 30
        reportProgress("③c 启动采集 ${capW}x${capH}@${captureFps}...")
        capturer.startCapture(capW, capH, captureFps)

        reportProgress("③d 挂载视频轨道...")
        localVideoTrack = factory.createVideoTrack("screen_track", videoSource)
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
                    // 用 15M 上限 + 初始 6M：既覆盖 1080p 动态内容的编码预算，又避免冲击跨网带宽。
                    enc.maxBitrateBps = 15_000_000
                    enc.minBitrateBps = 3_000_000
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
                // 打开应用瞬间丢包高：初始带宽 6M 保守起步，由拥塞控制按丢包率自适应爬升，
                // 避免启动即冲击导致瞬间拥塞卡顿
                try {
                    peerConnection?.setBitrate(3_000_000, 6_000_000, 15_000_000)
                    Log.d(TAG, "已设置初始带宽 3/6/15 Mbps")
                } catch (t: Throwable) {
                    Log.w(TAG, "setBitrate 失败: ${t.message}")
                }
            }
        } ?: run {
            Log.e(TAG, "localVideoTrack 为空，未添加视频轨道")
        }

        // 系统音频改走 DataChannel（SystemAudioBridge），不再添加麦克风音频轨道
        Log.d(TAG, "屏幕采集已启动: ${capW}x${capH}@${captureFps}fps (码率上限25M)")
        reportProgress("③e 采集就绪")
        return true
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
        videoCapturer?.changeCaptureFormat(capW, capH, 60)
        Log.d(TAG, "旋转后更新采集分辨率: ${capW}x${capH}@60")
    }

    /**
     * 拉取 WebRTC 实时统计（帧率/码率/往返延迟），供全屏悬浮信息条展示。
     * 返回 JSON 字符串摘要；失败返回 null。
     */
    fun collectStats(): String? {
        val pc = peerConnection ?: return null
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
                        var outLost = 0L
                        var outSent = 0L
                        val stats = report.statsMap
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
                                }
                                "outbound-rtp" -> {
                                    outFps = (s.members["framesPerSecond"] as? Number)?.toDouble() ?: 0.0
                                    outBytes += ((s.members["bytesSent"] as? Number)?.toDouble() ?: 0.0)
                                    outW = (s.members["frameWidth"] as? Number)?.toInt() ?: 0
                                    outH = (s.members["frameHeight"] as? Number)?.toInt() ?: 0
                                    // 发送端丢包：outbound-rtp 的 packetsLost/packetsSent
                                    outLost += (s.members["packetsLost"] as? Number)?.toLong() ?: 0L
                                    outSent += (s.members["packetsSent"] as? Number)?.toLong() ?: 0L
                                }
                                "candidate-pair" -> {
                                    val active = s.members["nominated"]
                                    if (active == true || active?.toString() == "true") {
                                        val r = (s.members["currentRoundTripTime"] as? Number)?.toDouble()
                                        if (r != null && r > 0) rtt = r * 1000.0
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
                            put("outLost", outLost)
                            put("outSent", outSent)
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
    private var curAdaptLevel = 0
    private var recoverTimer = 0
    private var lastAdaptDegradation: RtpParameters.DegradationPreference? = null
    private val adaptBitrateCaps = intArrayOf(15_000_000, 10_000_000, 7_000_000, 5_000_000, 3_000_000)
    // 增量丢包统计（outbound-rtp 累计值做差，避免长期运行后弱网检测失效）
    private var lastOutSentCum = 0L
    private var lastOutLostCum = 0L
    private var lastAdaptBitrateCap = 0

    /**
     * 腾讯会议式弱网自适应（v1.101）：按发送增量丢包率动态调节码率上限与分辨率策略。
     * - 丢包高（>=3%）：逐级降码率 + 切 MAINTAIN_FRAMERATE（保帧率降分辨率，画面流畅不卡顿）
     * - 丢包恢复（<1% 持续）：缓步回升码率 + 回 MAINTAIN_RESOLUTION（恢复高清晰度）
     * 由 MainActivity 统计线程周期调用（约 1.5s 一次）。
     *
     * @param outSentCum outbound-rtp packetsSent 累计值
     * @param outLostCum outbound-rtp packetsLost 累计值
     */
    fun adaptToNetwork(outSentCum: Long, outLostCum: Long) {
        val pc = peerConnection ?: return
        if (disposed) return
        // 本采样周期新增丢包/发送量（做差），首个周期无差值则跳过
        val dSent = outSentCum - lastOutSentCum
        val dLost = outLostCum - lastOutLostCum
        val sendLossPct = if (lastOutSentCum > 0 && dSent > 0) dLost * 100.0 / dSent else 0.0
        lastOutSentCum = outSentCum
        lastOutLostCum = outLostCum
        // 阈值：周期丢包率 >=3% 视为弱网需降质，<1% 视为已恢复
        val level = when {
            sendLossPct >= 5.0 -> adaptBitrateCaps.size - 1 // 最高档降质
            sendLossPct >= 3.0 -> 2
            sendLossPct >= 1.5 -> 1
            else -> 0
        }
        if (level > curAdaptLevel) {
            // 弱网加重：直接降到对应档位
            curAdaptLevel = level
            recoverTimer = 0
        } else if (level < curAdaptLevel) {
            // 网络好转：计数满 3 次（约 4.5s）才回升一档，避免抖动
            recoverTimer++
            if (recoverTimer >= 3) {
                curAdaptLevel--
                recoverTimer = 0
            }
        }
        val cap = adaptBitrateCaps[curAdaptLevel]
        // 仅档位变化时调码率/策略，避免周期重置影响拥塞控制收敛
        if (lastAdaptBitrateCap != cap) {
            lastAdaptBitrateCap = cap
            try {
                pc.setBitrate(1_500_000, cap, cap)
            } catch (t: Throwable) {
                Log.w(TAG, "自适应调码率失败: ${t.message}")
            }
            // 弱网降分辨率保帧率（腾讯会议流畅优先），网络好恢复高清晰度
            val degradation = if (curAdaptLevel > 0) {
                RtpParameters.DegradationPreference.MAINTAIN_FRAMERATE
            } else {
                RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION
            }
            lastAdaptDegradation = degradation
            try {
                videoSender?.let { rtp ->
                    val params = rtp.parameters
                    params.degradationPreference = degradation
                    rtp.parameters = params
                    Log.d(TAG, "弱网自适应: 档位${curAdaptLevel} 码率上限${cap / 1000000}M 策略=$degradation")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "自适应切分辨率策略失败: ${t.message}")
            }
        }
    }

    /** 重置弱网自适应状态（断开/重新连接时调用） */
    fun resetAdaptiveState() {
        curAdaptLevel = 0
        recoverTimer = 0
        lastAdaptDegradation = null
        lastOutSentCum = 0L
        lastOutLostCum = 0L
        lastAdaptBitrateCap = 0
    }

    fun disconnect() {
        if (disposed) return
        disposed = true
        resetAdaptiveState()
        videoCapturer?.stopCapture()
        videoCapturer?.dispose()
        localVideoTrack?.dispose()
        try { micAudioSource?.dispose() } catch (_: Throwable) {}
        micAudioSource = null
        localAudioTrack?.dispose()
        surfaceTextureHelper?.dispose()
        try { systemAudioChannel?.dispose() } catch (_: Throwable) {}
        systemAudioChannel = null
        systemAudioListener = null
        try { controlChannel?.dispose() } catch (_: Throwable) {}
        controlChannel = null
        controlListener = null
        videoSender = null
        micSender = null
        peerConnection?.close()
        peerConnection?.dispose()
        peerConnection = null
        videoCapturer = null
        localVideoTrack = null
        localAudioTrack = null
        surfaceTextureHelper = null
        Log.d(TAG, "WebRTC 已断开并清理")
    }
}
