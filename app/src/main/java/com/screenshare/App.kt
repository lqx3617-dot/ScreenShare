package com.screenshare

import android.app.Application
import android.app.NotificationManager
import android.content.Intent
import android.os.Process
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.concurrent.TimeUnit

/**
 * 全局 Application：初始化落盘日志 + 注册未捕获崩溃兜底。
 *
 * 崩溃兜底链路（无论哪个 Activity 崩溃都走这里）：
 * 1. 堆栈写入 AppLogger 落盘日志 + 外部存储 crash-xxx.log
 * 2. 同步 POST 到信令服务器 /crash（2s 超时，失败忽略）
 * 3. 重启到稳定的 MeetingActivity，避免实验性特性导致 App 完全打不开
 *
 * 注意：上报必须在 Application 层注册。此前上报只在 MainActivity.installCrashHandler
 * 里注册，新界面（LiquidHomeActivity）启动崩溃时该 handler 尚未安装，服务器收不到。
 */
class App : Application() {

    companion object {
        lateinit var instance: App
            private set
    }

    /**
     * 账号在线状态长连接（进程级）。
     *
     * 以前挂在 LiquidHomeActivity 上，进会议室（startActivity MainActivity + CLEAR_TOP）
     * 会销毁 LiquidHomeActivity 触发 disconnect()，导致共享邀请还没发出 WS 就关了。
     * 提到 Application 后跨 Activity 生存，登出时才断开。
     */
    var presenceClient: PresenceClient? = null
        private set

    /** 登录/恢复会话后调用：建立账号长连接 */
    fun connectPresence(token: String, listener: PresenceClient.Listener) {
        presenceClient?.disconnect()
        presenceClient = PresenceClient(BuildConfig.SIGNAL_URL, listener).also {
            it.connect(token)
            AppLogger.app("[APP] 建立 PresenceClient url=${BuildConfig.SIGNAL_URL}")
        }
    }

    /** 登出时调用：断开账号长连接 */
    fun disconnectPresence() {
        presenceClient?.disconnect()
        presenceClient = null
        AppLogger.app("[APP] 断开 PresenceClient（登出）")
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        AppLogger.init(this)
        // 极光推送：隐私授权后初始化（App 无隐私弹窗，直接启用）
        try {
            cn.jpush.android.api.JPushInterface.setDebugMode(BuildConfig.DEBUG)
            cn.jpush.android.api.JPushInterface.init(this)
        } catch (t: Throwable) {
            AppLogger.app("[JPush] 初始化失败：${t.message}")
        }
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val content = StringBuilder()
                    .append("time=").append(System.currentTimeMillis()).append('\n')
                    .append("thread=").append(thread.name).append('\n')
                    .append(sw.toString())
                // 落盘应用私有日志（可在旧界面导出）
                AppLogger.app("未捕获崩溃(thread=${thread.name}): $sw")
                // 写外部存储，与历史崩溃文件格式一致
                try {
                    getExternalFilesDir(null)?.let { dir ->
                        File(dir, "crash-${System.currentTimeMillis()}.log")
                            .writeText(content.toString())
                    }
                } catch (_: Throwable) {}
                // 尽力上报信令服务器，失败忽略；放子线程并限时 join，
                // 同步 execute() 最坏阻塞 4 秒（connect+read 超时），在主线程会拖慢进程退出
                try {
                    val signalUrl = BuildConfig.SIGNAL_URL
                    if (!signalUrl.isNullOrEmpty()) {
                        val base = signalUrl
                            .replace("wss://", "https://")
                            .replace("ws://", "http://")
                            .trimEnd('/')
                        val url = base.substringBeforeLast('/', base)
                        val body = content.toString().toByteArray()
                            .toRequestBody("text/plain".toMediaType())
                        val req = Request.Builder()
                            .url("$url/crash")
                            .addHeader("x-diag-token", BuildConfig.DIAG_TOKEN)
                            .post(body)
                            .build()
                        val uploader = Thread {
                            try {
                                OkHttpClient.Builder()
                                    .connectTimeout(2, TimeUnit.SECONDS)
                                    .readTimeout(2, TimeUnit.SECONDS)
                                    .build()
                                    .newCall(req).execute().close()
                            } catch (_: Throwable) {}
                        }.apply { isDaemon = true; start() }
                        uploader.join(1500)
                    }
                } catch (_: Throwable) {}
            } catch (t: Throwable) {
                Log.e("App", "崩溃处理失败", t)
            }
            // 恢复通知过滤：会议期间可能开启了「仅限优先通知」，崩溃后未恢复会残留整机静音
            try {
                getSystemService(NotificationManager::class.java)
                    ?.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
            } catch (_: Throwable) {}
            try {
                val intent = Intent(this, LiquidHomeActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                startActivity(intent)
            } catch (ignored: Throwable) {
                Log.e("App", "崩溃后跳转失败", ignored)
            }
            Process.killProcess(Process.myPid())
        }
    }
}
