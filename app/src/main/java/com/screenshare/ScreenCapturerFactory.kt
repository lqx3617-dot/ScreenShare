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
    // 暂存 MediaProjection 权限结果
    @Volatile private var pendingProjectionData: Intent? = null
    @Volatile private var pendingResultCode: Int = Activity.RESULT_CANCELED

    /**
     * 启用 WebRTC native 日志（默认不打进 logcat，必须显式打开才能诊断）
     */
    fun enableDiagnosticLogging() {
        try {
            Logging.enableLogToDebugOutput(Logging.Severity.LS_INFO)
            Log.d(TAG, "WebRTC 日志已启用 (LS_INFO)")
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
                pendingProjectionData = data
                pendingResultCode = resultCode
                Log.d(TAG, "MediaProjection 权限已获取 (resultCode=$resultCode, data.uri=${data.data})")
                return true
            } else {
                pendingProjectionData = null
                pendingResultCode = resultCode
                Log.w(TAG, "MediaProjection 权限被拒绝 (resultCode=$resultCode, data=${data})")
                return false
            }
        }
        return false
    }

    /**
     * 创建屏幕采集器（WebRTC 的 VideoCapturer）
     * 必须在调用 requestPermission 且系统弹窗用户点了"允许"之后才能调用
     */
    fun createScreenCapturer(context: Context): VideoCapturer? {
        val data = pendingProjectionData ?: run {
            Log.e(TAG, "没有 MediaProjection 权限数据，先调用 requestPermission")
            return null
        }
        if (pendingResultCode != Activity.RESULT_OK) {
            Log.e(TAG, "MediaProjection 结果码不是 RESULT_OK: $pendingResultCode")
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
    fun hasPermission(): Boolean = pendingProjectionData != null && pendingResultCode == Activity.RESULT_OK

    /**
     * 清除权限缓存（断开后调用）
     */
    fun clearPermission() {
        pendingProjectionData = null
        pendingResultCode = Activity.RESULT_CANCELED
    }
}
