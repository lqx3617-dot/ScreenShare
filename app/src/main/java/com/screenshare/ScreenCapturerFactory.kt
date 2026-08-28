package com.screenshare
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.util.Log
import org.webrtc.Logging
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.VideoCapturer
/**
 * 屏幕采集工厂。
 *
 * 核心流程：
 * 1. 通过 MediaProjectionManager 获取屏幕采集权限（系统会弹窗让用户确认）
 * 2. 拿到 MediaProjection 后喂给 WebRTC 的 ScreenCapturerAndroid
 * 3. WebRTC 内部用 MediaCodec 硬编码成 H.264 视频流
 *
 * 关键点：MediaProjection 权限是一次性的，App 被杀后需要重新授权。
 */
object ScreenCapturerFactory {
    private const val TAG = "ScreenCapturer"
    const val REQUEST_MEDIA_PROJECTION = 1001
    // 暂存 MediaProjection 权限结果。使用原子引用把 data 与 resultCode 合并为一份状态，
    // 避免两个 @Volatile 分别写入时出现 data 已更新、resultCode 仍旧的半写窗口。
    private data class ProjectionPermission(val data: Intent?, val resultCode: Int)
    private val pendingPermission = java.util.concurrent.atomic.AtomicReference(
        ProjectionPermission(null, Activity.RESULT_CANCELED)
    )

    /**
     * 启用 WebRTC native 日志（默认不打进 logcat，必须显式打开才能诊断）
     * 诊断期间临时用 LS_INFO 查看 ICE 候选与连接状态；定位问题后可改回 LS_WARNING。
     */
    fun enableDiagnosticLogging() {
        try {
            // 正式包降级为 LS_WARNING：INFO 会在采集/ICE 高频路径刷屏并拖慢 logcat。
            // 需要深入诊断时再临时改回 LS_INFO。
            Logging.enableLogToDebugOutput(Logging.Severity.LS_WARNING)
            Log.d(TAG, "WebRTC 日志已启用 (LS_WARNING 正式模式)")
        } catch (t: Throwable) {
            Log.w(TAG, "启用 WebRTC 日志失败: ${t.message}")
        }
    }

    /**
     * 请求屏幕采集权限（会弹系统确认框）
     */
    fun requestPermission(activity: Activity) {
        val projectionManager = activity.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        activity.startActivityForResult(
            projectionManager.createScreenCaptureIntent(),
            REQUEST_MEDIA_PROJECTION
        )
    }

    /**
     * 处理权限请求结果（在 Activity.onActivityResult 中调用）
     */
    fun handleActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode == REQUEST_MEDIA_PROJECTION) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                pendingPermission.set(ProjectionPermission(data, resultCode))
                Log.d(TAG, "MediaProjection 权限已获取 (resultCode=$resultCode)")
                return true
            } else {
                pendingPermission.set(ProjectionPermission(null, resultCode))
                Log.w(TAG, "MediaProjection 权限被拒绝 (resultCode=$resultCode, data=${data})")
                return false
            }
        }
        return false
    }

    /**
     * 创建屏幕采集器（WebRTC 的 VideoCapturer）
     * 必须在调用 requestPermission 且系统弹窗用户点了"允许"之后才能调用
     *
     * 注意：这里只创建 ScreenCapturerAndroid，不预先 getMediaProjection。
     * MediaProjection 由 ScreenCapturerAndroid.startCapture 内部获取（全应用唯一一次），
     * 音频内录等其他功能通过 WebRTCPeer.mediaProjection() 复用同一个实例，
     * 避免同一投影 token 二次 getMediaProjection 导致部分设备 createVirtualDisplay 卡死。
     */
    fun createScreenCapturer(@Suppress("UNUSED_PARAMETER") context: Context): VideoCapturer? {
        val permission = pendingPermission.get()
        val data = permission.data ?: run {
            Log.e(TAG, "没有 MediaProjection 权限数据，先调用 requestPermission")
            return null
        }
        if (permission.resultCode != Activity.RESULT_OK) {
            Log.e(TAG, "MediaProjection 结果码不是 RESULT_OK: ${permission.resultCode}")
            return null
        }
        // 新版 WebRTC 的 ScreenCapturerAndroid
        // 构造参数是 MediaProjection 权限 Intent，内部自己去 getMediaProjection。
        return try {
            ScreenCapturerAndroid(data, object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.d(TAG, "MediaProjection 被系统停止")
                }
            }).also {
                Log.d(TAG, "ScreenCapturerAndroid 创建成功")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "ScreenCapturerAndroid 创建异常: ${t.message}", t)
            null
        }
    }

    /**
     * 检查是否已获得权限（用 resultCode 判定，比 data 缓存更可靠）
     */
    fun hasPermission(): Boolean {
        val permission = pendingPermission.get()
        return permission.data != null && permission.resultCode == Activity.RESULT_OK
    }

    /**
     * 清除权限缓存（断开后调用）
     */
    fun clearPermission() {
        pendingPermission.set(ProjectionPermission(null, Activity.RESULT_CANCELED))
    }
}
