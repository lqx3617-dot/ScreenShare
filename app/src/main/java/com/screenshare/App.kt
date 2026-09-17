package com.screenshare

import android.app.Application
import android.content.Intent
import android.os.Process
import android.util.Log
import java.io.PrintWriter
import java.io.StringWriter

/**
 * 全局 Application：初始化落盘日志 + 注册未捕获崩溃兜底。
 *
 * 崩溃兜底策略：把堆栈写入 AppLogger 落盘文件，然后重启到稳定的 MeetingActivity
 * （旧界面，经过长期验证），避免新界面实验性特性导致 App 完全打不开。
 * 用户可在旧界面「更多」面板导出日志文件交给开发者精确定位。
 */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        AppLogger.init(this)
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                AppLogger.app("未捕获崩溃(thread=${thread.name}): $sw")
            } catch (ignored: Throwable) {
                Log.e("App", "写崩溃日志失败", ignored)
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
