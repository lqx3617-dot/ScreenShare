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
        /** 服务器创建/加入房间成功（host 会带 token），可以开始屏幕采集与 SDP 交换 */
        fun onRoomReady(role: String, viewerId: Int)
        /** 对端已加入房间，等待/开始交换信令 */
        fun onPeerReady()
        /** 收到对端转发的信令数据（SDP/ICE 编码串）；viewerId 标识来源/目标 viewer（host 多路用） */
        fun onRelay(data: String, viewerId: Int)
        /** 新 viewer 加入（仅 host 收到），viewerId 用于建立对应连接 */
        fun onViewerJoined(viewerId: Int)
        /** 某 viewer 离开（仅 host 收到） */
        fun onViewerLeft(viewerId: Int)
        /** host 离开（所有 viewer 收到） */
        fun onHostLeft()
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
    private var token = ""
    private var asHost = false
    private var attempt = 0
    /** V4: 本端在房间中的 viewerId（host 恒为 0；viewer 为服务器分配） */
    private var myViewerId = 0
    // V3.1: 应用层心跳——10s 周期 ping 保持连接活跃，防止移动网络假断开
    private val heartbeatHandler = android.os.Handler(android.os.Looper.getMainLooper())
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

    /** 创建房间（共享方，token 为空由服务器生成）或加入房间（观看方，需填 token）。code 为 4 位口令。 */
    fun connect(code: String, asHost: Boolean, token: String = "") {
        closedByUs = false
        this.code = code
        this.asHost = asHost
        this.token = token
        attempt = 0
        myViewerId = 0
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
                    if (!asHost) put("token", token)
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
        if (attempt >= MAX_ATTEMPTS) {
            listener.onError(failMsg)
            return
        }
        val delayMs = RETRY_BASE_MS * attempt
        listener.onRetrying("信令连接异常，${delayMs / 1000} 秒后自动重试（第 $attempt/$MAX_ATTEMPTS 次）...")
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ tryConnect() }, delayMs)
    }

    private fun handleMessage(text: String) {
        val json = try { JSONObject(text) } catch (e: Exception) { return }
        val vid = json.optInt("viewerId", myViewerId)
        when (json.optString("type")) {
            "created" -> {
                // host：保存服务器下发的房间 token（join 时需告知 viewer）
                token = json.optString("token", token)
                listener.onRoomReady("created", 0)
            }
            "joined" -> {
                myViewerId = json.optInt("viewerId", 0)
                listener.onRoomReady("joined", myViewerId)
            }
            "peer-ready" -> listener.onPeerReady()
            "viewer-joined" -> listener.onViewerJoined(json.optInt("viewerId", 0))
            "viewer-left" -> listener.onViewerLeft(json.optInt("viewerId", 0))
            "host-left" -> listener.onHostLeft()
            "relay" -> listener.onRelay(json.optString("data"), vid)
            "pong" -> { lastPongMs = System.currentTimeMillis() }
            "error" -> listener.onError(json.optString("message", "服务器错误"))
            else -> Log.w(TAG, "未知消息: ${json.optString("type")}")
        }
    }

    /** 发送信令数据到对端（SDP/ICE 编码串）。host 发给指定 viewer 需传 viewerId；viewer 发 host 传 0 即可 */
    fun sendRelay(data: String, viewerId: Int = 0) {
        val msg = JSONObject().apply {
            put("type", "relay")
            put("data", data)
            if (asHost && viewerId > 0) put("viewerId", viewerId)
        }
        webSocket?.send(msg.toString())
    }

    /** 获取服务器分配的 token（host 创建房间后有效，用于告知 viewer join） */
    fun getToken(): String = token

    fun disconnect() {
        closedByUs = true
        heartbeatHandler.removeCallbacksAndMessages(null)
        webSocket?.close(1000, "bye")
        webSocket = null
    }
}
