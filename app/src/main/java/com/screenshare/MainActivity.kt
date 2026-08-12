package com.screenshare

import android.Manifest
import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.graphics.RenderEffect
import android.graphics.Shader
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
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
        private const val PERM_REQUEST_ALBUM = 102
    }

    private lateinit var binding: ActivityMainBinding
    private var eglBaseContext: EglBase.Context? = null
    private var peer: WebRTCPeer? = null
    @Volatile private var isHost = false
    private var hostSessionActive = false

    // 口令共享（信令服务器模式）
    private var signalClient: SignalClient? = null
    private var signalMode = false
    private var signalPeerReady = false
    // host 端：对方（viewer）是否已加入房间。服务器对 host 发的是 viewer-joined 而非 peer-ready，
    // 用此标记判断"对方已加入"（关闭会议号弹窗 / 授权后不弹窗）
    private var viewerJoined = false
    private var signalPendingOfferData: String? = null
    // 对端尚未加入时缓存的 ICE 候选（加入后连同 offer 一起补发，避免服务器"对端尚未加入"拒发丢失）
    private var signalPendingCandidates = mutableListOf<IceCandidate>()
    private var signalCode: String? = null
    // 本次会话是否已发起屏幕授权请求（避免重复弹授权框）
    private var authorizationRequested = false
    // Trickle ICE：SDP 是否已通过信令发出，之后的候选才单独增量发送
    private var signalSdpSent = false
    // V4: 采集/主会话是否就绪（新 viewer 加入时据此决定立即发 Offer 或排队）
    private var screenCaptureReady = false

    // ICE 候选缓存（打包进信令 SDP 一起发送）
    private val iceCandidates = mutableListOf<IceCandidate>()
    // 本机候选类型累计计数（避免每候选 O(n) 重扫全部）
    private var candCountHost = 0
    private var candCountSrflx = 0
    private var candCountRelay = 0
    private var candCountOther = 0

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
    private var fullscreenScale = 1f
    @Volatile private var isFullscreen = false
    // 全屏悬浮信息条：周期拉取 WebRTC 统计并刷新显示（后台线程轮询，避免 getStats 阻塞主线程导致 ANR/闪退）
    private var statsThread: android.os.HandlerThread? = null
    private var statsTimer: android.os.Handler? = null
    private var statsRunnable: Runnable? = null
    private var lastStatsBytesIn = 0L
    private var lastStatsBytesOut = 0L
    private var lastStatsTime = 0L
    // 增量丢包统计基准（后台线程读写）
    private var lastLostTotal = 0L
    private var lastLost = 0L
    // 弱网/编码负载自适应线程：与全屏状态无关，连接建立即运行（修复"非全屏打开视频软件卡顿"）
    private var adaptiveThread: android.os.HandlerThread? = null
    private var adaptiveHandler: android.os.Handler? = null
    private var adaptiveRunnable: Runnable? = null
    // 诊断上报去重签名（值变化才重报）
    @Volatile private var lastDiagSig = ""

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

    // 控制模式触摸轨迹累积（down 起点 → 各 move 点），抬手时打包为完整滑动指令
    private val ctrlPoints = ArrayList<FloatArray>()

    // 滑动实时跟手节流：MOVE 阶段每 50ms 发送一次完整路径
    private var lastCtrlSend = 0L

    // 触摸会话判定：是否已进入滑动、按下时间与起点（区分点击/长按/滑动）
    private var ctrlMoveStarted = false
    private var ctrlDownTime = 0L
    private var ctrlDownNX = 0f
    private var ctrlDownNY = 0f

    // 增量滑动：上一段已发送的终点（用于发送"上一点→当前点"短段，跟手且报文小）
    private var ctrlLastNX = 0f
    private var ctrlLastNY = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 崩溃日志采集：Java 层崩溃写入外部存储文件，下次启动可查看/上报，便于定位真机闪退
        installCrashHandler()
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

        // 科技感入场动画：标题区/状态卡/会议区错峰淡入上滑
        val fadeIn = android.view.animation.AnimationUtils.loadAnimation(this, R.anim.anim_fade_slide)
        binding.llTitle.startAnimation(android.view.animation.AnimationUtils.loadAnimation(this, R.anim.anim_fade_slide))
        binding.llStatus.startAnimation(fadeIn)
        binding.llSignal.startAnimation(android.view.animation.AnimationUtils.loadAnimation(this, R.anim.anim_fade_slide))

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
        binding.btnAlbum.setOnClickListener { onAlbumClicked() }
        binding.btnRemoteControl.setOnClickListener { onRemoteControlToggle() }
        binding.btnCtrlBack.setOnClickListener { onCtrlKeyClicked("back") }
        binding.btnCtrlHome.setOnClickListener { onCtrlKeyClicked("home") }
        binding.btnCtrlRecents.setOnClickListener { onCtrlKeyClicked("recents") }
        binding.btnCtrlText.setOnClickListener { onCtrlTextClicked() }
        binding.btnCtrlSetup.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        binding.btnCtrlLock.setOnClickListener { onCtrlLockClicked() }

        // 分享链接唤起：冷启动时解析 screenshare://join?code=XXXX
        handleShareLink(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareLink(intent)
    }

    /** 解析分享链接并自动加入：screenshare://join?code=XXXX */
    private fun handleShareLink(intent: Intent?) {
        val uri = intent?.data ?: return
        // 兼容不同浏览器解析：intent:// 唤起时部分解析会把 query 并入 host，
        // 因此先从 query 取，取不到再从完整字符串兜底提取
        val code = uri.getQueryParameter("code")?.trim()?.takeIf { it.isNotEmpty() }
            ?: Regex("code=([0-9]{4})").find(uri.toString())?.groupValues?.get(1) ?: ""
        if (!Regex("^[0-9]{4}$").matches(code)) {
            Toast.makeText(this, "无效的分享链接", Toast.LENGTH_SHORT).show()
            return
        }
        // 当前已在共享或连接中：不打断现有会话
        if (hostSessionActive || signalMode) {
            Toast.makeText(this, "当前会话进行中，无法加入", Toast.LENGTH_SHORT).show()
            return
        }
        joinMeetingWithCode(code)
    }

    /** 崩溃日志采集：Java 层崩溃写入外部存储，便于下次启动查看/上报定位闪退 */
    private fun installCrashHandler() {
        try {
            val prev = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                try {
                    val sw = java.io.StringWriter()
                    throwable.printStackTrace(java.io.PrintWriter(sw))
                    val content = StringBuilder()
                        .append("time=").append(System.currentTimeMillis()).append('\n')
                        .append("thread=").append(thread.name).append('\n')
                        .append(sw.toString())
                    val dir = getExternalFilesDir(null)
                    if (dir != null) {
                        val f = java.io.File(dir, "crash-${System.currentTimeMillis()}.log")
                        f.writeText(content.toString())
                    }
                    // 尽力上报信令服务器（诊断模式），失败则忽略；缩短超时避免拖慢进程退出
                    try {
                        val base = BuildConfig.SIGNAL_URL
                            .replace("wss://", "https://").replace("ws://", "http://")
                            .trimEnd('/')
                        val url = base.substringBeforeLast('/', base)
                        val body = content.toString().toByteArray().toRequestBody("text/plain".toMediaType())
                        val req = okhttp3.Request.Builder()
                            .url("$url/crash")
                            .addHeader("x-diag-token", BuildConfig.DIAG_TOKEN)
                            .post(body)
                            .build()
                        okhttp3.OkHttpClient.Builder().connectTimeout(2, java.util.concurrent.TimeUnit.SECONDS)
                            .readTimeout(2, java.util.concurrent.TimeUnit.SECONDS).build()
                            .newCall(req).execute().close()
                    } catch (_: Throwable) {}
                } catch (_: Throwable) {}
                prev?.uncaughtException(thread, throwable)
            }
        } catch (_: Throwable) {}
    }

    private val diagExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

    // 复用单个 OkHttpClient（避免每次上报重建连接池/线程池）
    private val diagClient = okhttp3.OkHttpClient.Builder()
        .connectTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    /** 诊断上报：把编码器/瓶颈/丢包/延迟信息 POST 到信令服务器 /diag 落盘，便于远程定位真机问题 */
    private fun reportDiagnostic(text: String) {
        val role = if (isHost) "host" else "viewer"
        val payload = "time=${System.currentTimeMillis()} role=$role $text\n"
        diagExecutor.execute {
            try {
                val base = BuildConfig.SIGNAL_URL
                    .replace("wss://", "https://").replace("ws://", "http://")
                    .trimEnd('/')
                val url = base.substringBeforeLast('/', base)
                val body = payload.toByteArray().toRequestBody("text/plain".toMediaType())
                val req = okhttp3.Request.Builder()
                    .url("$url/diag")
                    .addHeader("x-diag-token", BuildConfig.DIAG_TOKEN)
                    .post(body)
                    .build()
                diagClient.newCall(req).execute().close()
            } catch (_: Throwable) {}
        }
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
            Toast.makeText(this, "控制通道未就绪 ${p.controlChannelDebug()}", Toast.LENGTH_SHORT).show()
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

    /** 观看方：控制模式下单指触摸。down 发送按下，MOVE 按 66ms 节流发送增量滑动段，抬手结束 */
    private fun handleControlTouch(event: MotionEvent, renderer: SurfaceViewRenderer) {
        val p = peer ?: return
        if (lastFrameW <= 0 || lastFrameH <= 0) return
        val action = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> "down"
            MotionEvent.ACTION_MOVE -> "move"
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> "up"
            else -> return
        }
        // 坐标映射复用 CoordinateMapper（纯函数），触点在黑边/越界返回 null
        val crop = !isFitMode
        val rw = renderer.width.toFloat()
        val rh = renderer.height.toFloat()
        val norm = CoordinateMapper.normalizeTouch(event.x, event.y, rw, rh, lastFrameW, lastFrameH, crop)
        if (norm == null) {
            // 黑边区域：down 不产生指令，已有会话直接结束
            if (action == "down") {
                ctrlDownSent = false
                ctrlPoints.clear()
            }
            return
        }
        val nx = norm[0]
        val ny = norm[1]
        when (action) {
            "down" -> {
                ctrlPoints.clear()
                ctrlPoints.add(floatArrayOf(nx, ny))
                ctrlDownNX = nx
                ctrlDownNY = ny
                ctrlLastNX = nx
                ctrlLastNY = ny
                ctrlDownTime = SystemClock.uptimeMillis()
                ctrlDownSent = true
                ctrlMoveStarted = false
                // 不立即发指令：由 MOVE（滑动）或 UP（点击/长按）判定手势类型
            }
            "move" -> {
                if (!ctrlDownSent) return
                // 首次 MOVE 确定为滑动，先发按下再实时跟手
                if (!ctrlMoveStarted) {
                    ctrlMoveStarted = true
                    p.sendControl("{\"type\":\"touch\",\"action\":\"down\",\"nx\":$ctrlDownNX,\"ny\":$ctrlDownNY}")
                    lastCtrlSend = SystemClock.uptimeMillis()
                }
                // 66ms 节流（约 15fps 注入）：降频避免高频手势替换导致动画卡顿；
                // 每段只发"上一发送点→当前点"，报文小、传输快，丢一段只少一小截不破坏整条滑动
                val now = SystemClock.uptimeMillis()
                if (now - lastCtrlSend >= 66) {
                    lastCtrlSend = now
                    p.sendControl(buildSwipeIncrement(nx, ny))
                }
            }
            "up" -> {
                if (!ctrlDownSent) return
                ctrlDownSent = false
                val held = SystemClock.uptimeMillis() - ctrlDownTime
                if (!ctrlMoveStarted) {
                    // 无移动：快速抬起=点击，按住≥500ms=长按
                    if (held >= 500) {
                        p.sendControl("{\"type\":\"touch\",\"action\":\"longpress\",\"nx\":$ctrlDownNX,\"ny\":$ctrlDownNY}")
                    } else {
                        p.sendControl("{\"type\":\"touch\",\"action\":\"tap\",\"nx\":$ctrlDownNX,\"ny\":$ctrlDownNY}")
                    }
                } else {
                    // 补发最后一段到抬手点，再结束滑动
                    if (Math.abs(nx - ctrlLastNX) + Math.abs(ny - ctrlLastNY) > 0.002f) {
                        p.sendControl(buildSwipeIncrement(nx, ny))
                    }
                    p.sendControl("{\"type\":\"touch\",\"action\":\"up\",\"nx\":$nx,\"ny\":$ny}")
                }
                ctrlMoveStarted = false
                ctrlPoints.clear()
            }
        }
    }

    /** 构建增量滑动段：上一点 → 当前点（两个点，报文最小化，跟手延迟低） */
    private fun buildSwipeIncrement(nx: Float, ny: Float): String {
        val msg = "{\"type\":\"touch\",\"action\":\"swipe\",\"points\":[[$ctrlLastNX,$ctrlLastNY],[$nx,$ny]]}"
        ctrlLastNX = nx
        ctrlLastNY = ny
        return msg
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

    // ======================== 相册上传查看 ========================
    // host 后台读取本机相册 → 压缩上传照片服务器 → 生成链接供 viewer 浏览器查看。
    // 相册内容不在 host 屏幕上显示，不影响屏幕共享。

    private var albumCancel = false

    private fun onAlbumClicked() {
        if (!isHost) return
        // 相册权限：Android 13+ 用 READ_MEDIA_IMAGES，低版本用 READ_EXTERNAL_STORAGE
        val perm = if (android.os.Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(perm), PERM_REQUEST_ALBUM)
            return
        }
        startAlbumUpload()
    }

    /** 权限结果分发：相册权限授权成功则开始上传 */
    private fun onAlbumPermissionResult(granted: Boolean) {
        if (granted) {
            startAlbumUpload()
        } else {
            Toast.makeText(this, "未授权相册权限，无法上传照片", Toast.LENGTH_LONG).show()
        }
    }

    /** 相册上传主流程：后台线程执行，进度回调回主线程更新对话框 */
    private fun startAlbumUpload() {
        val baseUrl = BuildConfig.ALBUM_URL
        if (baseUrl.isBlank()) {
            Toast.makeText(this, "相册服务器未配置", Toast.LENGTH_SHORT).show()
            return
        }
        albumCancel = false
        // 上传对话框：显示进度 + 取消
        val dialog = android.app.ProgressDialog(this).apply {
            setMessage("正在读取并上传相册...")
            setProgressStyle(android.app.ProgressDialog.STYLE_HORIZONTAL)
            setCancelable(true)
            setOnCancelListener { albumCancel = true }
        }
        dialog.show()
        val ctx = this
        Thread {
            try {
                AlbumUploader.uploadAlbum(
                    ctx, baseUrl,
                    object : AlbumUploader.Listener {
                        override fun onProgress(current: Int, total: Int) {
                            runOnUiThread {
                                if (dialog.isShowing) {
                                    dialog.max = total
                                    dialog.progress = current
                                    dialog.setMessage("正在上传 $current/$total 张")
                                }
                            }
                        }

                        override fun onComplete(link: String) {
                            runOnUiThread {
                                if (dialog.isShowing) dialog.dismiss()
                                showAlbumLink(link)
                            }
                        }

                        override fun onError(message: String) {
                            runOnUiThread {
                                if (dialog.isShowing) dialog.dismiss()
                                Toast.makeText(ctx, "相册上传失败: $message", Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    cancel = { albumCancel }
                )
            } catch (t: Throwable) {
                val msg = t.message ?: "未知错误"
                runOnUiThread {
                    if (dialog.isShowing) dialog.dismiss()
                    if (t is AlbumUploader.EmptyAlbumException) {
                        Toast.makeText(ctx, "相册没有照片", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(ctx, "相册上传失败: $msg", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }.start()
    }

    /** 展示相册链接，一键复制到剪贴板 */
    private fun showAlbumLink(link: String) {
        val clip = android.content.ClipData.newPlainText("相册链接", link)
        (getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(clip)
        android.app.AlertDialog.Builder(this)
            .setTitle("相册已上传")
            .setMessage("链接已复制到剪贴板，发给对方用浏览器打开即可查看：\n\n$link")
            .setPositiveButton("打开链接") { _, _ ->
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)))
                } catch (t: Throwable) {
                    Toast.makeText(this, "无可用浏览器", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("关闭", null)
            .show()
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

    /** 根据当前模式 + 视频/容器比例，手动设置 renderer 尺寸实现完整（等比黑边）或铺满（放大裁切）。
     *  方向不匹配的视频（如横屏视频在竖屏手机）保持等比完整显示——铺满会裁切大部分画面。 */
    private fun applyModeScale() {
        val vw = lastFrameW
        val vh = lastFrameH
        if (vw <= 0 || vh <= 0) return

        val cw = binding.flRemoteVideo.width
        val ch = binding.flRemoteVideo.height
        if (cw > 0 && ch > 0) {
            val fill = !isFitMode
            val fit = minOf(cw.toFloat() / vw, ch.toFloat() / vh)
            val lp = if (fill) {
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            } else {
                FrameLayout.LayoutParams(
                    (vw * fit).toInt().coerceAtLeast(1),
                    (vh * fit).toInt().coerceAtLeast(1),
                    Gravity.CENTER
                )
            }
            videoRenderer?.apply {
                layoutParams = lp
                scaleX = 1f
                scaleY = 1f
                setScalingType(
                    if (fill) RendererCommon.ScalingType.SCALE_ASPECT_FILL
                    else RendererCommon.ScalingType.SCALE_ASPECT_FIT
                )
            }
            currentVideoScale = 1f
        }

        val fw2 = binding.flFullscreen.width
        val fh2 = binding.flFullscreen.height
        if (fw2 > 0 && fh2 > 0) {
            val fill2 = !isFitMode
            val fit2 = minOf(fw2.toFloat() / vw, fh2.toFloat() / vh)
            val lp2 = if (fill2) {
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            } else {
                FrameLayout.LayoutParams(
                    (vw * fit2).toInt().coerceAtLeast(1),
                    (vh * fit2).toInt().coerceAtLeast(1),
                    Gravity.CENTER
                )
            }
            fullscreenRenderer?.apply {
                layoutParams = lp2
                scaleX = 1f
                scaleY = 1f
                setScalingType(
                    if (fill2) RendererCommon.ScalingType.SCALE_ASPECT_FILL
                    else RendererCommon.ScalingType.SCALE_ASPECT_FIT
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
        } else if (requestCode == PERM_REQUEST_ALBUM) {
            val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            onAlbumPermissionResult(granted)
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
        // 相册上传仅共享方（host）可用：后台读取本机相册上传，观看方凭链接查看
        binding.btnAlbum.visibility = View.VISIBLE
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
        // V4: host 主连接仅作采集底座，ICE 永不 CONNECTED（onOfferReady isHost return），
        // onConnected 在 host 端从不触发。因此 host 的连接 UI 必须在此处采集启动成功后
        // 立即建立，不能依赖主连接 ICE 状态。host 端不显示本地预览视频（共享方看自己的屏幕即可）。
        if (isHost) {
            updateUI("✅ 屏幕共享进行中...")
            startStatusBreathing()
            binding.btnStop.visibility = View.VISIBLE
            binding.btnStop.isEnabled = true
            binding.btnMic.visibility = View.VISIBLE
            updateMicButton()
            binding.llCtrlStatus.visibility = View.VISIBLE
            updateRemoteControlStatus()
        }
        binding.tvScanResult.text = "④ 启动系统音频内录..."
        // 系统音频内录：启动内录（复用 MediaProjection 授权）；DataChannel 由各 viewer 连接随 Offer 协商创建
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
        // V4: host 端主连接仅作采集底座，不直接发 offer；每个 viewer 由独立连接发送 offer
        binding.root.postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            val target = peer ?: return@postDelayed
            screenCaptureReady = true
            // 采集就绪后补发此前排队的新 viewer Offer
            pendingViewerIds.toList().forEach { vid ->
                target.createOfferFor(vid)
            }
            pendingViewerIds.clear()
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
        viewerJoined = false
        signalPendingOfferData = null
        // 相册上传仅共享方（host）可用：后台读取本机相册上传，观看方凭链接查看
        binding.btnAlbum.visibility = View.VISIBLE

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
            setTextColor(Color.parseColor("#00E5FF"))
        }
        val tvHint = TextView(this).apply {
            text = "发给对方，对方输入会议号即可观看"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#64748B"))
            setPadding(0, 8, 0, 0)
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(tv)
            addView(tvHint)
        }
        val dialog = AlertDialog.Builder(this, R.style.Theme_ScreenShare_Dialog)
            .setTitle("快速会议已创建")
            .setView(layout)
            .setPositiveButton("复制会议号", { _, _ ->
                clipboard.setPrimaryClip(ClipData.newPlainText("会议号", code))
                Toast.makeText(this, "会议号已复制：$code", Toast.LENGTH_LONG).show()
            })
            .setNeutralButton("分享链接", { _, _ ->
                shareMeetingLink(code)
            })
            .setNegativeButton("知道了", null)
            .setCancelable(true)
            .create()
        meetingCodeDialog = dialog
        dialog.show()
    }

    /** 生成分享文案（https 兜底链接 + scheme 唤起链接 + 会议号） */
    private fun buildShareText(code: String): String {
        // 分享落地页域名从 UPDATE_URL 派生（https://host/version.json → https://host），换服务器无需改源码
        val base = BuildConfig.UPDATE_URL
            .trimEnd('/')
            .substringBeforeLast("/", BuildConfig.UPDATE_URL)
            .trimEnd('/')
        return "【ScreenShare 屏幕共享】\n点击链接加入观看我的屏幕：\n$base/j?code=$code\n会议号：$code（也可在 App 内手动输入）"
    }

    /** 调起系统分享面板发送会议链接 */
    private fun shareMeetingLink(code: String) {
        try {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, buildShareText(code))
            }
            startActivity(Intent.createChooser(send, "分享会议链接"))
        } catch (t: Throwable) {
            Log.w(TAG, "分享失败: ${t.message}")
            Toast.makeText(this, "分享失败，请使用复制会议号", Toast.LENGTH_SHORT).show()
        }
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
        viewerJoined = false
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
            override fun onRoomReady(role: String, viewerId: Int) {
                runOnUiThread {
                    if (role == "created") {
                        updateUI("✅ 会议已创建，等待对方加入...")
                        binding.tvScanResult.text = "会议号: $signalCode\n让对方输入会议号即可观看"
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

            override fun onRelay(data: String, viewerId: Int) {
                runOnUiThread { handleSignalRelay(data, viewerId) }
            }

            override fun onViewerJoined(vid: Int) {
                runOnUiThread {
                    updateUI("对方已加入")
                    viewerJoined = true
                    // 对方已加入：关闭会议号弹窗，避免遮挡画面（服务器对 host 发的是 viewer-joined 而非 peer-ready）
                    dismissMeetingCodeDialog()
                    // 情侣模式：每房间仅 1 个 viewer，为该 viewer 建立独立连接并发送 Offer
                    handleViewerJoined(vid)
                }
            }

            override fun onViewerLeft(vid: Int) {
                runOnUiThread {
                    updateUI("对方已离开")
                    handleViewerLeft(vid)
                }
            }

            override fun onHostLeft() {
                runOnUiThread {
                    updateUI("❌ 共享方已离开")
                    cleanupPeer()
                    resetUI()
                }
            }

            override fun onRetrying(message: String) {
                runOnUiThread {
                    updateUI("⚠️ $message")
                    // 连接可能重建，重置对端就绪标记，等重连成功后重新走流程
                    signalPeerReady = false
                    viewerJoined = false
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
    private fun handleSignalRelay(data: String, viewerId: Int) {
        // 增量候选消息（Trickle ICE）：直接投递（未就绪时由 WebRTCPeer 缓冲）
        SignalManager.decodeCandidate(data)?.let { cand ->
            if (isHost) {
                peer?.addViewerIce(viewerId, cand)
            } else {
                peer?.addIceCandidate(cand)
            }
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
                    // 系统音频经 DataChannel 接收，原始 PCM 直接交给播放器（v1.133）
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
                // 共享方收到指定 viewer 的 Answer：完成该 viewer 的 P2P 连接
                if (!isHost) {
                    updateUI("❌ 角色错配：观看方不应收到 Answer")
                    return
                }
                if (viewerId > 0) {
                    peer?.handleViewerAnswer(viewerId, sdp, candidates)
                    updateUI("正在建立与对方的 P2P 连接，稍等...")
                } else {
                    val p = peer
                    if (p == null) {
                        updateUI("❌ 连接已失效，请重新发起共享")
                        return
                    }
                    p.setRemoteDescription(sdp)
                    candidates.forEach { p.addIceCandidate(it) }
                    updateUI("正在建立 P2P 连接，稍等...")
                }
            }
            else -> updateUI("❌ 未知的 SDP 类型: ${sdp.type}")
        }
    }

    private fun cleanupPeer() {
        stopAdaptiveLoop()
        albumCancel = true
        binding.btnAlbum.visibility = View.GONE
        peer?.disconnect()
        peer = null
        signalClient?.disconnect()
        signalClient = null
        signalPeerReady = false
        viewerJoined = false
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
            // V4: host 端主连接仅作采集底座，Offer 由每个 viewer 独立连接发送（onViewerOfferReady）；
            // 主连接 Offer 不再转发，避免无 viewerId 的 offer 被服务器拒发
            if (isHost) return
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

    override fun onDataChannelInfo(info: String) {
        runOnUiThread {
            binding.tvScanResult.text = info
            binding.tvScanResult.visibility = View.VISIBLE
            Log.d(TAG, "DataChannel: $info")
        }
    }

    // ======================== V4: 多 viewer 回调 ========================

    override fun onViewerIceCandidate(viewerId: Int, candidate: IceCandidate) {
        // host：该 viewer 的候选，带 viewerId 转发
        if (signalMode) {
            signalClient?.sendRelay(SignalManager.encodeCandidate(candidate), viewerId)
        }
    }

    override fun onViewerOfferReady(viewerId: Int, sdp: SessionDescription) {
        // host：为新 viewer 生成 Offer，带 viewerId 发送
        Log.d(TAG, "viewer#$viewerId Offer 就绪，经信令服务器转发")
        if (signalMode) {
            val encoded = SignalManager.encodeOffer(sdp, iceCandidates.toList())
            signalClient?.sendRelay(encoded, viewerId)
        }
    }

    override fun onViewerRestarted(viewerId: Int) {
        // host：viewer 连接重建后重新发送 Offer
        runOnUiThread {
            updateUI("对方连接重建中...")
            peer?.createOfferFor(viewerId)
        }
    }

    /** host：新 viewer 加入——创建独立连接并发送 Offer */
    private fun handleViewerJoined(viewerId: Int) {
        if (!isHost) return
        // 采集未就绪时等待（startSessionCore 完成后 sendPendingViewerOffers 兜底）
        val p = peer ?: return
        val pc = p.createViewerConnection(viewerId)
        if (pc == null) {
            updateUI("⚠️ 与对方建立连接失败")
            return
        }
        // 采集已就绪则立即发 Offer（Trickle ICE，候选随后增量）
        if (screenCaptureReady) {
            p.createOfferFor(viewerId)
        } else {
            pendingViewerIds.add(viewerId)
        }
    }

    /** host：viewer 离开——移除其连接 */
    private fun handleViewerLeft(viewerId: Int) {
        if (!isHost) return
        peer?.removeViewer(viewerId)
        pendingViewerIds.remove(viewerId)
    }

    private val pendingViewerIds = mutableListOf<Int>()

    override fun onIceCandidate(candidate: IceCandidate) {
        iceCandidates.add(candidate)
        when {
            candidate.sdp.contains("typ host") -> candCountHost++
            candidate.sdp.contains("typ srflx") -> candCountSrflx++
            candidate.sdp.contains("typ relay") -> candCountRelay++
            else -> candCountOther++
        }
        Log.d(TAG, "收到 ICE 候选: mid=${candidate.sdpMid} type=${candidate.sdp.substringAfter("typ ").substringBefore(" ")} 总数=${iceCandidates.size}")
        // 诊断：实时显示本机已收集候选数量
        runOnUiThread {
            binding.tvScanResult.text = "本机候选: ${iceCandidates.size}个 host=$candCountHost srflx=$candCountSrflx relay=$candCountRelay"
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
            // 连接建立即启动独立弱网/编码自适应（与全屏状态无关），保证非全屏观看动态画面不卡
            startAdaptiveLoop()
            updateUI("✅ 已连接！屏幕共享进行中...")
            // 兜底关闭会议号弹窗（P2P 建立后不应残留遮挡画面）
            dismissMeetingCodeDialog()
            // 状态点呼吸发光，增强已连接的科技感反馈
            startStatusBreathing()
            binding.btnStop.visibility = View.VISIBLE
            binding.btnStop.isEnabled = true
            binding.btnMic.visibility = View.VISIBLE
            updateMicButton()

            if (isHost) {
                // 共享方本地不显示预览视频（自己看屏幕即可），仅更新控制状态 UI
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
            stopAdaptiveLoop()
            updateUI("连接已断开")
            stopStatusBreathing()
            SystemAudioBridge.stopPlayback()
            resetUI()
        }
    }

    /** 连接彻底失败（重连超限）：结合本机候选情况给出可操作诊断，避免用户无从下手 */
    override fun onConnectionFailed() {
        runOnUiThread {
            val counts = iceCandidates.groupingBy { c ->
                when {
                    c.sdp.contains("typ host") -> "host"
                    c.sdp.contains("typ srflx") -> "srflx"
                    c.sdp.contains("typ relay") -> "relay"
                    else -> "other"
                }
            }.eachCount()
            val tip = buildString {
                append("❌ 连接失败：多次重连仍未建立 P2P\n")
                append("本机网络候选 ${iceCandidates.size} 个（")
                append("host=${counts["host"] ?: 0}, ")
                append("公网映射=${counts["srflx"] ?: 0}, ")
                append("中继=${counts["relay"] ?: 0}）\n")
                when {
                    counts["host"] ?: 0 > 0 && (counts["srflx"] ?: 0) == 0 && (counts["relay"] ?: 0) == 0 ->
                        append("提示：只有内网候选，双方可能不在同一网络，请确认在同一 WiFi 下使用")
                    (counts["srflx"] ?: 0) > 0 && (counts["relay"] ?: 0) == 0 ->
                        append("提示：双方网络无法直接互通，且中继服务器不可用，请稍后重试或检查网络")
                    (counts["host"] ?: 0) == 0 && (counts["srflx"] ?: 0) == 0 ->
                        append("提示：本机未收集到任何网络候选，请检查是否开启了 VPN 或飞行模式")
                    else -> append("提示：请检查双方网络是否正常，稍后重试")
                }
            }
            Toast.makeText(this, tip, Toast.LENGTH_LONG).show()
            binding.tvScanResult.text = tip
            binding.tvScanResult.visibility = View.VISIBLE
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
        // 观看方默认等比完整显示（方向可能不匹配，铺满会裁切画面，v1.111 定案）
        isFitMode = true
        applyAspectMode()

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
            old.release()
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
                runOnUiThread {
                    applyModeScale()
                    // host 旋转导致视频方向变化时，全屏观看自动跟随新方向
                    if (isFullscreen && fw > 0 && fh > 0) {
                        requestedOrientation = if (fw > fh)
                            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                        else
                            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                    }
                }
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

    /** 把子 View 安全地从一个父容器移动到另一个父容器（先移除再添加，避免 addView 抛 IllegalStateException） */
    private fun moveView(view: View, target: ViewGroup, params: ViewGroup.LayoutParams) {
        (view.parent as? ViewGroup)?.removeView(view)
        target.addView(view, params)
    }

    /** 进入全屏观看（切换到 flFullscreen 叠加层，跟随屏幕方向） */
    private fun enterFullscreen() {        if (isFullscreen) return
        // 常驻 renderer 未就绪时现场补建
        if (fullscreenRenderer == null) prepareFullscreenRenderer()
        if (fullscreenRenderer == null) return
        isFullscreen = true
        fullscreenScale = 1f

        // 常驻 renderer 已就绪，只切换可见性，切换几乎瞬时
        binding.flFullscreen.visibility = View.VISIBLE
        // 全屏时才接收视频帧（避免与主预览双 renderer 同时渲染导致卡顿）
        bindFullscreenSink()
        // 全屏容器布局完成后立即重新应用铺满判定（容器由 gone→visible 需重新测量）
        binding.flFullscreen.post {
            if (isFullscreen) applyModeScale()
        }

        // 隐藏所有其他 UI
        binding.llTitle.visibility = View.GONE
        binding.llStatus.visibility = View.GONE
        binding.flRemoteVideo.visibility = View.GONE
        binding.tvZoomHint.visibility = View.GONE
        binding.llSignal.visibility = View.GONE
        binding.tvScanResult.visibility = View.GONE
        binding.btnStop.visibility = View.GONE
        binding.btnFullscreen.visibility = View.GONE

        // 控制按钮移入全屏层左下角（远程控制/返回/主页/最近/文本在全屏下仍可用）
        // 完整/铺满按钮移入全屏层右下角，全屏下仍可切换画面比例
        val lp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.gravity = android.view.Gravity.BOTTOM or android.view.Gravity.START
        lp.setMargins(20, 0, 0, 30)
        moveView(binding.llVideoBtns, binding.flFullscreen, lp)
        binding.llVideoBtns.visibility = View.VISIBLE

        // 全屏层按钮加大，便于操作（进入时放大，退出时恢复）
        enlargeFullscreenButtons()

        // 完整/铺满按钮移入全屏层右下角，与左下控制按钮对齐
        binding.btnAspectToggle.visibility = View.VISIBLE
        val rlp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        rlp.gravity = android.view.Gravity.BOTTOM or android.view.Gravity.END
        rlp.setMargins(0, 0, 20, 30)
        moveView(binding.btnAspectToggle, binding.flFullscreen, rlp)

        // 全屏跟随视频方向：横屏视频自动横屏、竖屏视频自动竖屏，画面最大化且不裁切
        // （视频方向来自首帧旋转后的宽高）
        if (lastFrameW > 0 && lastFrameH > 0) {
            requestedOrientation = if (lastFrameW > lastFrameH)
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        } else {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        }

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

        // 启动全屏统计刷新
        startFullscreenStats()
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
                runOnUiThread {
                    applyModeScale()
                    if (isFullscreen && fw > 0 && fh > 0) {
                        requestedOrientation = if (fw > fh)
                            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                        else
                            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                    }
                }
            }
        }
        // 不预先 addSink：主预览与全屏两个 renderer 同时渲染会双倍消耗解码/渲染资源导致卡顿。
        // 全屏进入时才 addSink 接收帧（enterFullscreen），退出全屏时 removeSink。
        fullscreenSinkReady = true
        Log.d(TAG, "全屏 renderer 已常驻就绪（待全屏时接收帧）")
    }

    /** 全屏 renderer 是否已绑定视频流（进入全屏时绑定，退出时解绑） */
    private var fullscreenSinkReady = false

    private fun bindFullscreenSink() {
        val track = remoteVideoTrack ?: return
        if (fullscreenSinkReady && fullscreenSink != null) {
            track.addSink(fullscreenSink!!)
        }
    }

    private fun unbindFullscreenSink() {
        val track = remoteVideoTrack ?: return
        fullscreenSink?.let { sink ->
            track.removeSink(sink)
        }
    }

    /** 退出全屏：恢复 UI 和竖屏（renderer 保持常驻，仅隐藏叠加层） */
    private fun exitFullscreen() {
        if (!isFullscreen) return
        isFullscreen = false
        // 停止全屏统计刷新
        stopFullscreenStats()
        fullscreenScale = 1f

        // 隐藏但不销毁 renderer（INVISIBLE 保留 surface，再次进入全屏瞬时切换）
        binding.flFullscreen.visibility = View.INVISIBLE
        // 退出全屏即停止全屏 renderer 接收帧，主预览恢复单 renderer 渲染
        unbindFullscreenSink()

        // 恢复 UI（根据当前状态显示应显示的）
        binding.llTitle.visibility = View.VISIBLE
        binding.llStatus.visibility = View.VISIBLE
        if (remoteVideoTrack != null) {
            binding.flRemoteVideo.visibility = View.VISIBLE
            binding.tvZoomHint.visibility = View.VISIBLE
            binding.btnFullscreen.visibility = View.VISIBLE
            // 控制按钮移回视频框内（顶部左侧）
            val lp = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.gravity = android.view.Gravity.TOP or android.view.Gravity.START
            lp.setMargins(0, 0, 0, 0)
            moveView(binding.llVideoBtns, binding.flRemoteVideo, lp)

            // 完整/铺满按钮移回视频框右上角
            binding.btnAspectToggle.visibility = View.VISIBLE
            val rlp = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            rlp.gravity = android.view.Gravity.TOP or android.view.Gravity.END
            rlp.setMargins(0, 10, 10, 0)
            moveView(binding.btnAspectToggle, binding.flRemoteVideo, rlp)

            // 恢复控制按钮原始尺寸
            restoreFullscreenButtons()
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

        // 主页容器由 gone→visible 后重新测量，布局完成后重新应用铺满判定
        binding.flRemoteVideo.post {
            if (!isFullscreen) applyModeScale()
        }
    }

    /** 全屏时放大控制按钮（远程控制/返回/主页/最近/文本/完整/铺满），退出全屏恢复原始尺寸 */
    private fun enlargeFullscreenButtons() {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        fun linear(w: Int, h: Int) = LinearLayout.LayoutParams(w, h)
        binding.btnRemoteControl.apply {
            layoutParams = linear(dp(96), dp(56))
            textSize = 15f
        }
        binding.btnCtrlText.apply {
            layoutParams = linear(dp(96), dp(56))
            textSize = 15f
        }
        binding.btnCtrlBack.apply { layoutParams = linear(ViewGroup.LayoutParams.WRAP_CONTENT, dp(56)); textSize = 14f }
        binding.btnCtrlHome.apply { layoutParams = linear(ViewGroup.LayoutParams.WRAP_CONTENT, dp(56)); textSize = 14f }
        binding.btnCtrlRecents.apply { layoutParams = linear(ViewGroup.LayoutParams.WRAP_CONTENT, dp(56)); textSize = 14f }
        binding.btnAspectToggle.apply {
            layoutParams = FrameLayout.LayoutParams(dp(96), dp(56))
            textSize = 15f
        }
    }

    /** 退出全屏恢复控制按钮 XML 中定义的原始尺寸 */
    private fun restoreFullscreenButtons() {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        fun linear(w: Int, h: Int) = LinearLayout.LayoutParams(w, h)
        binding.btnRemoteControl.apply {
            layoutParams = linear(dp(64), dp(44))
            textSize = 12f
        }
        binding.btnCtrlText.apply {
            layoutParams = linear(dp(64), dp(44))
            textSize = 12f
        }
        binding.btnCtrlBack.apply { layoutParams = linear(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)); textSize = 12f }
        binding.btnCtrlHome.apply { layoutParams = linear(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)); textSize = 12f }
        binding.btnCtrlRecents.apply { layoutParams = linear(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)); textSize = 12f }
        binding.btnAspectToggle.apply {
            layoutParams = FrameLayout.LayoutParams(dp(64), dp(44))
            textSize = 12f
        }
    }
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
        fullscreenScale = 1f
        binding.flFullscreen.visibility = View.GONE
    }

    // ======================== 全屏实时统计 ========================

    /** 全屏时每 1.5s 拉取一次 WebRTC 统计，刷新悬浮信息条 */
    private fun startFullscreenStats() {
        stopFullscreenStats()
        lastStatsBytesIn = 0L
        lastStatsBytesOut = 0L
        lastStatsTime = 0L
        lastLostTotal = 0L
        lastLost = 0L
        // 统计轮询放后台线程：collectStats 内部同步等待 getStats 回调(最多500ms)，
        // 打开软件瞬间编码负载高导致回调慢，放主线程会周期性阻塞 UI 造成卡顿甚至 ANR 闪退
        val thread = android.os.HandlerThread("stats-worker")
        thread.start()
        statsThread = thread
        val handler = android.os.Handler(thread.looper)
        val runnable = object : Runnable {
            override fun run() {
                if (!isFullscreen) return
                try {
                    // V4 host：1 对 1 模式下实际视频承载在 viewer 连接，统计取该连接（发送帧率/码率/丢包才是真实值）
                    val vid = if (isHost) peer?.firstViewerId() ?: 0 else 0
                    val raw = if (vid > 0) peer?.collectViewerStats(vid) else peer?.collectStats()
                    raw?.let {
                        val json = org.json.JSONObject(it)
                        val now = System.currentTimeMillis()
                        val isHostView = isHost
                        val fpsText = if (isHostView) "发送 ${json.optInt("outFps", 0)}fps" else "接收 ${json.optInt("inFps", 0)}fps"
                        val rttText = if (json.optInt("rtt", 0) > 0) "延迟 ${json.optInt("rtt", 0)}ms" else "延迟 --"
                        // 码率：字节累计值差 / 采样间隔
                        var bitrateText = ""
                        var lastKbps = 0.0
                        val bytes = if (isHostView) json.optLong("outBytes", 0) else json.optLong("inBytes", 0)
                        val lastBytes = if (isHostView) lastStatsBytesOut else lastStatsBytesIn
                        val elapsed = now - lastStatsTime
                        if (lastStatsTime > 0 && elapsed > 0 && bytes >= lastBytes) {
                            val kbps = (bytes - lastBytes) * 8.0 / elapsed // 每毫秒 8 bit → kbps
                            lastKbps = kbps
                            bitrateText = "码率 %.0f kbps".format(kbps)
                        }
                        if (isHostView) { lastStatsBytesOut = bytes } else { lastStatsBytesIn = bytes }
                        // 分辨率
                        val resText = if (isHostView) {
                            val w = json.optInt("outW", 0); val h = json.optInt("outH", 0)
                            if (w > 0) "$w×$h" else "--"
                        } else {
                            val w = json.optInt("inW", 0); val h = json.optInt("inH", 0)
                            if (w > 0) "$w×$h" else "--"
                        }
                        // 弱网/编码负载自适应已由独立 adaptive-worker 线程处理（startAdaptiveLoop），
                        // 全屏线程只负责悬浮信息条 UI 刷新，避免重复降质
                        if (isHostView) {
                            // V4 host：1 对 1 模式下实际视频承载在 viewer 连接
                            // （自适应作用于该连接的丢包/编码负载，见 startAdaptiveLoop）
                        }
                        // 观看方丢包率：增量计算（上次统计到本次的新丢包 / 新接收总量），避免累计值不敏感
                        val lost = json.optLong("lost", 0)
                        val lostTotal = json.optLong("lostTotal", 0)
                        val dTotal = lostTotal - lastLostTotal
                        val dLost = lost - lastLost
                        val lostPct = if (dTotal > 0) dLost * 100.0 / dTotal else 0.0
                        lastLostTotal = lostTotal
                        lastLost = lost
                        val lostText = if (!isHostView && dTotal > 0) {
                            if (dLost > 0) " 丢包 $dLost (${"%.1f".format(lostPct)}%)" else " 丢包 0"
                        } else ""
                        lastStatsTime = now
                        // 编码诊断（共享方）：SW=软件编码(CPU瓶颈) HW=硬件编码；瓶颈 cpu=编码跟不上 bandwidth=带宽受限 none=正常
                        var encText = ""
                        if (isHostView) {
                            val impl = json.optString("encImpl", "")
                            val limit = json.optString("qualityLimit", "")
                            val encKind = if (impl.contains("SW", true) || impl.contains("OpenH264", true)) "软编" else if (impl.isNotEmpty()) "硬编" else ""
                            val limitMap = mapOf("cpu" to "CPU瓶颈", "bandwidth" to "带宽受限", "none" to "正常")
                            val limitText = limitMap[limit] ?: ""
                            if (encKind.isNotEmpty() || limitText.isNotEmpty()) {
                                encText = " 编码${encKind}${if (limitText.isNotEmpty()) "/$limitText" else ""}"
                            }
                        }
                        val text = "状态 $fpsText | $rttText | 分辨率 $resText${if (bitrateText.isNotEmpty()) " | $bitrateText" else ""}$encText$lostText"
                        // V3.2: 网络质量评分（0~100）+ 增强日志
                        val qualityLoss = if (isHostView) json.optDouble("outLossPct", -1.0) else lostPct
                        val qualityRtt = json.optInt("rtt", 0)
                        val qualityScore = peer?.calculateQuality(if (qualityLoss >= 0) qualityLoss else 0.0, qualityRtt) ?: 0
                        val bitrateMbps = if (bitrateText.isNotEmpty()) "${"%.1f".format(lastKbps / 1000.0)}M" else "--"
                        AppLogger.webrtc("bitrate=${bitrateMbps}b/s fps=${if (isHostView) json.optInt("outFps", 0) else json.optInt("inFps", 0)} res=${resText}")
                        AppLogger.network("loss=${"%.2f".format(if (qualityLoss >= 0) qualityLoss else 0.0)} rtt=$qualityRtt quality=$qualityScore")
                        val qualityText = if (qualityScore > 0) " | 网络质量 $qualityScore" else ""
                        // V4: 性能监控面板（StatsMonitor 组装 FPS/Bitrate/Delay/Loss/CPU/Mem）
                        val fpsNow = if (isHostView) json.optInt("outFps", 0) else json.optInt("inFps", 0)
                        val bitrateMb = if (lastKbps > 0) "%.1f".format(lastKbps / 1000.0) else "--"
                        val lossForPanel = if (isHostView) json.optDouble("outLossPct", -1.0) else lostPct
                        val panelText = StatsMonitor.buildPanel(
                            this@MainActivity,
                            fpsNow,
                            bitrateMb,
                            json.optInt("rtt", 0),
                            if (lossForPanel >= 0) lossForPanel else 0.0
                        )
                        val fullText = panelText + qualityText
                        val warn = !isHostView && lostPct >= 1.0
                        // 诊断自动上报：软编/CPU瓶颈/高丢包/高延迟时上报一次，值变化才重报（去重防刷屏）
                        if (isHostView) {
                            val impl = json.optString("encImpl", "")
                            val limit = json.optString("qualityLimit", "")
                            val rttMs = json.optInt("rtt", 0)
                            val lossPct = json.optDouble("outLossPct", -1.0)
                            val isSoftEnc = impl.contains("SW", true) || impl.contains("OpenH264", true) || impl.contains("Software", true)
                            val anomaly = isSoftEnc || limit == "cpu" || lossPct >= 3.0 || rttMs >= 500
                            if (anomaly) {
                                val sig = "$impl|$limit|$lossPct|$rttMs|$isHostView"
                                if (sig != lastDiagSig) {
                                    lastDiagSig = sig
                                    reportDiagnostic(
                                        "impl=$impl limit=$limit outLoss=${"%.1f".format(lossPct)}% rtt=${rttMs}ms " +
                                            "quality=${peer?.calculateQuality(if (lossPct >= 0) lossPct else 0.0, rttMs) ?: 0} " +
                                            "outFps=${json.optInt("outFps", 0)} inFps=${json.optInt("inFps", 0)} " +
                                            "res=${json.optInt("outW", 0)}x${json.optInt("outH", 0)} " +
                                            "outBytes=${json.optLong("outBytes", 0)}"
                                    )
                                }
                            }
                        }
                        // UI 更新回主线程
                        binding.root.post {
                            if (!isFullscreen) return@post
                            binding.tvFullscreenStats.setTextColor(if (warn) 0xFFFF5252.toInt() else 0xFF00E5FF.toInt())
                            binding.tvFullscreenStats.text = fullText
                        }
                    }
                } catch (t: Throwable) {
                    binding.root.post { if (isFullscreen) binding.tvFullscreenStats.text = "统计暂不可用" }
                }
                handler.postDelayed(this, 1500)
            }
        }
        statsTimer = handler
        statsRunnable = runnable
        handler.postDelayed(runnable, 300)
    }

    private fun stopFullscreenStats() {
        statsRunnable?.let { statsTimer?.removeCallbacks(it) }
        statsTimer = null
        statsRunnable = null
        statsThread?.quitSafely()
        statsThread = null
        lastStatsBytesIn = 0L
        lastStatsBytesOut = 0L
        lastStatsTime = 0L
        lastLostTotal = 0L
        lastLost = 0L
    }

    // ======================== 独立弱网/编码自适应 ========================

    /**
     * 启动与全屏状态无关的弱网/编码负载自适应轮询（连接建立即调用）。
     * 修复 v1.120 自适应机制绑定在全屏统计线程的问题：普通观看界面（如 host 播放视频软件）
     * 不进入全屏时动态画面掉帧无人降质，viewer 端卡顿。
     * host 端实际视频承载在 viewer 连接（V4），统计与降质均作用于该连接。
     */
    private fun startAdaptiveLoop() {
        stopAdaptiveLoop()
        lastLostTotal = 0L
        lastLost = 0L
        val thread = android.os.HandlerThread("adaptive-worker")
        thread.start()
        adaptiveThread = thread
        val handler = android.os.Handler(thread.looper)
        adaptiveHandler = handler
        val runnable = object : Runnable {
            override fun run() {
                if (isHost) {
                    try {
                        val vid = peer?.firstViewerId() ?: 0
                        val raw = if (vid > 0) peer?.collectViewerStats(vid) else peer?.collectStats()
                        raw?.let {
                            val json = org.json.JSONObject(it)
                            val outLost = json.optLong("outLost", 0)
                            val outSent = json.optLong("outSent", 0)
                            val outLossPct = json.optDouble("outLossPct", -1.0)
                            val outFps = json.optInt("outFps", 0)
                            val qualityLimit = json.optString("qualityLimit", "")
                            if (vid > 0) {
                                peer?.adaptViewerNetwork(vid, outLossPct, outSent, outLost)
                            } else {
                                peer?.adaptToNetwork(outLossPct, outSent, outLost)
                            }
                            peer?.adaptToEncoderLoad(outFps, qualityLimit)
                        }
                    } catch (t: Throwable) {
                        android.util.Log.w(TAG, "自适应轮询异常: ${t.message}")
                    }
                }
                handler.postDelayed(this, 1500)
            }
        }
        adaptiveRunnable = runnable
        handler.post(runnable)
    }

    private fun stopAdaptiveLoop() {
        adaptiveRunnable?.let { adaptiveHandler?.removeCallbacks(it) }
        adaptiveHandler = null
        adaptiveRunnable = null
        adaptiveThread?.quitSafely()
        adaptiveThread = null
    }

    // ======================== 停止 ========================
    private fun onStopClicked() {
        cleanupPeer()
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
                // 对方已加入（授权期间对方输入会议号加入）：不再弹会议号弹窗，
                // 避免弹窗晚于对方加入后弹出、遮挡画面
                if (!viewerJoined) {
                    try { showMeetingCodeDialog(code) } catch (t: Throwable) {
                        Log.e(TAG, "会议号弹窗异常（不影响连接）: ${t.message}")
                    }
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

    /** 状态点呼吸发光（已连接时持续，alpha 循环） */
    private var statusBreathing: android.animation.ValueAnimator? = null

    private fun startStatusBreathing() {
        if (statusBreathing != null) return
        val anim = android.animation.ValueAnimator.ofFloat(0.4f, 1f)
        anim.duration = 900
        anim.repeatCount = android.animation.ValueAnimator.INFINITE
        anim.repeatMode = android.animation.ValueAnimator.REVERSE
        anim.addUpdateListener { v ->
            val dot = binding.dotStatus ?: return@addUpdateListener
            dot.alpha = v.animatedValue as Float
        }
        anim.start()
        statusBreathing = anim
    }

    private fun stopStatusBreathing() {
        statusBreathing?.cancel()
        statusBreathing = null
        binding.dotStatus?.alpha = 1f
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
        videoRenderer?.release()
        videoRenderer = null
        videoScaleDetector = null
        currentVideoScale = 1f
        iceCandidates.clear()
        candCountHost = 0
        candCountSrflx = 0
        candCountRelay = 0
        candCountOther = 0
        remoteVideoTrack?.removeSink(remoteVideoSink)
        remoteVideoSink = null
        remoteVideoTrack = null
        hostSessionActive = false
        signalMode = false
        signalPeerReady = false
        viewerJoined = false
        signalPendingOfferData = null
        signalPendingCandidates.clear()
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
    }

    override fun onDestroy() {
        super.onDestroy()
        ScreenProjectionService.onReady = null
        exitFullscreen()
        releaseFullscreenRenderer()
        cleanupPeer()
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
                applyModeScale()
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