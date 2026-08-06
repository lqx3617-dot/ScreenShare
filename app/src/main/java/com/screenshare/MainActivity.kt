package com.screenshare

import android.Manifest
import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.graphics.RenderEffect
import android.graphics.Shader
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.screenshare.databinding.ActivityMainBinding
import org.webrtc.*

/**
 * 主界面 Activity，串联所有模块：
 * 1. 权限申请
 * 2. WebRTC PeerConnection 创建
 * 3. 会议号连接（信令服务器）
 * 4. SDP 交换 + ICE 候选交换
 * 5. 远程视频渲染
 *
 * 操作流程：
 * Host 端：快速会议 → 自动生成 4 位会议号 → 等待对方输入会议号加入
 * Join 端：加入会议 → 输入 Host 的 4 位会议号 → 连接建立
 */
class MainActivity : AppCompatActivity(), WebRTCPeer.Listener {

    companion object {
        private const val TAG = "MainActivity"
        private const val PERM_REQUEST_CODE = 100
        private const val PERM_REQUEST_MIC = 101
    }

    private lateinit var binding: ActivityMainBinding
    private var eglBaseContext: EglBase.Context? = null
    private var peer: WebRTCPeer? = null
    private var isHost = false
    private var hostSessionActive = false

    // 口令共享（信令服务器模式）
    private var signalClient: SignalClient? = null
    private var signalMode = false
    private var signalPeerReady = false
    private var signalPendingOfferData: String? = null
    // 对端尚未加入时缓存的 ICE 候选（加入后连同 offer 一起补发，避免服务器"对端尚未加入"拒发丢失）
    private var signalPendingCandidates = mutableListOf<IceCandidate>()
    private var signalCode: String? = null
    // 本次会话是否已发起屏幕授权请求（避免重复弹授权框）
    private var authorizationRequested = false
    // Trickle ICE：SDP 是否已通过信令发出，之后的候选才单独增量发送
    private var signalSdpSent = false

    // ICE 候选缓存（打包进信令 SDP 一起发送）
    private val iceCandidates = mutableListOf<IceCandidate>()

    // 远程视频渲染
    private var remoteVideoSink: VideoSink? = null

    // 视频画面双指缩放
    private var videoScaleDetector: ScaleGestureDetector? = null
    private var videoRenderer: SurfaceViewRenderer? = null
    private var currentVideoScale = 1f
    private var minVideoScale = 1f
    private var maxVideoScale = 4f

    // 最近一帧视频分辨率（rotatedWidth/Height），用于手动计算完整/铺满
    private var lastFrameW = 0
    private var lastFrameH = 0

    // 全屏观看：复用同一个 videoTrack，在 flFullscreen 叠加层里用独立 renderer
    private var remoteVideoTrack: VideoTrack? = null
    private var fullscreenRenderer: SurfaceViewRenderer? = null
    private var fullscreenSink: VideoSink? = null
    private var fullscreenScaleDetector: ScaleGestureDetector? = null
    private var fullscreenScale = 1f
    private var isFullscreen = false

    // 显示模式：true=完整显示(等比，可能有黑边)，false=铺满(无黑边，边缘裁切)
    private var isFitMode = true

    // 容器尺寸变化监听是否已注册
    private var layoutListenerAdded = false

    // 会议号弹窗（连接建立后自动关闭，避免遮挡后续界面）
    private var meetingCodeDialog: AlertDialog? = null

    // 观看方帧率切换（60/30 帧）
    private var currentFps = 60

    // 麦克风（会议内双向对讲）：false=已开启且未静音，true=已开启但静音
    private var micMuted = false

    // 远程控制（观看方控制共享方）：true=控制模式（单指触摸下发控制指令）
    private var isControlMode = false

    // 控制模式下是否已发送 down（用于过滤黑边区域的 move/up）
    private var ctrlDownSent = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        eglBaseContext = EglBase.create().eglBaseContext

        // iOS 液态玻璃：为玻璃卡片/按钮应用背景模糊（backdrop blur）
        applyLiquidGlass(
            binding.llStatus,
            binding.llSignal,
            binding.btnSignalHost,
            binding.btnSignalJoin,
            binding.btnStop
        )

        checkPermissions()

        // 云更新：静默检查新版本（异步，不影响正常使用）
        UpdateChecker.check(this)

