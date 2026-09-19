package com.screenshare

import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 账号在线状态客户端：App 启动后建立长连接，完成 auth 认领，
 * 接收好友在线状态（presence）、好友申请（friend-request/accepted）与共享邀请（share-invite）。
 *
 * 与房间信令 SignalClient 分离：那条连接按需建立（create/join 房间），
 * 本连接常驻，服务端按 userId 聚合多连接在线状态，两者互不影响。
 */
class PresenceClient(
    private val url: String,
    private val listener: Listener
) {
    interface Listener {
        /** auth 认领成功 */
        fun onAuthed(userId: String)
        /** auth 失败（令牌无效/过期），应清除本地登录并跳登录页 */
        fun onAuthFailed()
        /** 好友上下线状态变化 */
        fun onPresence(userId: String, online: Boolean)
        /** 收到好友申请 */
        fun onFriendRequest(requestId: String, fromUserId: String, fromNickname: String)
        /** 好友申请被对方同意（或对方接受了我的申请） */
        fun onFriendAccepted(friendUserId: String, friendNickname: String)
        /** 收到共享邀请 */
        fun onShareInvite(inviteId: String, code: String, fromUserId: String, fromNickname: String)
        /** 邀请被对方接受/拒绝 */
        fun onShareInviteResult(inviteId: String, accepted: Boolean, reason: String)
        /** 连接异常（将自动重连，仅提示） */
        fun onRetrying(message: String)
        /** 不可恢复错误 */
        fun onError(message: String)
    }

    private companion object {
        const val TAG = "PresenceClient"
        const val MAX_ATTEMPTS = 6
        const val RETRY_BASE_MS = 2000L
        const val HEARTBEAT_INTERVAL_MS = 20000L
    }

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }
    private val handler = Handler(Looper.getMainLooper())
    private var webSocket: WebSocket? = null
    private var token: String = ""
    private var closedByUs = false
    private var attempt = 0
    private var authed = false

    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            if (closedByUs) return
            webSocket?.send("""{"type":"ping"}""")
            handler.postDelayed(this, HEARTBEAT_INTERVAL_MS)
        }
    }

    /** 建立连接并用令牌认领身份 */
    fun connect(token: String) {
        closedByUs = false
        this.token = token
        attempt = 0
        authed = false
        tryConnect()
    }

    private fun tryConnect() {
        attempt++
        Log.d(TAG, "WS 连接尝试 #$attempt")
        val request = Request.Builder().url(url).build()
        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WS 已连接，发送 auth 认领")
                attempt = 0
                // 认领身份；服务端校验后回 auth-ok / auth-error
                webSocket.send(JSONObject().apply {
                    put("type", "auth")
                    put("token", this@PresenceClient.token)
                }.toString())
                handler.postDelayed(heartbeatRunnable, HEARTBEAT_INTERVAL_MS)
            }

            override fun onMessage(webSocket: WebSocket, text: String) = handleMessage(text)

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                handler.removeCallbacks(heartbeatRunnable)
                if (closedByUs) return
                Log.w(TAG, "WS 异常: ${t.message}")
                scheduleRetry("账号连接异常")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                handler.removeCallbacks(heartbeatRunnable)
                if (closedByUs) return
                scheduleRetry("账号连接已断开")
            }
        })
    }

    private fun scheduleRetry(failMsg: String) {
        if (closedByUs) return
        if (attempt >= MAX_ATTEMPTS) {
            listener.onError(failMsg)
            return
        }
        val delayMs = RETRY_BASE_MS * attempt
        listener.onRetrying("$failMsg，${delayMs / 1000} 秒后重试（第 $attempt/$MAX_ATTEMPTS 次）")
        handler.postDelayed({ tryConnect() }, delayMs)
    }

    private fun handleMessage(text: String) {
        if (closedByUs) return
        val json = try { JSONObject(text) } catch (e: Exception) { return }
        when (json.optString("type")) {
            "auth-ok" -> {
                authed = true
                listener.onAuthed(json.optString("userId"))
            }
            "auth-error" -> {
                authed = false
                listener.onAuthFailed()
            }
            "presence" -> listener.onPresence(json.optString("userId"), json.optBoolean("online", false))
            "friend-request" -> {
                val from = json.optJSONObject("from")
                listener.onFriendRequest(
                    json.optString("requestId"),
                    from?.optString("userId") ?: "",
                    from?.optString("nickname") ?: ""
                )
            }
            "friend-accepted" -> {
                val friend = json.optJSONObject("friend")
                listener.onFriendAccepted(
                    friend?.optString("userId") ?: "",
                    friend?.optString("nickname") ?: ""
                )
            }
            "share-invite" -> {
                val from = json.optJSONObject("from")
                listener.onShareInvite(
                    json.optString("inviteId"),
                    json.optString("code"),
                    from?.optString("userId") ?: "",
                    from?.optString("nickname") ?: ""
                )
            }
            "share-invite-result" -> listener.onShareInviteResult(
                json.optString("inviteId"),
                json.optBoolean("accepted", false),
                json.optString("reason")
            )
            "pong" -> Unit
            "error" -> listener.onError(json.optString("message", "服务器错误"))
            else -> Log.w(TAG, "未知消息: ${json.optString("type")}")
        }
    }

    /** 向好友发起共享邀请（建房后调用，把房间号定向推给对方） */
    fun sendShareInvite(toUserId: String, code: String) {
        send(JSONObject().apply {
            put("type", "share-invite")
            put("toUserId", toUserId)
            put("code", code)
        }.toString())
    }

    /** 接受对方的共享邀请 */
    fun acceptShareInvite(inviteId: String) {
        send(JSONObject().apply {
            put("type", "share-invite-accept")
            put("inviteId", inviteId)
        }.toString())
    }

    /** 拒绝对方的共享邀请 */
    fun rejectShareInvite(inviteId: String) {
        send(JSONObject().apply {
            put("type", "share-invite-reject")
            put("inviteId", inviteId)
        }.toString())
    }

    private fun send(msg: String) {
        val ws = webSocket
        if (ws == null || closedByUs) {
            Log.w(TAG, "WS 未就绪，消息未发送: $msg")
            return
        }
        try { ws.send(msg) } catch (t: Throwable) {}
    }

    fun disconnect() {
        closedByUs = true
        handler.removeCallbacks(heartbeatRunnable)
        handler.removeCallbacksAndMessages(null)
        try { webSocket?.close(1000, "bye") } catch (t: Throwable) {}
        webSocket = null
    }
}
