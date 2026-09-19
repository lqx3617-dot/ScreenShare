package com.screenshare

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 「喊TA」轻量投递：用临时 WebSocket 短连接发送 pls-join 后立即断开（不进入房间）。
 * HomeFragment 与 MeetingActivity 原各自维护一份实现，逻辑与错误提示不一致，合并于此。
 */
object PlsJoinSender {
    private val mainHandler = Handler(Looper.getMainLooper())
    // 共享 client：每次新建 OkHttpClient 会重复创建线程池/连接池，旧实例只能等 GC
    // 回收，频繁「喊TA」会累积线程
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    fun send(context: Context, code: String) {
        val appCtx = context.applicationContext
        val url = BuildConfig.SIGNAL_URL
        if (url.isNullOrBlank()) {
            mainHandler.post { Toast.makeText(appCtx, "信令服务未配置，无法呼叫", Toast.LENGTH_LONG).show() }
            return
        }
        val wsReq = Request.Builder().url(url).build()
        val ws = client.newWebSocket(wsReq, object : okhttp3.WebSocketListener() {
            override fun onOpen(webSocket: okhttp3.WebSocket, response: okhttp3.Response) {
                webSocket.send(JSONObject().apply { put("type", "pls-join"); put("code", code) }.toString())
                mainHandler.postDelayed({ webSocket.close(1000, "done") }, 1200)
            }

            override fun onMessage(webSocket: okhttp3.WebSocket, text: String) {
                val j = try { JSONObject(text) } catch (e: Exception) { return }
                if (j.optString("type") == "error") {
                    val msg = j.optString("message", "")
                    mainHandler.post { Toast.makeText(appCtx, if (msg.isBlank()) "提醒失败" else msg, Toast.LENGTH_SHORT).show() }
                    webSocket.close(1000, "err")
                }
            }

            override fun onFailure(webSocket: okhttp3.WebSocket, t: Throwable, response: okhttp3.Response?) {
                mainHandler.post { Toast.makeText(appCtx, "提醒失败：无法连接服务器", Toast.LENGTH_SHORT).show() }
            }
        })
        // 兜底延时关闭，防止 onOpen 未触发时连接悬挂
        mainHandler.postDelayed({ ws.cancel() }, 3000)
    }
}
