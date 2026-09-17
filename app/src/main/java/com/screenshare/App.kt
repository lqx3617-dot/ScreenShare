package com.screenshare

import android.app.Application
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

    override fun onCreate() {
        super.onCreate()
        AppLogger.init(this)
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
                // 尽力上报信令服务器，失败忽略；缩短超时避免拖慢进程退出
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
                        OkHttpClient.Builder()
                            .connectTimeout(2, TimeUnit.SECONDS)
                            .readTimeout(2, TimeUnit.SECONDS)
                            .build()
                            .newCall(req).execute().close()
                    }
                } catch (_: Throwable) {}
            } catch (t: Throwable) {
                Log.e("App", "崩溃处理失败", t)
            }
            try {
                val intent = Intent(this, MeetingActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                startActivity(intent)
            } catch (ignored: Throwable) {
                Log.e("App", "崩溃后跳转失败", ignored)
            }
            Process.killProcess(Process.myPid())
        }
    }
}
