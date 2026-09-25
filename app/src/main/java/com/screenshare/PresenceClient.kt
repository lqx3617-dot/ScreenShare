package com.screenshare

import android.os.Handler
import android.os.Looper
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
        /** 对方结束了会议，暂存邀请作废，应关掉已弹出的邀请框 */
        fun onInviteCancelled(inviteId: String)
        /** 情侣邀请：对方想与我绑定 */
        fun onCoupleInvite(fromNickname: String)
        /** 情侣绑定成功（对方接受了我的邀请，或我接受了对方的邀请） */
        fun onCoupleBound(partnerNickname: String)
        /** 情侣关系被对方解除 */
        fun onCoupleDissolved()
        /** 对方完成了每日打卡 */
        fun onCoupleCheckin(fromNickname: String)
        /** 连接异常（将自动重连，仅提示） */
        fun onRetrying(message: String)
        /** 不可恢复错误 */
        fun onError(message: String)
    }

    private companion object {
        const val TAG = "PRESENCE"
        const val MAX_ATTEMPTS = 0 // 0 = 无限重连，账号长连接必须常驻
        const val RETRY_BASE_MS = 2000L
        const val RETRY_MAX_MS = 30000L
        const val HEARTBEAT_INTERVAL_MS = 20000L
    }

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }
    private val handler = Handler(Looper.getMainLooper())
    // 读写跨越 UI 线程与 OkHttp WebSocket 回调线程，需保证可见性
    @Volatile private var webSocket: WebSocket? = null
    @Volatile private var token: String = ""
    @Volatile private var closedByUs = false
    @Volatile private var attempt = 0
    @Volatile private var authed = false

    /** WS 已连接且 auth 认领完成，可发消息 */
    val isReady: Boolean
        get() = !closedByUs && authed && webSocket != null

    private fun log(msg: String) = AppLogger.app("[$TAG] $msg")

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
        log("WS 连接尝试 #$attempt url=$url")
        val request = Request.Builder().url(url).build()
        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                log("WS 已连接，发送 auth 认领")
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
                authed = false
                if (closedByUs) return
                log("WS 异常: ${t.message}")
                scheduleRetry("账号连接异常")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                handler.removeCallbacks(heartbeatRunnable)
                authed = false
                if (closedByUs) return
                log("WS 已断开 code=$code reason=$reason")
                scheduleRetry("账号连接已断开")
            }
        })
    }

    private fun scheduleRetry(failMsg: String) {
        if (closedByUs) return
        if (MAX_ATTEMPTS > 0 && attempt >= MAX_ATTEMPTS) {
            log("重连次数耗尽，放弃: $failMsg")
            listener.onError(failMsg)
            return
        }
        // 指数退避，封顶 30s：账号长连接要一直挂着，不能因为短时网络抖动永久下线
        val delayMs = (RETRY_BASE_MS shl minOf(attempt, 4)).coerceAtMost(RETRY_MAX_MS)
        log("将在 ${delayMs}ms 后重连（第 $attempt 次失败）")
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
            // host 结束会议时，暂存邀请作废：关掉被邀请方已弹出的邀请框
            "invite-cancelled" -> listener.onInviteCancelled(json.optString("inviteId"))
            "couple-invite" -> {
                val from = json.optJSONObject("from")
                listener.onCoupleInvite(from?.optString("nickname") ?: "")
            }
            "couple-bound" -> {
                val partner = json.optJSONObject("partner")
                listener.onCoupleBound(partner?.optString("nickname") ?: "")
            }
            "couple-dissolved" -> listener.onCoupleDissolved()
            "couple-checkin" -> {
                val from = json.optJSONObject("from")
                listener.onCoupleCheckin(from?.optString("nickname") ?: "TA")
            }
            "pong" -> Unit
            "error" -> listener.onError(json.optString("message", "服务器错误"))
            else -> log("未知消息: ${json.optString("type")}")
        }
    }

    /** 向好友发起共享邀请（建房后调用，把房间号定向推给对方） */
    /** 发送共享邀请；返回是否已投递到已认证的连接 */
    fun sendShareInvite(toUserId: String, code: String): Boolean {
        return send(JSONObject().apply {
            put("type", "share-invite")
            put("toUserId", toUserId)
            put("code", code)
        }.toString(), "share-invite -> ${toUserId.take(8)} room=$code")
    }

    /** 接受对方的共享邀请 */
    fun acceptShareInvite(inviteId: String): Boolean {
        return send(JSONObject().apply {
            put("type", "share-invite-accept")
            put("inviteId", inviteId)
        }.toString(), "share-invite-accept")
    }

    /** 拒绝对方的共享邀请 */
    fun rejectShareInvite(inviteId: String): Boolean {
        return send(JSONObject().apply {
            put("type", "share-invite-reject")
            put("inviteId", inviteId)
        }.toString(), "share-invite-reject")
    }

    /**
     * 投递一条消息；返回是否真的送进已认证的 WS。
     * 未连接/auth 未完成/ws.send 返回 false（队列已满或连接已关）时返回 false，
     * 调用方据此决定等待重连还是提示用户，不能像以前一样默默吞掉。
     */
    private fun send(msg: String, desc: String): Boolean {
        val ws = webSocket
        if (ws == null || closedByUs) {
            log("发送失败（无连接）：$desc")
            return false
        }
        if (!authed) {
            log("发送失败（未 auth）：$desc")
            return false
        }
        return try {
            val ok = ws.send(msg)
            if (ok) log("已发送：$desc") else log("发送失败（send 返回 false）：$desc")
            ok
        } catch (t: Throwable) {
            log("发送异常：$desc ${t.message}")
            false
        }
    }

    fun disconnect() {
        closedByUs = true
        handler.removeCallbacks(heartbeatRunnable)
        handler.removeCallbacksAndMessages(null)
        try { webSocket?.close(1000, "bye") } catch (t: Throwable) {}
        webSocket = null
    }
}
