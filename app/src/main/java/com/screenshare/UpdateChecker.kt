package com.screenshare

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

object UpdateChecker {
    private const val TAG = "UpdateChecker"
    private const val THREAD_COUNT = 2

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

    private fun promptUpdate(context: Context, info: JSONObject) {
        AlertDialog.Builder(context)
            .setTitle("发现新版本 ${info.getString("versionName")}")
            .setMessage(info.optString("note", "有新版本可用"))
            .setPositiveButton("立即更新") { _, _ -> downloadAndInstall(context, info) }
            .setNegativeButton("暂不", null)
            .show()
    }

    private fun downloadAndInstall(context: Context, info: JSONObject) {
        val apkUrl = info.getString("url")
        val dir = context.getExternalFilesDir(null)
        // 清理历史更新包，避免同名文件被安装器缓存导致装到旧版本
        dir?.listFiles()?.forEach { if (it.name.startsWith("update")) it.delete() }
        // 文件名带版本号，每次版本唯一 Uri，绕过 Android 安装器缓存
        val target = File(dir, "update-${info.optString("versionName", "new")}.apk")

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
            try {
                val conn = URL(apkUrl).openConnection() as HttpURLConnection
                conn.connectTimeout = 30000
                conn.readTimeout = 30000
                conn.requestMethod = "GET"
                conn.connect()
                val totalBytes = conn.contentLength
                conn.disconnect()

                if (totalBytes <= 0) {
                    (context as? android.app.Activity)?.runOnUiThread {
                        if (dialog.isShowing) dialog.dismiss()
                        Toast.makeText(context, "获取文件大小失败", Toast.LENGTH_LONG).show()
                    }
                    return@Thread
                }

                target.delete()
                val raf = RandomAccessFile(target, "rw")
                raf.setLength(totalBytes.toLong())
                raf.close()

                val chunkSize = totalBytes / THREAD_COUNT
                val downloaded = AtomicLong(0)
                val errors = java.util.concurrent.CopyOnWriteArrayList<Exception>()
                val latch = java.util.concurrent.CountDownLatch(THREAD_COUNT)
                val activity = context as? android.app.Activity

                activity?.runOnUiThread {
                    textView.text = "正在下载(2线程) 0/${formatSize(totalBytes.toLong())}"
                }

                for (i in 0 until THREAD_COUNT) {
                    val start = i * chunkSize
                    val end = if (i == THREAD_COUNT - 1) totalBytes - 1 else (i + 1) * chunkSize - 1
                    val threadId = i

                    Thread {
                        try {
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
                                    return@Thread
                                }
                                count = input.read(buf)
                                if (count < 0) break
                                rf.write(buf, 0, count)
                                val total = downloaded.addAndGet(count.toLong())
                                val now = System.currentTimeMillis()
                                if (now - lastUpdate > 150) {
                                    lastUpdate = now
                                    val pct = (total * 100 / totalBytes).toInt()
                                    activity?.runOnUiThread {
                                        progressBar.progress = pct
                                        textView.text = "正在下载(2线程) ${formatSize(total)}/${formatSize(totalBytes.toLong())}"
                                    }
                                }
                            }
                            rf.close()
                            input.close()
                            c.disconnect()
                        } catch (e: Exception) {
                            Log.e(TAG, "线程$threadId 下载失败: ${e.message}")
                            errors.add(e)
                        } finally {
                            latch.countDown()
                        }
                    }.apply { isDaemon = true }.start()
                }

                latch.await()

                if (cancelled.get()) {
                    target.delete()
                    return@Thread
                }

                if (errors.isNotEmpty()) {
                    activity?.runOnUiThread {
                        if (dialog.isShowing) dialog.dismiss()
                        Toast.makeText(context, "下载失败: ${errors[0].message}", Toast.LENGTH_LONG).show()
                    }
                    target.delete()
                    return@Thread
                }

                activity?.runOnUiThread {
                    progressBar.progress = 100
                    textView.text = "正在校验文件..."
                }

                val expectedMd5 = info.optString("md5", "")
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
            } catch (e: Exception) {
                Log.e(TAG, "下载失败: ${e.message}")
                (context as? android.app.Activity)?.runOnUiThread {
                    if (dialog.isShowing) dialog.dismiss()
                    Toast.makeText(context, "下载失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
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