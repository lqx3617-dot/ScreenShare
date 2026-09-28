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
    // 已写入「应用启动」分隔线的版本。进程未重启但应用更新（Activity 以新 ClassLoader
    // 重建）时，靠版本差异识别需要重写分隔线
    private var markerVersion = 0
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
     * 初始化日志文件并写入设备信息头部（机型验证：型号/系统/分辨率/内存/诊断ID）。
     * 同版本内幂等；跨版本（应用更新后进程未重启）时重写分隔线，保证导出切片的版本标识正确
     */
    fun init(context: Context) {
        synchronized(lock) {
            val curVer = BuildConfig.VERSION_CODE
            // 同版本已初始化才跳过。应用更新后若进程未被杀死（Android 以新 ClassLoader
            // 重建 Activity），logFile 仍非 null 但 BuildConfig 已是新版本：此时仍要重写
            // 分隔线，否则导出切片会拿到旧版本分隔线，误判本次运行的版本
            if (logFile != null && markerVersion == curVer) return
            try {
                val dir = File(context.filesDir, LOG_DIR).apply { mkdirs() }
                val f = logFile ?: File(dir, LOG_NAME)
                if (f.exists() && f.length() > MAX_FILE_BYTES) {
                    // 启动即超限：截断保留后半段，防止旧日志无限累积
                    truncateTail(f)
                }
                logFile = f
                markerVersion = curVer
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
                    truncateTail(f)
                }
                f.appendText("${timeFmt.format(Date())} $line\n")
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * 截断到文件后半段。takeLast 是字符级截断，可能从行中间切断，被切断的「应用启动」
     * 分隔线会让导出切片的 lastIndexOf("==== 应用启动 ") 匹配失败、回退到更旧的分隔线，
     * 上传日志的版本标识随之错乱。截断后丢弃首个不完整行，按行边界对齐
     */
    private fun truncateTail(f: File) {
        val tail = f.readText().takeLast((MAX_FILE_BYTES / 2).toInt())
        val nl = tail.indexOf('\n')
        f.writeText(if (nl in 0..(tail.length - 2)) tail.substring(nl + 1) else tail)
    }
}
