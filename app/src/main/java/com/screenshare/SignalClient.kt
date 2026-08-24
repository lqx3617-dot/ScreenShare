package com.screenshare

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 口令共享模式下的信令客户端（WebSocket）。
 *
 * 与二维码模式的差异：不经过二维码，直接把 SDP/ICE 编码后的 JSON 串
 * 通过信令服务器原样转发给对端（复用 SignalManager 的编解码格式）。
 *
 * 服务器协议见 server/server.js。
 */
class SignalClient(
    private val url: String,
    private val listener: Listener
) {
    interface Listener {
        /** 服务器创建/加入房间成功，可以开始屏幕采集与 SDP 交换 */
        fun onRoomReady(role: String, viewerId: Int)
        /** 对端已加入房间，等待/开始交换信令 */
        fun onPeerReady()
        /** 收到对端转发的信令数据（SDP/ICE 编码串）；viewerId 标识来源/目标 viewer（host 多路用） */
        fun onRelay(data: String, viewerId: Int)
        /** 有人请求加入（仅 host 收到，等待确认）；调用 acceptViewer/rejectViewer 回应 */
        fun onJoinRequest(viewerId: Int)
        /** 加入请求已提交，等待 host 确认（仅 viewer 收到） */
        fun onJoinPending()
        /** host 拒绝了加入请求（仅 viewer 收到） */
        fun onJoinRejected()
        /** 新 viewer 加入（仅 host 收到），viewerId 用于建立对应连接 */
        fun onViewerJoined(viewerId: Int)
        /** 某 viewer 离开（仅 host 收到） */
        fun onViewerLeft(viewerId: Int)
        /** host 离开（所有 viewer 收到） */
        fun onHostLeft()
        /** host 收到观看方「喊TA」（come-on）：对方在等你上屏 */
        fun onComeOn()
        /** 连接异常，但将在几秒后自动重试（仅提示，不应清理本地状态） */
        fun onRetrying(message: String)
        /** 服务器返回错误，或多次重连后仍失败 */
        fun onError(message: String)
        /** 连接关闭 */
        fun onClosed(reason: String)
    }

    private companion object {
        const val TAG = "SignalClient"
        // 可重复使用的 HttpClient：支持 WS 时 60s 空闲自动断线重连兜底
        private val httpClient by lazy {
            OkHttpClient.Builder()
                .pingInterval(20, TimeUnit.SECONDS)
                .build()
        }
        // 自动重连：最多重试次数与基础间隔（1s、2s、3s...）
        const val MAX_ATTEMPTS = 4
        const val RETRY_BASE_MS = 1000L
        // V3.1: 应用层心跳间隔（保持 WS 活跃，防止移动网络假断开）
        const val HEARTBEAT_INTERVAL_MS = 10000L
        // V3.2: 心跳 pong 超时判定——超过 30s 未收到 pong 视为 WS 假死，主动重连
        const val PONG_TIMEOUT_MS = 30000L
    }

    private var webSocket: WebSocket? = null
    private var closedByUs = false
    private var code = ""
    private var asHost = false
    private var attempt = 0
    /** V4: 本端在房间中的 viewerId（host 恒为 0；viewer 为服务器分配） */
    private var myViewerId = 0
    // 信令待发队列：WS 未就绪（断开/重连中）时缓存 relay 消息，连接恢复后统一补发，
    // 避免网络波动瞬间 SDP/ICE 发送静默丢失导致连接卡死
    private val pendingRelays = java.util.concurrent.ConcurrentLinkedQueue<String>()
    // V3.1: 应用层心跳——10s 周期 ping 保持连接活跃，防止移动网络假断开
    private val heartbeatHandler = android.os.Handler(android.os.Looper.getMainLooper())
    // 自动重连 Handler：disconnect 时必须移除待执行的重连任务，避免退出后仍发起连接
    private val retryHandler = android.os.Handler(android.os.Looper.getMainLooper())
    // V3.2: 心跳假死检测——超过 30s 未收到 pong 视为 WebSocket 假死，主动断开并自动重连
    private var lastPongMs = 0L
    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            if (closedByUs) return
            val now = System.currentTimeMillis()
            // 已建立过 pong 基线且长期收不到 pong → 假死，主动取消连接触发 onFailure 重连
            if (lastPongMs > 0 && now - lastPongMs > PONG_TIMEOUT_MS) {
                Log.w(TAG, "心跳超时(${now - lastPongMs}ms 无 pong)，判定 WS 假死，主动重连")
                webSocket?.cancel()
                webSocket = null
                scheduleRetry("信令心跳超时，自动重连中...")
                return
            }
            webSocket?.send("""{"type":"ping"}""")
            heartbeatHandler.postDelayed(this, HEARTBEAT_INTERVAL_MS)
        }
    }

    /** 创建房间（共享方）或加入房间（观看方）。code 为 4 位口令。 */
    fun connect(code: String, asHost: Boolean) {
        closedByUs = false
        this.code = code
        this.asHost = asHost
        attempt = 0
        myViewerId = 0
        pendingRelays.clear()
        tryConnect()
    }

    private fun tryConnect() {
        attempt++
        Log.d(TAG, "WS 连接尝试 #$attempt")
        val request = Request.Builder().url(url).build()
        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                attempt = 0
                Log.d(TAG, "WS 已连接，发送 create/join: code=$code asHost=$asHost")
                val msg = JSONObject().apply {
                    put("type", if (asHost) "create" else "join")
                    put("code", code)
                }
                webSocket.send(msg.toString())
                // V3.1: 连接成功后启动应用层心跳
                lastPongMs = System.currentTimeMillis()
                heartbeatHandler.removeCallbacksAndMessages(null)
                heartbeatHandler.postDelayed(heartbeatRunnable, HEARTBEAT_INTERVAL_MS)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                heartbeatHandler.removeCallbacksAndMessages(null)
                if (closedByUs) return
                Log.e(TAG, "WS 连接失败: ${t.message}")
                scheduleRetry("无法连接信令服务器: ${t.message}")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                heartbeatHandler.removeCallbacksAndMessages(null)
                if (closedByUs) return
                Log.w(TAG, "WS 已关闭 code=$code reason=$reason")
                scheduleRetry("信令连接已断开，自动重连中...")
            }
        })
    }

    /** 自动重连：网络波动（如 Software caused connection abort）时几次重试通常能恢复 */
    private fun scheduleRetry(failMsg: String) {
        if (closedByUs) return
        if (attempt >= MAX_ATTEMPTS) {
            listener.onError(failMsg)
            return
        }
        val delayMs = RETRY_BASE_MS * attempt
        listener.onRetrying("信令连接异常，${delayMs / 1000} 秒后自动重试（第 $attempt/$MAX_ATTEMPTS 次）...")
        retryHandler.postDelayed({ tryConnect() }, delayMs)
    }

    private fun handleMessage(text: String) {
        val json = try { JSONObject(text) } catch (e: Exception) { return }
        val vid = json.optInt("viewerId", myViewerId)
        when (json.optString("type")) {
            "created" -> {
                // host：房间创建成功，等待 viewer 加入
                listener.onRoomReady("created", 0)
                flushPending()
            }
            "joined" -> {
                myViewerId = json.optInt("viewerId", 0)
                listener.onRoomReady("joined", myViewerId)
                flushPending()
            }
            "join-pending" -> listener.onJoinPending()
            "join-request" -> listener.onJoinRequest(json.optInt("viewerId", 0))
            "join-rejected" -> listener.onJoinRejected()
            "peer-ready" -> listener.onPeerReady()
            "viewer-joined" -> listener.onViewerJoined(json.optInt("viewerId", 0))
            "viewer-left" -> listener.onViewerLeft(json.optInt("viewerId", 0))
            "host-left" -> listener.onHostLeft()
            "come-on" -> listener.onComeOn()
            "relay" -> listener.onRelay(json.optString("data"), vid)
            "pong" -> { lastPongMs = System.currentTimeMillis() }
            "error" -> listener.onError(json.optString("message", "服务器错误"))
            else -> Log.w(TAG, "未知消息: ${json.optString("type")}")
        }
    }

    /** host 同意 viewer 加入 */
    fun acceptViewer(viewerId: Int) {
        webSocket?.send(JSONObject().apply {
            put("type", "accept")
            put("viewerId", viewerId)
        }.toString())
    }

    /** host 拒绝 viewer 加入 */
    fun rejectViewer(viewerId: Int) {
        webSocket?.send(JSONObject().apply {
            put("type", "reject")
            put("viewerId", viewerId)
        }.toString())
    }

    /** 观看方「喊TA」：通知 host 快点来上屏（服务器会把此消息转为 come-on 给 host） */
    fun sendPlsJoin() {
        val msg = JSONObject().apply {
            put("type", "pls-join")
            if (code.isNotBlank()) put("code", code)
        }
        val ws = webSocket
        if (ws == null || closedByUs) {
            // WS 未连接：入队等重连成功补发（come-on 意图不丢失）
            pendingRelays.offer(msg.toString())
            return
        }
        try { ws.send(msg.toString()) } catch (t: Throwable) {}
    }

    /** 发送信令数据到对端（SDP/ICE 编码串）。host 发给指定 viewer 需传 viewerId；viewer 发 host 传 0 即可 */
    fun sendRelay(data: String, viewerId: Int = 0) {
        val msg = JSONObject().apply {
            put("type", "relay")
            put("data", data)
            if (asHost && viewerId > 0) put("viewerId", viewerId)
        }
        val ws = webSocket
        if (ws == null || closedByUs) {
            // WS 未连接或已主动断开：缓存，等重连成功后补发（onOpen → flushPending）
            pendingRelays.offer(msg.toString())
            return
        }
        val ok = try {
            ws.send(msg.toString())
        } catch (t: Throwable) {
            false
        }
        if (!ok) {
            Log.w(TAG, "relay 发送失败（WS 未就绪），已入待发队列")
            pendingRelays.offer(msg.toString())
        }
    }

    /** 连接恢复后补发缓存中的信令消息（onOpen 时调用），保证 SDP/ICE 不因网络波动丢失 */
    private fun flushPending() {
        while (true) {
            val m = pendingRelays.poll() ?: break
            val sent = try {
                webSocket?.send(m) ?: false
            } catch (t: Throwable) {
                false
            }
            if (!sent) {
                // 又失败了：放回队首，等待下次重连补发
                pendingRelays.offer(m)
                break
            }
        }
        if (pendingRelays.isNotEmpty()) {
            Log.d(TAG, "补发完成，剩余 ${pendingRelays.size} 条待下次连接")
        }
    }

    fun disconnect() {
        closedByUs = true
        heartbeatHandler.removeCallbacksAndMessages(null)
        retryHandler.removeCallbacksAndMessages(null)
        webSocket?.close(1000, "bye")
        webSocket = null
    }
}
