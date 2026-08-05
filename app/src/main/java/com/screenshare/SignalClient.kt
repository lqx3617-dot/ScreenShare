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
        fun onRoomReady(role: String)
        /** 对端已加入房间，等待/开始交换信令 */
        fun onPeerReady()
        /** 收到对端转发的信令数据（SDP/ICE 编码串） */
        fun onRelay(data: String)
        /** 对端已离开 */
        fun onPeerLeft()
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
    }

    private var webSocket: WebSocket? = null
    private var closedByUs = false
    private var code = ""
    private var asHost = false
    private var attempt = 0

    /** 创建房间（共享方）或加入房间（观看方）。code 为 4-12 位口令。 */
    fun connect(code: String, asHost: Boolean) {
        closedByUs = false
        this.code = code
        this.asHost = asHost
        attempt = 0
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
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (closedByUs) return
                Log.e(TAG, "WS 连接失败: ${t.message}")
                scheduleRetry("无法连接信令服务器: ${t.message}")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
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
        when (json.optString("type")) {
            "created", "joined" -> listener.onRoomReady(json.optString("type"))
            "peer-ready" -> listener.onPeerReady()
            "relay" -> listener.onRelay(json.optString("data"))
            "peer-left" -> listener.onPeerLeft()
            "pong" -> {}
            "error" -> listener.onError(json.optString("message", "服务器错误"))
            else -> Log.w(TAG, "未知消息: ${json.optString("type")}")
        }
    }

    /** 发送信令数据到对端（SDP/ICE 编码串） */
    fun sendRelay(data: String) {
        val msg = JSONObject().apply {
            put("type", "relay")
            put("data", data)
        }
        webSocket?.send(msg.toString())
    }

    fun disconnect() {
        closedByUs = true
        webSocket?.close(1000, "bye")
        webSocket = null
    }
}
