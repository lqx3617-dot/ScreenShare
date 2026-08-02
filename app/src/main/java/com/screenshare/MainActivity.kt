package com.screenshare

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.journeyapps.barcodescanner.BarcodeEncoder
import com.screenshare.databinding.ActivityMainBinding
import org.webrtc.*
import java.util.concurrent.Executors

/**
 * 主界面 Activity，串联所有模块：
 * 1. 权限申请
 * 2. WebRTC PeerConnection 创建
 * 3. 二维码生成 / 扫码
 * 4. SDP 交换 + ICE 候选交换
 * 5. 远程视频渲染
 *
 * 操作流程：
 * Host 端：点击"我要共享" → 申请屏幕权限 → 创建 Offer → 生成二维码 → 等待对方扫码
 * Join 端：点击"扫码观看" → 扫 Host 的二维码 → 生成 Answer → 生成二维码给 Host 扫 → 连接建立
 */
class MainActivity : AppCompatActivity(), WebRTCPeer.Listener {

    companion object {
        private const val TAG = "MainActivity"
        private const val PERM_REQUEST_CODE = 100
    }

    private lateinit var binding: ActivityMainBinding
    private var eglBaseContext: EglBase.Context? = null
    private var peer: WebRTCPeer? = null
    private var isHost = false

    // ICE 候选缓存（等待所有候选收集完再编码进二维码）
    private val iceCandidates = mutableListOf<IceCandidate>()
    private var pendingOfferSdp: SessionDescription? = null
    private var pendingAnswerSdp: SessionDescription? = null

    // 远程视频渲染
    private var remoteVideoSink: VideoSink? = null

    // 决策延迟执行线程池
    private val executor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        eglBaseContext = EglBase.create().eglBaseContext

        checkPermissions()

