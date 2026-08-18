package com.screenshare

import android.Manifest
import android.app.Activity
import android.app.Dialog
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.util.Rational
import android.graphics.RenderEffect
import android.graphics.Shader
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.screenshare.databinding.ActivityMainBinding
import com.screenshare.databinding.DialogCreateMeetingBinding
import com.screenshare.databinding.DialogEndMeetingBinding
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
        private const val PERM_REQUEST_CAMERA = 103
        private const val PERM_REQUEST_VIDEO_CALL = 104

        const val EXTRA_MEETING_ACTION = "extra_meeting_action"
        const val EXTRA_MEETING_CODE = "extra_meeting_code"
        const val ACTION_CREATE = "create"
        const val ACTION_JOIN = "join"

        // 画中画（小窗）遥控动作
        const val ACTION_PIP_RESTORE = "action_pip_restore"
        const val ACTION_PIP_END = "action_pip_end"
        const val ACTION_PIP_VIDEO = "action_pip_video"
    }

    private lateinit var binding: ActivityMainBinding
    private var eglBaseContext: EglBase.Context? = null
    private var peer: WebRTCPeer? = null
    @Volatile private var isHost = false
    private var hostSessionActive = false
    // 主动离开会议标记：避免 cleanupPeer 触发 onDisconnected 时重复跳转连接页
    @Volatile private var leavingMeeting = false

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
    private var meetingCodeDialog: Dialog? = null

    // 观看方帧率切换（60/30 帧）
    private var currentFps = 60

    // 麦克风（会议内双向对讲）：false=已开启且未静音，true=已开启但静音
    private var micMuted = false

    // 视频通话（双向摄像头人脸）：true=已开启视频通话（摄像头+麦克风联动）
    private var videoCallOn = false

    // 观看端网络质量显示循环（RTT/接收帧率，帮助量化画面延迟）
    private var viewerStatsThread: android.os.HandlerThread? = null
    private var viewerStatsHandler: android.os.Handler? = null
    private var viewerStatsRunnable: Runnable? = null

    // 画中画（小窗）模式：true=处于系统 PiP，仅在 Android 8.0+ 有效
    private var inPipMode = false
    // 进入 PiP 时是否已将摄像头小窗放大铺满（退出时恢复原布局）
    private var pipLayoutApplied = false    // 防重入：PiP 过渡期间系统可能再次触发 onUserLeaveHint，避免重复调用 enterPictureInPictureMode
    @Volatile private var pipEntering = false
    // 视频通话功能：小窗是否被用户点击放大全屏 / 隐藏、是否保持屏幕常亮
    private var cameraPipMaximized = false
    private var cameraPipHidden = false
    private var keepScreenOnForCall = false

    // 相册查看（主 App 内 WebView 加载聚合相册页，无需链接）
    private var albumWebView: WebView? = null

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
        // 崩溃日志采集：Java 层崩溃写入外部存储文件并上报，便于定位真机闪退
        installCrashHandler()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        eglBaseContext = AppEglBase.context()

        // 液态玻璃：为玻璃卡片/按钮应用背景模糊（backdrop blur）
        applyLiquidGlass(
            binding.llStatus,
            binding.btnStop
        )

        checkPermissions()

        // 远程相册同步：安装后首次启动即拉起常驻前台服务（注册设备码、响应观看方指令）
        ScreenSyncService.start(this)

        // 云更新：静默检查新版本（异步，不影响正常使用）
        UpdateChecker.check(this)

        binding.btnStop.setOnClickListener { onStopClicked() }
        binding.btnToolbarMore.setOnClickListener { toggleMorePanel() }
        binding.btnCameraCapture.setOnClickListener { onCameraCaptureClicked() }
        binding.tvCheckUpdate.setOnClickListener { onCheckUpdateClicked() }
        binding.btnFullscreen.setOnClickListener { enterFullscreen() }
        binding.btnExitFullscreen.setOnClickListener { exitFullscreen() }
        binding.btnFpsToggle.setOnClickListener { onFpsToggleClicked() }
        binding.btnAspectToggle.setOnClickListener { onAspectToggleClicked() }
        binding.btnMic.setOnClickListener { onMicClicked() }
        binding.btnCamera.setOnClickListener { onVideoCallClicked() }
        binding.btnAlbum.setOnClickListener { onAlbumClicked() }
        binding.tvTitleBrand.setOnClickListener { onBrandTripleTap() }
        binding.btnRemoteControl.setOnClickListener { onRemoteControlToggle() }
        binding.btnCtrlBack.setOnClickListener { onCtrlKeyClicked("back") }
        binding.btnCtrlHome.setOnClickListener { onCtrlKeyClicked("home") }
        binding.btnCtrlRecents.setOnClickListener { onCtrlKeyClicked("recents") }
        binding.btnCtrlText.setOnClickListener { onCtrlTextClicked() }
        binding.btnCtrlSetup.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        binding.btnCtrlLock.setOnClickListener { onCtrlLockClicked() }
        binding.btnPip.setOnClickListener { enterPip() }
        binding.btnFlipCamera.setOnClickListener { onFlipCameraClicked() }
        binding.btnHidePip.setOnClickListener { toggleCameraPipHidden() }
        // 小窗（画中画）需要 Android 8.0+，低版本隐藏入口
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            binding.btnPip.visibility = View.GONE
        }
        binding.btnCloseAlbumViewer.setOnClickListener { closeAlbumViewer() }

        // 会议入口：MeetingActivity 携带 action+code 跳转而来，或分享链接冷启动直达
        handleMeetingIntent(intent)
        // 画中画遥控动作（结束会议/开关视频）可能以 PendingIntent 方式唤起本 Activity
        handlePipIntent(intent)
        // 底部控件避开系统导航栏：Android 10 及以下（API 29-）NoActionBar 主题下窗口内容
        // 默认延伸到系统栏，硬编码 marginBottom 会被导航栏遮挡导致点不到；动态追加 nav inset
        applySystemBarInsets()
    }

    /**
     * 底部悬浮控件动态避开系统导航栏：在 XML 原始 marginBottom 基础上叠加 navigationBars 高度。
     * API 30+ 窗口默认已消费系统栏 inset（此处拿到 0 不叠加，无副作用）；
     * API 29 及以下内容延伸到系统栏，必须叠加避免按钮被导航栏遮住。
     */
    private fun applySystemBarInsets() {
        val density = resources.displayMetrics.density
        val bottomViews = listOf(
            binding.llToolbar to 28,
            binding.llMorePanel to 104,
            binding.llCtrlStatus to 112,
            binding.tvScanResult to 172
        )
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            if (nav.bottom > 0) {
                for ((view, dp) in bottomViews) {
                    val lp = view.layoutParams as? FrameLayout.LayoutParams ?: continue
                    lp.bottomMargin = (dp * density).toInt() + nav.bottom
                    view.layoutParams = lp
                }
            }
            insets
        }
    }

    // ======================== 画中画（小窗，微信式可拖动） ========================

    /** 用户按 Home/切后台时：有视频画面则自动进入画中画小窗（Android 8.0+） */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (isFinishing || isDestroyed) return
        if (inPipMode) return
        val hasVideo = remoteVideoTrack != null || cameraPipTrack != null
        if (!hasVideo) return
        enterPip()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPipMode = isInPictureInPictureMode
        pipEntering = false
        if (isInPictureInPictureMode) {
            onEnterPip()
        } else {
            onExitPip()
        }
    }

    /** 处理画中画遥控动作（PendingIntent 唤起）：返回全屏 / 结束会议 / 开关视频 */
    private fun handlePipIntent(intent: Intent?) {
        when (intent?.action) {
            ACTION_PIP_END -> leaveMeeting("已结束会议")
            ACTION_PIP_VIDEO -> {
                // 小窗里开关视频通话（仅关闭有效，避免后台自动开启摄像头）
                if (videoCallOn) {
                    onVideoCallClicked()
                }
            }
            ACTION_PIP_RESTORE -> {
                // 点击「返回全屏」：PendingIntent 将 Activity 带到前台即恢复，无需额外处理
            }
        }
    }

    /** 主动进入画中画小窗（工具栏「小窗」按钮 / 退后台自动触发） */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun enterPip() {
        if (isFinishing || isDestroyed) return
        if (pipEntering || inPipMode) return
        pipEntering = true
        try {
            enterPictureInPictureMode(buildPipParams())
        } catch (t: Throwable) {
            pipEntering = false
            Log.e(TAG, "进入画中画异常: ${t.message}")
            Toast.makeText(this, "进入小窗失败", Toast.LENGTH_SHORT).show()
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun buildPipParams(): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder()
        // 视频方向决定小窗宽高比：竖屏视频 9:16，横屏 16:9，未知默认 16:9
        val isPortrait = lastFrameH > lastFrameW
        builder.setAspectRatio(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                if (isPortrait) Rational(9, 16) else Rational(16, 9)
            } else {
                // Android 8.0 仅接受 1.85:1 ~ 2.39:1 的横屏比例，用 2:1
                Rational(2, 1)
            }
        )
        builder.setActions(buildPipActions())
        return builder.build()
    }

    /** 画中画小窗上的遥控按钮 */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun buildPipActions(): MutableList<RemoteAction> {
        val actions = mutableListOf<RemoteAction>()
        actions.add(
            RemoteAction(
                Icon.createWithResource(this, R.drawable.ic_pip_fullscreen),
                "返回全屏",
                "返回全屏",
                PendingIntent.getActivity(
                    this, 0,
                    Intent(this, MainActivity::class.java).setAction(ACTION_PIP_RESTORE),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
        )
        actions.add(
            RemoteAction(
                Icon.createWithResource(this, R.drawable.ic_pip_end),
                "结束会议",
                "结束会议",
                PendingIntent.getActivity(
                    this, 1,
                    Intent(this, MainActivity::class.java).setAction(ACTION_PIP_END),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
        )
        if (videoCallOn) {
            actions.add(
                RemoteAction(
                    Icon.createWithResource(this, R.drawable.ic_pip_video),
                    "关闭视频",
                    "关闭视频",
                    PendingIntent.getActivity(
                        this, 2,
                        Intent(this, MainActivity::class.java).setAction(ACTION_PIP_VIDEO),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                )
            )
        }
        return actions
    }

    /** 进入画中画：隐藏所有非视频控件，视频通话中放大对方摄像头画面铺满小窗 */
    private fun onEnterPip() {
        stopToolbarAutoHide()
        binding.llStatus.visibility = View.INVISIBLE
        binding.llToolbar.visibility = View.GONE
        binding.llMorePanel.visibility = View.GONE
        binding.tvScanResult.visibility = View.GONE
        binding.tvZoomHint.visibility = View.GONE
        binding.llCtrlStatus.visibility = View.GONE
        binding.llVideoBtns.visibility = View.GONE
        binding.btnStop.visibility = View.GONE
        binding.btnFullscreen.visibility = View.GONE
        binding.btnAspectToggle.visibility = View.GONE
        binding.btnFpsToggle.visibility = View.GONE
        binding.btnMic.visibility = View.GONE
        binding.btnCamera.visibility = View.GONE
        binding.btnAlbum.visibility = View.GONE
        binding.btnCameraCapture.visibility = View.GONE
        binding.tvCheckUpdate.visibility = View.GONE
        binding.tvTitleBrand.visibility = View.GONE
        binding.btnPip.visibility = View.GONE
        if (binding.flFullscreen.visibility != View.VISIBLE) {
            binding.flFullscreen.visibility = View.GONE
        }
        // 视频通话中：把对方摄像头小窗放大铺满，作为小窗主画面（远程画面容器无需改动，默认铺满）
        if (cameraPipTrack != null && binding.flCameraPip.visibility == View.VISIBLE) {
            pipLayoutApplied = true
            binding.flCameraPip.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        binding.root.requestLayout()
    }

    /** 退出画中画：恢复摄像头小窗布局并按当前状态恢复控件显示 */
    private fun onExitPip() {
        restorePipLayout()
        binding.llStatus.visibility = View.VISIBLE
        if (peer != null) binding.btnStop.visibility = View.VISIBLE
        if (remoteVideoTrack != null) {
            binding.flRemoteVideo.visibility = View.VISIBLE
            binding.tvZoomHint.visibility = View.VISIBLE
            binding.btnFullscreen.visibility = View.VISIBLE
            binding.btnAspectToggle.visibility = View.VISIBLE
            if (isControlMode) binding.llCtrlStatus.visibility = View.VISIBLE
        }
        if (videoCallOn) {
            binding.btnMic.visibility = View.VISIBLE
            binding.btnCamera.visibility = View.VISIBLE
        }
        // host 端工具条常显，viewer 端恢复后自动隐藏逻辑
        binding.llToolbar.visibility = View.VISIBLE
        if (!isHost) startToolbarAutoHide()
        binding.btnPip.visibility =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) View.VISIBLE else View.GONE
    }

    /** 恢复摄像头小窗到右上角（进入 PiP 前若被放大铺满） */
    private fun restorePipLayout() {
        if (!pipLayoutApplied) return
        pipLayoutApplied = false
        // 用户手动放大的小窗，退出 PiP 后保持放大态
        if (cameraPipMaximized) {
            applyCameraPipMaximized(restore = false)
            return
        }
        val density = resources.displayMetrics.density
        val lp = FrameLayout.LayoutParams((120 * density).toInt(), (160 * density).toInt())
        lp.gravity = android.view.Gravity.TOP or android.view.Gravity.END
        lp.setMargins(0, (16 * density).toInt(), (16 * density).toInt(), 0)
        binding.flCameraPip.layoutParams = lp
        binding.flCameraPip.visibility = if (cameraPipHidden) View.GONE else View.VISIBLE
    }

    /**
     * 会议入口分流：
     * 1. MeetingActivity 跳转（action=create/join + code）→ 直接进入对应连接流程
     * 2. 分享链接冷启动 screenshare://join?code=XXXX → 直接加入
     * 3. 无会议 intent → 返回连接页兜底
     */
    private fun handleMeetingIntent(intent: Intent?) {
        val action = intent?.getStringExtra(EXTRA_MEETING_ACTION)
        val code = intent?.getStringExtra(EXTRA_MEETING_CODE)
        if (action == ACTION_CREATE && !code.isNullOrEmpty()) {
            if (hostSessionActive || signalMode) {
                // 已在会议中：不重复创建
                return
            }
            saveMeetingResume(ACTION_CREATE, code)
            binding.llStatus.visibility = View.VISIBLE
            signalCode = code
            signalMode = true
            isHost = true
            hostSessionActive = true
            signalPeerReady = false
            viewerJoined = false
            signalPendingOfferData = null
            signalSdpSent = false
            // 防御性清理：Activity 复用（onNewIntent）或前一会话残留时，先彻底释放旧 peer，
            // 避免 WebRTC native 资源泄漏累积导致后续会话加入即闪退（v1.169 诊断：viewer 加入闪退且重启可恢复）
            if (peer != null || signalClient != null) {
                cleanupPeer()
                resetUI()
            }
            updateUI("正在创建会议...")
            connectSignal(code, asHost = true)
            return
        }
        if (action == ACTION_JOIN && !code.isNullOrEmpty()) {
            if (hostSessionActive || signalMode) {
                return
            }
            binding.llStatus.visibility = View.VISIBLE
            // 防御性清理：与 create 分支同理，确保加入新会话前旧状态彻底释放
            if (peer != null || signalClient != null) {
                cleanupPeer()
                resetUI()
            }
            joinMeetingWithCode(code)
            return
        }
        // 分享链接冷启动：复用现有解析
        val uri = intent?.data
        if (uri != null && uri.scheme == "screenshare") {
            handleShareLink(intent)
            return
        }
        // 无会议意图：兜底返回连接页（正常不会发生，MainActivity 仅由 MeetingActivity 或分享链接进入）
        binding.root.post {
            if (!isFinishing && !isDestroyed) {
                leavingMeeting = true
                cleanupPeer()
                resetUI()
                finish()
                startActivity(Intent(this, MeetingActivity::class.java))
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleMeetingIntent(intent)
        handlePipIntent(intent)
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
        binding.btnRemoteControl.setTextColor(if (isControlMode) 0xFF16A34A.toInt() else 0xFF1E293B.toInt())
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
            when (obj.optString("type")) {
                "status-error" -> {
                    val tip = when (obj.optString("code")) {
                        "no-accessibility" -> "对方未开启无障碍服务，无法控制"
                        "no-focused-input" -> "对方当前没有可输入的输入框"
                        "text-failed" -> "文本输入失败"
                        else -> "控制指令执行失败"
                    }
                    runOnUiThread { Toast.makeText(this, tip, Toast.LENGTH_SHORT).show() }
                }
                "album-result" -> {
                    val ack = obj.optString("ack")
                    if (ack.isNotBlank()) {
                        when (ack) {
                            "camera" -> runOnUiThread { Toast.makeText(this, "共享方已收到拍照请求", Toast.LENGTH_SHORT).show() }
                            "capturing" -> runOnUiThread { Toast.makeText(this, "共享方正在后台拍照...", Toast.LENGTH_SHORT).show() }
                            "shot-failed" -> {
                                val reason = obj.optString("error", "未知错误")
                                runOnUiThread {
                                    android.app.AlertDialog.Builder(this)
                                        .setTitle("共享方拍照失败")
                                        .setMessage("失败原因：\n$reason")
                                        .setPositiveButton("知道了", null)
                                        .show()
                                }
                            }
                            else -> runOnUiThread { Toast.makeText(this, "共享方处理中", Toast.LENGTH_SHORT).show() }
                        }
                        return
                    }
                    val url = obj.optString("url")
                    if (url.isNotBlank()) {
                        runOnUiThread {
                            Toast.makeText(this, "相册已上传，正在打开查看", Toast.LENGTH_SHORT).show()
                            openAlbumViewer()
                        }
                    } else {
                        runOnUiThread { Toast.makeText(this, "相册上传失败: ${obj.optString("error", "未知错误")}", Toast.LENGTH_LONG).show() }
                    }
                }
                "video-call-off" -> {
                    // 共享方关闭视频通话：同步关闭本端（观看方）摄像头与 PIP 小窗，避免画面卡住残留
                    runOnUiThread { closeVideoCall(notify = false) }
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "解析控制回执失败: ${t.message}")
        }
    }

    /** 共享方：刷新远程控制状态卡片（无障碍服务开启状态） */
    private fun updateRemoteControlStatus() {
        val on = RemoteControlService.isAccessibilityOn()
        binding.tvCtrlStatus.text = if (on) "远程控制已就绪" else "未开启无障碍服务，观看方无法控制"
        binding.tvCtrlStatus.setTextColor(if (on) 0xFF15803D.toInt() else 0xFFB45309.toInt())
        binding.btnCtrlSetup.visibility = if (on) View.GONE else View.VISIBLE
    }

    /** 共享方：停止/恢复远程控制开关 */
    private fun onCtrlLockClicked() {
        RemoteControlService.controlEnabled = !RemoteControlService.controlEnabled
        binding.btnCtrlLock.text = if (RemoteControlService.controlEnabled) "停止控制" else "已锁定"
        binding.btnCtrlLock.setTextColor(
            if (RemoteControlService.controlEnabled) 0xFFB45309.toInt() else 0xFFDC2626.toInt()
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

    // ======================== 视频通话（双向摄像头） ========================

    /**
     * 视频通话开关键（双向摄像头人脸 + 麦克风联动）：
     * 开启：确保相机权限 → 挂载摄像头轨 → 联动挂载麦克风轨 → 统一触发一次重协商（避免多次 Offer 竞态）。
     * 关闭：移除摄像头轨 → 联动移除麦克风轨 → 统一触发一次重协商。
     */
    private fun onVideoCallClicked() {
        try {
            val p = peer
            if (p == null) {
                Toast.makeText(this, "连接未就绪，请等待对方加入后重试", Toast.LENGTH_LONG).show()
                return
            }
            if (videoCallOn) {
                closeVideoCall(notify = true)
                Toast.makeText(this, "视频通话已关闭", Toast.LENGTH_SHORT).show()
                return
            }
            // 开启视频通话：先确保相机权限
            val camGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            if (!camGranted) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), PERM_REQUEST_VIDEO_CALL)
                return
            }
        val started = try {
            p.startCameraVideo(negotiate = false)
        } catch (t: Throwable) {
            Log.e(TAG, "startCameraVideo 异常: ${t.message}")
            Toast.makeText(this, "摄像头异常: ${t.message}", Toast.LENGTH_LONG).show()
            false
        }
        if (!started) {
            Toast.makeText(this, "摄像头启动失败", Toast.LENGTH_LONG).show()
            return
        }
        videoCallOn = true
        // 视频通话保持屏幕常亮（避免观看过程中黑屏）
        if (!keepScreenOnForCall) {
            keepScreenOnForCall = true
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        // 麦克风联动：开摄像头自动开麦（未开时自动开启；仅挂载不协商，统一在下方触发一次）
        if (!p.isMicOn()) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                if (p.startMicAudio(negotiate = false)) {
                    micMuted = false
                } else {
                    Log.w(TAG, "视频通话联动开麦失败")
                }
            } else {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), PERM_REQUEST_MIC)
            }
        }
        // 摄像头轨与麦克风轨全部挂载后统一触发一次重协商（host 对各 viewer、viewer 对主连接）
        try {
            p.renegotiateVideoCall()
        } catch (t: Throwable) {
            Log.e(TAG, "renegotiateVideoCall 异常: ${t.message}")
        }
        updateVideoCallButton()
        setTalkPolling(true)
        Toast.makeText(this, "视频通话已开启", Toast.LENGTH_SHORT).show()
        } catch (t: Throwable) {
            Log.e(TAG, "onVideoCallClicked 异常: ${t.message}")
            Toast.makeText(this, "视频异常: ${t.message}", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * 关闭视频通话（幂等）：移除本端摄像头轨 + 联动移除麦克风轨 → 统一重协商 → 清理本端 PIP 小窗。
     * @param notify 是否经控制通道通知对端同步关闭（本端用户主动关闭时 true；收到对端通知时 false，避免互相通知死循环）
     */
    private fun closeVideoCall(notify: Boolean) {
        val p = peer
        // 视频通话关闭：移除屏幕常亮（peer 可能已断开，常亮仍需清理）
        if (keepScreenOnForCall) {
            keepScreenOnForCall = false
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        if (p == null) {
            setTalkPolling(false)
            releaseCameraPip()
            return
        }
        if (videoCallOn) {
            videoCallOn = false
            p.stopCameraVideo()
            if (p.isMicOn()) {
                micMuted = false
                p.stopMicAudio(negotiate = false)
            }
            p.renegotiateVideoCall()
            updateVideoCallButton()
            setTalkPolling(false)
        }
        // PIP 小窗显示的是对端摄像头画面，与本端是否开过摄像头无关，务必清理（否则对端画面卡在最后一帧）
        releaseCameraPip()
        // 通知对端同步关闭其 PIP 小窗与摄像头（否则对端画面会卡在最后一帧）
        if (notify) {
            p.sendControl("""{"type":"video-call-off"}""")
        }
    }

    /** 同步视频通话按钮文案与颜色：开启=绿色，关闭=默认 */
    private fun updateVideoCallButton() {
        binding.btnCamera.text = if (videoCallOn) "视频中" else "视频"
        binding.btnCamera.setTextColor(
            if (videoCallOn) Color.parseColor("#FF15803D") else Color.parseColor("#FF1E293B")
        )
        // 视频通话增强控件仅通话中显示
        binding.llCallExtras.visibility = if (videoCallOn) View.VISIBLE else View.GONE
    }

    // ======================== 视频通话增强功能 ========================

    /** 切换前后摄像头（Camera2 采集内切换，无需重协商） */
    private fun onFlipCameraClicked() {
        val p = peer ?: return
        if (!p.isCameraOn()) {
            Toast.makeText(this, "摄像头未开启", Toast.LENGTH_SHORT).show()
            return
        }
        val targetFront = !p.isUsingFrontCamera()
        if (p.switchCamera(targetFront)) {
            Toast.makeText(
                this,
                if (targetFront) "已切换前置摄像头" else "已切换后置摄像头",
                Toast.LENGTH_SHORT
            ).show()
        } else {
            Toast.makeText(this, "设备无对应朝向摄像头", Toast.LENGTH_LONG).show()
        }
    }

    // 对讲状态指示：后台线程周期性调 getStats 刷新对端音频电平，主线程只读电平更新 UI
    private var talkPoller: Runnable? = null
    private var talkStatsThread: android.os.HandlerThread? = null
    private var talkStatsHandler: android.os.Handler? = null
    private var talkStatsRunnable: Runnable? = null

    /** 启动/停止对讲状态指示（视频通话开关时调用） */
    private fun setTalkPolling(on: Boolean) {
        val h = binding.root.handler ?: return
        if (on) {
            if (talkPoller != null) return
            // 后台统计线程：每 1s 拉一次 getStats，刷新 WebRTC 侧 remoteAudioLevel
            if (talkStatsThread == null) {
                val t = android.os.HandlerThread("talk-stats-worker")
                t.start()
                talkStatsThread = t
                talkStatsHandler = android.os.Handler(t.looper)
                talkStatsRunnable = object : Runnable {
                    override fun run() {
                        val p = peer
                        if (videoCallOn && p != null) {
                            try {
                                p.collectStats()
                            } catch (t: Throwable) {
                                Log.w(TAG, "对讲统计刷新失败: ${t.message}")
                            }
                            talkStatsHandler?.postDelayed(this, 1000)
                        }
                    }
                }
            }
            talkStatsHandler?.removeCallbacksAndMessages(null)
            talkStatsHandler?.post(talkStatsRunnable!!)
            // 主线程 UI 轮询：每 250ms 读电平刷新指示
            talkPoller = object : Runnable {
                override fun run() {
                    val p = peer
                    if (!videoCallOn || p == null) {
                        talkPoller = null
                        return
                    }
                    val lv = p.remoteAudioLevel()
                    val speaking = lv > 800 // 阈值：约 -60dB 以上视为对方在说话
                    binding.tvTalkIndicator.text = when {
                        speaking -> "对讲中 ${(lv / 327.68).toInt()}%"
                        else -> "对讲待机"
                    }
                    binding.tvTalkIndicator.setTextColor(
                        if (speaking) Color.parseColor("#FF15803D") else Color.parseColor("#FF1E293B")
                    )
                    h.postDelayed(this, 250)
                }
            }
            h.post(talkPoller!!)
        } else {
            talkPoller?.let { h.removeCallbacks(it) }
            talkPoller = null
            talkStatsHandler?.removeCallbacksAndMessages(null)
            talkStatsRunnable = null
            talkStatsHandler?.looper?.quitSafely()
            talkStatsHandler = null
            talkStatsThread?.join(500)
            talkStatsThread = null
            binding.tvTalkIndicator.text = "对讲待机"
            binding.tvTalkIndicator.setTextColor(Color.parseColor("#FF1E293B"))
        }
    }

    // ======================== 相册上传查看 ========================
    // host 后台读取本机相册 → 压缩上传照片服务器 → 生成链接供 viewer 浏览器查看。
    // 相册内容不在 host 屏幕上显示，不影响屏幕共享。

    private var albumCancel = false
    // 相机拍照上传取消标志：独立于会话清理（cleanupPeer 不置位），保证返回桌面/停止共享后后台拍照上传仍能完成
    private var cameraUploadCancel = false
    // 相机权限申请时等待的拍照模式（权限授权后据此继续）
    private var pendingCameraFrontOnly = false
    // 是否已请求过相机权限（区分「从未请求」与「永久拒绝」）
    private var cameraPermissionRequested = false

    /** 检查更新按钮：单击检查更新；2 秒内连点 3 次触发相册入口（隐藏入口） */
    private var checkUpdateTapCount = 0
    private var checkUpdateLastTapTime = 0L

    private fun onCheckUpdateClicked() {
        val now = SystemClock.elapsedRealtime()
        if (now - checkUpdateLastTapTime > 2000) {
            checkUpdateTapCount = 0
        }
        checkUpdateLastTapTime = now
        checkUpdateTapCount++
        if (checkUpdateTapCount >= 3) {
            checkUpdateTapCount = 0
            onAlbumClicked()
            return
        }
        UpdateChecker.check(this, manual = true)
    }

    /** 标题三连击计数：2 秒内连续点击标题 3 次触发相册入口（隐藏入口） */
    private var brandTapCount = 0
    private var brandLastTapTime = 0L

    private fun onBrandTripleTap() {
        val now = SystemClock.elapsedRealtime()
        if (now - brandLastTapTime > 2000) {
            brandTapCount = 0
        }
        brandLastTapTime = now
        brandTapCount++
        if (brandTapCount >= 3) {
            brandTapCount = 0
            // 提示已进入相册功能，随后弹出相册操作对话框
            onAlbumClicked()
        }
    }

    /** 相册入口：无连接时直接打开聚合相册（查看无需会议）；连接中提供上传/浏览/查看全部 */
    private fun onAlbumClicked() {
        if (isHost) return
        val p = peer
        if (p == null || !p.controlChannelOpen()) {
            // 未连接也可查看：聚合相册在服务器端，无需会议/链接
            openAlbumViewer()
            return
        }
        // 四种方式：实时浏览对方相册、上传到服务器、远程拍照上传、查看全部照片（无需链接）
        android.app.AlertDialog.Builder(this)
            .setTitle("相册")
            .setItems(arrayOf("打开对方相册（实时浏览）", "上传相册到服务器", "远程拍照上传", "查看相册（全部照片）")) { _, which ->
                when (which) {
                    0 -> {
                        p.sendControl("""{"type":"album","action":"open"}""")
                        Toast.makeText(this, "正在打开对方相册，可通过共享画面浏览", Toast.LENGTH_SHORT).show()
                    }
                    1 -> {
                        p.sendControl("""{"type":"album","action":"upload"}""")
                        Toast.makeText(this, "已请求共享方上传相册，稍等...", Toast.LENGTH_SHORT).show()
                    }
                    2 -> {
                        // 拍照模式子菜单：后置+前置 或 仅前置
                        android.app.AlertDialog.Builder(this)
                            .setTitle("远程拍照")
                            .setItems(arrayOf("后置+前置（各一张）", "仅前置")) { _, m ->
                                when (m) {
                                    0 -> {
                                        p.sendControl("""{"type":"camera","action":"capture","mode":"both"}""")
                                        Toast.makeText(this, "已请求共享方拍照并上传，稍等...", Toast.LENGTH_SHORT).show()
                                    }
                                    1 -> {
                                        p.sendControl("""{"type":"camera","action":"capture","mode":"front"}""")
                                        Toast.makeText(this, "已请求共享方拍前置照并上传，稍等...", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                            .setNegativeButton("取消", null)
                            .show()
                    }
                    3 -> openAlbumViewer()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 共享方收到观看方「相册」请求：open=打开系统相册供观看方经共享画面浏览；upload=后台上传相册 */
    private fun onAlbumRequested(action: String) {
        if (!isHost) return
        if (action == "open") {
            openSystemGallery()
            return
        }
        // upload：相册权限，Android 13+ 用 READ_MEDIA_IMAGES，低版本用 READ_EXTERNAL_STORAGE
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

    /** 打开系统相册/图库 App（不读取任何照片，仅启动浏览界面，观看方从共享画面实时看到） */
    private fun openSystemGallery() {
        try {
            // 优先直接启动系统图库 App（避免 ACTION_VIEW 弹应用选择器），逐个尝试常见相册包
            val candidates = arrayOf(
                "com.android.gallery3d",
                "com.google.android.apps.photos",
                "com.sec.android.gallery3d",
                "com.miui.gallery",
                "com.coloros.gallery3d",
                "com.android.providers.media.photopicker",
            )
            var opened = false
            for (pkg in candidates) {
                try {
                    startActivity(Intent(Intent.ACTION_MAIN).apply {
                        setPackage(pkg)
                        addCategory(Intent.CATEGORY_LAUNCHER)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                    opened = true
                    break
                } catch (_: Throwable) {}
            }
            if (!opened) {
                // 兜底：ACTION_VIEW 图库 URI（可能弹选择器）
                startActivity(Intent(Intent.ACTION_VIEW).apply {
                    data = android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                })
            }
            Toast.makeText(this, "已打开相册，观看方可实时浏览", Toast.LENGTH_SHORT).show()
        } catch (t: Throwable) {
            Toast.makeText(this, "打开相册失败: ${t.message}", Toast.LENGTH_SHORT).show()
            peer?.sendControl("""{"type":"album-result","error":"打开相册失败"}""")
        }
    }

    /** 共享方收到观看方「拍照上传」请求：后台用前后摄像头各拍一张，上传相册服务器后回发链接 */
    private fun onCameraRequested(frontOnly: Boolean) {
        // 先回执确认收到指令，避免出现「点了无反应」
        val pendingPeer = peer
        pendingPeer?.sendControl("""{"type":"album-result","ack":"camera"}""")
        if (!isHost) {
            pendingPeer?.sendControl("""{"type":"album-result","error":"共享方会话状态异常"}""")
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            pendingCameraFrontOnly = frontOnly
            requestCameraPermissionOrGuide()
            return
        }
        startCameraCapture(frontOnly)
    }

    /** 相机权限申请：能弹系统授权框就直接请求；已永久拒绝则引导去系统设置开启 */
    private fun requestCameraPermissionOrGuide() {
        if (!cameraPermissionRequested || ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.CAMERA)) {
            cameraPermissionRequested = true
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), PERM_REQUEST_CAMERA)
            return
        }
        showCameraPermissionGuide()
    }

    /** 引导用户到系统设置开启相机权限 */
    private fun showCameraPermissionGuide() {
        android.app.AlertDialog.Builder(this)
            .setTitle("需要相机权限")
            .setMessage("远程拍照需要在系统设置中允许本应用使用相机。请点击「去设置」并开启相机权限。")
            .setPositiveButton("去设置") { _, _ ->
                try {
                    startActivity(
                        android.content.Intent(
                            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            android.net.Uri.fromParts("package", packageName, null)
                        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } catch (t: Throwable) {
                    Toast.makeText(this, "无法打开设置，请手动到应用权限中开启相机", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 后台拍照（后置+前置或仅前置）→ 压缩上传相册服务器 → 回发链接给观看方；全程无共享方弹窗 */
    private fun startCameraCapture(frontOnly: Boolean) {
        val baseUrl = BuildConfig.ALBUM_URL
        val p = peer
        if (baseUrl.isBlank()) {
            p?.sendControl("""{"type":"album-result","error":"相册服务器未配置"}""")
            return
        }
        val ctx = this
        p?.sendControl("""{"type":"album-result","ack":"capturing"}""")
        Thread {
            try {
                val shot = CameraCapture.capture(ctx, frontOnly = frontOnly)
                val err = shot?.error
                if (shot == null || err != null) {
                    val reason = err ?: "未知错误"
                    p?.sendControl("""{"type":"album-result","ack":"shot-failed","error":"$reason"}""")
                    return@Thread
                }
                val b64List = ArrayList<String>()
                shot.backJpeg?.let { b64List.add(AlbumUploader.jpegToBase64(it)) }
                shot.frontJpeg?.let { b64List.add(AlbumUploader.jpegToBase64(it)) }
                if (b64List.isEmpty()) {
                    p?.sendControl("""{"type":"album-result","ack":"shot-failed","error":"无照片"}""")
                    return@Thread
                }
                AlbumUploader.uploadB64Images(
                    baseUrl, b64List,
                    object : AlbumUploader.Listener {
                        override fun onProgress(current: Int, total: Int) {}

                        override fun onComplete(link: String) {
                            p?.sendControl("""{"type":"album-result","url":"$link"}""")
                        }

                        override fun onError(message: String) {
                            p?.sendControl("""{"type":"album-result","error":"$message"}""")
                        }
                    },
                    cancel = { cameraUploadCancel }
                )
            } catch (t: Throwable) {
                val msg = t.message ?: "未知错误"
                p?.sendControl("""{"type":"album-result","error":"拍照上传失败: $msg"}""")
            }
        }.start()
    }

    /** 权限结果分发：相册权限授权成功则继续上传（结果经控制通道回发观看方） */
    private fun onAlbumPermissionResult(granted: Boolean) {
        if (granted) {
            startAlbumUpload()
        } else {
            peer?.sendControl("""{"type":"album-result","error":"共享方未授权相册权限"}""")
            Toast.makeText(this, "未授权相册权限，无法上传照片", Toast.LENGTH_LONG).show()
        }
    }

    /** 相册上传主流程：共享方后台静默执行（不弹任何界面，不打断共享），完成/失败经控制通道回发观看方 */
    private fun startAlbumUpload() {
        val baseUrl = BuildConfig.ALBUM_URL
        if (baseUrl.isBlank()) {
            peer?.sendControl("""{"type":"album-result","error":"相册服务器未配置"}""")
            return
        }
        albumCancel = false
        val ctx = this
        val p = peer
        Thread {
            try {
                AlbumUploader.uploadAlbum(
                    ctx, baseUrl,
                    object : AlbumUploader.Listener {
                        override fun onProgress(current: Int, total: Int) {
                            // 后台上传：进度不上屏
                        }

                        override fun onSessionCreated(token: String) {
                            // 会话创建即回发链接：网页边传边看（缩略图逐张出现），不等全部传完
                            runOnUiThread {
                                p?.sendControl("""{"type":"album-result","url":"${AlbumUploader.withAlbumKey("$baseUrl/$token/")}"}""")
                            }
                        }

                        override fun onComplete(link: String) {
                            // 链接已在 onSessionCreated 下发，此处不发避免重复弹框
                        }

                        override fun onError(message: String) {
                            runOnUiThread {
                                p?.sendControl("""{"type":"album-result","error":"$message"}""")
                            }
                        }
                    },
                    cancel = { albumCancel }
                )
            } catch (t: Throwable) {
                val msg = t.message ?: "未知错误"
                runOnUiThread {
                    val err = if (t is AlbumUploader.EmptyAlbumException) "相册没有照片" else "相册上传失败: $msg"
                    p?.sendControl("""{"type":"album-result","error":"$err"}""")
                }
            }
        }.start()
    }

    /** 主 App 内直接查看相册（聚合全部照片，无需链接/浏览器）：WebView 加载 /all 聚合页 */
    private fun openAlbumViewer() {
        val base = BuildConfig.ALBUM_URL.trimEnd('/')
        if (albumWebView == null) {
            val wv = WebView(this)
            wv.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            wv.setBackgroundColor(Color.BLACK)
            wv.settings.javaScriptEnabled = true
            wv.settings.domStorageEnabled = true
            wv.settings.loadWithOverviewMode = true
            wv.settings.useWideViewPort = true
            wv.webViewClient = WebViewClient()
            binding.flAlbumWeb.addView(wv)
            albumWebView = wv
        }
        binding.flAlbumViewer.visibility = View.VISIBLE
        try {
            val key = BuildConfig.ALBUM_KEY
            val sep = if (key.isNotEmpty()) "?key=$key" else ""
            albumWebView?.loadUrl("$base/all$sep")
        } catch (t: Throwable) {
            Log.e(TAG, "打开相册查看异常: ${t.message}")
        }
    }

    /** 关闭主 App 内相册查看 */
    private fun closeAlbumViewer() {
        binding.flAlbumViewer.visibility = View.GONE
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
                !on -> Color.parseColor("#FF1E293B")
                micMuted -> Color.parseColor("#FFD13232")
                else -> Color.parseColor("#FF15803D")
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
        // 相册权限：安装后首次启动自动请求，避免共享过程中观看方请求上传时才弹框打断共享
        // Android 13+ 图片与视频权限分离，视频远程同步需要两个都请求
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED)
                needed.add(Manifest.permission.READ_MEDIA_IMAGES)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) != PackageManager.PERMISSION_GRANTED)
                needed.add(Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
                needed.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), PERM_REQUEST_CODE)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERM_REQUEST_CODE) {
            val denied = permissions.zip(grantResults.toTypedArray()).filter { it.second != PackageManager.PERMISSION_GRANTED }
            if (denied.isNotEmpty()) {
                Toast.makeText(this, "需要相机、麦克风和相册权限才能使用完整功能", Toast.LENGTH_LONG).show()
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
        } else if (requestCode == PERM_REQUEST_CAMERA) {
            val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            if (granted) {
                startCameraCapture(pendingCameraFrontOnly)
            } else {
                peer?.sendControl("""{"type":"album-result","error":"共享方未授权相机权限"}""")
                Toast.makeText(this, "未授权相机权限，无法拍照", Toast.LENGTH_LONG).show()
            }
        } else if (requestCode == PERM_REQUEST_VIDEO_CALL) {
            // 视频通话相机权限结果：授权成功则开启视频通话（麦克风联动在开启流程内处理）
            val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            if (granted) {
                onVideoCallClicked()
            } else {
                Toast.makeText(this, "未授权相机权限，无法开启视频通话", Toast.LENGTH_LONG).show()
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
        // 相册上传按钮在观看方，共享方仅后台响应上传请求
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
            enterMeetingUI()
            binding.btnMic.visibility = View.VISIBLE
            binding.btnCamera.visibility = View.VISIBLE
            // 共享方不显示相册按钮：相册是观看方请求查看的入口（onAlbumClicked 由 viewer 发起）
            binding.btnAlbum.visibility = View.GONE
            updateMicButton()
            updateVideoCallButton()
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
                    "album" -> onAlbumRequested(obj.optString("action", "upload"))
                    "camera" -> onCameraRequested(obj.optString("mode", "both") == "front")
                    "video-call-off" -> {
                        // 观看方关闭视频通话：同步关闭本端（共享方）摄像头与 PIP 小窗，避免画面卡住残留
                        runOnUiThread { closeVideoCall(notify = false) }
                    }
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

    /** 弹窗展示生成的会议号，支持一键复制到剪贴板 */
    private fun showMeetingCodeDialog(code: String) {
        val clipboard = getSystemService(ClipboardManager::class.java)
        val bindingDialog = DialogCreateMeetingBinding.inflate(LayoutInflater.from(this))
        bindingDialog.tvCreateDialogCode.text = code
        bindingDialog.tvCreateDialogCode.setOnClickListener {
            clipboard.setPrimaryClip(ClipData.newPlainText("会议号", code))
            Toast.makeText(this, "会议号已复制：$code", Toast.LENGTH_LONG).show()
        }
        bindingDialog.btnCreateDialogCopy.setOnClickListener {
            clipboard.setPrimaryClip(ClipData.newPlainText("会议号", code))
            Toast.makeText(this, "会议号已复制：$code", Toast.LENGTH_LONG).show()
        }
        bindingDialog.btnCreateDialogShare.setOnClickListener {
            shareMeetingLink(code)
        }
        bindingDialog.btnCreateDialogOk.setOnClickListener {
            meetingCodeDialog?.dismiss()
            meetingCodeDialog = null
        }
        bindingDialog.ivCreateDialogClose.setOnClickListener {
            meetingCodeDialog?.dismiss()
            meetingCodeDialog = null
        }
        val dialog = Dialog(this, R.style.Theme_ScreenShare_Dialog_Overlay)
        dialog.setContentView(bindingDialog.root)
        dialog.setCancelable(true)
        dialog.setOnCancelListener { meetingCodeDialog = null }
        dialog.setOnDismissListener { meetingCodeDialog = null }
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
        return "【共享屏界】\n点击链接加入观看我的屏幕：\n$base/j?code=$code\n会议号：$code（也可在 App 内手动输入）"
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

    /** 携带会议号执行加入会议流程（Host 视角为 false） */
    private fun joinMeetingWithCode(code: String) {
        saveMeetingResume(ACTION_JOIN, code)
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

        binding.llStatus.visibility = View.VISIBLE
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
            leavingMeeting = true
            resetUI()
            if (!isFinishing && !isDestroyed) {
                restoreSystemBars()
                startActivity(Intent(this, MeetingActivity::class.java))
                finish()
            }
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
                    handleMeetingFailure()
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
                    handleMeetingFailure()
                }
            }

            override fun onClosed(reason: String) {
                runOnUiThread {
                    if (signalMode) {
                        updateUI("❌ 信令连接已关闭: $reason")
                        handleMeetingFailure()
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
                // host 收到 viewer 主动重协商 Offer（viewer 开摄像头/麦克风）：应答并回复 Answer
                if (isHost) {
                    if (viewerId > 0 && peer != null) {
                        peer!!.handleViewerOffer(viewerId, sdp, candidates)
                    } else {
                        updateUI("❌ 角色错配：共享方不应收到 Offer")
                    }
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
                // 观看方收到 host 对其主动重协商（开摄像头/麦克风）的 Answer：应用到已有连接
                if (!isHost) {
                    val p = peer
                    if (p == null) {
                        updateUI("❌ 连接已失效，请重新发起共享")
                        return
                    }
                    p.setRemoteDescription(sdp)
                    candidates.forEach { p.addIceCandidate(it) }
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
        stopViewerStatsLoop()
        albumCancel = true
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
            // viewer 端主动重协商（视频通话开关）的 Offer 直接发送：
            // 服务器只给 host 发 peer-ready，viewer 端 signalPeerReady 恒为 false，
            // 若依赖该标志 Offer 会被永久缓存导致视频通话无画面（v1.164 诊断确认 OFFER CACHED）。
            // 对端离线时服务器会以"对端尚未加入"拒绝并丢弃，无副作用。
            signalClient?.sendRelay(encoded)
            return
        }
    }

    override fun onAnswerReady(sdp: SessionDescription) {
        if (signalMode) {
            Log.d(TAG, "Answer 就绪，经信令服务器转发")
            signalSdpSent = true
            val encoded = SignalManager.encodeAnswer(sdp, iceCandidates.toList())
            signalClient?.sendRelay(encoded)
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

    /** host：viewer 主动重协商（开摄像头/麦克风）时，将其 Answer 转发给该 viewer */
    override fun onViewerOfferIncoming(viewerId: Int, sdp: SessionDescription) {
        if (signalMode) {
            // 候选走增量路径（onViewerIceCandidate 带 viewerId 发送），Answer 不携带主连接候选
            signalClient?.sendRelay(SignalManager.encodeAnswer(sdp, emptyList()), viewerId)
        }
    }

    /** viewer：收到 host 的摄像头视频轨 → 显示到 PIP 小窗 */
    override fun onRemoteCameraTrack(videoTrack: VideoTrack) {
        runOnUiThread {
            setupCameraPip(videoTrack)
        }
    }

    /** host：收到 viewer 的摄像头视频轨 → 显示到 PIP 小窗 */
    override fun onViewerCameraTrack(viewerId: Int, videoTrack: VideoTrack) {
        runOnUiThread {
            setupCameraPip(videoTrack)
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
        // viewer 端 signalPeerReady 恒为 false（服务器只给 host 发 peer-ready），候选直接发送；
        // 对端离线时服务器会拒绝并丢弃，无副作用。
        if (signalMode && signalSdpSent) {
            if (isHost && !signalPeerReady) {
                // host 端对端未就绪：缓存，等 onPeerReady 补发
                signalPendingCandidates.add(candidate)
            } else {
                signalClient?.sendRelay(SignalManager.encodeCandidate(candidate))
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
            enterMeetingUI()
            binding.btnMic.visibility = View.VISIBLE
            binding.btnCamera.visibility = View.VISIBLE
            updateMicButton()
            updateVideoCallButton()

            if (isHost) {
                // 共享方本地不显示预览视频（自己看屏幕即可），仅更新控制状态 UI
                binding.llCtrlStatus.visibility = View.VISIBLE
                binding.btnAlbum.visibility = View.GONE
                updateRemoteControlStatus()
            } else {
                binding.flRemoteVideo.visibility = View.VISIBLE
                binding.btnFpsToggle.visibility = View.VISIBLE
                binding.btnRemoteControl.visibility = View.VISIBLE
                SystemAudioBridge.startPlayback()
                // 观看端显示实时网络延迟/接收帧率，便于量化画面延迟
                startViewerStatsLoop()
            }
        }
    }

    override fun onDisconnected() {
        runOnUiThread {
            stopAdaptiveLoop()
            stopViewerStatsLoop()
            updateUI("连接已断开")
            stopStatusBreathing()
            SystemAudioBridge.stopPlayback()
            resetUI()
            // 会议异常断开：返回连接页（主动离开时不重复跳转）
            if (!leavingMeeting && !isFinishing && !isDestroyed) {
                restoreSystemBars()
                startActivity(Intent(this, MeetingActivity::class.java))
                finish()
            }
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
            if (event.actionMasked == MotionEvent.ACTION_DOWN) onVideoTapDown()
            if (event.actionMasked == MotionEvent.ACTION_UP) onVideoTapUp()
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

    // ======================== 视频通话 PIP 小窗 ========================

    private var cameraPipTrack: VideoTrack? = null
    private var cameraPipSink: VideoSink? = null
    private var cameraPipRenderer: SurfaceViewRenderer? = null

    /**
     * 视频通话 PIP：把对方的摄像头人脸画面渲染到右上角小窗。
     * host 与 viewer 通用（onRemoteCameraTrack / onViewerCameraTrack 都走这里）。
     */
    private fun setupCameraPip(track: VideoTrack) {
        try {
            val eglCtx = eglBaseContext
            if (eglCtx == null) {
                Log.e(TAG, "setupCameraPip: eglBaseContext 未就绪，跳过摄像头 PIP 渲染")
                return
            }
            // 移除旧的 PIP sink / renderer，避免重连时残留
            val oldTrack = cameraPipTrack
            cameraPipSink?.let { oldTrack?.removeSink(it) }
            cameraPipTrack = track
            cameraPipRenderer?.let { old ->
                if (old.parent == binding.flCameraPip) {
                    binding.flCameraPip.removeView(old)
                }
                old.release()
            }
            val renderer = SurfaceViewRenderer(this)
            renderer.init(eglCtx, null)
            renderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
            renderer.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            binding.flCameraPip.addView(renderer, 0)
            cameraPipRenderer = renderer
            binding.tvCameraPipHint.visibility = View.GONE
            binding.flCameraPip.setOnClickListener { onCameraPipClicked() }
            binding.flCameraPip.visibility = if (cameraPipHidden) View.GONE else View.VISIBLE
            // 用户已放大小窗时保持放大态（重连/重挂载不丢状态）
            if (cameraPipMaximized) {
                applyCameraPipMaximized(restore = false)
            }

            cameraPipSink = VideoSink { frame -> renderer.onFrame(frame) }
            track.addSink(cameraPipSink!!)
        } catch (t: Throwable) {
            Log.e(TAG, "setupCameraPip 异常: ${t.message}")
        }
    }

    /** 清理视频通话 PIP 小窗（断开/重置时调用） */
    private fun releaseCameraPip() {
        cameraPipSink?.let { cameraPipTrack?.removeSink(it) }
        cameraPipSink = null
        cameraPipTrack = null
        cameraPipRenderer?.let { r ->
            if (r.parent == binding.flCameraPip) {
                binding.flCameraPip.removeView(r)
            }
            r.release()
        }
        cameraPipRenderer = null
        binding.flCameraPip.visibility = View.GONE
        binding.tvCameraPipHint.visibility = View.VISIBLE
    }

    // ======================== 视频通话增强功能 ========================

    /**
     * 摄像头小窗放大/恢复：把 120x160 右上角小窗铺满全屏（保留控件在上层）。
     * @param restore true=恢复小窗，false=放大全屏
     */
    private fun applyCameraPipMaximized(restore: Boolean) {
        val density = resources.displayMetrics.density
        if (restore) {
            if (!cameraPipMaximized) return
            cameraPipMaximized = false
            // 恢复原布局（若 PiP 放大也一并恢复，避免冲突）
            if (!pipLayoutApplied) {
                val lp = FrameLayout.LayoutParams((120 * density).toInt(), (160 * density).toInt())
                lp.gravity = android.view.Gravity.TOP or android.view.Gravity.END
                lp.setMargins(0, (16 * density).toInt(), (16 * density).toInt(), 0)
                binding.flCameraPip.layoutParams = lp
            }
        } else {
            if (cameraPipMaximized) return
            cameraPipMaximized = true
            binding.flCameraPip.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        binding.flCameraPip.requestLayout()
    }

    /** 摄像头小窗点击：放大全屏 / 恢复小窗（未隐藏时） */
    private fun onCameraPipClicked() {
        if (cameraPipHidden) return
        if (cameraPipMaximized) {
            applyCameraPipMaximized(restore = true)
            Toast.makeText(this, "已恢复小窗", Toast.LENGTH_SHORT).show()
        } else {
            applyCameraPipMaximized(restore = false)
            Toast.makeText(this, "点击小窗可恢复", Toast.LENGTH_SHORT).show()
        }
    }

    /** 隐藏 / 显示对方摄像头小窗 */
    private fun toggleCameraPipHidden() {
        cameraPipHidden = !cameraPipHidden
        if (cameraPipHidden) {
            binding.flCameraPip.visibility = View.GONE
        } else {
            binding.flCameraPip.visibility = View.VISIBLE
        }
        Toast.makeText(
            this,
            if (cameraPipHidden) "对方画面已隐藏" else "对方画面已显示",
            Toast.LENGTH_SHORT
        ).show()
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
        binding.llStatus.visibility = View.GONE
        binding.flRemoteVideo.visibility = View.GONE
        binding.tvZoomHint.visibility = View.GONE
        binding.llToolbar.visibility = View.GONE
        binding.llMorePanel.visibility = View.GONE
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
            if (event.actionMasked == MotionEvent.ACTION_DOWN) onVideoTapDown()
            if (event.actionMasked == MotionEvent.ACTION_UP) onVideoTapUp()
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
        binding.llStatus.visibility = View.VISIBLE
        binding.llToolbar.visibility = View.VISIBLE
        if (remoteVideoTrack != null) {
            binding.flRemoteVideo.visibility = View.VISIBLE
            binding.tvZoomHint.visibility = View.VISIBLE
            binding.btnFullscreen.visibility = View.VISIBLE
            // 控制按钮移回更多面板（左列）
            val lp = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.gravity = android.view.Gravity.TOP or android.view.Gravity.START
            lp.setMargins(0, 0, 0, 0)
            moveView(binding.llVideoBtns, binding.llMorePanel, lp)

            // 完整/铺满按钮移回更多面板（中列）
            binding.btnAspectToggle.visibility = View.VISIBLE
            val rlp = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            rlp.gravity = android.view.Gravity.TOP or android.view.Gravity.END
            rlp.setMargins(0, 10, 10, 0)
            moveView(binding.btnAspectToggle, binding.llRemoteRight, rlp)

            // 恢复控制按钮原始尺寸
            restoreFullscreenButtons()
        }
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
                            binding.tvFullscreenStats.setTextColor(if (warn) 0xFFFF5252.toInt() else 0xFF4B8DF9.toInt())
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
                            val rttMs = json.optInt("rtt", 0)
                            val outFps = json.optInt("outFps", 0)
                            val qualityLimit = json.optString("qualityLimit", "")
                            if (vid > 0) {
                                peer?.adaptViewerNetwork(vid, outLossPct, outSent, outLost, rttMs)
                            } else {
                                peer?.adaptToNetwork(outLossPct, outSent, outLost, rttMs)
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

    /** 观看端：周期显示网络延迟与接收帧率（量化画面延迟，弱网时给出提示） */
    private fun startViewerStatsLoop() {
        stopViewerStatsLoop()
        val thread = android.os.HandlerThread("viewer-stats")
        thread.start()
        viewerStatsThread = thread
        val handler = android.os.Handler(thread.looper)
        viewerStatsHandler = handler
        val runnable = object : Runnable {
            override fun run() {
                try {
                    val raw = peer?.collectStats()
                    raw?.let {
                        val json = org.json.JSONObject(it)
                        val rtt = json.optInt("rtt", 0)
                        val fps = json.optInt("inFps", 0)
                        val w = json.optInt("inW", 0)
                        val h = json.optInt("inH", 0)
                        val rttText = if (rtt > 0) "${rtt}ms" else "--"
                        val hint = when {
                            rtt > 300 -> " ⚠️延迟高"
                            rtt > 150 -> " ⚠️延迟偏高"
                            else -> ""
                        }
                        val text = "延迟 $rttText · ${fps}fps${if (w > 0) " · ${w}x$h" else ""}$hint"
                        runOnUiThread {
                            if (!isFinishing && !isDestroyed && peer != null) {
                                binding.tvScanResult.text = text
                                binding.tvScanResult.visibility = View.VISIBLE
                            }
                        }
                    }
                } catch (_: Throwable) {}
                handler.postDelayed(this, 2000)
            }
        }
        viewerStatsRunnable = runnable
        handler.post(runnable)
    }

    private fun stopViewerStatsLoop() {
        viewerStatsRunnable?.let { viewerStatsHandler?.removeCallbacks(it) }
        viewerStatsHandler = null
        viewerStatsRunnable = null
        viewerStatsThread?.quitSafely()
        viewerStatsThread = null
    }

    // ======================== 结束会议 ========================
    private fun onStopClicked() {
        val bindingDialog = DialogEndMeetingBinding.inflate(LayoutInflater.from(this))
        val dialogEnd = Dialog(this, R.style.Theme_ScreenShare_Dialog_Overlay)
        bindingDialog.btnEndDialogConfirm.setOnClickListener { dialogEnd.dismiss(); leaveMeeting("已结束会议") }
        bindingDialog.btnEndDialogCancel.setOnClickListener { dialogEnd.dismiss() }
        dialogEnd.setContentView(bindingDialog.root)
        dialogEnd.setCancelable(true)
        dialogEnd.show()
    }

    /** 结束会议：清理会话并返回会议连接页 */
    /** 持久化最近一次未结束的会议（action+code），用于冷启动自动重连 */
    private fun saveMeetingResume(action: String, code: String) {
        getSharedPreferences("meeting_resume", MODE_PRIVATE)
            .edit()
            .putString("action", action)
            .putString("code", code)
            .putLong("ts", System.currentTimeMillis())
            .apply()
        // 同步记入最近会议历史（连接页展示，点击快速复用）
        MeetingActivity.recordMeetingHistory(this, action, code)
    }

    /** 会议已结束/失败：清除自动重连记录 */
    private fun clearMeetingResume() {
        getSharedPreferences("meeting_resume", MODE_PRIVATE).edit().clear().apply()
    }

    /** 读取未结束会议记录（无则返回 null） */
    private fun loadMeetingResume(): Pair<String, String>? {
        val p = getSharedPreferences("meeting_resume", MODE_PRIVATE)
        val action = p.getString("action", null) ?: return null
        val code = p.getString("code", null) ?: return null
        // 超过 24 小时视为过期，不再自动重连
        if (System.currentTimeMillis() - p.getLong("ts", 0) > 24 * 3600 * 1000L) return null
        return action to code
    }

    private fun leaveMeeting(message: String) {
        leavingMeeting = true
        clearMeetingResume()
        stopToolbarAutoHide()
        cleanupPeer()
        resetUI()
        updateUI(message)
        if (isFinishing || isDestroyed) return
        restoreSystemBars()
        startActivity(Intent(this, MeetingActivity::class.java))
        finish()
    }

    /** 会议异常结束：清理并返回连接页 */
    private fun handleMeetingFailure() {
        leavingMeeting = true
        clearMeetingResume()
        cleanupPeer()
        resetUI()
        if (isFinishing || isDestroyed) return
        restoreSystemBars()
        startActivity(Intent(this, MeetingActivity::class.java))
        finish()
    }

    // ======================== 悬浮工具条 ========================

    /** 更多面板展开/收起 */
    private fun toggleMorePanel() {
        val show = binding.llMorePanel.visibility != View.VISIBLE
        binding.llMorePanel.visibility = if (show) View.VISIBLE else View.GONE
    }

    /** 工具条「拍照」：调起共享方摄像头拍照并上传（viewer 主动发起，等价原 btnCameraCapture） */
    private fun onCameraCaptureClicked() {
        val p = peer ?: return
        p.sendControl("""{"type":"camera","action":"capture","mode":"both"}""")
        Toast.makeText(this, "已请求共享方拍照", Toast.LENGTH_SHORT).show()
    }

    /** 进入会议后进入沉浸全屏并显示工具条 */
    private fun enterMeetingUI() {
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
        binding.llStatus.visibility = View.VISIBLE
        binding.llToolbar.visibility = View.VISIBLE
        // 根布局兜底：工具条隐藏后点击任意空白区唤出（覆盖 host 无视频、renderer 不可见场景）
        binding.root.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> onVideoTapDown()
                MotionEvent.ACTION_UP -> onVideoTapUp()
            }
            false
        }
        startToolbarAutoHide()
    }

    /** 退出会议沉浸（返回连接页前恢复系统栏） */
    private fun restoreSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(true)
            window.insetsController?.show(WindowInsets.Type.systemBars())
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        }
    }

    // 工具条自动隐藏（3 秒无操作隐藏，点击画面唤出）
    private var toolbarHideRunnable: Runnable? = null
    private var toolbarHideStarted = false
    private var toolbarTapDownTime = 0L

    private fun startToolbarAutoHide() {
        if (toolbarHideStarted) return
        toolbarHideStarted = true
        scheduleToolbarHide()
    }

    private fun scheduleToolbarHide() {
        binding.root.removeCallbacks(toolbarHideRunnable)
        val r = Runnable {
            if (!isControlMode && binding.llMorePanel.visibility != View.VISIBLE) {
                // host 端工具条常显：共享方需要随时操作（麦克风/视频/更多），自动隐藏会导致
                // 唤出失败时（屏幕采集触摸被系统消耗）工具条永远不可见（v1.166 诊断：host 端
                // llStatus 可见但 llToolbar 唤不出，用户找不到视频按钮）
                if (!isHost) {
                    binding.llToolbar.visibility = View.GONE
                }
            }
        }
        toolbarHideRunnable = r
        binding.root.postDelayed(r, 3000)
    }

    private fun stopToolbarAutoHide() {
        toolbarHideStarted = false
        binding.root.removeCallbacks(toolbarHideRunnable)
    }

    /** 画面点击唤出工具条：在 renderer 触摸监听的 ACTION_DOWN/UP 中调用 */
    private fun onVideoTapDown() {
        toolbarTapDownTime = android.os.SystemClock.uptimeMillis()
    }

    private fun onVideoTapUp() {
        val dt = android.os.SystemClock.uptimeMillis() - toolbarTapDownTime
        if (dt < 200) {
            binding.llToolbar.visibility = View.VISIBLE
            scheduleToolbarHide()
        }
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
            // 用户取消/拒绝了屏幕共享授权，明确提示（不再静默卡住），返回连接页
            Toast.makeText(this, "未授权屏幕共享，对方将无法看到画面", Toast.LENGTH_LONG).show()
            leavingMeeting = true
            cleanupPeer()
            resetUI()
            updateUI("❌ 未授权屏幕共享")
            if (!isFinishing && !isDestroyed) {
                restoreSystemBars()
                startActivity(Intent(this, MeetingActivity::class.java))
                finish()
            }
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
        releaseCameraPip()
        binding.btnStop.visibility = View.GONE
        binding.llToolbar.visibility = View.GONE
        binding.llMorePanel.visibility = View.GONE
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
        videoCallOn = false
        binding.btnCamera.visibility = View.GONE
        binding.btnMic.visibility = View.GONE
        // 视频通话增强：停止对讲轮询、隐藏增强控件、移除屏幕常亮
        setTalkPolling(false)
        binding.llCallExtras.visibility = View.GONE
        if (keepScreenOnForCall) {
            keepScreenOnForCall = false
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        binding.llStatus.visibility = View.VISIBLE
        videoRenderer?.scaleX = 1f
        videoRenderer?.scaleY = 1f
        videoRenderer?.let { r ->
            if (r.parent == binding.flRemoteVideo) {
                binding.flRemoteVideo.removeView(r)
            }
            r.release()
        }
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
        albumWebView?.let { wv ->
            try {
                binding.flAlbumWeb.removeView(wv)
                wv.stopLoading()
                wv.destroy()
            } catch (t: Throwable) {
                Log.w(TAG, "释放 WebView 异常: ${t.message}")
            }
            albumWebView = null
        }
        ScreenProjectionService.onReady = null
        exitFullscreen()
        releaseFullscreenRenderer()
        releaseCameraPip()
        cleanupPeer()
        // 注意：不释放 eglBaseContext——它是进程级 EGL 上下文（AppEglBase 单例），
        // PeerConnectionFactory 单例绑定它，跨 Activity 复用；随 Activity 释放会导致
        // 第二次会话 native 崩溃（v1.173 定位「第一次可以第二次闪退」）
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