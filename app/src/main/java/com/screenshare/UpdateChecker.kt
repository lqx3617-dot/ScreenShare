package com.screenshare

import android.Manifest
import android.app.AlertDialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

object UpdateChecker {
    private const val TAG = "UpdateChecker"
    private const val THREAD_COUNT = 4
    private const val RETRY_TIMES = 3
    private const val CHANNEL_ID = "update_channel"
    private const val PREFS = "update_checker"
    private const val KEY_AUTO_TS = "auto_check_ts"
    // 静默自动检查节流：12 小时内不重复自动检查（手动检查不受限）
    private const val AUTO_CHECK_INTERVAL_MS = 12 * 60 * 60 * 1000L
    private const val CANCEL_ACTION = "com.screenshare.CANCEL_UPDATE"
    private const val NOTIFICATION_ID = 1001

    fun check(context: Context) = check(context, manual = false)

    fun check(context: Context, manual: Boolean) {
        val url = BuildConfig.UPDATE_URL
        if (url.isNullOrEmpty()) {
            if (manual) {
                (context as? android.app.Activity)?.runOnUiThread {
                    Toast.makeText(context, "未配置更新服务器", Toast.LENGTH_LONG).show()
                }
            }
            return
        }
        // 自动检查节流：距上次成功检查不足 12h 直接跳过，避免频繁请求
        if (!manual) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val last = prefs.getLong(KEY_AUTO_TS, 0L)
            if (System.currentTimeMillis() - last < AUTO_CHECK_INTERVAL_MS) return
        }
        Thread {
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.requestMethod = "GET"
                val code = conn.responseCode
                if (code != 200) {
                    conn.disconnect()
                    if (manual) {
                        (context as? android.app.Activity)?.runOnUiThread {
                            Toast.makeText(context, "更新服务器响应异常($code)", Toast.LENGTH_LONG).show()
                        }
                    }
                    return@Thread
                }
                val json = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                // 请求成功（无论结果如何）记录自动检查时间，刷新节流窗口
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putLong(KEY_AUTO_TS, System.currentTimeMillis()).apply()
                val info = JSONObject(json)
                val serverCode = info.getInt("versionCode")
                if (serverCode > BuildConfig.VERSION_CODE) {
                    (context as? android.app.Activity)?.runOnUiThread {
                        promptUpdate(context, info)
                    }
                } else if (manual) {
                    (context as? android.app.Activity)?.runOnUiThread {
                        Toast.makeText(context, "已是最新版本", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "版本检查失败: ${e.message}")
                if (manual) {
                    (context as? android.app.Activity)?.runOnUiThread {
                        Toast.makeText(context, "版本检查失败: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }.apply { isDaemon = true }.start()
    }

    /**
     * 更新提示：展示版本对比、更新说明（changelog/note）与包大小；
     * forced=true 时禁止跳过（无「暂不」按钮），仅提供「立即更新」。
     */
    private fun promptUpdate(context: Context, info: JSONObject) {
        val forced = info.optBoolean("forced", false)
        val versionName = info.optString("versionName", "新")
        val sizeText = formatSize(info.optLong("size", 0L))
        val changelog = info.optString("changelog", "")
            .ifEmpty { info.optString("note", "有新版本可用") }
        val msg = buildString {
            append("当前版本 v${BuildConfig.VERSION_NAME} → 新版本 v$versionName")
            if (sizeText.isNotEmpty() && sizeText != "0 B") append("（$sizeText）")
            append("\n\n更新说明：\n")
            append(changelog)
            if (forced) append("\n\n此版本为强制更新，请尽快完成更新。")
        }
        val builder = AlertDialog.Builder(context)
            .setTitle("发现新版本 v$versionName")
            .setMessage(msg)
            .setPositiveButton("立即更新") { _, _ -> downloadAndInstall(context, info) }
        if (!forced) {
            builder.setNegativeButton("暂不", null)
        }
        builder.show()
    }

    private fun downloadAndInstall(context: Context, info: JSONObject) {
        val apkUrl = info.getString("url")
        val dir = context.getExternalFilesDir(null)
        val versionName = info.optString("versionName", "new")
        // 清理历史旧版本更新包（保留当前目标版本，用于文件复用）
        dir?.listFiles()?.forEach { if (it.name.startsWith("update") && it.name != "update-$versionName.apk") it.delete() }
        // 文件名带版本号，每次版本唯一 Uri，绕过 Android 安装器缓存
        val target = File(dir, "update-$versionName.apk")
        val expectedMd5 = info.optString("md5", "")

        // 文件复用：同版本安装包已存在且 MD5 校验通过 → 直接安装，不重复下载
        if (target.exists()) {
            if (expectedMd5.isEmpty() || md5(target) == expectedMd5) {
                installApk(context, target)
                return
            }
            target.delete()
        }

        // Android 13+ 通知栏下载需要通知权限；未授权时降级为 Activity 内进度条（功能不受影响）
        val notifyAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

        if (notifyAllowed) {
            downloadWithNotification(context, info, target, apkUrl, expectedMd5)
        } else {
            downloadWithDialog(context, info, target, apkUrl, expectedMd5)
        }
    }

    /** 通知栏下载：后台进行（用户可离开页面），进度与速度展示在通知栏，可取消，完成自动安装 */
    private fun downloadWithNotification(context: Context, info: JSONObject, target: File, apkUrl: String, expectedMd5: String) {
        val appCtx = context.applicationContext
        val nm = NotificationManagerCompat.from(appCtx)
        val versionName = info.optString("versionName", "新")
        createNotificationChannel(appCtx)

        val cancelled = AtomicBoolean(false)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) { cancelled.set(true) }
        }
        ContextCompat.registerReceiver(appCtx, receiver, IntentFilter(CANCEL_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)
        val cancelPi = PendingIntent.getBroadcast(appCtx, 0, Intent(CANCEL_ACTION), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val builder = NotificationCompat.Builder(appCtx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("正在下载更新 v$versionName")
            .setContentText("准备连接下载服务器...")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, 0, false)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "取消", cancelPi)
        nm.notify(NOTIFICATION_ID, builder.build())

        var lastBytes = 0L
        val lastReport = AtomicLong(0)
        val ok = downloadToFile(apkUrl, target, cancelled) { total, totalBytes ->
            val now = System.currentTimeMillis()
            var speedText = ""
            val delta = now - lastReport.get()
            if (delta >= 500) {
                speedText = "${formatSize((total - lastBytes) * 1000 / delta)}/s"
                lastBytes = total
                lastReport.set(now)
            }
            builder.setProgress(100, (total * 100 / totalBytes).toInt(), false)
                .setContentText("${formatSize(total)}/${formatSize(totalBytes)} $speedText")
            nm.notify(NOTIFICATION_ID, builder.build())
        }

        nm.cancel(NOTIFICATION_ID)
        try { appCtx.unregisterReceiver(receiver) } catch (_: Throwable) {}

        if (cancelled.get()) {
            target.delete()
            return
        }
        if (!ok) {
            target.delete()
            Toast.makeText(context, "下载失败，请重试", Toast.LENGTH_LONG).show()
            return
        }
        if (expectedMd5.isNotEmpty() && md5(target) != expectedMd5) {
            target.delete()
            Toast.makeText(context, "下载校验失败，请重试", Toast.LENGTH_LONG).show()
            return
        }
        (context as? android.app.Activity)?.runOnUiThread { installApk(context, target) }
    }

    /** 降级方案：无通知权限时使用 Activity 内进度条弹窗下载 */
    private fun downloadWithDialog(context: Context, info: JSONObject, target: File, apkUrl: String, expectedMd5: String) {
        val versionName = info.optString("versionName", "new")
        val progressBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
        }
        val textView = TextView(context).apply {
            text = "准备连接下载服务器..."
            textSize = 14f
            setPadding(50, 20, 50, 20)
        }
        val layout = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(textView)
            addView(progressBar)
            setPadding(50, 20, 50, 20)
        }

        val dialog = AlertDialog.Builder(context)
            .setTitle("下载更新(${THREAD_COUNT}线程)")
            .setView(layout)
            .setCancelable(false)
            .setNegativeButton("取消", null)
            .show()

        val cancelled = AtomicBoolean(false)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
            cancelled.set(true)
            dialog.dismiss()
        }

        Thread {
            val activity = context as? android.app.Activity
            val ok = downloadToFile(apkUrl, target, cancelled) { total, totalBytes ->
                activity?.runOnUiThread {
                    progressBar.progress = (total * 100 / totalBytes).toInt()
                    textView.text = "正在下载(${THREAD_COUNT}线程) ${formatSize(total)}/${formatSize(totalBytes)}"
                }
            }
            if (cancelled.get()) {
                target.delete()
                return@Thread
            }
            if (!ok) {
                activity?.runOnUiThread {
                    if (dialog.isShowing) dialog.dismiss()
                    Toast.makeText(context, "下载失败，请重试", Toast.LENGTH_LONG).show()
                }
                target.delete()
                return@Thread
            }
            activity?.runOnUiThread {
                progressBar.progress = 100
                textView.text = "正在校验文件..."
            }
            if (expectedMd5.isNotEmpty() && md5(target) != expectedMd5) {
                activity?.runOnUiThread {
                    if (dialog.isShowing) dialog.dismiss()
                    Toast.makeText(context, "下载校验失败，请重试", Toast.LENGTH_LONG).show()
                }
                target.delete()
                return@Thread
            }
            activity?.runOnUiThread {
                if (dialog.isShowing) dialog.dismiss()
                installApk(context, target)
            }
        }.start()
    }

    /**
     * 分段并发下载到 target。每段失败单独重试（最多 RETRY_TIMES 次），
     * 不中断其他分段；仅当所有分段重试耗尽才判定失败。
     * @param onProgress 回调已下载字节数与总字节数（节流后调用）
     * @return true=全部成功；false=存在失败段或下载准备失败
     */
    private fun downloadToFile(apkUrl: String, target: File, cancelled: AtomicBoolean, onProgress: (Long, Long) -> Unit): Boolean {
        return try {
            val conn = URL(apkUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = 30000
            conn.readTimeout = 30000
            conn.requestMethod = "GET"
            conn.connect()
            val totalBytes = conn.contentLengthLong
            conn.disconnect()

            if (totalBytes <= 0) return false

            target.delete()
            RandomAccessFile(target, "rw").use { it.setLength(totalBytes.toLong()) }

            val chunkSize = totalBytes / THREAD_COUNT
            val downloaded = AtomicLong(0)
            val failedSegments = CopyOnWriteArrayList<Int>()
            val latch = CountDownLatch(THREAD_COUNT)

            for (i in 0 until THREAD_COUNT) {
                val start = i * chunkSize
                val end = if (i == THREAD_COUNT - 1) totalBytes - 1 else (i + 1) * chunkSize - 1
                val threadId = i
                Thread {
                    var success = false
                    var attempt = 0
                    while (!success && attempt < RETRY_TIMES && !cancelled.get()) {
                        attempt++
                        success = downloadSegment(apkUrl, target, start, end, cancelled, downloaded, totalBytes, onProgress)
                    }
                    if (!success) failedSegments.add(threadId)
                    latch.countDown()
                }.apply { isDaemon = true }.start()
            }

            latch.await()
            !cancelled.get() && failedSegments.isEmpty()
        } catch (e: Exception) {
            Log.e(TAG, "下载准备失败: ${e.message}")
            false
        }
    }

    /** 下载单个分段；返回该分段是否成功。cancelled 置位时视为正常退出（不判失败）。 */
    private fun downloadSegment(
        apkUrl: String,
        target: File,
        start: Long,
        end: Long,
        cancelled: AtomicBoolean,
        downloaded: AtomicLong,
        totalBytes: Long,
        onProgress: (Long, Long) -> Unit
    ): Boolean {
        return try {
            val c = URL(apkUrl).openConnection() as HttpURLConnection
            c.connectTimeout = 30000
            c.readTimeout = 60000
            c.setRequestProperty("Range", "bytes=$start-$end")
            c.connect()

            val code = c.responseCode
            if (code != 206 && code != 200) {
                throw java.io.IOException("服务器返回 $code，不支持分段下载")
            }

            val input = c.inputStream
            val buf = ByteArray(32768)
            val rf = RandomAccessFile(target, "rw")
            rf.seek(start.toLong())
            var count: Int
            var lastUpdate = 0L

            while (true) {
                if (cancelled.get()) {
                    input.close()
                    rf.close()
                    c.disconnect()
                    return true
                }
                count = input.read(buf)
                if (count < 0) break
                rf.write(buf, 0, count)
                val total = downloaded.addAndGet(count.toLong())
                val now = System.currentTimeMillis()
                if (now - lastUpdate > 150) {
                    lastUpdate = now
                    onProgress(total, totalBytes)
                }
            }
            rf.close()
            input.close()
            c.disconnect()
            true
        } catch (e: Exception) {
            Log.w(TAG, "分段下载失败 [$start-$end]: ${e.message}")
            false
        }
    }

    private fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "更新下载", NotificationManager.IMPORTANCE_LOW).apply {
                description = "应用更新下载进度"
            }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1048576 -> "${bytes / 1024} KB"
        else -> "%.1f MB".format(bytes.toDouble() / 1048576)
    }

    private fun installApk(context: Context, apk: File) {
        // Android 8+ 需要"安装未知应用"权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            (context as? android.app.Activity)?.runOnUiThread {
                AlertDialog.Builder(context)
                    .setTitle("需要安装权限")
                    .setMessage("请允许「安装未知应用」权限，才能安装更新")
                    .setPositiveButton("去设置") { _, _ ->
                        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                            .setData(Uri.parse("package:${context.packageName}"))
                        context.startActivity(intent)
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
            return
        }
        try {
            val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
            val intent = Intent(Intent.ACTION_VIEW)
            intent.setDataAndType(uri, "application/vnd.android.package-archive")
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, "未找到安装程序", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Log.e(TAG, "安装失败: ${e.message}")
            (context as? android.app.Activity)?.runOnUiThread {
                Toast.makeText(context, "安装失败: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun md5(file: File): String {
        val digest = java.security.MessageDigest.getInstance("MD5")
        file.inputStream().use { input ->
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}