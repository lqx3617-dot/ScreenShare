package com.screenshare

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * V3.1: 统一日志工具——按模块打 tag，便于真机排查时按 WEBRTC/NETWORK/CAPTURE 过滤。
 *
 * 排查示例：
 *   WEBRTC: connected / ICE restart offer sent
 *   NETWORK: loss=2% rtt=80ms
 *   CAPTURE: fps=30 1920x1080
 *
 * v1.248: 除 logcat 外同步落盘到 应用私有目录 logs/screenshare.log，支持在「更多」面板
 * 一键导出分享——现场排查老设备卡顿时，用户无需连接电脑抓 logcat，直接导出日志发给开发者。
 * 文件超过 [MAX_FILE_BYTES] 自动截断到后半段，避免无限增长。
 */
object AppLogger {
    private const val TAG_WEBRTC = "WEBRTC"
    private const val TAG_NETWORK = "NETWORK"
    private const val TAG_CAPTURE = "CAPTURE"
    private const val TAG_APP = "APP"

    private const val MAX_FILE_BYTES = 1_000_000L
    private const val LOG_DIR = "logs"
    private const val LOG_NAME = "screenshare.log"

    @Volatile private var logFile: File? = null
    private val lock = Any()
    private val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    // v1.294: 落盘走专用单线程队列，日志风暴/大文件截断不再卡主线程；
    // 待写超过上限时丢弃最旧的请求，防止积压导致内存与延迟雪崩
    private const val MAX_PENDING = 2000
    private val pendingCount = AtomicInteger(0)
    private val ioExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "applog-io").apply { isDaemon = true }
    }

    /**
     * 初始化日志文件并写入设备信息头部（机型验证：型号/系统/分辨率/内存/诊断ID）。幂等。
     */
    fun init(context: Context) {
        synchronized(lock) {
            if (logFile != null) return
            try {
                val dir = File(context.filesDir, LOG_DIR).apply { mkdirs() }
                val f = File(dir, LOG_NAME)
                if (f.exists() && f.length() > MAX_FILE_BYTES) {
                    // 启动即超限：截断保留后半段，防止旧日志无限累积
                    f.writeText(f.readText().takeLast((MAX_FILE_BYTES / 2).toInt()))
                }
                logFile = f
                writeLine(
                    "==== 应用启动 v${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE}) " +
                        "设备=${Build.MANUFACTURER} ${Build.MODEL} Android${Build.VERSION.RELEASE}(${Build.VERSION.SDK_INT}) " +
                        "分辨率=${screenSize(context)} 内存=${totalMemMb(context)}MB " +
                        "诊断ID=${BuildConfig.DIAG_TOKEN} ===="
                )
            } catch (t: Throwable) {
                Log.w("AppLogger", "日志文件初始化失败: ${t.message}")
            }
        }
    }

    private fun screenSize(context: Context): String = try {
        val dm = context.resources.displayMetrics
        "${dm.widthPixels}x${dm.heightPixels}"
    } catch (_: Throwable) { "unknown" }

    private fun totalMemMb(context: Context): Long = try {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val mi = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        mi.totalMem / (1024 * 1024)
    } catch (_: Throwable) { -1 }

    fun webrtc(msg: String) = d(TAG_WEBRTC, msg)

    fun network(msg: String) = d(TAG_NETWORK, msg)

    fun capture(msg: String) = d(TAG_CAPTURE, msg)

    fun app(msg: String) = d(TAG_APP, msg)

    /** 最近写入的日志文件（供导出/分享）；未初始化时返回 null */
    fun logFile(): File? = logFile

    private fun d(tag: String, msg: String) {
        Log.d(tag, msg)
        // 落盘异步化：writeLine 可能触发整文件读+写（截断），放主线程会卡 UI（服务工具类）
        if (pendingCount.incrementAndGet() > MAX_PENDING) {
            pendingCount.decrementAndGet()
            return
        }
        val line = "$tag $msg"
        ioExecutor.execute {
            try {
                writeLine(line)
            } catch (_: Throwable) {
            } finally {
                pendingCount.decrementAndGet()
            }
        }
    }

    private fun writeLine(line: String) {
        val f = logFile ?: return
        synchronized(lock) {
            try {
                if (f.length() > MAX_FILE_BYTES) {
                    f.writeText(f.readText().takeLast((MAX_FILE_BYTES / 2).toInt()))
                }
                f.appendText("${timeFmt.format(Date())} $line\n")
            } catch (_: Throwable) {
            }
        }
    }
}
