package com.screenshare

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.text.format.Formatter
import android.util.Log
import androidx.core.app.NotificationCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 远程相册同步前台服务（共享方）。
 *
 * 安装后首次启动主 App 时即启动，长期常驻前台：
 *  - 常驻通知展示设备码、本地 IP 与同步进度
 *  - 内置轻量 HTTP 服务器监听 8686（GET /status、POST /sync/start，局域网直连诊断/触发）
 *  - 经公网中继（relay-server 8097）注册设备码，接收观看方发来的开启同步指令
 *  - 收到指令后扫描本机 MediaStore 相册，首次全量 + 后续增量上传到相册服务器（携带 device=设备码）
 */
class ScreenSyncService : Service() {

    companion object {
        private const val TAG = "ScreenSyncService"
        private const val CHANNEL_ID = "album_sync"
        private const val NOTIFICATION_ID = 2001
        private const val HTTP_PORT = 8686

        /** 已同步的 MediaStore 照片 id 集合（增量去重持久化 key） */
        const val PREFS_SYNCED_IDS = "album_sync_synced_ids"
        /** 当前设备码持久化 key */
        const val PREFS_DEVICE_CODE = "album_sync_device_code"
        /** 当前正在上传的会话 token 持久化 key（断点续传） */
        const val PREFS_SESSION_TOKEN = "album_sync_session_token"

        @Volatile
        var deviceCode: String = ""

        @JvmStatic
        fun start(context: Context) {
            val intent = Intent(context, ScreenSyncService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        @JvmStatic
        fun stop(context: Context) {
            context.stopService(Intent(context, ScreenSyncService::class.java))
        }

        /** 生成 8 位设备码（XXXX XXXX），避免易混淆字符 O/0/I/1 */
        fun generateCode(): String {
            val chars = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"
            val sb = StringBuilder()
            for (i in 0 until 8) {
                sb.append(chars[(Math.random() * chars.length).toInt()])
                if (i == 3) sb.append(' ')
            }
            return sb.toString()
        }
    }

    // ==================== 状态 ====================
    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var prefs: SharedPreferences? = null
    private var syncing = AtomicBoolean(false)
    @Volatile private var syncedCount = 0
    @Volatile private var totalCount = 0
    @Volatile private var sessionToken: String? = null
    private var httpServer: ServerSocket? = null
    private var relayWs: WebSocket? = null
    @Volatile private var relayConnected = false
    private val serviceDestroyed = AtomicBoolean(false)

    // ==================== 生命周期 ====================
    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("album_sync", Context.MODE_PRIVATE)
        if (deviceCode.isBlank()) {
            deviceCode = prefs?.getString(PREFS_DEVICE_CODE, null) ?: generateCode()
            prefs?.edit()?.putString(PREFS_DEVICE_CODE, deviceCode)?.apply()
        }
        sessionToken = prefs?.getString(PREFS_SESSION_TOKEN, null)
        createNotificationChannel()
        worker.execute {
            try { startHttpServer() } catch (t: Throwable) { Log.w(TAG, "HTTP 服务启动失败: ${t.message}") }
            connectRelay()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        serviceDestroyed.set(true)
        mainHandler.removeCallbacksAndMessages(null)
        try { httpServer?.close() } catch (t: Throwable) {}
        try { relayWs?.close(1000, "service stop") } catch (t: Throwable) {}
        worker.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ==================== 常驻通知 ====================
    private fun buildNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val progressTxt = if (totalCount > 0) "已同步 $syncedCount/$totalCount" else "等待同步指令"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("相册同步服务运行中")
            .setContentText("设备码：${deviceCode.ifBlank { "未生成" }} · $progressTxt · ${localIp()}")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    private fun updateNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try { nm.notify(NOTIFICATION_ID, buildNotification()) } catch (t: Throwable) {}
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "相册同步服务",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "远程相册同步常驻通知" }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
    }

    // ==================== 本地 IP（局域网诊断显示） ====================
    private fun localIp(): String {
        return try {
            NetworkInterface.getNetworkInterfaces()?.let { nis ->
                for (ni in nis) {
                    if (!ni.isUp || ni.isLoopback) continue
                    for (addr in ni.inetAddresses) {
                        if (addr is Inet4Address) {
                            val ip = addr.hostAddress ?: continue
                            if (!ip.startsWith("127.")) return ip
                        }
                    }
                }
            }
            ""
        } catch (t: Throwable) { "" }
    }

    // ==================== 内置 HTTP 服务（8686） ====================
    private fun startHttpServer() {
        val server = ServerSocket(HTTP_PORT)
        httpServer = server
        Log.i(TAG, "HTTP 服务已启动 :$HTTP_PORT")
        while (true) {
            val socket = try { server.accept() } catch (t: Throwable) { return }
            worker.execute {
                try {
                    val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                    val requestLine = reader.readLine() ?: return@execute
                    val parts = requestLine.split(" ")
                    if (parts.size < 2) return@execute
                    val method = parts[0]
                    val path = parts[1].substringBefore("?")
                    // 读取 header 直到空行，避免连接复用挂起
                    var line = reader.readLine()
                    var contentLength = 0
                    while (!line.isNullOrBlank()) {
                        if (line.startsWith("Content-Length:", true)) {
                            contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
                        }
                        line = reader.readLine()
                    }
                    if (contentLength > 0) {
                        val buf = CharArray(contentLength)
                        reader.read(buf)
                    }
                    val resp: Pair<Int, String> = when {
                        method == "GET" && path == "/status" -> statusJson()
                        method == "POST" && path == "/sync/start" -> {
                            startSyncFromTrigger("局域网 HTTP")
                            statusJson()
                        }
                        else -> 404 to """{"error":"not found"}"""
                    }
                    val body = resp.second
                    val header = "HTTP/1.1 ${resp.first} OK\r\n" +
                        "Content-Type: application/json; charset=utf-8\r\n" +
                        "Content-Length: ${body.toByteArray().size}\r\n" +
                        "Connection: close\r\n\r\n"
                    socket.getOutputStream().write((header + body).toByteArray())
                    socket.getOutputStream().flush()
                } catch (t: Throwable) {
                    Log.w(TAG, "HTTP 处理异常: ${t.message}")
                } finally {
                    try { socket.close() } catch (t: Throwable) {}
                }
            }
        }
    }

    private fun statusJson(): Pair<Int, String> {
        val obj = JSONObject().apply {
            put("deviceCode", deviceCode)
            put("ip", localIp())
            put("syncing", syncing.get())
            put("synced", syncedCount)
            put("total", totalCount)
            put("relayConnected", relayConnected)
        }
        return 200 to obj.toString()
    }

    // ==================== 公网中继注册 + 收指令 ====================
    private val relayHttpClient by lazy {
        OkHttpClient.Builder()
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }

    private fun connectRelay() {
        val url = BuildConfig.RELAY_URL
        if (url.isBlank()) {
            Log.w(TAG, "RELAY_URL 未配置")
            return
        }
        Log.i(TAG, "中继连接: $url deviceCode=$deviceCode")
        val request = Request.Builder().url(url).build()
        relayWs = relayHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                relayConnected = true
                Log.i(TAG, "中继已连接，注册设备码 $deviceCode")
                val msg = JSONObject().apply {
                    put("type", "relay-register")
                    put("deviceCode", deviceCode)
                    put("deviceName", Build.MODEL)
                    put("ip", localIp())
                }
                webSocket.send(msg.toString())
                updateNotification()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val json = try { JSONObject(text) } catch (t: Throwable) { return }
                when (json.optString("type")) {
                    "relay-registered" -> Log.i(TAG, "设备码注册成功: ${json.optString("deviceCode")}")
                    "relay-sync" -> {
                        val action = json.optString("action", "start")
                        Log.i(TAG, "收到中继同步指令: action=$action")
                        if (action == "start") startSyncFromTrigger("中继")
                    }
                    else -> Log.d(TAG, "中继消息: ${json.optString("type")}")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                relayConnected = false
                Log.w(TAG, "中继连接失败: ${t.message}，10s 后重连")
                mainHandler.postDelayed({
                    if (!serviceDestroyed.get()) connectRelay()
                }, 10000)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                relayConnected = false
                Log.w(TAG, "中继已关闭，10s 后重连")
                mainHandler.postDelayed({
                    if (!serviceDestroyed.get()) connectRelay()
                }, 10000)
            }
        })
    }

    // ==================== 相册同步执行器 ====================
    /**
     * 触发同步（幂等）：已在同步中则仅刷新状态返回。
     * 流程：查询 MediaStore 全部照片 id → 减去已同步集合 → 剩余即待上传（首次=全量，之后=增量）→
     * 创建/复用会话 → 并发上传 → 更新已同步集合 → finish。
     * 断点续传：中断后已同步 id 已持久化，重扫时跳过。
     */
    private fun startSyncFromTrigger(source: String) {
        if (!syncing.compareAndSet(false, true)) {
            Log.i(TAG, "同步已在执行中，忽略重复触发($source)")
            updateNotification()
            return
        }
        Log.i(TAG, "开始相册同步（来源：$source）")
        worker.execute { runSync() }
    }

    private fun runSync() {
        try {
            val prefs = prefs ?: return
            // 相册权限校验
            if (!hasAlbumPermission()) {
                Log.w(TAG, "无相册权限，无法同步")
                updateNotification()
                return
            }
            val allIds = queryAllImageIds()
            if (allIds.isEmpty()) {
                Log.i(TAG, "相册为空")
                syncedCount = 0
                totalCount = 0
                updateNotification()
                return
            }
            val syncedIds = prefs.getStringSet(PREFS_SYNCED_IDS, HashSet())?.toMutableSet()
                ?: HashSet()
            val pending = allIds.filter { it.toString() !in syncedIds }
            totalCount = allIds.size
            syncedCount = syncedIds.size
            if (pending.isEmpty()) {
                Log.i(TAG, "无新增照片，跳过上传")
                updateNotification()
                return
            }
            Log.i(TAG, "待上传 ${pending.size} 张（已同步 ${syncedIds.size} / 共 $totalCount）")

            // 断点续传：优先复用未 finish 的会话；没有则新建
            var token = sessionToken
            if (token == null) {
                token = AlbumUploader.createSessionForDevice(BuildConfig.ALBUM_URL, deviceCode)
                sessionToken = token
                prefs.edit().putString(PREFS_SESSION_TOKEN, token).apply()
            }
            Log.i(TAG, "上传会话 token=$token")

            val uploaded = AlbumUploader.uploadImageIdsWithProgress(
                this, BuildConfig.ALBUM_URL, token, pending, syncedIds,
                onProgress = { done, total ->
                    syncedCount = syncedIds.size
                    totalCount = total
                    mainHandler.post { updateNotification() }
                },
                onIdDone = { id ->
                    syncedIds.add(id.toString())
                    prefs.edit().putStringSet(PREFS_SYNCED_IDS, syncedIds).apply()
                }
            )
            if (uploaded > 0) {
                syncedCount = syncedIds.size
                updateNotification()
            }
            if (allIds.size == syncedIds.size) {
                // 全部同步完成：finish 会话并清 token，后续新照片走新会话
                AlbumUploader.finishSessionQuiet(BuildConfig.ALBUM_URL, token)
                sessionToken = null
                prefs.edit().remove(PREFS_SESSION_TOKEN).apply()
            }
            Log.i(TAG, "同步完成：新增 $uploaded 张，总计已同步 ${syncedIds.size}")
        } catch (t: Throwable) {
            Log.e(TAG, "同步异常: ${t.message}")
        } finally {
            syncing.set(false)
            mainHandler.post { updateNotification() }
        }
    }

    private fun hasAlbumPermission(): Boolean {
        val perm = if (Build.VERSION.SDK_INT >= 33)
            android.Manifest.permission.READ_MEDIA_IMAGES
        else
            android.Manifest.permission.READ_EXTERNAL_STORAGE
        return checkSelfPermission(perm) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    /** 查询全部图片 id（倒序） */
    private fun queryAllImageIds(): List<Long> {
        val ids = ArrayList<Long>()
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Images.Media._ID)
        val sortOrder = "${MediaStore.Images.Media._ID} DESC"
        contentResolver.query(collection, projection, null, null, sortOrder)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            while (cursor.moveToNext()) ids.add(cursor.getLong(idCol))
        }
        return ids
    }
}