        binding.btnHost.setOnClickListener { onHostClicked() }
        binding.btnJoin.setOnClickListener { onJoinClicked() }
        binding.btnStop.setOnClickListener { onStopClicked() }
    }

    // ======================== 权限 ========================

    private fun checkPermissions() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            needed.add(Manifest.permission.CAMERA)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            needed.add(Manifest.permission.RECORD_AUDIO)

        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), PERM_REQUEST_CODE)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERM_REQUEST_CODE) {
            val denied = permissions.zip(grantResults.toTypedArray()).filter { it.second != PackageManager.PERMISSION_GRANTED }
            if (denied.isNotEmpty()) {
                Toast.makeText(this, "需要相机和麦克风权限才能共享屏幕", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ======================== Host 端逻辑 ========================

    private fun onHostClicked() {
        isHost = true
        updateUI("正在申请屏幕采集权限...")
        binding.btnHost.isEnabled = false
        binding.btnJoin.isEnabled = false

        // 先申请屏幕采集权限
        ScreenCapturerFactory.requestPermission(this)
    }

    /**
     * Host 端：收到 MediaProjection 权限后，创建 PeerConnection、启动屏幕采集、创建 Offer
     */
    private fun startHostSession() {
        // Android 14: 必须先以 mediaProjection 类型启动前台服务，否则 getMediaProjection 抛 SecurityException
        ScreenProjectionService.start(this)
        updateUI("正在建立 WebRTC 连接...")

        peer = WebRTCPeer(this, eglBaseContext!!, this)
        val pc = peer!!.createPeerConnection()
        if (pc == null) {
            updateUI("❌ PeerConnection 创建失败")
            return
        }

        // 启动屏幕采集
        peer!!.startScreenCapture(null)

        // 等 ICE 收集一些候选后创建 Offer（给 ICE 一点时间收集）
        executor.execute {
            Thread.sleep(2000) // 等 2 秒让 ICE 收集
            peer!!.createOffer()
        }
    }

    // ======================== Join 端逻辑 ========================

    private fun onJoinClicked() {
        isHost = false
        updateUI("请扫描对方的二维码...")

        // 启动 ZXing 扫码界面
        val integrator = com.google.zxing.integration.android.IntentIntegrator(this)
        integrator.setDesiredBarcodeFormats(com.google.zxing.integration.android.IntentIntegrator.QR_CODE)
        integrator.setPrompt("扫描共享者的二维码")
        integrator.setCameraId(0)
        integrator.setBeepEnabled(true)
        integrator.setOrientationLocked(false)
        integrator.initiateScan()
    }

    /**
     * Join 端：扫码成功后，解析 Offer，创建 PeerConnection，生成 Answer
     */
    private fun handleScannedQrCode(rawData: String) {
        val decoded = SignalManager.decode(rawData)
        if (decoded == null) {
            updateUI("❌ 二维码内容无效")
            return
        }

        val (sdp, candidates) = decoded
        if (sdp.type != SessionDescription.Type.OFFER) {
            updateUI("❌ 期望收到 Offer，但收到了 ${sdp.type}")
            return
        }

        updateUI("正在建立连接...")
        peer = WebRTCPeer(this, eglBaseContext!!, this)
        val pc = peer!!.createPeerConnection()
        if (pc == null) {
            updateUI("❌ PeerConnection 创建失败")
            return
        }

        // 先设置远程 Offer（内部会自动触发创建 Answer）
        peer!!.setRemoteDescription(sdp)

        // 添加 ICE 候选
        candidates.forEach { peer!!.addIceCandidate(it) }

        // 等 Answer 创建完后，等待 ICE 收集，再生成二维码给 Host 扫
        executor.execute {
            Thread.sleep(3000) // 等 Answer + ICE 收集
            val answerSdp = pendingAnswerSdp ?: run {
                runOnUiThread { updateUI("❌ Answer 未生成") }
                return@execute
            }
            val answerData = SignalManager.encodeAnswer(answerSdp, iceCandidates.toList())
            val qrBitmap = generateQRCode(answerData)

            runOnUiThread {
                if (qrBitmap != null) {
                    binding.ivQRCode.setImageBitmap(qrBitmap)
                    binding.ivQRCode.visibility = View.VISIBLE
                    updateUI("请让共享者扫描这个二维码")
                } else {
                    updateUI("❌ 二维码生成失败，数据过长")
                }
            }
        }
    }

    // ======================== Host 端：扫码获取 Answer ========================

    private fun handleHostScannedQrCode(rawData: String) {
        val decoded = SignalManager.decode(rawData)
        if (decoded == null) {
            updateUI("❌ 二维码内容无效")
            return
        }

        val (sdp, candidates) = decoded
        if (sdp.type != SessionDescription.Type.ANSWER) {
            updateUI("❌ 期望收到 Answer，但收到了 ${sdp.type}")
            return
        }

        peer?.setRemoteDescription(sdp)
        candidates.forEach { peer?.addIceCandidate(it) }
        updateUI("正在建立 P2P 连接，稍等...")
    }

    // ======================== WebRTCPeer.Listener 回调 ========================

    override fun onOfferReady(sdp: SessionDescription) {
        Log.d(TAG, "Offer 就绪，生成二维码")
        pendingOfferSdp = sdp

        val qrData = SignalManager.encodeOffer(sdp, iceCandidates.toList())
        val qrBitmap = generateQRCode(qrData)

        runOnUiThread {
            if (qrBitmap != null) {
                binding.ivQRCode.setImageBitmap(qrBitmap)
                binding.ivQRCode.visibility = View.VISIBLE
                updateUI("二维码已生成，请让对方扫码")
            } else {
                updateUI("❌ 二维码生成失败")
            }
        }
    }

    override fun onAnswerReady(sdp: SessionDescription) {
        Log.d(TAG, "Answer 就绪")
        pendingAnswerSdp = sdp
    }

    override fun onIceCandidate(candidate: IceCandidate) {
        iceCandidates.add(candidate)
        Log.d(TAG, "收到 ICE 候选: ${candidate.sdpMid}")
    }

    override fun onConnected() {
        runOnUiThread {
            updateUI("✅ 已连接！屏幕共享进行中...")
            binding.btnStop.visibility = View.VISIBLE
            binding.btnStop.isEnabled = true
            binding.btnHost.isEnabled = false
            binding.btnJoin.isEnabled = false
            binding.ivQRCode.visibility = View.GONE

            if (!isHost) {
                binding.flRemoteVideo.visibility = View.VISIBLE
            }
        }
    }

    override fun onDisconnected() {
        runOnUiThread {
            updateUI("连接已断开")
            resetUI()
        }
    }

    override fun onRemoteVideoTrack(videoTrack: VideoTrack) {
        runOnUiThread {
            binding.flRemoteVideo.visibility = View.VISIBLE

            // 移除旧的 renderer
            remoteVideoSink?.let { oldSink ->
                videoTrack.removeSink(oldSink)
            }

            val renderer = SurfaceViewRenderer(this)
            renderer.init(eglBaseContext, null)
            renderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
            binding.flRemoteVideo.removeAllViews()
            binding.flRemoteVideo.addView(renderer)

            remoteVideoSink = VideoSink { frame ->
                renderer.onFrame(frame)
            }
            videoTrack.addSink(remoteVideoSink!!)
            Log.d(TAG, "远程视频轨道已绑定到 SurfaceViewRenderer")
        }
    }

    // ======================== 停止 ========================

    private fun onStopClicked() {
        peer?.disconnect()
        ScreenProjectionService.stop(this)
        ScreenCapturerFactory.clearPermission()
        resetUI()
        updateUI("已停止共享")
    }

    // ======================== Activity Result ========================

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        // 屏幕采集权限
        if (ScreenCapturerFactory.handleActivityResult(requestCode, resultCode, data)) {
            startHostSession()
            return
        }

        // ZXing 扫码结果
        val result = com.google.zxing.integration.android.IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        if (result != null && result.contents != null) {
            val rawData = result.contents
            binding.tvScanResult.text = "扫码结果: ${rawData.take(50)}..."
            binding.tvScanResult.visibility = View.VISIBLE

            if (isHost) {
                handleHostScannedQrCode(rawData)
            } else {
                handleScannedQrCode(rawData)
            }
        }
    }

    // ======================== 工具方法 ========================

    private fun generateQRCode(content: String): Bitmap? {
        return try {
            val writer = QRCodeWriter()
            val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, 512, 512)
            val width = bitMatrix.width
            val height = bitMatrix.height
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
            for (x in 0 until width) {
                for (y in 0 until height) {
                    bitmap.setPixel(x, y, if (bitMatrix[x, y]) Color.BLACK else Color.WHITE)
                }
            }
            bitmap
        } catch (e: Exception) {
            Log.e(TAG, "生成二维码失败: ${e.message}")
            null
        }
    }

    private fun updateUI(status: String) {
        binding.tvStatus.text = status
        Log.d(TAG, status)
    }

    private fun resetUI() {
        binding.btnHost.isEnabled = true
        binding.btnJoin.isEnabled = true
        binding.btnStop.visibility = View.GONE
        binding.ivQRCode.visibility = View.GONE
        binding.flRemoteVideo.visibility = View.GONE
        binding.tvScanResult.visibility = View.GONE
        iceCandidates.clear()
        pendingOfferSdp = null
        pendingAnswerSdp = null
        remoteVideoSink = null
    }

    override fun onDestroy() {
        super.onDestroy()
        peer?.disconnect()
        executor.shutdownNow()
    }
}