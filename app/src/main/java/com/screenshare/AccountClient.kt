package com.screenshare

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 账号/好友 REST 客户端（复用 okhttp）。
 * base URL 由信令地址同源推导：wss://host/ws -> https://host。
 * 所有接口返回 ApiResult，UI 层按 code 处理错误（401 统一要求重新登录）。
 */
object AccountClient {

    sealed class ApiResult<out T> {
        data class Success<T>(val data: T) : ApiResult<T>()
        data class Failure(val code: String, val message: String, val http: Int) : ApiResult<Nothing>()
    }

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val baseUrl: String = run {
        // 信令地址形如 wss://host/ws 或 ws://host/ws，账号 REST 同源走 https/http
        val host = BuildConfig.SIGNAL_URL.substringBefore("/ws")
        host.replaceFirst("wss://", "https://").replaceFirst("ws://", "http://")
    }

    data class Profile(
        val userId: String,
        val nickname: String,
        val avatar: String,
        val friendCode: String
    )

    data class LoginResult(val userId: String, val token: String, val profile: Profile)

    data class FriendItem(
        val userId: String,
        val nickname: String,
        val avatar: String,
        val online: Boolean
    )

    data class FriendRequestItem(val requestId: String, val from: Profile, val createdAt: Long)

    private suspend fun callRaw(
        method: String,
        path: String,
        body: JSONObject? = null,
        token: String? = null
    ): ApiResult<String> = withContext(Dispatchers.IO) {
        try {
            val builder = Request.Builder().url(baseUrl + path)
            if (!token.isNullOrBlank()) builder.header("Authorization", "Bearer $token")
            if (body != null) {
                builder.method(method, body.toString().toRequestBody(JSON_MEDIA))
            } else {
                builder.method(method, null)
            }
            client.newCall(builder.build()).execute().use { res ->
                val text = res.body?.string() ?: ""
                if (res.code in 200..299) {
                    ApiResult.Success(text)
                } else {
                    val parsed = if (text.isNotEmpty()) runCatching { JSONObject(text) }.getOrNull() else null
                    val errCode = parsed?.optString("error") ?: ""
                    val errMsg = parsed?.optString("message") ?: ""
                    ApiResult.Failure(
                        errCode.ifBlank { "http_${res.code}" },
                        errMsg.ifBlank { "网络错误（${res.code}）" },
                        res.code
                    )
                }
            }
        } catch (e: Exception) {
            ApiResult.Failure("network", "网络异常，请检查网络连接", -1)
        }
    }

    private suspend fun call(
        method: String,
        path: String,
        body: JSONObject? = null,
        token: String? = null
    ): ApiResult<JSONObject> = when (val r = callRaw(method, path, body, token)) {
        is ApiResult.Success -> {
            val o = if (r.data.isNotEmpty()) runCatching { JSONObject(r.data) }.getOrNull() else JSONObject()
            if (o != null) ApiResult.Success(o) else ApiResult.Failure("invalid_response", "响应格式错误", -1)
        }
        is ApiResult.Failure -> r
    }

    private fun parseProfile(o: JSONObject) = Profile(
        userId = o.getString("userId"),
        nickname = o.optString("nickname"),
        avatar = o.optString("avatar").ifBlank { "0" },
        friendCode = o.optString("friendCode")
    )

    private fun parseLogin(o: JSONObject) = LoginResult(
        userId = o.getString("userId"),
        token = o.getString("token"),
        profile = parseProfile(o.getJSONObject("profile"))
    )

    suspend fun register(nickname: String, password: String): ApiResult<LoginResult> {
        val r = call(
            "POST", "/account/register",
            JSONObject()
                .put("nickname", nickname)
                .put("password", password)
        )
        return when (r) {
            is ApiResult.Success -> ApiResult.Success(parseLogin(r.data))
            is ApiResult.Failure -> r
        }
    }

    suspend fun login(nickname: String, password: String): ApiResult<LoginResult> {
        val r = call(
            "POST", "/account/login",
            JSONObject().put("nickname", nickname).put("password", password)
        )
        return when (r) {
            is ApiResult.Success -> ApiResult.Success(parseLogin(r.data))
            is ApiResult.Failure -> r
        }
    }

    suspend fun logout(token: String) = call("POST", "/account/logout", JSONObject(), token)

    suspend fun getMe(token: String): ApiResult<Profile> {
        val r = call("GET", "/account/me", null, token)
        return when (r) {
            is ApiResult.Success -> ApiResult.Success(parseProfile(r.data))
            is ApiResult.Failure -> r
        }
    }

    suspend fun updateProfile(token: String, nickname: String? = null, avatar: String? = null): ApiResult<Profile> {
        val body = JSONObject()
        if (nickname != null) body.put("nickname", nickname)
        if (avatar != null) body.put("avatar", avatar)
        val r = call("PATCH", "/account/profile", body, token)
        return when (r) {
            is ApiResult.Success -> ApiResult.Success(parseProfile(r.data))
            is ApiResult.Failure -> r
        }
    }

    suspend fun getFriends(token: String): ApiResult<List<FriendItem>> {
        val r = callRaw("GET", "/friends", null, token)
        return when (r) {
            is ApiResult.Success -> {
                val arr = if (r.data.isNotEmpty()) JSONArray(r.data) else JSONArray()
                ApiResult.Success((0 until arr.length()).map { parseFriendItem(arr.getJSONObject(it)) })
            }
            is ApiResult.Failure -> r
        }
    }

    private fun parseFriendItem(o: JSONObject) = FriendItem(
        userId = o.getString("userId"),
        nickname = o.optString("nickname"),
        avatar = o.optString("avatar").ifBlank { "0" },
        online = o.optBoolean("online", false)
    )

    suspend fun getFriendRequests(token: String): ApiResult<List<FriendRequestItem>> {
        val r = callRaw("GET", "/friends/requests", null, token)
        return when (r) {
            is ApiResult.Success -> {
                val arr = if (r.data.isNotEmpty()) JSONArray(r.data) else JSONArray()
                val list = (0 until arr.length()).map {
                    val o = arr.getJSONObject(it)
                    FriendRequestItem(
                        requestId = o.getString("requestId"),
                        from = parseProfile(o.getJSONObject("from")),
                        createdAt = o.optLong("createdAt")
                    )
                }
                ApiResult.Success(list)
            }
            is ApiResult.Failure -> r
        }
    }

    suspend fun sendFriendRequest(token: String, friendCode: String) =
        call("POST", "/friends/request", JSONObject().put("friendCode", friendCode.trim().uppercase()), token)

    suspend fun acceptFriend(token: String, requestId: String) =
        call("POST", "/friends/accept", JSONObject().put("requestId", requestId), token)

    suspend fun rejectFriend(token: String, requestId: String) =
        call("POST", "/friends/reject", JSONObject().put("requestId", requestId), token)

    suspend fun removeFriend(token: String, friendId: String) =
        call("DELETE", "/friends/$friendId", null, token)
}
