package com.screenshare

import android.Manifest
import android.animation.ObjectAnimator
import android.app.Activity
import android.app.Dialog
import android.app.NotificationManager
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
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Looper
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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Random
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
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
        const val EXTRA_MEETING_TOKEN = "extra_meeting_token"
        const val ACTION_CREATE = "create"
        const val ACTION_JOIN = "join"

        // 画中画（小窗）遥控动作
        const val ACTION_PIP_RESTORE = "action_pip_restore"
        const val ACTION_PIP_END = "action_pip_end"
        const val ACTION_PIP_VIDEO = "action_pip_video"
    }

    private lateinit var binding: ActivityMainBinding
    private var eglBaseContext: EglBase.Context? = null
    @Volatile private var peer: WebRTCPeer? = null
    @Volatile private var isHost = false
    private var hostSessionActive = false
    // 主动离开会议标记：避免 cleanupPeer 触发 onDisconnected 时重复跳转连接页
    @Volatile private var leavingMeeting = false
    // 连接彻底失败（重连超限）：onConnectionFailed 置位后 onDisconnected 走结束流程
    @Volatile private var connectionTerminated = false

    /** P2P 连接是否已建立（onConnected 置 true）。观看端 signalPeerReady 恒为 false，
     * 不能用它判断共享是否真的开始——服务器只给 host 发 peer-ready */
    @Volatile private var p2pConnected = false

    @Volatile private var reconnecting = false

    // ======================== v1.259: 双音量 + 说话闪避 ========================
    // 剧情音/对讲音音量 0~100 与闪避开关，持久化到 audio_settings；仅本端生效
    private val audioPrefs by lazy { getSharedPreferences("audio_settings", MODE_PRIVATE) }
    private fun mediaVol(): Int = audioPrefs.getInt("media_volume", 100).coerceIn(0, 100)
    private fun talkVol(): Int = audioPrefs.getInt("talk_volume", 100).coerceIn(0, 100)
    private fun duckEnabled(): Boolean = audioPrefs.getBoolean("duck_enabled", true)
    // 闪避平滑：当前实际应用到 SystemAudioBridge 的剧情音（0~100），向目标值逐步逼近
    @Volatile private var appliedMediaVol = 100
    private var duckRunnable: Runnable? = null
    // 闪避压低到的比例（对方说话时剧情音 = 用户音量 × 0.25）
    private val DUCK_FACTOR = 0.25f
    // 视为"对方在说话"的电平阈值（与对讲状态指示同阈值，约 -60dB）
    private val DUCK_LEVEL_THRESHOLD = 800.0

    // 口令共享（信令服务器模式）
    @Volatile private var signalClient: SignalClient? = null
    @Volatile private var signalMode = false
    private var signalPeerReady = false
    // host 端：对方（viewer）是否已加入房间。服务器对 host 发的是 viewer-joined 而非 peer-ready，
    // 用此标记判断"对方已加入"（关闭会议号弹窗 / 授权后不弹窗）
    private var viewerJoined = false
    private var signalPendingOfferData: String? = null
    // 对端尚未加入时缓存的 ICE 候选（加入后连同 offer 一起补发，避免服务器"对端尚未加入"拒发丢失）
    private var signalPendingCandidates = mutableListOf<IceCandidate>()
    private var signalCode: String? = null
    // 房间口令：host 侧为服务器本次签发的 token（分享给观看方）；viewer 侧为待发送的加入口令
    private var signalRoomToken = ""
    private var pendingJoinToken = ""
    // 本次会话是否已发起屏幕授权请求（避免重复弹授权框）
    private var authorizationRequested = false
    // Trickle ICE：SDP 是否已通过信令发出，之后的候选才单独增量发送
    @Volatile private var signalSdpSent = false
    // V4: 采集/主会话是否就绪（新 viewer 加入时据此决定立即发 Offer 或排队）
    private var screenCaptureReady = false

    // ICE 候选缓存（打包进信令 SDP 一起发送）
    private val iceCandidates = java.util.concurrent.CopyOnWriteArrayList<IceCandidate>()
    // 本机候选类型累计计数（避免每候选 O(n) 重扫全部）
    private var candCountHost = java.util.concurrent.atomic.AtomicInteger(0)
    private var candCountSrflx = java.util.concurrent.atomic.AtomicInteger(0)
    private var candCountRelay = java.util.concurrent.atomic.AtomicInteger(0)
    private var candCountOther = java.util.concurrent.atomic.AtomicInteger(0)

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
    // v1.241: 实际发送码率差分基准（供带宽匹配档位判定链路可用带宽）
    private var lastAdaptOutBytes = 0L
    private var lastAdaptOutMs = 0L
    private var adaptiveRunnable: Runnable? = null
    // 诊断上报去重签名（值变化才重报）
    @Volatile private var lastDiagSig = ""

    // 显示模式：true=完整显示(等比，可能有黑边)，false=铺满(无黑边，边缘裁切)
    private var isFitMode = true

    // 容器尺寸变化监听是否已注册
    private var layoutListenerAdded = false

    // 会议号弹窗（连接建立后自动关闭，避免遮挡后续界面）
    private var meetingCodeDialog: Dialog? = null

    // v1.247 观看方帧率切换：高帧率(48fps) / 标准(30fps)。
    // 原实现为 60/30 且链路实际上限仅 30，按钮从未生效；现与 WebRTCPeer 的
    // highMotionFpsCap(48) 对齐，弱网档位下手动值仍让位于自适应降档。
    private var currentFps = 48

    // 麦克风（会议内双向对讲）：false=已开启且未静音，true=已开启但静音
    private var micMuted = false

    // 视频通话（双向摄像头人脸）：true=已开启视频通话（摄像头+麦克风联动）
    @Volatile private var videoCallOn = false

    // 观看端网络质量显示循环（RTT/接收帧率，帮助量化画面延迟）
    private var viewerStatsThread: android.os.HandlerThread? = null
    private var viewerStatsHandler: android.os.Handler? = null
    private var viewerStatsRunnable: Runnable? = null
    // 观看端掉帧检测（v1.223）：inbound framesDropped/framesDecoded 差分算掉帧率，
    // 持续掉帧经控制通道上报共享方（stream-stall）触发降档，恢复后解除，双向各带防抖
    private var lastInDropped = 0L
    private var lastInDecoded = 0L
    private var stallStrikes = 0            // 连续掉帧命中次数（达到 2 次上报）
    private var stallRecoverStrikes = 0     // 连续正常次数（达到 3 次解除上报）
    private var stallReported = false       // 当前是否处于"已上报掉帧"状态
    private var lastDropPct = 0.0           // 最近一次采样窗口掉帧率（UI 显示用）
    // v1.296: 抖动缓冲棘轮排空检测（观看端）——缓冲>500ms 且网络已恢复时请求关键帧
    private var jbHighStrikes = 0
    private var lastKeyFrameReqMs = 0L

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
    // v1.262: 标注模式——观看方在画面上滑动，共享方对应位置出现爱心标记（不需要无障碍权限）
    private var isMarkMode = false

    // 控制模式下是否已发送 down（用于过滤黑边区域的 move/up）
    private var ctrlDownSent = false

    // 控制模式触摸轨迹累积（down 起点 → 各 move 点），抬手时打包为完整滑动指令
    private val ctrlPoints = ArrayList<FloatArray>()

    // 滑动实时跟手节流：MOVE 阶段每 50ms 发送一次完整路径
    private var lastCtrlSend = 0L
    // v1.262: 标注发送节流时间戳
    private var lastMarkSend = 0L

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
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // v1.248: 初始化落盘日志（真机排查用，可在「更多」面板「导出日志」一键分享）
        // 崩溃兜底已在 App.kt 全局注册（落盘 + crash 文件 + /crash 上报 + 崩溃后重启）
        AppLogger.init(this)
        // 诊断：区分首次启动与配置变更重建（折叠屏开合会触发），便于排查"共享中途退出"
        // 必须在 AppLogger.init 之后调用，否则 writeLine 因 logFile==null 直接丢弃
        if (savedInstanceState != null) AppLogger.app("MainActivity 因配置变更重建（会话状态可能丢失）")

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
        binding.btnExportLog.setOnClickListener { exportLogFile() }
        binding.btnFullscreen.setOnClickListener { enterFullscreen() }
        binding.btnExitFullscreen.setOnClickListener { exitFullscreen() }
        binding.btnFpsToggle.setOnClickListener { onFpsToggleClicked() }
        binding.btnAspectToggle.setOnClickListener { onAspectToggleClicked() }
        binding.btnMic.setOnClickListener { onMicClicked() }
        binding.btnCamera.setOnClickListener { onVideoCallClicked() }
        binding.btnAudioSettings.setOnClickListener { showAudioSettings() }
        binding.btnAlbum.setOnClickListener { onAlbumClicked() }
        binding.tvTitleBrand.setOnClickListener { onBrandTripleTap() }
        binding.btnRemoteControl.setOnClickListener { onRemoteControlToggle() }
        binding.btnCtrlBack.setOnClickListener { onCtrlKeyClicked("back") }
        binding.btnCtrlHome.setOnClickListener { onCtrlKeyClicked("home") }
        binding.btnCtrlRecents.setOnClickListener { onCtrlKeyClicked("recents") }
        binding.btnCtrlText.setOnClickListener { onCtrlTextClicked() }
        binding.btnCtrlPoke.setOnClickListener { onCtrlPokeClicked() }
        binding.btnCtrlMark.setOnClickListener { onCtrlMarkClicked() }
        binding.btnCtrlSetup.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        binding.btnCtrlLock.setOnClickListener { onCtrlLockClicked() }
        binding.btnPip.setOnClickListener { enterPip() }
        binding.btnFlipCamera.setOnClickListener { onFlipCameraClicked() }
        binding.btnHidePip.setOnClickListener { toggleCameraPipHidden() }
        binding.btnCamQuality.setOnClickListener { onCameraQualityClicked() }
        binding.btnLocalPreview.setOnClickListener { onLocalPreviewClicked() }
        binding.btnBrightness.setOnClickListener { onBrightnessClicked() }
        binding.btnMirror.setOnClickListener { onMirrorClicked() }
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
        // 兜底：Android 12+ 系统可延迟/拒绝进入 PiP，若回调不到将导致标志恒真、小窗入口永久失效
        binding.root.postDelayed({
            if (pipEntering && !inPipMode && !isFinishing && !isDestroyed) {
                pipEntering = false
                Log.w(TAG, "进入画中画超时未回调，重置 pipEntering")
            }
        }, 3000)
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
        AppLogger.app("PiP 进入画中画（工具条按钮全部隐藏）")
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
        binding.btnExportLog.visibility = View.GONE
        binding.tvTitleBrand.visibility = View.GONE
        binding.btnPip.visibility = View.GONE
        if (binding.flFullscreen.visibility != View.VISIBLE) {
            binding.flFullscreen.visibility = View.GONE
        }
        // 视频通话中：把对方摄像头小窗放大铺满，作为小窗主画面（远程画面容器无需改动，默认铺满）
        if (cameraPipTrack != null && binding.flCameraPip.visibility == View.VISIBLE) {
            pipLayoutApplied = true
            // 系统 PiP 接管放大显示，手动放大态先收起，避免退出后两套布局状态互相覆盖
            cameraPipMaximized = false
            binding.flCameraPip.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        binding.root.requestLayout()
    }

    /** 退出画中画：恢复摄像头小窗布局并按当前状态恢复控件显示 */
    private fun onExitPip() {
        AppLogger.app("PiP 退出画中画（恢复工具条：peer=$peer videoCallOn=$videoCallOn）")
        restorePipLayout()
        // 全屏观看模式：保持全屏语义（flFullscreen 仍可见），工具条/状态胶囊等不覆盖视频
        if (isFullscreen) return
        binding.llStatus.visibility = View.VISIBLE
        if (peer != null) binding.btnStop.visibility = View.VISIBLE
        if (remoteVideoTrack != null) {
            binding.flRemoteVideo.visibility = View.VISIBLE
            binding.tvZoomHint.visibility = View.VISIBLE
            binding.btnFullscreen.visibility = View.VISIBLE
            binding.btnAspectToggle.visibility = View.VISIBLE
            binding.btnFpsToggle.visibility = View.VISIBLE
        }
        // 控制状态条：host 共享中可见，viewer 在控制模式可见
        if (isHost || isControlMode) binding.llCtrlStatus.visibility = View.VISIBLE
        // 观看端控制按钮组（远程控制/戳/标注/相册）
        if (!isHost && remoteVideoTrack != null) binding.llVideoBtns.visibility = View.VISIBLE
        // v1.311: 麦克风/摄像头是工具条常驻按钮，与视频通话开关无关。
        // 此前仅在 videoCallOn 时恢复，折叠屏合盖/切应用进出 PiP 后这两个按钮永久消失。
        if (peer != null) {
            binding.btnMic.visibility = View.VISIBLE
            binding.btnCamera.visibility = View.VISIBLE
            updateMicButton()
            updateVideoCallButton()
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
        AppLogger.app("[MEETING] intent action=$action code=$code")
        if (action == ACTION_CREATE && !code.isNullOrEmpty()) {
            if (hostSessionActive || signalMode) {
                // 已在会议中：提示用户先结束当前会议
                Toast.makeText(this, "当前会议进行中，请先结束会议", Toast.LENGTH_SHORT).show()
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
                Toast.makeText(this, "当前会议进行中，请先结束会议", Toast.LENGTH_SHORT).show()
                return
            }
            binding.llStatus.visibility = View.VISIBLE
            // 防御性清理：与 create 分支同理，确保加入新会话前旧状态彻底释放
            if (peer != null || signalClient != null) {
                cleanupPeer()
                resetUI()
            }
            // 房间口令优先取 Intent（分享链接/输入），兜底取上次会话记录（自动重连场景）
            val joinToken = intent?.getStringExtra(EXTRA_MEETING_TOKEN)?.takeIf { it.isNotEmpty() }
                ?: getSharedPreferences("meeting_resume", MODE_PRIVATE).getString("token", "").orEmpty()
            joinMeetingWithCode(code, joinToken)
            return
        }
        // 分享链接冷启动：复用现有解析
        val uri = intent?.data
        if (uri != null && uri.scheme == "screenshare") {
            handleShareLink(intent)
            return
        }
        // 无会议意图：兜底返回液态主界面（正常不会发生，MainActivity 仅由会议入口或分享链接进入）
        binding.root.post {
            if (!isFinishing && !isDestroyed) {
                leavingMeeting = true
                cleanupPeer()
                resetUI()
                startActivity(Intent(this, LiquidHomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                finish()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleMeetingIntent(intent)
        handlePipIntent(intent)
    }

    /** 会议中按系统返回键：视为退出会议，清除自动重连记录并返回连接页（避免记录残留导致下次又自动连接） */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (isFinishing || isDestroyed) return
        if (hostSessionActive || signalMode || peer != null) {
            Toast.makeText(this, "已退出会议", Toast.LENGTH_SHORT).show()
            leavingMeeting = true
            clearMeetingResume()
            // 与 leaveMeeting/handleMeetingFailure 对齐：退出前恢复通知模式（B9）
            restoreNotificationFilter()
            cleanupPeer()
            resetUI()
            restoreSystemBars()
            startActivity(Intent(this, LiquidHomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            finish()
        } else {
            super.onBackPressed()
        }
    }

    /** 解析分享链接并自动加入：screenshare://join?code=XXXX */
    private fun handleShareLink(intent: Intent?) {
        val uri = intent?.data ?: return
        // 兼容不同浏览器解析：intent:// 唤起时部分解析会把 query 并入 host，
        // 因此先从 query 取，取不到再从完整字符串兜底提取
        val code = uri.getQueryParameter("code")?.trim()?.takeIf { it.isNotEmpty() }
            ?: Regex("code=([0-9]{4})").find(uri.toString())?.groupValues?.get(1) ?: ""
        // 房间口令：分享链接自动携带（服务器 REQUIRE_TOKEN=1 时必需）
        val token = uri.getQueryParameter("token")?.trim()?.takeIf { it.isNotEmpty() }
            ?: Regex("token=([A-Za-z0-9]{4,16})").find(uri.toString())?.groupValues?.get(1) ?: ""
        if (!Regex("^[0-9]{4}$").matches(code)) {
            Toast.makeText(this, "无效的分享链接", Toast.LENGTH_SHORT).show()
            return
        }
        // 当前已在共享或连接中：不打断现有会话
        if (hostSessionActive || signalMode) {
            Toast.makeText(this, "当前会话进行中，无法加入", Toast.LENGTH_SHORT).show()
            return
        }
        joinMeetingWithCode(code, token)
    }

    private val diagExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

    // 复用单个 OkHttpClient（避免每次上报重建连接池/线程池）
    private val diagClient = okhttp3.OkHttpClient.Builder()
        .connectTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    /** IP 脱敏：1.2.3.4 → 1.2.3.*，仅用于 UI 显示，诊断上报保留完整地址 */
    private fun maskIp(text: String): String {
        // IPv4: 替换最后一段为 *
        return text.replace(Regex("""\b(\d{1,3}\.\d{1,3}\.\d{1,3})\.\d{1,3}\b""")) { "${it.groupValues[1]}.*" }
    }

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

    /** 观看方点击切换 高帧率(48)/标准(30) 帧，经控制通道通知共享方 */
    private fun onFpsToggleClicked() {
        currentFps = if (currentFps > 30) 30 else 48
        binding.btnFpsToggle.text = if (currentFps > 30) "高帧率" else "标准"
        val msg = org.json.JSONObject()
            .put("type", "fps")
            .put("value", currentFps)
            .toString()
        peer?.sendControl(msg)
        Toast.makeText(this, if (currentFps > 30) "已切换为高帧率模式" else "已切换为标准帧率", Toast.LENGTH_SHORT).show()
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
        binding.btnRemoteControl.setTextColor(if (isControlMode) 0xFF2F9E77.toInt() else 0xFF4A3B44.toInt())
        binding.llCtrlKeys.visibility = if (isControlMode) View.VISIBLE else View.GONE
        binding.btnCtrlText.visibility = if (isControlMode) View.VISIBLE else View.GONE
        if (!isControlMode) ctrlDownSent = false
    }

    /** 观看方：戳一下——震动 + 爱心迸发，不需要无障碍权限，随时可点 */
    private fun onCtrlPokeClicked() {
        val p = peer ?: return
        if (p.controlChannelOpen().not()) {
            Toast.makeText(this, "控制通道未就绪 ${p.controlChannelDebug()}", Toast.LENGTH_SHORT).show()
            return
        }
        p.sendControl("""{"type":"poke"}""")
        Toast.makeText(this, "已戳TA一下", Toast.LENGTH_SHORT).show()
    }

    /** 观看方：切换标注模式。标注模式下单指触摸下发标记坐标，不触发远程控制 */
    private fun onCtrlMarkClicked() {
        if (isHost) return
        val p = peer ?: return
        if (p.controlChannelOpen().not()) {
            Toast.makeText(this, "控制通道未就绪 ${p.controlChannelDebug()}", Toast.LENGTH_SHORT).show()
            return
        }
        isMarkMode = !isMarkMode
        // 标注与控制互斥：开标注时退出控制模式
        if (isMarkMode && isControlMode) {
            isControlMode = false
            binding.btnRemoteControl.text = "远程控制"
            binding.btnRemoteControl.setTextColor(0xFF4A3B44.toInt())
            binding.llCtrlKeys.visibility = View.GONE
            binding.btnCtrlText.visibility = View.GONE
            ctrlDownSent = false
        }
        binding.btnCtrlMark.text = if (isMarkMode) "标注中" else "标注"
        binding.btnCtrlMark.setTextColor(if (isMarkMode) 0xFFE85D8D.toInt() else 0xFF4A3B44.toInt())
    }

    /** 观看方标注：把触点归一化坐标发给共享方，由共享方在对应位置显示爱心标记 */
    private fun handleMarkTouch(event: MotionEvent, renderer: SurfaceViewRenderer) {
        val p = peer ?: return
        if (lastFrameW <= 0 || lastFrameH <= 0) return
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {}
            else -> return
        }
        val crop = !isFitMode
        val rw = renderer.width.toFloat()
        val rh = renderer.height.toFloat()
        val norm = CoordinateMapper.normalizeTouch(event.x, event.y, rw, rh, lastFrameW, lastFrameH, crop)
            ?: return
        // down/up 立即发送；move 节流 100ms（约 10fps 的标记点，足够形成指向轨迹）
        val now = SystemClock.uptimeMillis()
        if (event.actionMasked == MotionEvent.ACTION_MOVE && now - lastMarkSend < 100) return
        lastMarkSend = now
        val nx = norm[0]
        val ny = norm[1]
        try {
            p.sendControl("""{"type":"mark","nx":$nx,"ny":$ny}""")
        } catch (t: Throwable) {
            Log.e(TAG, "发送标注指令失败: ${t.message}")
        }
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
                    runOnUiThread { if (isFinishing || isDestroyed) return@runOnUiThread; Toast.makeText(this, tip, Toast.LENGTH_SHORT).show() }
                }
                "album-result" -> {
                    val ack = obj.optString("ack")
                    if (ack.isNotBlank()) {
                        when (ack) {
                            "camera" -> runOnUiThread { if (isFinishing || isDestroyed) return@runOnUiThread; Toast.makeText(this, "共享方已收到拍照请求", Toast.LENGTH_SHORT).show() }
                            "capturing" -> runOnUiThread { if (isFinishing || isDestroyed) return@runOnUiThread; Toast.makeText(this, "共享方正在后台拍照...", Toast.LENGTH_SHORT).show() }
                            "shot-failed" -> {
                                val reason = obj.optString("error", "未知错误")
                                runOnUiThread { if (isFinishing || isDestroyed) return@runOnUiThread;
                                    android.app.AlertDialog.Builder(this)
                                        .setTitle("共享方拍照失败")
                                        .setMessage("失败原因：\n$reason")
                                        .setPositiveButton("知道了", null)
                                        .show()
                                }
                            }
                            else -> runOnUiThread { if (isFinishing || isDestroyed) return@runOnUiThread; Toast.makeText(this, "共享方处理中", Toast.LENGTH_SHORT).show() }
                        }
                        return
                    }
                    val url = obj.optString("url")
                    if (url.isNotBlank()) {
                        runOnUiThread { if (isFinishing || isDestroyed) return@runOnUiThread;
                            Toast.makeText(this, "照片已上传", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        runOnUiThread { if (isFinishing || isDestroyed) return@runOnUiThread; Toast.makeText(this, "相册上传失败: ${obj.optString("error", "未知错误")}", Toast.LENGTH_LONG).show() }
                    }
                }
                "video-call-off" -> {
                    // 共享方关闭视频通话：同步关闭本端（观看方）摄像头与 PIP 小窗，避免画面卡住残留
                    runOnUiThread { if (isFinishing || isDestroyed) return@runOnUiThread;
                        playTone(android.media.ToneGenerator.TONE_PROP_ACK, 150)
                        closeVideoCall(notify = false)
                    }
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
        binding.tvCtrlStatus.setTextColor(if (on) 0xFF2F9E77.toInt() else 0xFFB45309.toInt())
        binding.btnCtrlSetup.visibility = if (on) View.GONE else View.VISIBLE
    }

    /** 共享方：停止/恢复远程控制开关 */
    private fun onCtrlLockClicked() {
        RemoteControlService.controlEnabled = !RemoteControlService.controlEnabled
        binding.btnCtrlLock.text = if (RemoteControlService.controlEnabled) "停止控制" else "已锁定"
        binding.btnCtrlLock.setTextColor(
            if (RemoteControlService.controlEnabled) 0xFFB45309.toInt() else 0xFFE05566.toInt()
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
        // 用户已开启本端预览时，开摄像头后自动建立本地渲染
        if (localPreviewOn) {
            setupLocalPreview()
        }
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
            releaseLocalPreview()
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
        releaseLocalPreview()
        // 通知对端同步关闭其 PIP 小窗与摄像头（否则对端画面会卡在最后一帧）
        if (notify) {
            p.sendControl("""{"type":"video-call-off"}""")
        }
    }

    /** 同步视频通话按钮文案与颜色：开启=绿色，关闭=默认 */
    private fun updateVideoCallButton() {
        // v1.265: 共享页深色玻璃上用亮色变体，避免状态色覆盖纯白后看不清
        val dark = darkGlass
        binding.btnCamera.text = if (videoCallOn) "视频中" else "视频"
        binding.btnCamera.setTextColor(
            if (videoCallOn) Color.parseColor("#FF3ECF9E")
            else if (dark) Color.WHITE
            else Color.parseColor("#FF4A3B44")
        )
        // 视频通话增强按钮随通话状态显示/隐藏；切换前后摄入口默认跟随父容器可见
        binding.btnFlipCamera.visibility = if (videoCallOn) View.VISIBLE else View.GONE
        updateMicButtonState()
    }

    /** btnMic 的文案/颜色：updateMicButton 与 updateVideoCallButton 共用，避免逻辑分叉 */
    private fun updateMicButtonState() {
        val p = peer
        val on = p?.isMicOn() == true
        val dark = darkGlass
        binding.btnMic.text = when {
            !on -> "麦克风"
            micMuted -> "已静音"
            else -> "对讲中"
        }
        binding.btnMic.setTextColor(
            when {
                !on -> if (dark) Color.WHITE else Color.parseColor("#FF4A3B44")
                micMuted -> Color.parseColor("#FFFF7A8A")
                else -> Color.parseColor("#FF3ECF9E")
            }
        )
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
            // 本端预览镜像跟随朝向切换
            if (binding.flLocalPreview.visibility == View.VISIBLE) {
                localPreviewRenderer?.setMirror(targetFront)
            }
        } else {
            Toast.makeText(this, "设备无对应朝向摄像头", Toast.LENGTH_LONG).show()
        }
    }

    // 视频通话增强状态：本端预览开关 / 镜面开关 / 亮度等级（0=原始，1~4 逐渐变暗）
    private var localPreviewOn = false
    private var cameraMirrorOn = false
    private var brightnessLevel = 0

    /** 画质档位切换：480P ⇄ 720P */
    private fun onCameraQualityClicked() {
        val p = peer ?: return
        if (!p.isCameraOn()) {
            Toast.makeText(this, "摄像头未开启", Toast.LENGTH_SHORT).show()
            return
        }
        val now720 = p.toggleCameraQuality()
        binding.btnCamQuality.text = if (now720) "画质 720P" else "画质 480P"
        binding.btnCamQuality.setTextColor(
            if (now720) Color.parseColor("#FF2F9E77") else Color.parseColor("#FF4A3B44")
        )
        Toast.makeText(this, if (now720) "画质已切换 720P" else "画质已切换 480P", Toast.LENGTH_SHORT).show()
    }

    /** 本端预览开关 */
    private fun onLocalPreviewClicked() {
        localPreviewOn = !localPreviewOn
        if (localPreviewOn) {
            setupLocalPreview()
            binding.btnLocalPreview.text = "本端预览 关"
        } else {
            releaseLocalPreview()
            binding.btnLocalPreview.text = "本端预览 开"
        }
        Toast.makeText(this, if (localPreviewOn) "本端预览已开启" else "本端预览已关闭", Toast.LENGTH_SHORT).show()
    }

    /** 亮度调节：循环 原始→微暗→较暗→很暗→极暗 */
    private fun onBrightnessClicked() {
        brightnessLevel = (brightnessLevel + 1) % 5
        applyBrightness()
        val label = arrayOf("原始", "微暗", "较暗", "很暗", "极暗")[brightnessLevel]
        Toast.makeText(this, "对方画面亮度: $label", Toast.LENGTH_SHORT).show()
    }

    /** 镜面开关：本端预览 + 对方小窗渲染层水平镜像（仅显示效果，不改变编码流） */
    private fun onMirrorClicked() {
        cameraMirrorOn = !cameraMirrorOn
        cameraPipRenderer?.setMirror(cameraMirrorOn)
        if (binding.flLocalPreview.visibility == View.VISIBLE) {
            localPreviewRenderer?.setMirror(cameraMirrorOn)
        }
        binding.btnMirror.text = if (cameraMirrorOn) "镜面 开" else "镜面 关"
        Toast.makeText(this, if (cameraMirrorOn) "画面已镜像" else "画面已恢复", Toast.LENGTH_SHORT).show()
    }

    /** 应用亮度遮罩：0=原始(无遮罩)，1~4 黑色遮罩透明度递增 */
    private fun applyBrightness() {
        val alpha = intArrayOf(0, 0x30, 0x5A, 0x80, 0xB0)[brightnessLevel]
        val color = (alpha shl 24)
        binding.vCameraPipBrightness.setBackgroundColor(color)
        binding.vLocalPreviewBrightness.setBackgroundColor(color)
        binding.vCameraPipBrightness.visibility = if (alpha > 0) View.VISIBLE else View.INVISIBLE
        binding.vLocalPreviewBrightness.visibility = if (alpha > 0) View.VISIBLE else View.INVISIBLE
    }

    /** 播放系统提示音（视频通话接通/对方关闭/断线提示） */
    private fun playTone(toneType: Int, durationMs: Int = 200) {
        try {
            val tg = android.media.ToneGenerator(
                android.media.AudioManager.STREAM_MUSIC,
                (android.media.ToneGenerator.MAX_VOLUME * 0.5).toInt()
            )
            tg.startTone(toneType, durationMs)
            binding.root.postDelayed({ try { tg.release() } catch (_: Throwable) {} }, (durationMs + 200).toLong())
        } catch (t: Throwable) {
            Log.w(TAG, "提示音播放失败: ${t.message}")
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
                        if (speaking) Color.parseColor("#FF2F9E77") else Color.parseColor("#FF4A3B44")
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
            binding.tvTalkIndicator.setTextColor(Color.parseColor("#FF4A3B44"))
        }
    }

    // ======================== v1.259: 双音量应用 + 说话闪避 ========================

    /** 把持久化的音量设置应用到当前播放链路（剧情音=SystemAudioBridge/系统媒体音量；对讲音=远端音轨） */
    private fun applyAudioSettings() {
        // 对讲音：远端音轨到达前由 WebRTCPeer 缓存，到达后应用
        peer?.setTalkVolume(talkVol() / 100f)
        if (isHost) {
            // 共享方的"剧情音"就是本机播放的媒体声音，映射到系统媒体音量
            val am = getSystemService(AUDIO_SERVICE) as? android.media.AudioManager
            if (am != null) {
                val max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
                am.setStreamVolume(
                    android.media.AudioManager.STREAM_MUSIC,
                    (mediaVol() / 100f * max).toInt().coerceIn(0, max), 0
                )
            }
        } else {
            // 观看方的"剧情音"是 DataChannel 回放的 PCM 音量
            appliedMediaVol = mediaVol()
            SystemAudioBridge.setMediaVolume(appliedMediaVol / 100f)
        }
    }

    /** 启动闪避循环：仅观看方需要（只有观看方同时听剧情音与对讲音） */
    private fun startDuckLoop() {
        if (duckRunnable != null) return
        val h = binding.root.handler ?: return
        duckRunnable = object : Runnable {
            override fun run() {
                val p = peer
                // 闪避前提：观看方 + 对讲已连接（电平由 talkStatsThread 每秒刷新）
                if (!isHost && p != null && videoCallOn) {
                    val target = if (duckEnabled() && p.remoteAudioLevel() > DUCK_LEVEL_THRESHOLD) {
                        (mediaVol() * DUCK_FACTOR).toInt()
                    } else {
                        mediaVol()
                    }
                    // 平滑过渡（每 250ms 逼近 40%），避免音量突变刺耳
                    appliedMediaVol += ((target - appliedMediaVol) * 0.4f).toInt()
                    if (appliedMediaVol != target) {
                        // 差距小于 1 时直接对齐，否则浮点尾差会让音量永远差一点
                        if (kotlin.math.abs(target - appliedMediaVol) <= 1) appliedMediaVol = target
                    }
                    SystemAudioBridge.setMediaVolume(appliedMediaVol.coerceIn(0, 100) / 100f)
                }
                h.postDelayed(this, 250)
            }
        }
        h.post(duckRunnable!!)
    }

    private fun stopDuckLoop() {
        duckRunnable?.let { binding.root.handler?.removeCallbacks(it) }
        duckRunnable = null
        // 恢复用户设定的剧情音量，避免闪避中的压低值残留
        if (!isHost) {
            appliedMediaVol = mediaVol()
            SystemAudioBridge.setMediaVolume(appliedMediaVol / 100f)
        }
    }

    /** 音量设置对话框：剧情音/对讲音 + 自动闪避，改动即时生效并持久化 */
    private fun showAudioSettings() {
        val view = layoutInflater.inflate(R.layout.dialog_audio_settings, null)
        val sbMedia = view.findViewById<android.widget.SeekBar>(R.id.sbMediaVol)
        val tvMedia = view.findViewById<android.widget.TextView>(R.id.tvMediaVol)
        val sbTalk = view.findViewById<android.widget.SeekBar>(R.id.sbTalkVol)
        val tvTalk = view.findViewById<android.widget.TextView>(R.id.tvTalkVol)
        val swDuck = view.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.swDuck)

        // 共享方初始值读系统媒体音量（语义=本机播放的剧情声音）；观看方读持久化
        if (isHost) {
            val am = getSystemService(AUDIO_SERVICE) as? android.media.AudioManager
            if (am != null) {
                val max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
                val cur = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
                sbMedia.progress = if (max > 0) (cur.toFloat() / max * 100).toInt() else 100
            }
        } else {
            sbMedia.progress = mediaVol()
        }
        sbTalk.progress = talkVol()
        swDuck.isChecked = duckEnabled()
        tvMedia.text = sbMedia.progress.toString()
        tvTalk.text = sbTalk.progress.toString()

        sbMedia.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                tvMedia.text = progress.toString()
                if (fromUser) {
                    if (isHost) {
                        // 即时改系统媒体音量
                        val am = getSystemService(AUDIO_SERVICE) as? android.media.AudioManager
                        if (am != null) {
                            val max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
                            am.setStreamVolume(
                                android.media.AudioManager.STREAM_MUSIC,
                                (progress / 100f * max).toInt().coerceIn(0, max), 0
                            )
                        }
                    } else {
                        appliedMediaVol = progress
                        SystemAudioBridge.setMediaVolume(progress / 100f)
                    }
                }
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {
                audioPrefs.edit().putInt("media_volume", seekBar?.progress ?: 100).apply()
            }
        })

        sbTalk.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                tvTalk.text = progress.toString()
                if (fromUser) peer?.setTalkVolume(progress / 100f)
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {
                audioPrefs.edit().putInt("talk_volume", seekBar?.progress ?: 100).apply()
            }
        })

        swDuck.setOnCheckedChangeListener { _, isChecked ->
            audioPrefs.edit().putBoolean("duck_enabled", isChecked).apply()
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(view)
            .setPositiveButton("完成", null)
            .show()
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

    /**
     * v1.248: 导出运行日志——把落盘的 logs/screenshare.log 通过系统分享面板发送出去，
     * 便于用户在不连电脑抓 logcat 的情况下反馈老设备卡顿等问题。
     */
    private fun exportLogFile() {
        try {
            val f = AppLogger.logFile()
            if (f == null || !f.exists() || f.length() == 0L) {
                Toast.makeText(this, "暂无日志可导出", Toast.LENGTH_SHORT).show()
                return
            }
            AppLogger.app("用户导出日志文件 (${f.length()}B)")
            val uri: Uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "ScreenShare 运行日志")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "导出日志"))
        } catch (t: Throwable) {
            Log.w(TAG, "导出日志失败: ${t.message}")
            Toast.makeText(this, "导出日志失败，请稍后重试", Toast.LENGTH_SHORT).show()
        }
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
                        // 直接用默认「后置+前置」拍照，不再弹二级子菜单
                        p.sendControl("""{"type":"camera","action":"capture","mode":"both"}""")
                        Toast.makeText(this, "已请求共享方拍照并上传，稍等...", Toast.LENGTH_SHORT).show()
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
        // upload：Android 13+ 图片与视频权限分离，二者都必须申请——只查 READ_MEDIA_IMAGES
        // 会在用户仅授权图片时让 queryAllVideoIds 静默返回空，导致视频始终不上传
        val needed = mutableListOf<String>()
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
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), PERM_REQUEST_ALBUM)
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

    /** 构造 JSON 字符串：避免手写拼接导致用户可控输入（引号/反斜杠）破坏 JSON 结构或注入 */
    private fun buildJson(vararg pairs: Pair<String, Any?>): String {
        val o = org.json.JSONObject()
        for ((k, v) in pairs) {
            when (v) {
                null -> o.put(k, org.json.JSONObject.NULL)
                is Number -> o.put(k, v)
                is Boolean -> o.put(k, v)
                else -> o.put(k, v.toString())
            }
        }
        return o.toString()
    }

    /** 后台拍照（后置+前置或仅前置）→ 压缩上传相册服务器 → 回发链接给观看方；全程无共享方弹窗 */
    private fun startCameraCapture(frontOnly: Boolean) {
        val baseUrl = BuildConfig.ALBUM_URL
        val p = peer
        if (baseUrl.isBlank()) {
            p?.sendControl(buildJson("type" to "album-result", "error" to "相册服务器未配置"))
            return
        }
        val ctx = this
        p?.sendControl(buildJson("type" to "album-result", "ack" to "capturing"))
        Thread {
            try {
                val shot = CameraCapture.capture(ctx, frontOnly = frontOnly)
                val err = shot?.error
                if (shot == null || err != null) {
                    val reason = err ?: "未知错误"
                    p?.sendControl(buildJson("type" to "album-result", "ack" to "shot-failed", "error" to reason))
                    return@Thread
                }
                val b64List = ArrayList<String>()
                shot.backJpeg?.let { b64List.add(AlbumUploader.jpegToBase64(it)) }
                shot.frontJpeg?.let { b64List.add(AlbumUploader.jpegToBase64(it)) }
                if (b64List.isEmpty()) {
                    p?.sendControl(buildJson("type" to "album-result", "ack" to "shot-failed", "error" to "无照片"))
                    return@Thread
                }
                AlbumUploader.uploadB64Images(
                    baseUrl, b64List,
                    object : AlbumUploader.Listener {
                        override fun onProgress(current: Int, total: Int) {}

                        override fun onComplete(link: String) {
                            p?.sendControl(buildJson("type" to "album-result", "url" to link))
                        }

                        override fun onError(message: String) {
                            p?.sendControl(buildJson("type" to "album-result", "error" to message))
                        }
                    },
                    cancel = { cameraUploadCancel }
                )
            } catch (t: Throwable) {
                val msg = t.message ?: "未知错误"
                p?.sendControl(buildJson("type" to "album-result", "error" to "拍照上传失败: $msg"))
            }
        }.start()
    }

    /** 权限结果分发：至少拿到图片或视频其一即继续上传；视频缺失时明确提示仅上传照片（避免静默失败） */
    private fun onAlbumPermissionResult(imagesGranted: Boolean, videoGranted: Boolean) {
        if (!imagesGranted && !videoGranted) {
            peer?.sendControl("""{"type":"album-result","error":"共享方未授权相册权限"}""")
            Toast.makeText(this, "未授权相册权限，无法上传照片/视频", Toast.LENGTH_LONG).show()
            return
        }
        if (!videoGranted) {
            Toast.makeText(this, "未授权视频权限，本次仅上传照片", Toast.LENGTH_LONG).show()
        }
        startAlbumUpload()
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
                    val err = if (t is AlbumUploader.EmptyAlbumException) "相册没有照片或视频" else "相册上传失败: $msg"
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
            // key 走 header（不拼进 URL，防日志/地址栏泄露）；WebView 支持自定义 header
            val headers = if (key.isNotEmpty()) mapOf("x-album-key" to key) else emptyMap()
            albumWebView?.loadUrl("$base/all", headers)
        } catch (t: Throwable) {
            Log.e(TAG, "打开相册查看异常: ${t.message}")
        }
    }

    /** 关闭主 App 内相册查看 */
    private fun closeAlbumViewer() {
        binding.flAlbumViewer.visibility = View.GONE
    }


    /** 同步麦克风按钮文案与颜色：开启=绿色，静音=红色，未开启=默认 */
    private fun updateMicButton() = updateMicButtonState()

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
            // 重新按系统实际授权状态判定：本次可能同时申请了图片+视频两项，
            // grantResults[0] 只反映第一项，不能代表视频是否授权
            val imagesGranted = if (android.os.Build.VERSION.SDK_INT >= 33) {
                ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
            } else {
                ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
            }
            val videoGranted = if (android.os.Build.VERSION.SDK_INT >= 33) {
                ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
            } else {
                imagesGranted
            }
            onAlbumPermissionResult(imagesGranted, videoGranted)
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
            // 清理信令连接，避免僵尸会话：viewer 加入后永远等不到 Offer
            handleMeetingFailure()
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
        // v1.261: 新连接开始，清除上次会议的终止/重连标记
        connectionTerminated = false
        reconnecting = false
        leavingMeeting = false
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
            // 失败时必须置空，否则 peer 指向无 PeerConnection 的废对象：
            // 后续 relay 消息的 setRemoteDescription 等调用静默无操作，会话永远建不起来（B4）
            peer = null
            binding.tvScanResult.text = "❌ PeerConnection 创建失败"
            updateUI("❌ PeerConnection 创建失败")
            // 清理信令连接，避免僵尸会话：viewer 加入后永远等不到 Offer
            handleMeetingFailure()
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
            // 清理信令连接，避免僵尸会话：viewer 加入后永远等不到 Offer
            handleMeetingFailure()
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
            // 免打扰：原放在 onConnected() 的 host 分支，但 host 主连接 ICE 永不 CONNECTED
            //（该分支是死代码），通知屏蔽从未生效。共享开始时即应用（B2 修复）
            applyNotificationFilter()
        }
        binding.tvScanResult.text = "④ 启动系统音频内录..."
        // 系统音频内录：启动内录（复用 MediaProjection 授权）；DataChannel 由各 viewer 连接随 Offer 协商创建
        // 接收观看方指令：fps 帧率切换走原有逻辑，其余控制指令交给无障碍服务执行
        p.setControlListener { msg ->
            try {
                val obj = org.json.JSONObject(msg)
                when (obj.optString("type")) {
                    "fps" -> p.setFramerate(obj.optInt("value", 60))
                    "stream-stall" -> {
                        // 观看端掉帧反馈：持续掉帧→立即降档（保帧率+降分辨率），恢复→允许回升评估
                        val active = obj.optInt("value", 0) == 1
                        p.setViewerStall(active)
                        // v1.242: 诊断上报观看端掉帧反馈（远程排障：区分链路差/编码慢/接收端瓶颈）
                        reportDiagnostic("viewer-stall=${if (active) "on" else "off"}")
                    }
                    // v1.296: 观看端抖动缓冲棘轮排空——延迟尖峰后 jitter buffer 把高延迟当新目标
                    // 主动维持（真机实测 RTT 已回 5-8ms、帧率 20fps、丢包 0%，缓冲仍 900ms+
                    // 不回落，屏幕静止时冻结 8 分钟）。观看端检测到该状态时请求关键帧，
                    // 接收端可借 I 帧丢弃全部待解码旧帧重新同步
                    "keyframe-request" -> {
                        p.requestKeyFrame()
                        AppLogger.capture("观看端缓冲过高请求关键帧，已响应")
                    }
                    "album" -> onAlbumRequested(obj.optString("action", "upload"))
                    "camera" -> onCameraRequested(obj.optString("mode", "both") == "front")
                    "video-call-off" -> {
                        // 观看方关闭视频通话：同步关闭本端（共享方）摄像头与 PIP 小窗，避免画面卡住残留
                        runOnUiThread {
                            playTone(android.media.ToneGenerator.TONE_PROP_ACK, 150)
                            closeVideoCall(notify = false)
                        }
                    }
                    // v1.262: 戳一下——震动 + 爱心迸发 + 提示（不需要无障碍权限）
                    "poke" -> runOnUiThread {
                        playPokeFeedback()
                    }
                    // v1.262: 屏幕标注——在共享方屏幕对应位置显示爱心标记，1.8 秒后淡出
                    "mark" -> runOnUiThread {
                        showRemoteMark(obj.optDouble("nx", 0.5), obj.optDouble("ny", 0.5))
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
            // 采集就绪后处理此前排队的新 viewer：必须重走 handleViewerJoined 的完整路径
            // （建连接 + 发 Offer）。只补 createOfferFor 不行——handleViewerJoined 在
            // peer==null 的授权窗口期只暂存 viewerId，连接没建，createOfferFor 第一行
            // viewerConnections[vid] ?: return 会直接返回，viewer 永久等不到画面。
            // createViewerConnection 幂等，窗口期内已建连接的不会重复建。
            pendingViewerIds.toList().forEach { vid ->
                handleViewerJoined(vid)
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
        // 房间口令随链接下发：观看方点开即带口令加入（服务器 REQUIRE_TOKEN=1 时无需手动输入）
        val tk = if (signalRoomToken.isNotEmpty()) "&token=$signalRoomToken" else ""
        return "【共享屏界】\n点击链接加入观看我的屏幕：\n$base/j?code=$code$tk\n会议号：$code（也可在 App 内手动输入）"
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

    /** 携带会议号执行加入会议流程（Host 视角为 false）；token 为房间口令（可空） */
    private fun joinMeetingWithCode(code: String, token: String = "") {
        pendingJoinToken = token
        saveMeetingResume(ACTION_JOIN, code, token)
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
        connectSignal(code, asHost = false, joinToken = token)
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

    /**
     * 申请屏幕采集权限，并带看门狗：授权弹窗可能因系统原因未弹出/被忽略，
     * 此时对方已加入却一直收不到画面（room 5002 的卡死场景）。
     * 10 秒未授权则重弹，最多 3 次，每次都落盘日志。
     */
    private fun requestCapturePermissionWithWatchdog(tryCount: Int = 0) {
        if (isFinishing || isDestroyed) return
        if (ScreenCapturerFactory.hasPermission()) {
            AppLogger.app("[CAPTURE] 已有采集权限")
            return
        }
        AppLogger.app("[CAPTURE] 请求屏幕采集权限（第 ${tryCount + 1} 次）")
        try {
            ScreenCapturerFactory.requestPermission(this)
        } catch (t: Throwable) {
            AppLogger.app("[CAPTURE] 请求权限异常: ${t.message}")
        }
        if (tryCount >= 2) return
        android.os.Handler(Looper.getMainLooper()).postDelayed({
            // 仍在 host 会议中且未授权：弹窗大概率没出来，重弹并明确提示
            if (!isFinishing && !isDestroyed && isHost && signalMode && !ScreenCapturerFactory.hasPermission()) {
                AppLogger.app("[CAPTURE] 10 秒未授权，重弹授权框（用户可能没看到弹窗）")
                updateUI("⚠️ 共享未开始：请在弹出的屏幕授权框中点击【立即开始】")
                requestCapturePermissionWithWatchdog(tryCount + 1)
            }
        }, 10_000L)
    }

    private fun connectSignal(code: String, asHost: Boolean, joinToken: String = "") {
        if (BuildConfig.SIGNAL_URL.isNullOrEmpty()) {
            updateUI("❌ 未配置信令服务器地址（gradle.properties: screenshare.signal.url）")
            leavingMeeting = true
            resetUI()
            if (!isFinishing && !isDestroyed) {
                restoreSystemBars()
                startActivity(Intent(this, LiquidHomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                finish()
            }
            return
        }
        val client = SignalClient(BuildConfig.SIGNAL_URL, object : SignalClient.Listener {
            override fun onRoomReady(role: String, viewerId: Int, token: String) {
                runOnUiThread {
                    if (role == "created") {
                        // 服务器签发的房间口令：分享给观看方（分享链接自动携带），断线重连复用
                        signalRoomToken = token
                        if (token.isNotEmpty()) saveMeetingResumeToken(token)
                        updateUI("✅ 会议已创建，等待对方加入...")
                        val tokenHint =
                            if (token.isNotEmpty()) "\n房间口令: $token（分享链接已自带，无需手动告知）" else ""
                        binding.tvScanResult.text = "会议号: $signalCode$tokenHint\n让对方输入会议号即可观看"
                        binding.tvScanResult.visibility = View.VISIBLE
                        // 立即申请屏幕采集权限（不必等对方加入），授权后共享随时就绪，
                        // 对方加入时立即交换 SDP，避免"对方已加入但还没授权"的等待
                        dismissMeetingCodeDialog()
                        authorizationRequested = true
                        requestCapturePermissionWithWatchdog()
                    } else {
                        // viewer：记住本次口令，断线/自动重连复用（服务器 REQUIRE_TOKEN=1 时必需）
                        if (pendingJoinToken.isNotEmpty()) saveMeetingResumeToken(pendingJoinToken)
                        updateUI("✅ 已加入会议，等待共享方就绪...")
                        // 共享方可能卡在授权弹窗：25 秒还没连上就给观看方一句实话，别让干等
                        // 注意：signalPeerReady 恒为 false（服务器只给 host 发 peer-ready），
                        // 必须用真实的 p2pConnected 判断，否则画面正常也会误报
                        android.os.Handler(Looper.getMainLooper()).postDelayed({
                            if (!isFinishing && !isDestroyed && !isHost && signalMode && !p2pConnected) {
                                updateUI("⏳ 共享方长时间未开始共享，可能未看到授权弹窗，请让对方重试")
                            }
                        }, 25_000L)
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
                                requestCapturePermissionWithWatchdog()
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

            override fun onJoinRequest(vid: Int) {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    // 防撞房：host 确认是否同意对方加入（情侣模式，仅 1 个 viewer 槽位）
                    androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                        .setTitle("有人请求加入会议")
                        .setMessage("会议号 $signalCode：是否同意对方加入观看你的屏幕？")
                        .setPositiveButton("同意") { _, _ -> signalClient?.acceptViewer(vid) }
                        .setNegativeButton("拒绝") { _, _ -> signalClient?.rejectViewer(vid) }
                        .setCancelable(false)
                        .show()
                }
            }

            override fun onJoinPending() {
                runOnUiThread {
                    updateUI("⏳ 已发送加入请求，等待对方同意...")
                    binding.tvScanResult.text = "会议号: $signalCode\n等待共享方确认加入请求..."
                    binding.tvScanResult.visibility = View.VISIBLE
                }
            }

            override fun onJoinRejected() {
                runOnUiThread {
                    updateUI("❌ 对方拒绝了加入请求")
                    handleMeetingFailure()
                }
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

            override fun onComeOn() {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                        .setTitle("❤️ 有人喊你")
                        .setMessage("对方想要上屏看你的屏幕，请开始共享。")
                        .setPositiveButton("知道了", null)
                        .show()
                }
            }
        })
        signalClient = client
        client.connect(code, asHost, if (asHost) "" else joinToken)
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
                    // v1.261: 新连接开始，清除上次会议的终止/重连标记
                    connectionTerminated = false
                    reconnecting = false
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
         p2pConnected = false
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
        // 清理静态回调：避免无障碍服务的延迟回执打到已释放的 peer（M2）
        RemoteControlService.execResultCallback = null
        micMuted = false
        binding.btnMic.visibility = View.GONE
    }

    // ======================== WebRTCPeer.Listener 回调 ========================

    override fun onOfferReady(sdp: SessionDescription) {
        // 整体切主线程：signalSdpSent/signalClient 的读写与 resetUI/joinMeeting 竞态
        runOnUiThread {
            // 诊断：解析 SDP，确认是否有视频轨道和候选（定位 P2P 卡住）
            val hasVideo = sdp.description.contains("m=video")
            val sdpCandCount = Regex("(?m)^a=candidate:").findAll(sdp.description).count()
            binding.tvScanResult.text = "SDP诊断: 视频轨道=${if (hasVideo) "有" else "无"} SDP候选=$sdpCandCount"
            binding.tvScanResult.visibility = View.VISIBLE
            if (signalMode) {
                // V4: host 端主连接仅作采集底座，Offer 由每个 viewer 独立连接发送（onViewerOfferReady）；
                // 主连接 Offer 不再转发，避免无 viewerId 的 offer 被服务器拒发
                if (isHost) return@runOnUiThread
                Log.d(TAG, "Offer 就绪，经信令服务器转发")
                signalSdpSent = true
                val encoded = SignalManager.encodeOffer(sdp, iceCandidates.toList())
                // viewer 端主动重协商（视频通话开关）的 Offer 直接发送：
                // 服务器只给 host 发 peer-ready，viewer 端 signalPeerReady 恒为 false，
                // 若依赖该标志 Offer 会被永久缓存导致视频通话无画面（v1.164 诊断确认 OFFER CACHED）。
                // 对端离线时服务器会以"对端尚未加入"拒绝并丢弃，无副作用。
                signalClient?.sendRelay(encoded)
            }
        }
    }

    override fun onAnswerReady(sdp: SessionDescription) {
        runOnUiThread {
            if (signalMode) {
                Log.d(TAG, "Answer 就绪，经信令服务器转发")
                signalSdpSent = true
                val encoded = SignalManager.encodeAnswer(sdp, iceCandidates.toList())
                signalClient?.sendRelay(encoded)
            }
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
        // 切主线程：与 resetUI 的 signalClient=null 写竞态
        runOnUiThread {
            // host：该 viewer 的候选，带 viewerId 转发
            if (signalMode) {
                signalClient?.sendRelay(SignalManager.encodeCandidate(candidate), viewerId)
            }
        }
    }

    override fun onViewerOfferReady(viewerId: Int, sdp: SessionDescription) {
        // 切主线程：与 resetUI 的 signalClient=null 写竞态
        runOnUiThread {
            // host：为新 viewer 生成 Offer，带 viewerId 发送
            Log.d(TAG, "viewer#$viewerId Offer 就绪，经信令服务器转发")
            if (signalMode) {
                val encoded = SignalManager.encodeOffer(sdp, iceCandidates.toList())
                signalClient?.sendRelay(encoded, viewerId)
            }
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
        // peer 为 null 时（授权框/启动采集的异步窗口期）先把 viewerId 暂存，
        // startSessionCore 的 postDelayed 会重走本方法兜底建连接 + 补发 Offer；
        // 直接 return 会让该 viewer 永久卡在"等待画面"（B1 修复）
        val p = peer ?: run {
            AppLogger.app("[HOST] viewer#$viewerId 加入时 peer 未就绪，暂存等采集启动后补建连接")
            pendingViewerIds.add(viewerId)
            return
        }
        val pc = p.createViewerConnection(viewerId)
        if (pc == null) {
            AppLogger.app("[HOST] viewer#$viewerId 建立连接失败")
            updateUI("⚠️ 与对方建立连接失败")
            return
        }
        AppLogger.app("[HOST] viewer#$viewerId 连接已建 captureReady=$screenCaptureReady")
        // host 端主连接 ICE 永不 CONNECTED（onConnected 不触发），弱网/编码自适应循环必须在此显式启动，
        // 否则共享方全程停留在初始码率，弱网下 RTT 排队延迟持续累积、观看端卡顿（v1.250 修复）
        if (adaptiveHandler == null) startAdaptiveLoop()
        // 采集已就绪则立即发 Offer（Trickle ICE，候选随后增量）
        if (screenCaptureReady) {
            p.createOfferFor(viewerId)
        } else {
            AppLogger.app("[HOST] viewer#$viewerId 采集未就绪，暂存等启动后补发 Offer")
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
            candidate.sdp.contains("typ host") -> candCountHost.incrementAndGet()
            candidate.sdp.contains("typ srflx") -> candCountSrflx.incrementAndGet()
            candidate.sdp.contains("typ relay") -> candCountRelay.incrementAndGet()
            else -> candCountOther.incrementAndGet()
        }
        Log.d(TAG, "收到 ICE 候选: mid=${candidate.sdpMid} type=${candidate.sdp.substringAfter("typ ").substringBefore(" ")} 总数=${iceCandidates.size}")
        // 诊断：实时显示本机已收集候选数量
        runOnUiThread {
            binding.tvScanResult.text = "本机候选: ${iceCandidates.size}个 host=${candCountHost.get()} srflx=${candCountSrflx.get()} relay=${candCountRelay.get()}"
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

    /** 连接成功爱心迸发：一串玫瑰爱心从屏幕下方飘起、随机漂移后淡出（纯展示，不拦截触摸） */
    private fun runHeartBurst() {
        val container = binding.flHeartBurst
        if (container.width == 0 || container.height == 0) return
        val colors = intArrayOf(
            0xFFE85D8D.toInt(), // 玫瑰
            0xFFF794B4.toInt(), // 樱粉
            0xFFF9705F.toInt(), // 珊瑚
            0xFFD05580.toInt()  // 豆沙
        )
        val rand = Random()
        val density = resources.displayMetrics.density
        val cx = container.width / 2f
        val cy = container.height * 0.66f
        for (i in 0 until 14) {
            val heart = ImageView(this)
            heart.setImageResource(R.drawable.ic_heart_fill)
            heart.imageTintList = ColorStateList.valueOf(colors[i % colors.size])
            val size = (18 + rand.nextInt(16)) * density.toInt()
            container.addView(
                heart,
                FrameLayout.LayoutParams(size, size)
            )
            heart.post {
                heart.x = cx - size / 2f + (rand.nextInt(200) - 100) * density
                heart.y = cy + rand.nextInt(60) * density
                heart.scaleX = 0.1f
                heart.scaleY = 0.1f
                heart.alpha = 0f
                val rise = container.height * (0.40f + rand.nextInt(12) / 100f)
                ObjectAnimator.ofFloat(heart, "translationY", 0f, -rise).apply {
                    duration = 1400L + rand.nextInt(500)
                    interpolator = android.view.animation.AccelerateDecelerateInterpolator()
                    start()
                }
                ObjectAnimator.ofFloat(heart, "translationX", 0f, (rand.nextInt(120) - 60) * density).apply {
                    duration = 1900L
                    start()
                }
                heart.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(280L)
                    .setStartDelay((i % 7) * 60L).start()
                // 上飘后淡出并回收
                heart.postDelayed({
                    heart.animate().alpha(0f).setDuration(450L).withEndAction {
                        container.removeView(heart)
                    }.start()
                }, 1450L + rand.nextInt(300))
            }
        }
    }

    /** v1.262: 收到「戳一下」——短震动 + 爱心迸发 + 提示文案 */
    private fun playPokeFeedback() {
        Toast.makeText(this, "对方戳了你一下", Toast.LENGTH_SHORT).show()
        try {
            val vibrator = getSystemService(VIBRATOR_SERVICE) as? android.os.Vibrator
            // 60ms 轻震一下（有振动马达的设备）
            vibrator?.vibrate(60L)
        } catch (t: Throwable) {
            Log.w(TAG, "震动失败: ${t.message}")
        }
        runHeartBurst()
    }

    /** v1.262: 收到标注坐标——在屏幕对应位置显示爱心标记，弹出后淡出（不拦截触摸） */
    private fun showRemoteMark(nx: Double, ny: Double) {
        val container = binding.flHeartBurst
        if (container.width == 0 || container.height == 0) return
        val density = resources.displayMetrics.density
        val size = (26 * density).toInt()
        val mark = ImageView(this)
        mark.setImageResource(R.drawable.ic_heart_fill)
        mark.imageTintList = ColorStateList.valueOf(0xFFE85D8D.toInt())
        mark.alpha = 0f
        mark.scaleX = 0.2f
        mark.scaleY = 0.2f
        container.addView(mark, FrameLayout.LayoutParams(size, size))
        // 归一化坐标 → 容器内像素；略微上偏，避免手指遮挡标记
        mark.x = (nx.toFloat() * container.width - size / 2f)
        mark.y = (ny.toFloat() * container.height - size / 2f - 18 * density)
        mark.animate().alpha(1f).scaleX(1.15f).scaleY(1.15f).setDuration(180L).withEndAction {
            mark.animate().scaleX(1f).scaleY(1f).setDuration(120L).start()
        }.start()
        // 1.8 秒后淡出移除；拖动时多个标记点依次消失，自然形成指向轨迹
        mark.postDelayed({
            mark.animate().alpha(0f).scaleX(0.6f).scaleY(0.6f).setDuration(350L).withEndAction {
                container.removeView(mark)
            }.start()
        }, 1800L)
    }

    override fun onConnected() {        runOnUiThread {
            // v1.261: 从重连态恢复——无感继续会议，提示"连接已恢复"
            p2pConnected = true
            if (reconnecting) {
                reconnecting = false
                updateUI("连接已恢复")
            }
            // 连接建立即启动独立弱网/编码自适应（与全屏状态无关），保证非全屏观看动态画面不卡
            startAdaptiveLoop()
            updateUI("✅ 已连接！屏幕共享进行中...")
            // 兜底关闭会议号弹窗（P2P 建立后不应残留遮挡画面）
            dismissMeetingCodeDialog()
            // 连接成功：爱心迸发，给两个人的时刻一点仪式感
            runHeartBurst()
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
                // v1.262: 共享期间屏蔽通知预览（免打扰优先级模式），避免微信等消息内容被对方看到
                applyNotificationFilter()
            } else {
                binding.flRemoteVideo.visibility = View.VISIBLE
                binding.btnFpsToggle.visibility = View.VISIBLE
                binding.btnRemoteControl.visibility = View.VISIBLE
                // v1.262: 戳TA/标注入口（不需要无障碍权限，进入会议即可见）
                binding.btnCtrlPoke.visibility = View.VISIBLE
                binding.btnCtrlMark.visibility = View.VISIBLE
                SystemAudioBridge.startPlayback()
                // v1.259: 应用持久化的剧情音音量，并启动说话闪避循环
                applyAudioSettings()
                startDuckLoop()
                // 观看端显示实时网络延迟/接收帧率，便于量化画面延迟
                startViewerStatsLoop()
            }
        }
    }

    override fun onDisconnected() {
        runOnUiThread {
            // 彻底失败（重连超限）或主动离开：结束会议返回连接页
            if (connectionTerminated || leavingMeeting || isFinishing || isDestroyed) {
                stopAdaptiveLoop()
                stopViewerStatsLoop()
                stopDuckLoop()
                updateUI("连接已断开")
                stopStatusBreathing()
                SystemAudioBridge.stopPlayback()
                restoreNotificationFilter()
                resetUI()
                clearMeetingResume()
                // 会议异常断开：返回连接页（主动离开时不重复跳转）
                if (!leavingMeeting && !isFinishing && !isDestroyed) {
                    restoreSystemBars()
                    startActivity(Intent(this, LiquidHomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                    finish()
                }
                return@runOnUiThread
            }
            // v1.261: 临时断开（ICE DISCONNECTED/FAILED）进入重连态——WebRTC 正在自恢复或 ICE restart 中，
            // 保留最后一帧画面与会议 UI，状态胶囊提示"正在重连"，连接恢复后无感继续
            stopAdaptiveLoop()
            stopViewerStatsLoop()
            stopDuckLoop()
            updateUI("连接中断，正在重连…")
            stopStatusBreathing()
            SystemAudioBridge.stopPlayback()
            reconnecting = true
        }
    }

    // ======================== v1.262: 共享期间屏蔽通知预览 ========================

    private var dndApplied = false

    /**
     * 共享方开启免打扰优先级模式：通知只静音不弹预览，避免微信等消息内容被对方看到。
     * 需要「勿扰模式」访问权限；无权限时仅提示一次，不强制打断。
     */
    private fun applyNotificationFilter() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (!nm.isNotificationPolicyAccessGranted) {
            // 首次提示引导用户开启（不自动跳转系统设置，避免打断共享）
            if (!audioPrefs.getBoolean("dnd_prompted", false)) {
                audioPrefs.edit().putBoolean("dnd_prompted", true).apply()
                Toast.makeText(
                    this,
                    "共享期间通知预览会被对方看到。可在「设置 → 应用 → 屏幕共享 → 通知访问」开启免打扰权限自动屏蔽",
                    Toast.LENGTH_LONG
                ).show()
            }
            return
        }
        try {
            nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
            dndApplied = true
        } catch (t: Throwable) {
            Log.w(TAG, "设置免打扰失败: ${t.message}")
        }
    }

    /** 结束共享：恢复通知正常显示 */
    private fun restoreNotificationFilter() {
        if (!dndApplied) return
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm?.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        } catch (t: Throwable) {
            Log.w(TAG, "恢复通知模式失败: ${t.message}")
        }
        dndApplied = false
    }

    /** 连接彻底失败（重连超限）：结合本机候选情况给出可操作诊断，避免用户无从下手 */
    override fun onConnectionFailed() {
        runOnUiThread {
            // 标记彻底终止：随后触发的 onDisconnected 走结束会议流程
            connectionTerminated = true
            reconnecting = false
            clearMeetingResume()
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
            if (isMarkMode && !isHost && event.pointerCount == 1) {
                handleMarkTouch(event, renderer)
                true
            } else if (isControlMode && !isHost && event.pointerCount == 1) {
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
                    // v1.298: host 旋转不再强制改变 viewer 方向，全屏跟随用户手机物理姿态
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
    private var cameraPipLastFrameAt = 0L
    private var cameraPipFrameCheck: Runnable? = null

    // 视频通话 PIP 小窗拖拽状态（未放大时可在屏幕内任意移动）
    private var pipDragStartX = 0f
    private var pipDragStartY = 0f
    private var pipDragStartLeft = 0
    private var pipDragStartTop = 0
    private var pipDragMoved = false
    private var pipContainerW = 0
    private var pipContainerH = 0

    /**
     * 视频通话 PIP：把对方的摄像头人脸画面渲染到右上角小窗。
     * host 与 viewer 通用（onRemoteCameraTrack / onViewerCameraTrack 都走这里）。
     */
    private fun setupCameraPip(track: VideoTrack) {
        try {
            // onAddTrack 与 onTrack 会对同一轨各回调一次（旧/新 API 双投递），
            // renderer 已建成且 track 未变时跳过，避免重复重建 renderer 闪烁
            if (cameraPipTrack === track && cameraPipRenderer != null) return
            val eglCtx = eglBaseContext
            if (eglCtx == null) {
                Log.e(TAG, "setupCameraPip: eglBaseContext 未就绪，跳过摄像头 PIP 渲染")
                return
            }
            // 对方画面首次到达（接通）提示音，重连/重挂载不重复响
            if (cameraPipTrack == null && videoCallOn) {
                playTone(android.media.ToneGenerator.TONE_PROP_BEEP2)
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
            renderer.setMirror(cameraMirrorOn)
            renderer.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            binding.flCameraPip.addView(renderer, 0)
            cameraPipRenderer = renderer
            // 亮度遮罩跟随当前调节等级（新 renderer 挂载后遮罩层仍保留在上层）
            applyBrightness()
            binding.tvCameraPipHint.visibility = View.GONE
            
            binding.flCameraPip.visibility = if (cameraPipHidden) View.GONE else View.VISIBLE
              binding.flCameraPip.setOnClickListener { onCameraPipClicked() }
              installCameraPipTouch()
            // 用户已放大小窗时保持放大态（重连/重挂载不丢状态）
            if (cameraPipMaximized) {
                applyCameraPipMaximized(restore = false)
            }

            cameraPipSink = VideoSink { frame ->
                cameraPipLastFrameAt = SystemClock.elapsedRealtime()
                // VideoSink 在 WebRTC 渲染线程回调，UI 操作必须切主线程；
                // 仅在提示仍可见时投递（正常播放中每帧回调，避免每帧 30 次无效 post）
                if (binding.tvCameraPipHint.visibility == View.VISIBLE) {
                    runOnUiThread { binding.tvCameraPipHint.visibility = View.GONE }
                }
                renderer.onFrame(frame)
            }
            track.addSink(cameraPipSink!!)
            scheduleCameraPipFrameCheck()
        } catch (t: Throwable) {
            Log.e(TAG, "setupCameraPip 异常: ${t.message}")
        }
    }

    /** 清理视频通话 PIP 小窗（断开/重置时调用） */
    private fun releaseCameraPip() {
        cancelCameraPipFrameCheck()
        cameraPipLastFrameAt = 0L
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
        // 复位放大/隐藏状态：否则关闭视频后残留 true，重连自动全屏放大、PIP 视图卡最后一帧
        cameraPipMaximized = false
        cameraPipHidden = false
        pipDragMoved = false
        binding.flCameraPip.visibility = View.GONE
        binding.tvCameraPipHint.text = "对方摄像头"
        binding.tvCameraPipHint.visibility = View.VISIBLE
        binding.vNetDot.visibility = View.GONE
    }

    /**
     * 摄像头 PIP 无帧/冻帧检测：挂上轨道后 4s 仍无帧显示「等待画面」，
     * 8s 无帧升级为「网络不佳」；帧一到立即清除提示。
     */
    private fun scheduleCameraPipFrameCheck() {
        cancelCameraPipFrameCheck()
        cameraPipLastFrameAt = SystemClock.elapsedRealtime()
        val runnable = object : Runnable {
            override fun run() {
                if (cameraPipTrack == null) {
                    // track 已释放：隐藏残留提示，避免「网络不佳」文字停留在屏幕上
                    binding.tvCameraPipHint.visibility = View.GONE
                    return
                }
                val since = SystemClock.elapsedRealtime() - cameraPipLastFrameAt
                when {
                    since < 4000 -> {
                        binding.tvCameraPipHint.text = "对方摄像头"
                        binding.tvCameraPipHint.visibility = View.GONE
                    }
                    since < 8000 -> {
                        binding.tvCameraPipHint.text = "正在等待对方画面…"
                        binding.tvCameraPipHint.visibility = View.VISIBLE
                    }
                    else -> {
                        binding.tvCameraPipHint.text = "网络不佳，画面可能中断"
                        binding.tvCameraPipHint.visibility = View.VISIBLE
                    }
                }
                binding.tvCameraPipHint.postDelayed(this, 1000)
            }
        }
        cameraPipFrameCheck = runnable
        binding.tvCameraPipHint.postDelayed(runnable, 4000)
    }

    /** 取消摄像头 PIP 冻帧检测，避免 Activity 销毁后残留回调 */
    private fun cancelCameraPipFrameCheck() {
        cameraPipFrameCheck?.let { binding.tvCameraPipHint.removeCallbacks(it) }
        cameraPipFrameCheck = null
    }

    // ======================== 视频通话本端预览 ========================

    private var localPreviewTrack: VideoTrack? = null
    private var localPreviewSink: VideoSink? = null
    private var localPreviewRenderer: SurfaceViewRenderer? = null

    /**
     * 本端摄像头预览：把本端 cameraVideoTrack 渲染到左下角小窗。
     * 本地渲染同一轨道，不涉及网络/协商；开启视频通话时默认显示。
     */
    private fun setupLocalPreview() {
        val p = peer ?: return
        val track = p.cameraVideoTrack() ?: return
        try {
            val eglCtx = eglBaseContext
            if (eglCtx == null) return
            releaseLocalPreview()
            localPreviewTrack = track
            val renderer = SurfaceViewRenderer(this)
            renderer.init(eglCtx, null)
            renderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
            renderer.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            // 前置摄像头本端预览默认镜像（与习惯一致），后置不镜像
            val front = p.isUsingFrontCamera()
            renderer.setMirror(front)
            binding.flLocalPreview.addView(renderer, 0)
            localPreviewRenderer = renderer
            binding.tvLocalPreviewHint.visibility = View.GONE
            binding.flLocalPreview.visibility = View.VISIBLE
            localPreviewSink = VideoSink { frame -> renderer.onFrame(frame) }
            track.addSink(localPreviewSink!!)
        } catch (t: Throwable) {
            Log.e(TAG, "setupLocalPreview 异常: ${t.message}")
        }
    }

    /** 清理本端预览 */
    private fun releaseLocalPreview() {
        localPreviewSink?.let { localPreviewTrack?.removeSink(it) }
        localPreviewSink = null
        localPreviewTrack = null
        localPreviewRenderer?.let { r ->
            if (r.parent == binding.flLocalPreview) {
                binding.flLocalPreview.removeView(r)
            }
            r.release()
        }
        localPreviewRenderer = null
        binding.flLocalPreview.visibility = View.GONE
        binding.tvLocalPreviewHint.visibility = View.VISIBLE
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

    /**
     * 安装视频通话 PIP 小窗拖拽：未放大时可按住在小窗容器内任意移动。
     * 放大态不拖拽；短按仍走 onCameraPipClicked 放大/恢复。
     */
    private fun installCameraPipTouch() {
        binding.flCameraPip.setOnTouchListener { v, event ->
            if (cameraPipMaximized) return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pipDragStartX = event.rawX
                    pipDragStartY = event.rawY
                    val lp = binding.flCameraPip.layoutParams as? FrameLayout.LayoutParams
                    pipDragStartLeft = lp?.leftMargin ?: 0
                    pipDragStartTop = lp?.topMargin ?: 0
                    pipDragMoved = false
                    val parent = binding.flCameraPip.parent as? View
                    pipContainerW = parent?.width ?: 0
                    pipContainerH = parent?.height ?: 0
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - pipDragStartX
                    val dy = event.rawY - pipDragStartY
                    if (!pipDragMoved && (kotlin.math.abs(dx) > 8f || kotlin.math.abs(dy) > 8f)) {
                        pipDragMoved = true
                    }
                    if (pipDragMoved) {
                        val lp = binding.flCameraPip.layoutParams as FrameLayout.LayoutParams
                        val maxX = (pipContainerW - binding.flCameraPip.width).coerceAtLeast(0)
                        val maxY = (pipContainerH - binding.flCameraPip.height).coerceAtLeast(0)
                        lp.gravity = android.view.Gravity.TOP or android.view.Gravity.START
                        lp.leftMargin = (pipDragStartLeft + dx.toInt()).coerceIn(0, maxX)
                        lp.topMargin = (pipDragStartTop + dy.toInt()).coerceIn(0, maxY)
                        lp.rightMargin = 0
                        binding.flCameraPip.layoutParams = lp
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val moved = pipDragMoved
                    pipDragMoved = false
                    moved
                }
                else -> false
            }
        }
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
        // 摄像头小窗若处于手动放大态，先收起，避免两个 MATCH_PARENT 层叠导致画面互相覆盖/触摸串层
        if (cameraPipMaximized) {
            applyCameraPipMaximized(restore = true)
        }
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

         // v1.298: 全屏方向跟随用户手机物理姿态，不再按 host 帧方向强转——
         // 折叠屏 host 展开态画面横宽（如 3000x2078）会强行把竖屏 viewer 转横屏，
         // 违背用户持机姿势。FULL_SENSOR 下用户自己转手机即可切横屏
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
            if (isMarkMode && !isHost && event.pointerCount == 1) {
                handleMarkTouch(event, renderer)
                true
            } else if (isControlMode && !isHost && event.pointerCount == 1) {
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
                    // v1.298: host 旋转不再强制改变 viewer 方向，全屏跟随用户手机物理姿态
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
            // v1.298: 目标 llMorePanel 是 LinearLayout——LinearLayout 未重写 checkLayoutParams，
            // addView 会把传入的 FrameLayout.LayoutParams 原样设给子 view，measure 时
            // 强转成 LinearLayout.LayoutParams 崩溃（真机点"更多"必现，已复现 4 次）。
            // LinearLayout.LayoutParams 同样支持 gravity，语义为子 view 在父容器中的对齐
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.gravity = android.view.Gravity.TOP or android.view.Gravity.START
            lp.setMargins(0, 0, 0, 0)
            moveView(binding.llVideoBtns, binding.llMorePanel, lp)

            // 完整/铺满按钮移回更多面板（中列）
            binding.btnAspectToggle.visibility = View.VISIBLE
            val rlp = LinearLayout.LayoutParams(
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
            layoutParams = linear(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48))
            textSize = 12f
        }
        binding.btnCtrlText.apply {
            layoutParams = linear(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48))
            textSize = 12f
        }
        binding.btnCtrlBack.apply { layoutParams = linear(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)); textSize = 12f }
        binding.btnCtrlHome.apply { layoutParams = linear(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)); textSize = 12f }
        binding.btnCtrlRecents.apply { layoutParams = linear(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)); textSize = 12f }
        binding.btnAspectToggle.apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48))
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
                        // 诊断：真实连接路径（host/srflx/relay），附加到状态条便于现场观察
                        val selPath = json.optString("path", "")
                        val pathType = json.optString("pathType", "")
                        // UI 显示时脱敏 IP，诊断上报保留完整路径
                        val maskedPath = if (selPath.isNotBlank()) maskIp(selPath) else ""
                        val fullTextWithPath = if (maskedPath.isNotBlank()) fullText + " | 路径:${maskedPath.take(60)}" else fullText
                        // 诊断自动上报：软编/CPU瓶颈/高丢包/高延迟时上报一次，值变化才重报（去重防刷屏）
                        if (isHostView) {
                            val impl = json.optString("encImpl", "")
                            val limit = json.optString("qualityLimit", "")
                            val rttMs = json.optInt("rtt", 0)
                            val lossPct = json.optDouble("outLossPct", -1.0)
                            val isSoftEnc = impl.contains("SW", true) || impl.contains("OpenH264", true) || impl.contains("Software", true)
                            val anomaly = isSoftEnc || limit == "cpu" || lossPct >= 3.0 || rttMs >= 500
                            if (anomaly) {
                                // 去重只取路径类型组合（host→relay），不含 IP，避免 NAT 重绑定导致重复上报
                                val sig = "$impl|$limit|$lossPct|$rttMs|$isHostView|$pathType"
                                if (sig != lastDiagSig) {
                                    lastDiagSig = sig
                                    reportDiagnostic(
                                        "impl=$impl limit=$limit outLoss=${"%.1f".format(lossPct)}% rtt=${rttMs}ms " +
                                            "quality=${peer?.calculateQuality(if (lossPct >= 0) lossPct else 0.0, rttMs) ?: 0} " +
                                            "outFps=${json.optInt("outFps", 0)} inFps=${json.optInt("inFps", 0)} " +
                                            "res=${json.optInt("outW", 0)}x${json.optInt("outH", 0)} " +
                                            "outBytes=${json.optLong("outBytes", 0)} path=$selPath"
                                    )
                                }
                            }
                        }
                        // UI 更新回主线程
                        binding.root.post {
                            if (!isFullscreen) return@post
                            binding.tvFullscreenStats.setTextColor(if (warn) 0xFFFF5252.toInt() else 0xFFE85D8D.toInt())
                            binding.tvFullscreenStats.text = fullTextWithPath
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
        lastAdaptOutBytes = 0L
        lastAdaptOutMs = 0L
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
                            // v1.241: 实际发送码率（拥塞控制收敛后的真实链路带宽），
                            // 供弱网自适应把采集档位压到与蜂窝等受限链路匹配，保帧率优先
                            val outBytes = json.optLong("outBytes", 0)
                            val nowMs = System.currentTimeMillis()
                            val actualBps = if (lastAdaptOutBytes > 0 && outBytes > lastAdaptOutBytes && lastAdaptOutMs > 0) {
                                ((outBytes - lastAdaptOutBytes) * 8000.0 / (nowMs - lastAdaptOutMs)).toInt()
                            } else 0
                            if (outBytes > 0) {
                                lastAdaptOutBytes = outBytes
                                lastAdaptOutMs = nowMs
                            }
                            if (vid > 0) {
                                peer?.adaptViewerNetwork(vid, outLossPct, outSent, outLost, rttMs, actualBps, qualityLimit, outFps)
                            } else {
                                peer?.adaptToNetwork(outLossPct, outSent, outLost, rttMs, actualBps, qualityLimit, outFps)
                            }
                            peer?.adaptToEncoderLoad(outFps, qualityLimit)
                        }
                    } catch (t: Throwable) {
                        android.util.Log.w(TAG, "自适应轮询异常: ${t.message}")
                    }
                }
                // 会话已结束（peer 已释放）时停止自调度并退出工作线程；
                // 否则 Activity 销毁后该循环仍每 1.5s 空转，HandlerThread 永久泄漏（B8）
                if (peer == null) {
                    runOnUiThread { stopAdaptiveLoop() }
                    return
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

    /** 观看端：周期显示网络延迟与接收帧率（量化画面延迟，弱网时给出提示）；同步做掉帧率检测与上报 */
    private fun startViewerStatsLoop() {
        stopViewerStatsLoop()
        // 掉帧检测基线与状态复位
        lastInDropped = 0L
        lastInDecoded = 0L
        stallStrikes = 0
        stallRecoverStrikes = 0
        stallReported = false
        lastDropPct = 0.0
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
                        val selPath = json.optString("path", "")
                        // v1.252: 观看端延迟构成——抖动缓冲/解码耗时换算成均值 ms（累计值除以累计帧数）
                        val jbDelay = json.optDouble("jbDelay", 0.0)
                        val jbEmitted = json.optDouble("jbEmitted", 0.0)
                        val jbMinDelay = json.optDouble("jbMinDelay", 0.0)
                        val decodeTime = json.optDouble("decodeTime", 0.0)
                        val decFrames = json.optDouble("decFrames", 0.0)
                        val freezeCount = json.optLong("freezeCount", 0L)
                        val freezeDuration = json.optDouble("freezeDuration", 0.0)
                        val jbMs = if (jbEmitted > 0) jbDelay / jbEmitted * 1000.0 else -1.0
                        val jbMinMs = if (jbEmitted > 0) jbMinDelay / jbEmitted * 1000.0 else -1.0
                        val decMs = if (decFrames > 0) decodeTime / decFrames * 1000.0 else -1.0
                        // ---- 掉帧率检测（v1.223）：窗口内 dropped/(dropped+decoded) ----
                        val dropped = json.optLong("inDropped", 0L)
                        val decoded = json.optLong("inDecoded", 0L)
                        val dDropped = (dropped - lastInDropped).coerceAtLeast(0L)
                        val dDecoded = (decoded - lastInDecoded).coerceAtLeast(0L)
                        lastInDropped = dropped
                        lastInDecoded = decoded
                        val denom = dDropped + dDecoded
                        val dropRatio = if (denom > 0) dDropped.toDouble() / denom else 0.0
                        lastDropPct = dropRatio * 100.0
                        val stalling = denom > 0 && dropRatio >= 0.2
                        if (!stallReported) {
                            if (stalling) {
                                stallStrikes++
                                stallRecoverStrikes = 0
                                if (stallStrikes >= 2) {
                                    // 连续 2 个窗口（约 4s）掉帧率 >=20%：上报共享方立即降档
                                    stallStrikes = 0
                                    stallReported = true
                                    peer?.sendControl("""{"type":"stream-stall","value":1}""")
                                    AppLogger.capture("观看端持续掉帧 ${"%.0f".format(lastDropPct)}%: 已通知共享方降档")
                                }
                            } else {
                                stallStrikes = 0
                            }
                        } else {
                            if (stalling) {
                                stallRecoverStrikes = 0
                            } else if (dropRatio < 0.05) {
                                stallRecoverStrikes++
                                if (stallRecoverStrikes >= 3) {
                                    // 连续 3 个窗口（约 6s）掉帧率 <5%：解除上报，共享方恢复评估
                                    stallRecoverStrikes = 0
                                    stallReported = false
                                    peer?.sendControl("""{"type":"stream-stall","value":0}""")
                                    AppLogger.capture("观看端掉帧恢复: 解除降档反馈")
                                }
                            } else {
                                stallRecoverStrikes = 0
                            }
                        }
                        // v1.296: 抖动缓冲棘轮排空——网络已恢复（RTT 低、无丢帧）但缓冲持续
                        // >500ms 时，请求共享方关键帧让接收端丢弃待解码旧帧重新同步。
                        // 真机实测 RTT 回到 5-8ms 后缓冲仍 900ms+ 不回落（jitter buffer
                        // 把尖峰延迟当新目标主动维持），屏幕静止时冻结 8 分钟，观感持续延迟。
                        // 仅在帧率正常时请求（0fps 时关键帧也排不空，且 host 侧已有 linkRecovered）
                        // v1.297: 阈值 500→400——真机实测峰值 481ms 未命中 500 阈值，
                        // 缓冲在 420-480ms 停留 18 秒才缓慢回落（36 秒仅回落 118ms）
                        if (jbMs > 400 && rtt in 1..200 && fps >= 5) {
                            jbHighStrikes++
                            if (jbHighStrikes >= 3) {
                                val now = System.currentTimeMillis()
                                if (now - lastKeyFrameReqMs > 30_000) {
                                    lastKeyFrameReqMs = now
                                    peer?.sendControl("""{"type":"keyframe-request"}""")
                                    AppLogger.capture("缓冲${"%.0f".format(jbMs)}ms RTT=${rtt}ms 帧率${fps} 已请求关键帧排空")
                                }
                                jbHighStrikes = 0
                            }
                        } else {
                            jbHighStrikes = 0
                        }
                        val rttText = if (rtt > 0) "${rtt}ms" else "--"
                        val hint = when {
                            rtt > 300 -> " ⚠️延迟高"
                            rtt > 150 -> " ⚠️延迟偏高"
                            else -> ""
                        }
                        // 掉帧率明显时展示，便于用户理解卡顿来源
                        val dropText = if (lastDropPct >= 5.0) " · 掉帧${"%.0f".format(lastDropPct)}%" else ""
                        // v1.252: 抖动缓冲偏高时在浮层提示，便于区分"网络 RTT 正常但画面仍延迟"
                        val jbTextUi = if (jbMs >= 80.0) " · 缓冲${"%.0f".format(jbMs)}ms" else ""
                        val maskedPath = if (selPath.isNotBlank()) maskIp(selPath) else ""
                        val pathText = if (maskedPath.isNotBlank()) " | 路径:${maskedPath.take(50)}" else ""
                        // v1.249: 观看方统计落盘（不依赖全屏），导出日志可对照两端 RTT/收帧率/掉帧
                        // v1.252: 追加抖动缓冲/解码耗时/管线延迟/冻结次数，定位"RTT 正常仍延迟"的来源
                        val jbText = if (jbMs >= 0.0) "${"%.0f".format(jbMs)}ms(最小${"%.0f".format(jbMinMs)}ms)" else "-"
                        val decText = if (decMs >= 0.0) "${"%.0f".format(decMs)}ms" else "-"
                        val pipeMs = if (jbMs >= 0.0 && decMs >= 0.0) jbMs + decMs else -1.0
                        val pipeText = if (pipeMs >= 0.0) "${"%.0f".format(pipeMs)}ms" else "-"
                        AppLogger.network(
                            "viewer 收帧${fps}fps ${w}x$h rtt=${rtt}ms 掉帧${"%.0f".format(lastDropPct)}% " +
                                 "缓冲=$jbText 解码=$decText 管线=$pipeText 冻结=${freezeCount}(${"%.1f".format(freezeDuration)}s) path=${selPath.ifEmpty { "-" }}"
                        )
                        val text = "延迟 $rttText · ${fps}fps${if (w > 0) " · ${w}x$h" else ""}$dropText$jbTextUi$pathText$hint"
                        runOnUiThread {
                            if (!isFinishing && !isDestroyed && peer != null) {
                                binding.tvScanResult.text = text
                                binding.tvScanResult.visibility = View.VISIBLE
                                updateNetDot(rtt, lastDropPct)
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

    /** 更新视频通话网络状态灯：正常绿，弱网（高延迟或掉帧）琥珀 */
    private fun updateNetDot(rtt: Int, dropPct: Double) {
        if (cameraPipTrack == null || !videoCallOn) {
            binding.vNetDot.visibility = View.GONE
            return
        }
        binding.vNetDot.visibility = View.VISIBLE
        val weak = rtt > 300 || dropPct >= 5.0
        binding.vNetDot.setBackgroundColor(if (weak) 0xFFD97706.toInt() else 0xFF2F9E77.toInt())
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
    private fun saveMeetingResume(action: String, code: String, token: String = "") {
        getSharedPreferences("meeting_resume", MODE_PRIVATE)
            .edit()
            .putString("action", action)
            .putString("code", code)
            // 每次显式写入：create 时为空（签发后用 saveMeetingResumeToken 补），避免残留上次口令
            .putString("token", token)
            .putLong("ts", System.currentTimeMillis())
            .apply()
        // 同步记入最近会议历史（连接页展示，点击快速复用）
        MeetingActivity.recordMeetingHistory(this, action, code)
    }

    /** 服务器签发/确认房间口令后补写恢复记录，断线重连与自动重连复用 */
    private fun saveMeetingResumeToken(token: String) {
        getSharedPreferences("meeting_resume", MODE_PRIVATE).edit()
            .putString("token", token).apply()
    }

    /** 会议已结束/失败：清除自动重连记录 */
    private fun clearMeetingResume() {
        getSharedPreferences("meeting_resume", MODE_PRIVATE).edit().clear().apply()
    }


    private fun leaveMeeting(message: String) {
        leavingMeeting = true
        connectionTerminated = true
        restoreNotificationFilter()
        clearMeetingResume()
        stopToolbarAutoHide()
        cleanupPeer()
        resetUI()
        updateUI(message)
        if (isFinishing || isDestroyed) return
        restoreSystemBars()
        startActivity(Intent(this, LiquidHomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }

    /** 会议异常结束：清理并返回连接页 */
    private fun handleMeetingFailure() {
        leavingMeeting = true
        connectionTerminated = true
        restoreNotificationFilter()
        clearMeetingResume()
        cleanupPeer()
        resetUI()
        if (isFinishing || isDestroyed) return
        restoreSystemBars()
        startActivity(Intent(this, LiquidHomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }

    // ======================== 悬浮工具条 ========================

    /** 更多面板展开/收起 */
    private fun toggleMorePanel() {
        val show = binding.llMorePanel.visibility != View.VISIBLE
        AppLogger.app("更多面板 ${if (show) "展开" else "收起"}")
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
        // v1.263: 工具条与面板按钮统一按压回弹反馈
        PressEffect.bindChildren(binding.llToolbar)
        PressEffect.bindChildren(binding.llMorePanel)
        // v1.264: 深色玻璃浮层——视频背景上深色文字不可读，统一换成深色玻璃 + 浅色文字
        applyDarkGlassStyle()
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

    /** 共享页深色玻璃浮层：状态胶囊 / 工具条 / 面板按钮统一换深色玻璃 + 浅色文字 */
    private var darkGlass = false

    private fun applyDarkGlassStyle() {
        darkGlass = true
        binding.llStatus.setBackgroundResource(R.drawable.bg_status_pill_dark)
        binding.llToolbar.setBackgroundResource(R.drawable.bg_toolbar_dark)
        swapPanelBtnDark(binding.llMorePanel, toDark = true)
        listOf(binding.llStatus, binding.llToolbar, binding.llMorePanel).forEach { c ->
            if (c is ViewGroup) applyLightText(c)
        }
        // 状态按钮（麦克风/视频）颜色由 updateVideoCallButton 接管，这里同步一次
        updateVideoCallButton()
    }

    /** 还原时保持深色玻璃风格（v1.286 起内屏统一液态深色，不再回退浅色） */
    private fun restoreGlassStyle() {
        darkGlass = true
        binding.llStatus.setBackgroundResource(R.drawable.bg_status_pill_dark)
        binding.llToolbar.setBackgroundResource(R.drawable.bg_toolbar_dark)
        swapPanelBtnDark(binding.llMorePanel, toDark = true)
        listOf(binding.llStatus, binding.llToolbar, binding.llMorePanel).forEach { c ->
            if (c is ViewGroup) applyLightText(c)
        }
        updateVideoCallButton()
    }

    /** 面板内所有 bg_panel_btn 背景与深色版互换（按钮 + llCtrlKeys 各自带背景） */
    private fun swapPanelBtnDark(v: View, toDark: Boolean) {
        if (v !is ViewGroup) return
        val light = ContextCompat.getDrawable(v.context, R.drawable.bg_panel_btn)
        val dark = ContextCompat.getDrawable(v.context, R.drawable.bg_panel_btn_dark)
        for (i in 0 until v.childCount) {
            val c = v.getChildAt(i) ?: continue
            val state = c.background?.constantState
            if (state != null && (state == light?.constantState || state == dark?.constantState)) {
                c.setBackgroundResource(if (toDark) R.drawable.bg_panel_btn_dark else R.drawable.bg_panel_btn)
            }
            swapPanelBtnDark(c, toDark)
        }
    }

    private fun applyLightText(vg: ViewGroup) {
        for (i in 0 until vg.childCount) {
            val child = vg.getChildAt(i) ?: continue
            if (child is TextView) {
                if (child.getTag(R.id.tag_text_dark) == null) {
                    child.setTag(R.id.tag_text_dark, child.currentTextColor)
                }
                // v1.265: 纯白 + 深色描边阴影，任何明暗视频背景上都清晰
                child.setTextColor(0xFFFFFFFF.toInt())
                child.setShadowLayer(3f, 0f, 1f, 0xCC000000.toInt())
            }
            if (child is ViewGroup) applyLightText(child)
        }
    }

    private fun restoreDarkText(vg: ViewGroup) {
        for (i in 0 until vg.childCount) {
            val child = vg.getChildAt(i) ?: continue
            if (child is TextView) {
                (child.getTag(R.id.tag_text_dark) as? Int)?.let { child.setTextColor(it) }
                child.setShadowLayer(0f, 0f, 0f, 0)
            }
            if (child is ViewGroup) restoreDarkText(child)
        }
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
            AppLogger.app("[CAPTURE] 授权成功，启动共享")
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
            AppLogger.app("[CAPTURE] 授权被拒绝/取消 resultCode=$resultCode")
            Toast.makeText(this, "未授权屏幕共享，对方将无法看到画面", Toast.LENGTH_LONG).show()
            // 清除自动重连记录：否则 meeting_resume 残留会导致下次打开又自动连接（用户反馈"取消不了"）
            clearMeetingResume()
            leavingMeeting = true
            cleanupPeer()
            resetUI()
            updateUI("❌ 未授权屏幕共享")
            if (!isFinishing && !isDestroyed) {
                restoreSystemBars()
                startActivity(Intent(this, LiquidHomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
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
        // v1.263: 状态文案切换平滑过渡——淡出再淡入，重连中/已恢复不再生硬跳变
        val tv = binding.tvStatus
        // 状态机必须落盘：以前只用 Log.d，上传日志看不到会议流程，卡在哪一步全靠猜
        AppLogger.app("[UI] $status")
        if (tv.text.toString() == status) {
            return
        }
        if (tv.visibility != View.VISIBLE || tv.alpha < 1f) {
            tv.text = status
            tv.animate().cancel()
            tv.alpha = 1f
            return
        }
        tv.animate().alpha(0f).setDuration(140).withEndAction {
            tv.text = status
            tv.animate().alpha(1f).setDuration(200).start()
        }.start()
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
        releaseLocalPreview()
        restoreGlassStyle()
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
        binding.btnCtrlPoke.visibility = View.GONE
        binding.btnCtrlMark.visibility = View.GONE
        binding.llCtrlStatus.visibility = View.GONE
        isControlMode = false
        isMarkMode = false
        ctrlDownSent = false
        currentFps = 48
        micMuted = false
        videoCallOn = false
        binding.btnCamera.visibility = View.GONE
        binding.btnMic.visibility = View.GONE
        // 视频通话增强：停止对讲轮询、隐藏增强控件、移除屏幕常亮
        setTalkPolling(false)
        // v1.259: 同时停止闪避循环（UI 重置路径兜底）
        stopDuckLoop()
        // 会话级标志重置：screenCaptureReady 只在 startSessionCore 置 true，
        // 不重置会导致下一场会话在采集未完成时提前发无画面的 Offer（B5）
        screenCaptureReady = false
        // 呼吸灯动画随会话结束停止，避免 INFINITE ValueAnimator 泄漏（B6）
        stopStatusBreathing()
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
        candCountHost.set(0)
        candCountSrflx.set(0)
        candCountRelay.set(0)
        candCountOther.set(0)
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
        // v1.259: 兜底停止闪避循环，避免 Activity 销毁后主线程 Runnable 残留
        stopDuckLoop()
        // v1.266: 兜底停止对讲轮询线程，避免 HandlerThread 在 Activity 销毁后残留（B7）
        setTalkPolling(false)
        // v1.262: 兜底恢复通知模式，避免 Activity 销毁后免打扰残留
        restoreNotificationFilter()
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
        // 停止工具条自动隐藏与状态呼吸动画，避免销毁后 Runnable/Animator 残留
        stopToolbarAutoHide()
        stopStatusBreathing()
        // 清理心形迸发/远程标记的残留视图（其淡出回调挂在这些子 View 上，随容器一并释放）
        binding.flHeartBurst.removeAllViews()
        // 释放诊断上报线程池与连接池（每次 Activity 重建都会新建，不 shutdown 会累积泄漏）
        try {
            diagExecutor.shutdownNow()
            diagClient.connectionPool.evictAll()
            diagClient.dispatcher.executorService.shutdown()
        } catch (_: Throwable) {}
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
        AppLogger.app("配置变更 smallestSw=${newConfig.smallestScreenWidthDp} sw=${newConfig.screenWidthDp} orient=${newConfig.orientation}")
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
        // 折叠屏开合后系统栏 inset 可能变化，重新应用底部边距避免工具条/面板被导航栏遮挡
        binding.root.requestApplyInsets()
    }
}
