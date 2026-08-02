package com.screenshare

import android.content.Context
import android.util.Log
import org.webrtc.*
import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * WebRTC 对等连接管理。
 *
 * 设计决策：
 * - STUN 用 Google 公共服务器（免费，不需要部署）
 * - TURN 用 Open Relay 免费节点（应对 NAT 穿透失败的情况）
 * - 编码用硬件 MediaCodec，720p 30fps，码率 2.5Mbps
 * - 所有回调都在 EGL 线程，主线程切换由调用方负责
 */
class WebRTCPeer(
    private val context: Context,
    private val eglBaseContext: EglBase.Context,
    private val listener: Listener
) {
    companion object {
        private const val TAG = "WebRTCPeer"

        // STUN 服务器（Google 公共，免费）
        private val STUN_URLS = listOf(
            "stun:stun.l.google.com:19302",
            "stun:stun1.l.google.com:19302"
        )

        // TURN 服务器（Open Relay 免费节点，应对 NAT 穿透失败）
        private val TURN_URLS = listOf(
            "turn:openrelay.metered.ca:80?transport=udp",
            "turn:openrelay.metered.ca:443?transport=tcp"
        )
        private const val TURN_USER = "openrelayproject"
        private const val TURN_PASS = "openrelayproject"
    }

    interface Listener {
        fun onIceCandidate(candidate: IceCandidate)
        fun onOfferReady(sdp: SessionDescription)
        fun onAnswerReady(sdp: SessionDescription)
        fun onConnected()
        fun onDisconnected()
        fun onRemoteVideoTrack(videoTrack: VideoTrack)
    }

    private var peerConnection: PeerConnection? = null
    private var localVideoTrack: VideoTrack? = null
    private var localAudioTrack: AudioTrack? = null
    private var videoCapturer: VideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null

    // ICE 候选缓存（对等连接建立之前攒着，建立后批量发）
    private val pendingCandidates = mutableListOf<IceCandidate>()

    private val iceServers: List<IceServer> by lazy {
        val stunIceServers = STUN_URLS.map { IceServer(it) }
        val turnIceServer = IceServer(TURN_URLS, TURN_USER, TURN_PASS)
        stunIceServers + turnIceServer
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
        }

        override fun onIceCandidate(candidate: IceCandidate) {
            listener.onIceCandidate(candidate)
        }

        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}

        override fun onAddStream(stream: MediaStream?) {
            stream?.videoTracks?.firstOrNull()?.let { videoTrack ->
                listener.onRemoteVideoTrack(videoTrack)
            }
        }

        override fun onRemoveStream(stream: MediaStream?) {}
        override fun onDataChannel(channel: DataChannel?) {}
        override fun onRenegotiationNeeded() {}
        override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
            receiver?.track?.let { track ->
                if (track is VideoTrack) {
                    listener.onRemoteVideoTrack(track)
                }
            }
        }
    }

    fun createPeerConnection(): PeerConnection? {
        val factory = PeerConnectionFactory.builder()
            .setEglContext(eglBaseContext)
            .createPeerConnectionFactory()

        val config = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            iceTransportType = PeerConnection.IceTransportType.ALL
        }

        peerConnection = factory.createPeerConnection(config, pcObserver)
        return peerConnection
    }

    /**
     * 启动屏幕采集（Host 端调用）
     * 使用 Android MediaProjection + VideoCapturer 采集屏幕
     */
    fun startScreenCapture(mediaProjection: android.hardware.display.DisplayManager?) {
        val capturer = ScreenCapturerFactory.createScreenCapturer(context)
        if (capturer == null) {
            Log.e(TAG, "屏幕采集器创建失败")
            return
        }

        videoCapturer = capturer
        surfaceTextureHelper = SurfaceTextureHelper.create("ScreenCapture", eglBaseContext)

        val videoSource = PeerConnectionFactory.builder().createVideoSource(true)
        capturer.initialize(surfaceTextureHelper, context, videoSource.capturerObserver)
        capturer.startCapture(1280, 720, 30)

        localVideoTrack = PeerConnectionFactory.builder().createVideoTrack("video", videoSource)
        localVideoTrack?.setEnabled(true)

        // 添加视频轨道到 PeerConnection
        val pc = peerConnection ?: return
        val sender = pc.addTrack(localVideoTrack)
        sender?.setParameters(
            sender.parameters.apply {
                encodings?.firstOrNull()?.let { encoding ->
                    encoding.maxBitrate = 2_500_000  // 2.5Mbps
                    encoding.maxFramerate = 30.0
                }
            }
        )

        Log.d(TAG, "屏幕采集已启动: 1280x720@30fps")
    }

    /**
     * 创建 Offer（Host 端调用）
     */
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
                        listener.onOfferReady(sdp)
                    }
                    override fun onSetFailure(error: String?) {
                        Log.e(TAG, "setLocalDescription 失败: $error")
                    }
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onCreateFailure(p0: String?) {}
                }, sdp)
            }
            override fun onCreateFailure(error: String?) {
                Log.e(TAG, "创建 Offer 失败: $error")
            }
            override fun onSetSuccess() {}
            override fun onSetFailure(error: String?) {}
        }, constraints)
    }

    /**
     * 创建 Answer（Join 端调用）
     */
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
                        listener.onAnswerReady(sdp)
                    }
                    override fun onSetFailure(error: String?) {
                        Log.e(TAG, "setLocalDescription 失败: $error")
                    }
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onCreateFailure(p0: String?) {}
                }, sdp)
            }
            override fun onCreateFailure(error: String?) {
                Log.e(TAG, "创建 Answer 失败: $error")
            }
            override fun onSetSuccess() {}
            override fun onSetFailure(error: String?) {}
        }, constraints)
    }

    /**
     * 设置远程 SDP（收到对方的 Offer 或 Answer 后调用）
     */
    fun setRemoteDescription(sdp: SessionDescription) {
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                Log.d(TAG, "setRemoteDescription 成功: ${sdp.type}")
                // 如果是 Offer，创建 Answer
                if (sdp.type == SessionDescription.Type.OFFER) {
                    createAnswer()
                }
                // 发送缓存的 ICE 候选
                synchronized(pendingCandidates) {
                    pendingCandidates.forEach { peerConnection?.addIceCandidate(it) }
                    pendingCandidates.clear()
                }
            }
            override fun onSetFailure(error: String?) {
                Log.e(TAG, "setRemoteDescription 失败: $error")
            }
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onCreateFailure(p0: String?) {}
        }, sdp)
    }

    /**
     * 添加 ICE 候选
     */
    fun addIceCandidate(candidate: IceCandidate) {
        val pc = peerConnection
        if (pc == null || pc.remoteDescription == null) {
            synchronized(pendingCandidates) {
                pendingCandidates.add(candidate)
            }
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