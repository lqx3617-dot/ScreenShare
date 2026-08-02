package com.screenshare

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * MediaProjection 前台服务。
 *
 * Android 14 (targetSdk 34) 起，调用 getMediaProjection() 时必须有一个
 * foregroundServiceType="mediaProjection" 类型的前台服务正在运行，否则抛
 * SecurityException。本服务负责在采集期间以媒体投影类型常驻前台。
 */
class ScreenProjectionService : Service() {

    companion object {
        private const val TAG = "ScreenProjectionService"
        private const val CHANNEL_ID = "screen_projection"
        private const val NOTIFICATION_ID = 1001

        /**
         * 服务已进入 mediaProjection 前台（startForeground 完成）后的回调。
         * 解决 getMediaProjection 与前台服务启动之间的时序竞态：
         * 只有前台服务真正 startForeground 后，getMediaProjection 才不抛 SecurityException。
         */
        @Volatile
        var onReady: (() -> Unit)? = null

        /**
         * 启动前台服务。在获取到 MediaProjection 权限后、调用
         * getMediaProjection() 之前调用。
         */
        @JvmStatic
        fun start(context: Context) {
            val intent = Intent(context, ScreenProjectionService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * 停止前台服务。WebRTC 断开后调用。
         */
        @JvmStatic
        fun stop(context: Context) {
            context.startService(
                Intent(context, ScreenProjectionService::class.java)
                    .setAction(ACTION_STOP)
            )
        }

        private const val ACTION_STOP = "com.screenshare.action.STOP"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        // 前台服务已就绪，通知 App 可安全调用 getMediaProjection 了（必须在 startForeground 之后触发）
        onReady?.let { cb ->
            onReady = null
            try { cb() } catch (t: Throwable) { Log.w(TAG, "onReady 回调异常: ${t.message}") }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("屏幕共享进行中")
            .setContentText("正在共享您的屏幕")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "屏幕共享",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "屏幕共享前台通知"
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}
