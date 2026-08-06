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
                    // 低延迟 field trials：禁用延迟影响大的特性，加快帧送达
                    .setFieldTrials(
                        "WebRTC-MinimizeResamplingOnMobileVideoBitrateChange/Enabled/" +
                            "WebRTC-VideoHwDecoding/Enabled/" +
                            "WebRTC-FrameDropper/Enabled/" +
                            "WebRTC-JitterBufferTargetDelay/Enabled/"
                    )
                    .createInitializationOptions()
            )
            initialized = true
        }
    }

    private fun getFactory(): PeerConnectionFactory {
        return singletonFactory ?: synchronized(this) {
            singletonFactory ?: PeerConnectionFactory.builder()
                // 屏幕共享：H264 baseline（无 B 帧，编码/解码延迟最低；high profile 的 B 帧会引入重排延迟）
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
     *  最大边限制 2400 防超高。 */
    private fun captureSizeForScreen(): Pair<Int, Int> {
        val dm = context.resources.displayMetrics
        val w = dm.widthPixels
        val h = dm.heightPixels
        // 1920 上限：绝大多数设备的 H.264 硬件编码器原生支持 1080p 高度，
        // 2400 高度在部分设备（尤其平板）上编码器初始化会卡住或失败
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
                    // 高清屏幕共享：25Mbps 上限保障清晰度，弱网时拥塞控制自动降速
                    // minBitrate 4M：留出拥塞控制下降余量，避免强撑高清导致排队延迟升高
                    enc.maxBitrateBps = 25_000_000
                    enc.minBitrateBps = 4_000_000
                    enc.maxFramerate = 30
                    // 低延迟：屏幕共享视频流高优先级，避免拥塞控制过度平滑/抑制导致延迟升高
                    enc.networkPriority = 4
                    enc.bitratePriority = 4.0
                }
                // 码率不足时优先保分辨率（降帧率而非降清晰度），适配 2K 屏幕采集
                try {
                    params.degradationPreference = RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION
                } catch (t: Throwable) {
                    Log.w(TAG, "设置 degradationPreference 失败: ${t.message}")
                }
                rtp.parameters = params
                // 低延迟：初始带宽直接给足（12M），跳过从低码率爬坡的过程（爬坡期画面模糊且延迟偏高）
                try {
                    peerConnection?.setBitrate(4_000_000, 12_000_000, 25_000_000)
                    Log.d(TAG, "已设置初始带宽 4/12/25 Mbps")
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

    fun disconnect() {
        if (disposed) return
        disposed = true
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
