package com.screenshare.albumviewer

import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 远程相册同步：观看方中继客户端。
 *
 * 相册 APP 输入 8 位设备码后，连上公网中继（relay-server 8097）发送
 * {"type":"relay-sync","deviceCode":...,"action":"start"}，由中继转发给在线共享方，
 * 触发共享方后台将本机相册上传到相册服务器。
 *
 * 中继对观看方是短连接：发送指令后即关闭，随后观看方改从相册服务器轮询进度。
 * 若共享方离线，中继会在同一连接上回 {"type":"relay-sync-ack","error":"..."}。
 */
class RelayClient(
    private val onAck: (error: String?) -> Unit,
) {
    companion object {
        private const val TAG = "RelayClient"
        private const val TIMEOUT_MS = 15000L
        private val client by lazy {
            OkHttpClient.Builder()
                .pingInterval(10, TimeUnit.SECONDS)
                .build()
        }
    }

    private var ws: WebSocket? = null
    private val started = AtomicBoolean(false)
    private val replied = AtomicBoolean(false)
    private var timeoutRunnable: Runnable? = null

    /** 终态自动复位：任何终态（成功/失败/超时/关闭）后允许下次再次发送 */
    private fun finishAndReset() {
        timeoutRunnable?.let { android.os.Handler(android.os.Looper.getMainLooper()).removeCallbacks(it) }
        timeoutRunnable = null
        started.set(false)
        replied.set(false)
        try { ws?.cancel() } catch (t: Throwable) {}
        ws = null
    }

    /** 发送开启同步指令；回调 onAck(offset) —— 共享方在线时 error=null，离线时 error=提示 */
    fun sendSyncStart(deviceCode: String) {
        if (started.getAndSet(true)) return
        val url = BuildConfig.RELAY_URL
        if (url.isBlank()) {
            started.set(false)
            onAck("中继服务器未配置")
            return
        }
        Log.i(TAG, "连接中继发送同步指令 deviceCode=$deviceCode")
        val request = Request.Builder().url(url).build()
        ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val msg = JSONObject().apply {
                    put("type", "relay-sync")
                    put("deviceCode", deviceCode)
                    put("action", "start")
                }
                Log.i(TAG, "已连接中继，发送: $msg")
                webSocket.send(msg.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val json = try { JSONObject(text) } catch (t: Throwable) { return }
                when (json.optString("type")) {
                    "relay-sync-ack" -> {
                        val err = json.optString("error", "").ifBlank { null }
                        Log.i(TAG, "中继回执 error=$err")
                        if (replied.compareAndSet(false, true)) {
                            onAck(err)
                        }
                        finishAndReset()
                    }
                    else -> Log.d(TAG, "中继消息: ${json.optString("type")}")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "中继连接失败: ${t.message}")
                if (replied.compareAndSet(false, true)) {
                    onAck("无法连接中继服务器，请检查网络")
                }
                finishAndReset()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                finishAndReset()
            }
        })
        // 兜底超时：中继 15s 无回执时通知 UI（共享方可能已收到但 ack 丢失，视为已触发）
        val runnable = Runnable {
            if (replied.compareAndSet(false, true)) {
                onAck(null)
            }
            finishAndReset()
        }
        timeoutRunnable = runnable
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(runnable, TIMEOUT_MS)
    }

    /** 清除上次状态，允许再次发送 */
    fun reset() {
        finishAndReset()
    }
}
