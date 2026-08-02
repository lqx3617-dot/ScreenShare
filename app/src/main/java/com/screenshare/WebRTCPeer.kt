package com.screenshare

import android.content.Context
import android.util.Log
import org.webrtc.AudioTrack
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
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

        private val STUN_URLS = listOf(
            "stun:stun.l.google.com:19302",
            "stun:stun1.l.google.com:19302"
        )

        private val TURN_URLS = listOf(
            "turn:openrelay.metered.ca:80?transport=udp",
            "turn:openrelay.metered.ca:443?transport=tcp"
        )
        private const val TURN_USER = "openrelayproject"
        private const val TURN_PASS = "openrelayproject"

        // 单例 factory：整个进程共用
        @Volatile private var singletonFactory: PeerConnectionFactory? = null

        @Volatile private var initialized = false
        @Synchronized fun ensureInitialized(appContext: Context) {
            if (initialized) return
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(appContext)
                    .createInitializationOptions()
            )
            initialized = true
        }
    }

    private fun getFactory(): PeerConnectionFactory {
        return singletonFactory ?: synchronized(this) {
            singletonFactory ?: PeerConnectionFactory.builder()
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
    }

    private var peerConnection: PeerConnection? = null
    private var localVideoTrack: VideoTrack? = null
    private var localAudioTrack: AudioTrack? = null
    private var videoCapturer: VideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null

    init {
        ensureInitialized(context.applicationContext)
    }

    private val pendingCandidates = mutableListOf<IceCandidate>()
    // v1.3: 统一等待 ICE gathering 完成再打包（修复观看方候选丢失导致 P2P 卡死）
    private val gatheringLock = Object()
    private var gatheringCallback: (() -> Unit)? = null
    private var gatheringTimedOut = false
    private fun waitForGatheringComplete(cb: () -> Unit) {
        synchronized(gatheringLock) {
            if (gatheringTimedOut) { cb(); return }
            gatheringCallback = cb
        }
        // 8 秒兜底：若 gathering 迟迟不 COMPLETE（如无网口），强制放行防死等
        Thread {
            try { Thread.sleep(8000) } catch (t: Throwable) {}
            var c: (() -> Unit)? = null
            synchronized(gatheringLock) {
                if (gatheringCallback != null) {
                    gatheringTimedOut = true
                    c = gatheringCallback
                    gatheringCallback = null
                }
            }
            c?.invoke()
        }.start()
    }
    private fun notifyGatheringComplete() {
        var c: (() -> Unit)? = null
        synchronized(gatheringLock) {
            c = gatheringCallback
            gatheringCallback = null
        }
        c?.invoke()
    }

    // 新版 API：ICE 服务器直接用 URL 列表（含 TURN 凭据用 ":user:pass" 或通过 url 携带）
    private val iceServers: List<PeerConnection.IceServer> by lazy {
        STUN_URLS.map { PeerConnection.IceServer.builder(it).createIceServer() } +
            TURN_URLS.map {
                PeerConnection.IceServer.builder(it)
                    .setUsername(TURN_USER)
                    .setPassword(TURN_PASS)
                    .createIceServer()
            }
    }

    private val pcObserver = object : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState?) {
            Log.d(TAG, "Signaling: $state")
        }
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
            Log.d(TAG, "ICE Connection: $state")
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
            if (state == PeerConnection.IceGatheringState.COMPLETE) {
                listener.onIceGatheringComplete()
                notifyGatheringComplete()
            }
        }
        override fun onIceCandidate(candidate: IceCandidate) {
            Log.d(TAG, "onIceCandidate: ${candidate.sdp}")
            listener.onIceCandidate(candidate)
        }
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}

        @Deprecated("Deprecated in Java")
        override fun onAddStream(stream: MediaStream?) {
            stream?.videoTracks?.firstOrNull()?.let { listener.onRemoteVideoTrack(it) }
        }
        @Deprecated("Deprecated in Java")
        override fun onRemoveStream(stream: MediaStream?) {}
        override fun onDataChannel(channel: org.webrtc.DataChannel?) {}
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
        }
        peerConnection = factory.createPeerConnection(config, pcObserver)
        return peerConnection
    }

    /**
     * 启动屏幕采集。
     * capturer 由 ScreenCapturerFactory 内部缓存提供（真源是授权后拿到的 MediaProjection Intent）。
     * @return true 表示采集器创建并启动成功；false 表示失败（调用方应给 UI 提示）。
     */
    fun startScreenCapture(): Boolean {
        val capturer = ScreenCapturerFactory.createScreenCapturer(context) ?: run {
            Log.e(TAG, "屏幕采集器创建失败，请确认已授权屏幕采集权限")
            return false
        }
        videoCapturer = capturer
        surfaceTextureHelper = SurfaceTextureHelper.create("ScreenCapture", eglBaseContext)

        val factory = getFactory()
        // 新版：视频源从 factory 实例创建
        val videoSource = factory.createVideoSource(true)
        capturer.initialize(surfaceTextureHelper, context, videoSource.capturerObserver)
        capturer.startCapture(1280, 720, 30)

        localVideoTrack = factory.createVideoTrack("screen_track", videoSource)
        localVideoTrack?.setEnabled(true)

        // 添加视频轨道（默认 transceiver 传音视频；仅视频）
        localVideoTrack?.let { track ->
            val rtp = peerConnection?.addTrack(track)
            rtp?.let { sender ->
                val params = sender.parameters
                params.encodings?.firstOrNull()?.let { enc ->
                    enc.maxBitrateBps = 2_500_000
                    enc.maxFramerate = 30
                }
                sender.parameters = params
            }
        }
        Log.d(TAG, "屏幕采集已启动: 1280x720@30fps")
        return true
    }

    fun createOffer() {
        val pc = peerConnection ?: return
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        }
        pc.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                Log.d(TAG, "Offer 创建成功")
                pc.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        // v1.3: 等待 ICE gathering 完成（含候选）再回传，避免候选丢失
                        waitForGatheringComplete {
                            val ld = pc.localDescription
                            if (ld != null) listener.onOfferReady(ld) else listener.onOfferReady(sdp)
                        }
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
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        }
        pc.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                Log.d(TAG, "Answer 创建成功")
                pc.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        // v1.3: 等 ICE gathering 完成再打包 Answer，否则观看方候选缺失
                        waitForGatheringComplete {
                            val ld = pc.localDescription
                            listener.onAnswerReady(ld ?: sdp)
                        }
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

    fun disconnect() {
        videoCapturer?.stopCapture()
        videoCapturer?.dispose()
        localVideoTrack?.dispose()
        localAudioTrack?.dispose()
        surfaceTextureHelper?.dispose()
        peerConnection?.close()
        peerConnection?.dispose()
        peerConnection = null
        Log.d(TAG, "WebRTC 已断开并清理")
    }
}