        binding.btnStop.setOnClickListener { onStopClicked() }
        binding.btnSignalHost.setOnClickListener { onSignalHostClicked() }
        binding.btnSignalJoin.setOnClickListener { onSignalJoinClicked() }
        binding.tvCheckUpdate.setOnClickListener { UpdateChecker.check(this, manual = true) }
        binding.btnFullscreen.setOnClickListener { enterFullscreen() }
        binding.btnExitFullscreen.setOnClickListener { exitFullscreen() }
        binding.btnFpsToggle.setOnClickListener { onFpsToggleClicked() }
        binding.btnAspectToggle.setOnClickListener { onAspectToggleClicked() }
        binding.btnMic.setOnClickListener { onMicClicked() }
        binding.btnRemoteControl.setOnClickListener { onRemoteControlToggle() }
        binding.btnCtrlBack.setOnClickListener { onCtrlKeyClicked("back") }
        binding.btnCtrlHome.setOnClickListener { onCtrlKeyClicked("home") }
        binding.btnCtrlRecents.setOnClickListener { onCtrlKeyClicked("recents") }
        binding.btnCtrlText.setOnClickListener { onCtrlTextClicked() }
        binding.btnCtrlSetup.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        binding.btnCtrlLock.setOnClickListener { onCtrlLockClicked() }
    }

    /** 观看方点击切换 60/30 帧，经控制通道通知共享方 */
    private fun onFpsToggleClicked() {
        currentFps = if (currentFps == 60) 30 else 60
        binding.btnFpsToggle.text = "${currentFps}帧"
        val msg = org.json.JSONObject()
            .put("type", "fps")
            .put("value", currentFps)
            .toString()
        peer?.sendControl(msg)
        Toast.makeText(this, "已切换为 ${currentFps} 帧", Toast.LENGTH_SHORT).show()
    }

    /** 观看方点击切换 完整显示/铺满 模式，实时生效 */
    private fun onAspectToggleClicked() {
        isFitMode = !isFitMode
        applyAspectMode()
        binding.btnAspectToggle.text = if (isFitMode) "完整" else "铺满"
        Toast.makeText(this, if (isFitMode) "完整显示（等比，可能有黑边）" else "铺满屏幕（无黑边，边缘裁切）", Toast.LENGTH_SHORT).show()
    }

    // ==================== 远程控制（观看方控制共享方） ====================

    /** 观看方：切换控制模式。控制模式下单指触摸下发控制指令，双指仍本地缩放 */
    private fun onRemoteControlToggle() {
        if (isHost) return
        val p = peer ?: return
        if (p.controlChannelOpen().not()) {
            Toast.makeText(this, "控制通道未就绪", Toast.LENGTH_SHORT).show()
            return
        }
        isControlMode = !isControlMode
        binding.btnRemoteControl.text = if (isControlMode) "控制中" else "远程控制"
        binding.btnRemoteControl.setTextColor(if (isControlMode) 0xFF2BD98F.toInt() else 0xFFFFFFFF.toInt())
        binding.llCtrlKeys.visibility = if (isControlMode) View.VISIBLE else View.GONE
        binding.btnCtrlText.visibility = if (isControlMode) View.VISIBLE else View.GONE
        if (!isControlMode) ctrlDownSent = false
    }

    /** 观看方：发送系统按键指令（对方服务是否可用由共享方回执反馈） */
    private fun onCtrlKeyClicked(value: String) {
        val p = peer ?: return
        p.sendControl("""{"type":"key","value":"$value"}""")
    }

    /** 观看方：弹输入框，发送文本到共享方当前聚焦输入框 */
    private fun onCtrlTextClicked() {
        val p = peer ?: return
        val input = android.widget.EditText(this).apply {
            hint = "输入要发送到对方输入框的文字"
            setSingleLine(true)
            setPadding(40, 20, 40, 20)
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("发送文本")
            .setView(input)
            .setPositiveButton("发送") { _, _ ->
                val text = input.text.toString().trim()
                if (text.isEmpty()) return@setPositiveButton
                try {
                    p.sendControl(org.json.JSONObject()
                        .put("type", "text")
                        .put("value", text)
                        .toString())
                } catch (t: Throwable) {
                    Log.e(TAG, "发送文本指令失败: ${t.message}")
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 观看方：控制模式下单指触摸 → 归一化坐标 → 控制通道下发 */
    private fun handleControlTouch(event: MotionEvent, renderer: SurfaceViewRenderer) {
        val p = peer ?: return
        if (lastFrameW <= 0 || lastFrameH <= 0) return
        val action = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> "down"
            MotionEvent.ACTION_MOVE -> "move"
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> "up"
            else -> return
        }
        // 内联坐标映射（避免每帧 JSONObject/FloatArray 分配，降低触摸延迟）
        val crop = !isFitMode
        val rw = renderer.width.toFloat()
        val rh = renderer.height.toFloat()
        val vw = lastFrameW.toFloat()
        val vh = lastFrameH.toFloat()
        var left = 0f; var top = 0f; var right = rw; var bottom = rh
        if (!crop) {
            val scale = minOf(rw / vw, rh / vh)
            val cw = vw * scale
            val ch = vh * scale
            left = (rw - cw) / 2f
            top = (rh - ch) / 2f
            right = left + cw
            bottom = top + ch
        }
        val x = event.x
        val y = event.y
        if (x < left || x > right || y < top || y > bottom) {
            // 黑边区域：down 不产生指令，已有 down 提前结束（up）
            if (action == "down") ctrlDownSent = false
            return
        }
        if (action == "move" && !ctrlDownSent) return
        if (action == "down") ctrlDownSent = true
        if (action == "up") ctrlDownSent = false
        val nx = ((x - left) / (right - left)).coerceIn(0f, 1f)
        val ny = ((y - top) / (bottom - top)).coerceIn(0f, 1f)
        p.sendControl("{\"type\":\"touch\",\"action\":\"$action\",\"nx\":$nx,\"ny\":$ny}")
    }

    /** 观看方：接收共享方回发的控制状态提示 */
    private fun handleControlReply(msg: String) {
        try {
            val obj = org.json.JSONObject(msg)
            if (obj.optString("type") != "status-error") return
            val tip = when (obj.optString("code")) {
                "no-accessibility" -> "对方未开启无障碍服务，无法控制"
                "no-focused-input" -> "对方当前没有可输入的输入框"
                "text-failed" -> "文本输入失败"
                else -> "控制指令执行失败"
            }
            runOnUiThread { Toast.makeText(this, tip, Toast.LENGTH_SHORT).show() }
        } catch (t: Throwable) {
            Log.e(TAG, "解析控制回执失败: ${t.message}")
        }
    }

    /** 共享方：刷新远程控制状态卡片（无障碍服务开启状态） */
    private fun updateRemoteControlStatus() {
        val on = RemoteControlService.isAccessibilityOn()
        binding.tvCtrlStatus.text = if (on) "远程控制已就绪" else "未开启无障碍服务，观看方无法控制"
        binding.tvCtrlStatus.setTextColor(if (on) 0xFF7CFC9C.toInt() else 0xFFFFC107.toInt())
        binding.btnCtrlSetup.visibility = if (on) View.GONE else View.VISIBLE
    }

    /** 共享方：停止/恢复远程控制开关 */
    private fun onCtrlLockClicked() {
        RemoteControlService.controlEnabled = !RemoteControlService.controlEnabled
        binding.btnCtrlLock.text = if (RemoteControlService.controlEnabled) "停止控制" else "已锁定"
        binding.btnCtrlLock.setTextColor(
            if (RemoteControlService.controlEnabled) 0xFFFFC107.toInt() else 0xFFFF5252.toInt()
        )
        Toast.makeText(
            this,
            if (RemoteControlService.controlEnabled) "远程控制已恢复" else "已停止远程控制，指令将被忽略",
            Toast.LENGTH_SHORT
        ).show()
    }

    /**
     * 麦克风开关键（会议内双向对讲）：
     * 未开启 → 检查权限后启动麦克风音轨并重协商；已开启 → 切换静音/取消静音（不重协商）。
     */
    private fun onMicClicked() {
        val p = peer ?: return
        if (!p.isMicOn()) {
            // 未开启麦克风：确保录音权限，随后启动音轨并重协商
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), PERM_REQUEST_MIC)
                return
            }
            if (!p.startMicAudio()) {
                Toast.makeText(this, "麦克风启动失败", Toast.LENGTH_SHORT).show()
                return
            }
            micMuted = false
            p.renegotiate()
            updateMicButton()
            Toast.makeText(this, "麦克风已开启，可与对方对讲", Toast.LENGTH_SHORT).show()
        } else {
            micMuted = !micMuted
            p.setMicMuted(micMuted)
            updateMicButton()
            Toast.makeText(this, if (micMuted) "麦克风已静音" else "已取消静音", Toast.LENGTH_SHORT).show()
        }
    }

    /** 同步麦克风按钮文案与颜色：开启=绿色，静音=红色，未开启=默认 */
    private fun updateMicButton() {
        val p = peer
        val on = p?.isMicOn() == true
        binding.btnMic.text = when {
            !on -> "麦克风"
            micMuted -> "已静音"
            else -> "对讲中"
        }
        binding.btnMic.setTextColor(
            when {
                !on -> Color.parseColor("#FF111827")
                micMuted -> Color.parseColor("#FFD13232")
                else -> Color.parseColor("#FF12865C")
            }
        )
    }

    /** 同步当前显示模式到普通/全屏两个渲染器。
     *  完整模式：renderer 尺寸手动设为视频等比适配容器后的尺寸并居中（scalingType=FIT），
     *  避免 SurfaceViewRenderer 在"容器宽>高 + 竖屏视频"时内部缩放计算把上下裁掉；
     *  铺满模式：renderer 撑满容器 + scalingType=FILL。 */
    private fun applyAspectMode() {
        applyModeScale()
    }

    /** 根据当前模式 + 视频/容器比例，手动设置 renderer 尺寸实现完整（等比黑边）或铺满（放大裁切） */
    private fun applyModeScale() {
        val vw = lastFrameW
        val vh = lastFrameH
        if (vw <= 0 || vh <= 0) return

        val cw = binding.flRemoteVideo.width
        val ch = binding.flRemoteVideo.height
        if (cw > 0 && ch > 0) {
            val fit = minOf(cw.toFloat() / vw, ch.toFloat() / vh)
            val lp = if (isFitMode) {
                FrameLayout.LayoutParams(
                    (vw * fit).toInt().coerceAtLeast(1),
                    (vh * fit).toInt().coerceAtLeast(1),
                    Gravity.CENTER
                )
            } else {
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            }
            videoRenderer?.apply {
                layoutParams = lp
                scaleX = 1f
                scaleY = 1f
                setScalingType(
                    if (isFitMode) RendererCommon.ScalingType.SCALE_ASPECT_FIT
                    else RendererCommon.ScalingType.SCALE_ASPECT_FILL
                )
            }
            currentVideoScale = 1f
        }

        val fw2 = binding.flFullscreen.width
        val fh2 = binding.flFullscreen.height
        if (fw2 > 0 && fh2 > 0) {
            val fit2 = minOf(fw2.toFloat() / vw, fh2.toFloat() / vh)
            val lp2 = if (isFitMode) {
                FrameLayout.LayoutParams(
                    (vw * fit2).toInt().coerceAtLeast(1),
                    (vh * fit2).toInt().coerceAtLeast(1),
                    Gravity.CENTER
                )
            } else {
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            }
            fullscreenRenderer?.apply {
                layoutParams = lp2
                scaleX = 1f
                scaleY = 1f
                setScalingType(
                    if (isFitMode) RendererCommon.ScalingType.SCALE_ASPECT_FIT
                    else RendererCommon.ScalingType.SCALE_ASPECT_FILL
                )
            }
        }
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
        } else if (requestCode == PERM_REQUEST_MIC) {
            // 麦克风权限结果：授权成功则直接开启麦克风（与点击按钮走相同流程）
            val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            if (granted) {
                peer?.let { p ->
                    if (p.startMicAudio()) {
                        micMuted = false
                        p.renegotiate()
                        updateMicButton()
                        Toast.makeText(this, "麦克风已开启，可与对方对讲", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "麦克风启动失败", Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                Toast.makeText(this, "未授权麦克风权限，无法开启语音", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ======================== Host 会话核心（信令模式复用） ========================

    /**
     * 启动 Host 屏幕会话。
     *
     * 关键时序修复（SecurityException）：Android 14 要求调用 getMediaProjection() 时
     * 必须已有 foregroundServiceType="mediaProjection" 的前台服务在运行。而
     * startForegroundService() 是异步的——onStartCommand 里的 startForeground 要等
     * 主线程当前代码块返回后才执行。若启动服务后立即调用 getMediaProjection()，系统
     * 仍认为没有 mediaProjection 前台服务，从而抛 SecurityException。
     *
     * 解决：启动服务后不立即采集，而是等 ScreenProjectionService 在 startForeground
     * 完成时回调 onReady 通知“服务已就绪”，届时才真正创建 PeerConnection 并启动采集。
     * 用 postDelayed 延迟作兜底，防止 onReady 因异常未触发导致卡死。
     */
    private fun startHostSession() {
        sessionCoreStarted = false
        // 诊断进度：显示在 tvScanResult（独立区域，不被状态栏 updateUI 覆盖）
        binding.tvScanResult.text = "① 已授权，启动共享服务..."
        binding.tvScanResult.visibility = View.VISIBLE
        ScreenProjectionService.onReady = {
            runOnUiThread { startSessionCore() }
        }
        // Android 14: 必须先以 mediaProjection 类型启动前台服务，否则 getMediaProjection 抛 SecurityException
        try {
            ScreenProjectionService.start(this)
        } catch (t: Throwable) {
            binding.tvScanResult.text = "❌ 共享服务启动失败: ${t.message}"
            updateUI("❌ 共享服务启动失败")
            return
        }
        updateUI("正在建立 WebRTC 连接...")
        // 兜底：若 onReady 因异常未触发，延迟 600ms 后照样启动采集（此时 onStartCommand 必然已完成）
        binding.root.postDelayed({
            if (!isFinishing && !isDestroyed) startSessionCore()
        }, 600)
    }

    /**
     * 在 mediaProjection 前台服务就绪后执行实际的 PeerConnection 创建与屏幕采集。
     */
    // 保证 startSessionCore 只执行一次（onReady 回调与延迟兜底可能都会触发）
    private var sessionCoreStarted = false

    private fun startSessionCore() {
        if (sessionCoreStarted) return
        sessionCoreStarted = true
        // 启用 WebRTC 原生日志，便于诊断采集/信令问题
        ScreenCapturerFactory.enableDiagnosticLogging()
        val p = WebRTCPeer(this, eglBaseContext!!, this)
        peer = p
        // 采集启动的细粒度进度（③a~③e）实时显示到屏幕下方
        p.progressListener = { step ->
            runOnUiThread {
                binding.tvScanResult.text = step
                binding.tvScanResult.visibility = View.VISIBLE
            }
        }
        binding.tvScanResult.text = "② 创建 PeerConnection..."
        val pc = p.createPeerConnection()
        if (pc == null) {
            binding.tvScanResult.text = "❌ PeerConnection 创建失败"
            updateUI("❌ PeerConnection 创建失败")
            return
        }
        binding.tvScanResult.text = "③ 启动屏幕采集..."
        // 启动屏幕采集（失败时明确提示，不再静默卡在"连接中"）
        val ok = p.startScreenCapture()
        if (!ok) {
            val hint = if (ScreenCapturerFactory.hasPermission())
                "❌ 屏幕采集启动失败（内部错误），请重试"
            else
                "❌ 未检测到屏幕共享授权，请重新创建会议并务必点击【立即开始】"
            binding.tvScanResult.text = hint
            updateUI(hint)
            Toast.makeText(this, hint, Toast.LENGTH_LONG).show()
            ScreenProjectionService.stop(this)
            return
        }
        binding.tvScanResult.text = "④ 创建音频/控制通道..."
        // 系统音频内录：创建 DataChannel + 启动内录（复用 MediaProjection 授权）
        p.createSystemAudioChannel()
        p.createControlChannel()
        // 接收观看方指令：fps 帧率切换走原有逻辑，其余控制指令交给无障碍服务执行
        p.setControlListener { msg ->
            try {
                val obj = org.json.JSONObject(msg)
                when (obj.optString("type")) {
                    "fps" -> p.setFramerate(obj.optInt("value", 60))
                    else -> {
                        // 无障碍服务未开启或被共享方停止控制时回发提示
                        if (!RemoteControlService.handle(obj)) {
                            p.sendControl("""{"type":"status-error","code":"no-accessibility"}""")
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "解析控制指令失败: ${t.message}")
            }
        }
        // 指令执行失败（如无聚焦输入框）回发观看方
        RemoteControlService.execResultCallback = { errMsg -> p.sendControl(errMsg) }
        val audioOk = SystemAudioBridge.startCapture(p.mediaProjection()) { data ->
            p.sendSystemAudio(data)
        }
        if (!audioOk) {
            updateUI("⚠️ 系统音频内录启动失败（视频将无声）")
        } else {
            Log.d(TAG, "系统音频内录已启动")
        }
        binding.tvScanResult.text = "⑤ 正在生成连接信令(SDP)..."
        // 等 ICE 收集一些候选后创建 Offer（给 ICE 一点时间收集）
        // 重要：必须在主线程创建/操作 PeerConnection（与 createPeerConnection 同一线程），
        // 跨线程调用会导致 ICE 模块异常、候选不生成。
        binding.root.postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            val target = peer ?: return@postDelayed
            target.createOffer()
        }, 500)
    }


    // ======================== 会议号连接（信令服务器） ========================

    /** 快速会议：自动生成 4 位数字会议号，弹窗展示可复制，创建房间并开始共享 */
    private fun onSignalHostClicked() {
        // 生成 4 位数字会议号（类似腾讯会议）
        val code = generateMeetingCode()
        signalCode = code
        signalMode = true
        isHost = true
        hostSessionActive = true
        signalPeerReady = false
        signalPendingOfferData = null

        binding.btnSignalHost.isEnabled = false
        binding.btnSignalJoin.isEnabled = false

        // 先建立信令连接（核心流程优先，避免任何弹窗异常影响连接）
        updateUI("正在创建会议...")
        connectSignal(code, asHost = true)
        // 会议号弹窗延后到屏幕授权完成后显示（见 onActivityResult），
        // 避免会议号弹窗与系统授权框叠放导致授权框被遮挡、用户漏点"立即开始"
    }

    /** 弹窗展示生成的会议号，支持一键复制到剪贴板 */
    private fun showMeetingCodeDialog(code: String) {
        val clipboard = getSystemService(ClipboardManager::class.java)
        val tv = TextView(this).apply {
            text = code
            textSize = 56f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.BLACK)
        }
        val tvHint = TextView(this).apply {
            text = "发给对方后，对方点击【加入会议】输入该会议号即可观看"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#FF6B7280"))
            setPadding(0, 8, 0, 0)
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(tv)
            addView(tvHint)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("快速会议已创建")
            .setView(layout)
            .setPositiveButton("复制会议号", { _, _ ->
                clipboard.setPrimaryClip(ClipData.newPlainText("会议号", code))
                Toast.makeText(this, "会议号已复制：$code", Toast.LENGTH_LONG).show()
            })
            .setNegativeButton("知道了", null)
            .setCancelable(true)
            .create()
        meetingCodeDialog = dialog
        dialog.show()
    }

    /** 关闭会议号弹窗（对方加入/出错/断开时调用，避免遮挡后续界面） */
    private fun dismissMeetingCodeDialog() {
        try {
            meetingCodeDialog?.takeIf { it.isShowing }?.dismiss()
        } catch (_: Throwable) {}
        meetingCodeDialog = null
    }

    /** 加入会议：弹出美化后的自定义输入弹窗，输入 4 位会议号后加入 */
    private fun onSignalJoinClicked() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_join_meeting)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)

        val input = dialog.findViewById<EditText>(R.id.etMeetingCode)
        // 键盘「完成」键等同于点击加入
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                dialog.findViewById<Button>(R.id.btnJoinSubmit).performClick()
                true
            } else false
        }

        dialog.findViewById<Button>(R.id.btnJoinSubmit).setOnClickListener {
            val code = input.text.toString().trim()
            if (!validateSignalCode(code)) {
                // 会议号非法：震一下提示，保持弹窗继续输入
                input.requestFocus()
                input.selectAll()
                return@setOnClickListener
            }
            dialog.dismiss()
            joinMeetingWithCode(code)
        }
        dialog.findViewById<TextView>(R.id.btnJoinCancel).setOnClickListener { dialog.dismiss() }

        dialog.show()
        input.postDelayed({ input.requestFocus() }, 200)
    }

    /** 携带会议号执行加入会议流程（Host 视角为 false） */
    private fun joinMeetingWithCode(code: String) {
        signalCode = code
        signalMode = true
        isHost = false
        hostSessionActive = false
        signalPeerReady = false
        signalPendingOfferData = null
        signalPendingCandidates.clear()
        signalSdpSent = false
        authorizationRequested = false

        binding.btnSignalHost.isEnabled = false
        binding.btnSignalJoin.isEnabled = false

        updateUI("正在加入会议（$code）...")
        connectSignal(code, asHost = false)
    }

    /** 生成 4 位数字会议号（不重复，忽略极小概率碰撞） */
    private fun generateMeetingCode(): String {
        val sb = StringBuilder()
        val random = java.security.SecureRandom()
        repeat(4) { sb.append(random.nextInt(10)) }
        return sb.toString()
    }

    private fun validateSignalCode(code: String): Boolean {
        if (!Regex("^[0-9]{4}$").matches(code)) {
            Toast.makeText(this, "会议号为 4 位数字", Toast.LENGTH_SHORT).show()
            return false
        }
        return true
    }

    private fun connectSignal(code: String, asHost: Boolean) {
        if (BuildConfig.SIGNAL_URL.isNullOrEmpty()) {
            updateUI("❌ 未配置信令服务器地址（gradle.properties: screenshare.signal.url）")
            resetUI()
            return
        }
        val client = SignalClient(BuildConfig.SIGNAL_URL, object : SignalClient.Listener {
            override fun onRoomReady(role: String) {
                runOnUiThread {
                    if (role == "created") {
                        updateUI("✅ 会议已创建，等待对方加入...")
                        binding.tvScanResult.text = "会议号: $signalCode\n让对方输入此号码加入"
                        binding.tvScanResult.visibility = View.VISIBLE
                        // 立即申请屏幕采集权限（不必等对方加入），授权后共享随时就绪，
                        // 对方加入时立即交换 SDP，避免"对方已加入但还没授权"的等待
                        dismissMeetingCodeDialog()
                        authorizationRequested = true
                        ScreenCapturerFactory.requestPermission(this@MainActivity)
                    } else {
                        updateUI("✅ 已加入会议，等待共享方就绪...")
                    }
                }
            }

            override fun onPeerReady() {
                runOnUiThread {
                    signalPeerReady = true
                    // 对方已加入，关闭会议号弹窗，避免遮挡系统授权界面
                    dismissMeetingCodeDialog()
                    if (isHost) {
                        if (ScreenCapturerFactory.hasPermission()) {
                            // 授权已成功，正在启动共享（startHostSession 由 onActivityResult 触发）
                            updateUI("对方已加入，正在启动屏幕共享...")
                        } else {
                            // 授权未完成（授权框可能未弹出/用户没点），此时对方已加入：
                            // 明确提示 + 兜底重新弹授权框，避免一直卡在"正在启动屏幕共享"
                            if (!authorizationRequested) {
                                authorizationRequested = true
                                ScreenCapturerFactory.requestPermission(this@MainActivity)
                            }
                            updateUI("⚠️ 共享未开始：请在弹出的屏幕授权框中点击【立即开始】")
                        }
                    } else {
                        updateUI("共享方已就绪，等待画面...")
                    }
                    // 若 Offer 已就绪但此前尚未发送（对端未就绪），此时补发 offer + 缓存的候选
                    signalClient?.let { c ->
                        signalPendingOfferData?.let { c.sendRelay(it) }
                        signalPendingOfferData = null
                        if (signalPendingCandidates.isNotEmpty()) {
                            signalPendingCandidates.forEach { cand ->
                                c.sendRelay(SignalManager.encodeCandidate(cand))
                            }
                            signalPendingCandidates.clear()
                        }
                    }
                }
            }

            override fun onRelay(data: String) {
                runOnUiThread { handleSignalRelay(data) }
            }

            override fun onPeerLeft() {
                runOnUiThread {
                    updateUI("❌ 对方已离开")
                    cleanupPeer()
                    resetUI()
                }
            }

            override fun onRetrying(message: String) {
                runOnUiThread {
                    updateUI("⚠️ $message")
                    // 连接可能重建，重置对端就绪标记，等重连成功后重新走流程
                    signalPeerReady = false
                    signalSdpSent = false
                }
            }

            override fun onError(message: String) {
                runOnUiThread {
                    updateUI("❌ $message")
                    cleanupPeer()
                    resetUI()
                }
            }

            override fun onClosed(reason: String) {
                runOnUiThread {
                    if (signalMode) {
                        updateUI("❌ 信令连接已关闭: $reason")
                        cleanupPeer()
                        resetUI()
                    }
                }
            }
        })
        signalClient = client
        client.connect(code, asHost)
    }

    /**
     * 处理信令服务器转发来的 SDP 数据。
     * 格式与二维码一致（SignalManager 编码），只是传输通道从扫码改为网络。
     */
    private fun handleSignalRelay(data: String) {
        // 增量候选消息（Trickle ICE）：直接投递（未就绪时由 WebRTCPeer 缓冲）
        SignalManager.decodeCandidate(data)?.let { cand ->
            peer?.addIceCandidate(cand)
            return
        }
        val decoded = SignalManager.decode(data)
        if (decoded == null) {
            updateUI("❌ 信令数据无效")
            return
        }
        val (sdp, candidates) = decoded
        when (sdp.type) {
            SessionDescription.Type.OFFER -> {
                // 观看方收到 Offer：无连接时创建 PeerConnection 并回复 Answer；
                // 已存在连接时（麦克风开关触发的重协商 Offer）复用现有 peer，直接更新远端描述
                if (isHost) {
                    updateUI("❌ 角色错配：共享方不应收到 Offer")
                    return
                }
                updateUI("正在建立连接...")
                val isNewPeer = peer == null
                if (isNewPeer) {
                    val p = WebRTCPeer(this, eglBaseContext!!, this)
                    peer = p
                    if (p.createPeerConnection() == null) {
                        updateUI("❌ PeerConnection 创建失败")
                        return
                    }
                    // 系统音频经 DataChannel 接收，PCM 交给播放器
                    p.setSystemAudioListener { data ->
                        SystemAudioBridge.writePcm(data)
                    }
                    // 接收共享方回发的控制提示（无障碍未开启/文本失败等）
                    p.setControlListener { msg ->
                        handleControlReply(msg)
                    }
                }
                val p = peer!!
                p.setRemoteDescription(sdp)
                candidates.forEach { p.addIceCandidate(it) }
            }
            SessionDescription.Type.ANSWER -> {
                // 共享方收到 Answer：完成 P2P 连接
                if (!isHost) {
                    updateUI("❌ 角色错配：观看方不应收到 Answer")
                    return
                }
                val p = peer
                if (p == null) {
                    updateUI("❌ 连接已失效，请重新发起共享")
                    return
                }
                p.setRemoteDescription(sdp)
                candidates.forEach { p.addIceCandidate(it) }
                updateUI("正在建立 P2P 连接，稍等...")
            }
            else -> updateUI("❌ 未知的 SDP 类型: ${sdp.type}")
        }
    }

    private fun cleanupPeer() {
        peer?.disconnect()
        peer = null
        signalClient?.disconnect()
        signalClient = null
        signalPeerReady = false
        signalPendingOfferData = null
        signalPendingCandidates.clear()
        signalSdpSent = false
        dismissMeetingCodeDialog()
        SystemAudioBridge.stopCapture()
        SystemAudioBridge.stopPlayback()
        ScreenProjectionService.stop(this)
        ScreenCapturerFactory.clearPermission()
        micMuted = false
        binding.btnMic.visibility = View.GONE
    }

    // ======================== WebRTCPeer.Listener 回调 ========================

    override fun onOfferReady(sdp: SessionDescription) {
        // 诊断：解析 SDP，确认是否有视频轨道和候选（定位 P2P 卡住）
        val hasVideo = sdp.description.contains("m=video")
        val sdpCandCount = Regex("(?m)^a=candidate:").findAll(sdp.description).count()
        runOnUiThread {
            binding.tvScanResult.text = "SDP诊断: 视频轨道=${if (hasVideo) "有" else "无"} SDP候选=$sdpCandCount"
            binding.tvScanResult.visibility = View.VISIBLE
        }
        if (signalMode) {
            Log.d(TAG, "Offer 就绪，经信令服务器转发")
            signalSdpSent = true
            val encoded = SignalManager.encodeOffer(sdp, iceCandidates.toList())
            if (signalPeerReady) {
                // 对端已加入：立即发送
                signalClient?.sendRelay(encoded)
            } else {
                // 对端尚未加入：缓存，等 onPeerReady 时补发（避免服务器"对端尚未加入"拒发导致 offer 丢失）
                signalPendingOfferData = encoded
            }
            return
        }
    }

    override fun onAnswerReady(sdp: SessionDescription) {
        if (signalMode) {
            Log.d(TAG, "Answer 就绪，经信令服务器转发")
            signalSdpSent = true
            signalClient?.sendRelay(SignalManager.encodeAnswer(sdp, iceCandidates.toList()))
        }
    }

    override fun onIceGatheringComplete() {
        // ICE 候选收集完成；信令模式下已随 SDP 内嵌候选发送，无需额外动作
        Log.d(TAG, "ICE 收集完成，共 " + iceCandidates.size + " 个候选")
    }

    override fun onIceState(state: String) {
        runOnUiThread {
            binding.tvScanResult.text = state
            binding.tvScanResult.visibility = View.VISIBLE
        }
    }

    override fun onIceCandidate(candidate: IceCandidate) {
        iceCandidates.add(candidate)
        val type = when {
            candidate.sdp.contains("typ host") -> "host"
            candidate.sdp.contains("typ srflx") -> "srflx"
            candidate.sdp.contains("typ relay") -> "relay"
            else -> "other"
        }
        val counts = iceCandidates.groupingBy { c ->
            when {
                c.sdp.contains("typ host") -> "host"
                c.sdp.contains("typ srflx") -> "srflx"
                c.sdp.contains("typ relay") -> "relay"
                else -> "other"
            }
        }.eachCount()
        Log.d(TAG, "收到 ICE 候选: mid=${candidate.sdpMid} type=$type 总数=${iceCandidates.size} $counts")
        // 诊断：实时显示本机已收集候选数量
        runOnUiThread {
            binding.tvScanResult.text = "本机候选: ${iceCandidates.size}个 $counts"
            binding.tvScanResult.visibility = View.VISIBLE
        }
        // Trickle ICE：SDP 已发出后，新候选即时增量发送（不等 gathering 完成）
        if (signalMode && signalSdpSent) {
            if (signalPeerReady) {
                signalClient?.sendRelay(SignalManager.encodeCandidate(candidate))
            } else {
                // 对端尚未加入：缓存，等 onPeerReady 补发（服务器会以"对端尚未加入"拒发）
                signalPendingCandidates.add(candidate)
            }
        }
    }

    override fun onConnected() {
        runOnUiThread {
            updateUI("✅ 已连接！屏幕共享进行中...")
            binding.btnStop.visibility = View.VISIBLE
            binding.btnStop.isEnabled = true
            binding.btnMic.visibility = View.VISIBLE
            updateMicButton()

            if (isHost) {
                // 共享方本地预览：将本地采集轨道绑定到画面区（与观看方同一渲染链路）
                peer?.getLocalVideoTrack()?.let { setupVideoPreview(it) }
                binding.llCtrlStatus.visibility = View.VISIBLE
                updateRemoteControlStatus()
            } else {
                binding.flRemoteVideo.visibility = View.VISIBLE
                binding.btnFpsToggle.visibility = View.VISIBLE
                binding.btnRemoteControl.visibility = View.VISIBLE
                SystemAudioBridge.startPlayback()
            }
        }
    }

    override fun onDisconnected() {
        runOnUiThread {
            updateUI("连接已断开")
            SystemAudioBridge.stopPlayback()
            resetUI()
        }
    }

    override fun onRemoteVideoTrack(videoTrack: VideoTrack) {
        runOnUiThread { setupVideoPreview(videoTrack) }
    }

    /**
     * 通用视频预览渲染：观看方绑定远程轨道、共享方绑定本地轨道，两路径复用同一套逻辑。
     * 包含 renderer 创建/销毁、sink 绑定、缩放/双击复位、完整/铺满切换、全屏、方向适配。
     */
    private fun setupVideoPreview(track: VideoTrack) {
        binding.flRemoteVideo.visibility = View.VISIBLE

        // 移除旧的 renderer 和 sink（重连/切换预览时复用同一容器）
        val oldTrack = remoteVideoTrack
        remoteVideoSink?.let { oldSink ->
            oldTrack?.removeSink(oldSink)
        }
        remoteVideoTrack = track
        videoRenderer?.let { old ->
            if (old.parent == binding.flRemoteVideo) {
                binding.flRemoteVideo.removeView(old)
            }
        }

        val renderer = SurfaceViewRenderer(this)
        renderer.init(eglBaseContext, null)
        // 默认完整显示（等比，不裁切），用户可点右上角按钮切铺满
        renderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        renderer.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        binding.flRemoteVideo.addView(renderer, 0)
        videoRenderer = renderer
        currentVideoScale = 1f

        // 双指捏合缩放 + 双击复位
        val scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val newScale = (currentVideoScale * detector.scaleFactor).coerceIn(minVideoScale, maxVideoScale)
                applyVideoScale(renderer, newScale, detector.focusX, detector.focusY)
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                currentVideoScale = renderer.scaleX
            }
        })
        scaleDetector.isQuickScaleEnabled = true
        videoScaleDetector = scaleDetector

        renderer.setOnTouchListener { v, event ->
            if (isControlMode && !isHost && event.pointerCount == 1) {
                handleControlTouch(event, renderer)
                true
            } else {
                scaleDetector.onTouchEvent(event)
                if (event.action == MotionEvent.ACTION_UP && scaleDetector.scaleFactor == 1f) {
                    // 单击/双击复位
                    if (event.eventTime - event.downTime < 300) {
                        currentVideoScale = 1f
                        renderer.animate().scaleX(1f).scaleY(1f).setDuration(200).start()
                    }
                }
                true
            }
        }

        binding.tvZoomHint.visibility = View.VISIBLE
        binding.btnFullscreen.visibility = View.VISIBLE
        binding.btnAspectToggle.visibility = View.VISIBLE
        applyAspectMode()

        remoteVideoSink = VideoSink { frame ->
            renderer.onFrame(frame)
            val fw = frame.rotatedWidth
            val fh = frame.rotatedHeight
            if (fw != lastFrameW || fh != lastFrameH) {
                lastFrameW = fw
                lastFrameH = fh
                runOnUiThread { applyModeScale() }
            }
        }
        track.addSink(remoteVideoSink!!)
        Log.d(TAG, "视频轨道已绑定到 SurfaceViewRenderer (本地预览=${track == peer?.getLocalVideoTrack()})")

        // 容器尺寸变化（平板旋转/布局变化）时重新按比例适配画面
        if (!layoutListenerAdded) {
            layoutListenerAdded = true
            binding.flRemoteVideo.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                if (lastFrameW > 0) applyModeScale()
            }
            binding.flFullscreen.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                if (lastFrameW > 0) applyModeScale()
            }
        }

        // 预创建常驻全屏 renderer（隐藏状态下保留 surface），点全屏时瞬间显示
        prepareFullscreenRenderer()
    }

    // ======================== 全屏观看 ========================

    /** 进入全屏观看（切换到 flFullscreen 叠加层，跟随屏幕方向） */
    private fun enterFullscreen() {
        if (isFullscreen) return
        // 常驻 renderer 未就绪时现场补建
        if (fullscreenRenderer == null) prepareFullscreenRenderer()
        if (fullscreenRenderer == null) return
        isFullscreen = true
        fullscreenScale = 1f

        // 常驻 renderer 已就绪，只切换可见性，切换几乎瞬时
        binding.flFullscreen.visibility = View.VISIBLE

        // 隐藏所有其他 UI
        binding.llTitle.visibility = View.GONE
        binding.llStatus.visibility = View.GONE
        binding.flRemoteVideo.visibility = View.GONE
        binding.tvZoomHint.visibility = View.GONE
        binding.llSignal.visibility = View.GONE
        binding.tvScanResult.visibility = View.GONE
        binding.btnStop.visibility = View.GONE
        binding.btnFullscreen.visibility = View.GONE

        // 全屏跟随屏幕方向：竖屏竖着看、横屏横着看（不再强制横屏）
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR

        // 沉浸式：隐藏系统栏
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let { c ->
                c.hide(WindowInsets.Type.systemBars())
                c.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                (View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
        }
    }

    /**
     * 预创建常驻全屏 renderer 并绑定视频帧。
     * 关键优化：renderer 创建一次后常驻在 flFullscreen，进出全屏只切换可见性
     * （退出用 INVISIBLE 而非 GONE，避免 SurfaceView surface 销毁导致下次进入重建卡顿）。
     */
    private fun prepareFullscreenRenderer() {
        val track = remoteVideoTrack ?: return
        if (fullscreenRenderer != null) return

        val renderer = SurfaceViewRenderer(this)
        renderer.init(eglBaseContext, null)
        renderer.setScalingType(
            if (isFitMode) RendererCommon.ScalingType.SCALE_ASPECT_FIT
            else RendererCommon.ScalingType.SCALE_ASPECT_FILL
        )
        renderer.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        fullscreenRenderer = renderer
        binding.flFullscreen.addView(renderer, 0)
        binding.flFullscreen.visibility = View.INVISIBLE

        // 双指缩放
        val scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val newScale = (fullscreenScale * detector.scaleFactor).coerceIn(1f, 4f)
                applyVideoScale(renderer, newScale, detector.focusX, detector.focusY)
                return true
            }
            override fun onScaleEnd(detector: ScaleGestureDetector) {
                fullscreenScale = renderer.scaleX
            }
        })
        scaleDetector.isQuickScaleEnabled = true
        fullscreenScaleDetector = scaleDetector

        renderer.setOnTouchListener { _, event ->
            if (isControlMode && !isHost && event.pointerCount == 1) {
                handleControlTouch(event, renderer)
                true
            } else {
                scaleDetector.onTouchEvent(event)
                true
            }
        }

        fullscreenSink = VideoSink { frame ->
            renderer.onFrame(frame)
            val fw = frame.rotatedWidth
            val fh = frame.rotatedHeight
            if (fw != lastFrameW || fh != lastFrameH) {
                lastFrameW = fw
                lastFrameH = fh
                runOnUiThread { applyModeScale() }
            }
        }
        track.addSink(fullscreenSink!!)
        Log.d(TAG, "全屏 renderer 已常驻就绪")
    }

    /** 退出全屏：恢复 UI 和竖屏（renderer 保持常驻，仅隐藏叠加层） */
    private fun exitFullscreen() {
        if (!isFullscreen) return
        isFullscreen = false
        fullscreenScale = 1f
        fullscreenScaleDetector = null

        // 隐藏但不销毁 renderer（INVISIBLE 保留 surface，再次进入全屏瞬时切换）
        binding.flFullscreen.visibility = View.INVISIBLE

        // 恢复 UI（根据当前状态显示应显示的）
        binding.llTitle.visibility = View.VISIBLE
        binding.llStatus.visibility = View.VISIBLE
        if (remoteVideoTrack != null) {
            binding.flRemoteVideo.visibility = View.VISIBLE
            binding.tvZoomHint.visibility = View.VISIBLE
            binding.btnFullscreen.visibility = View.VISIBLE
        }
        binding.llSignal.visibility = View.VISIBLE
        if (peer != null) {
            binding.btnStop.visibility = View.VISIBLE
        }

        // 恢复系统栏
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(true)
            window.insetsController?.show(WindowInsets.Type.systemBars())
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        }

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
    }

    /** 会话结束时销毁常驻全屏 renderer */
    private fun releaseFullscreenRenderer() {
        fullscreenSink?.let { remoteVideoTrack?.removeSink(it) }
        fullscreenSink = null
        fullscreenRenderer?.let { r ->
            if (r.parent == binding.flFullscreen) {
                binding.flFullscreen.removeView(r)
            }
            r.release()
        }
        fullscreenRenderer = null
        fullscreenScaleDetector = null
        fullscreenScale = 1f
        binding.flFullscreen.visibility = View.GONE
    }

    // ======================== 停止 ========================

    private fun onStopClicked() {
        peer?.disconnect()
        peer = null
        signalClient?.disconnect()
        signalClient = null
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
            // 授权成功：显示会议号弹窗（供复制给对方）+ 启动共享
            signalCode?.let { code ->
                try { showMeetingCodeDialog(code) } catch (t: Throwable) {
                    Log.e(TAG, "会议号弹窗异常（不影响连接）: ${t.message}")
                }
            }
            startHostSession()
        } else if (requestCode == ScreenCapturerFactory.REQUEST_MEDIA_PROJECTION) {
            // 用户取消/拒绝了屏幕共享授权，明确提示（不再静默卡住）
            updateUI("❌ 未授权屏幕共享，对方将无法看到画面，请重新创建会议")
            Toast.makeText(this, "未授权屏幕共享，对方将无法看到画面", Toast.LENGTH_LONG).show()
        }
    }

    // ======================== 工具方法 ========================

    private fun applyVideoScale(renderer: View, scale: Float, focusX: Float, focusY: Float) {
        renderer.pivotX = focusX
        renderer.pivotY = focusY
        renderer.scaleX = scale
        renderer.scaleY = scale
    }

    /**
     * iOS 液态玻璃：对半透明玻璃视图应用背景模糊（backdrop blur）。
     * 系统级 createBackdropBlurEffect 为非公开 API，用反射调用（Android 13+ 可用）；
     * 不可用时降级为半透明玻璃观感，不影响布局与功能。
     */
    private fun applyLiquidGlass(vararg views: View) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        var effect: RenderEffect? = null
        try {
            val m = RenderEffect::class.java.getMethod(
                "createBackdropBlurEffect",
                Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                Shader.TileMode::class.java
            )
            effect = m.invoke(null, 28f, 28f, Shader.TileMode.CLAMP) as RenderEffect
        } catch (t: Throwable) {
            Log.w(TAG, "backdrop blur 不可用，降级为半透明玻璃: ${t.message}")
        }
        if (effect == null) return
        for (v in views) {
            try {
                v.setRenderEffect(effect)
            } catch (t: Throwable) {
                Log.w(TAG, "液态玻璃模糊失败: ${t.message}")
            }
        }
    }

    private fun updateUI(status: String) {
        binding.tvStatus.text = status
        Log.d(TAG, status)
    }

    private fun resetUI() {
        exitFullscreen()
        releaseFullscreenRenderer()
        binding.btnSignalHost.isEnabled = true
        binding.btnSignalJoin.isEnabled = true
        binding.btnStop.visibility = View.GONE
        binding.flRemoteVideo.visibility = View.GONE
        binding.tvScanResult.visibility = View.GONE
        binding.tvZoomHint.visibility = View.GONE
        binding.btnFullscreen.visibility = View.GONE
        binding.btnAspectToggle.visibility = View.GONE
        binding.btnFpsToggle.visibility = View.GONE
        binding.btnRemoteControl.visibility = View.GONE
        binding.llCtrlKeys.visibility = View.GONE
        binding.btnCtrlText.visibility = View.GONE
        binding.llCtrlStatus.visibility = View.GONE
        isControlMode = false
        ctrlDownSent = false
        currentFps = 60
        micMuted = false
        binding.btnMic.visibility = View.GONE
        binding.llTitle.visibility = View.VISIBLE
        binding.llStatus.visibility = View.VISIBLE
        binding.llSignal.visibility = View.VISIBLE
        videoRenderer?.scaleX = 1f
        videoRenderer?.scaleY = 1f
        videoRenderer = null
        videoScaleDetector = null
        currentVideoScale = 1f
        iceCandidates.clear()
        remoteVideoSink = null
        remoteVideoTrack = null
        hostSessionActive = false
        signalMode = false
        signalPeerReady = false
        signalPendingOfferData = null
        signalPendingCandidates.clear()
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
    }

    override fun onDestroy() {
        super.onDestroy()
        ScreenProjectionService.onReady = null
        exitFullscreen()
        releaseFullscreenRenderer()
        peer?.disconnect()
        peer = null
        signalClient?.disconnect()
        signalClient = null
    }

    /**
     * 屏幕旋转时 Activity 不重建（configChanges 接管），仅更新采集方向。
     * 避免旋转导致 WebRTC 连接与 MediaProjection 被销毁而中断共享。
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 全屏观看时横竖屏切换：复用 renderer 不重建（重建会黑屏卡顿）。
        // 只重置缩放并强制 relayout，让画面瞬间跟随新方向，切换最快。
        if (isFullscreen) {
            binding.root.post {
                fullscreenRenderer?.let { r ->
                    r.pivotX = 0f
                    r.pivotY = 0f
                    r.scaleX = 1f
                    r.scaleY = 1f
                    fullscreenScale = 1f
                    r.layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                    r.requestLayout()
                }
            }
        }
        // 仅当处于共享状态且本机是共享方时更新采集方向
        if (isHost && hostSessionActive && peer != null) {
            val displayMetrics = resources.displayMetrics
            val w = displayMetrics.widthPixels
            val h = displayMetrics.heightPixels
            peer?.updateCaptureOrientation(w, h)
            updateUI("屏幕方向已更新")
        }
    }
}