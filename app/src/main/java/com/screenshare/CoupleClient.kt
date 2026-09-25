package com.screenshare

import org.json.JSONArray
import org.json.JSONObject

/**
 * 情侣绑定与情侣空间 REST 客户端。
 * 复用 AccountClient 的网络层（baseUrl / client / call），接口对齐服务端 couple 路由。
 */
object CoupleClient {

    data class CoupleInvitation(
        val invitationId: String,
        val fromNickname: String,
        val fromAvatar: String,
        val createdAt: Long
    )

    data class Partner(
        val userId: String,
        val nickname: String,
        val avatar: String,
        val online: Boolean
    )

    data class LocInfo(
        val lat: Double,
        val lng: Double,
        val reportedAt: Long,
        /** 逆地理编码地址（服务端高德解码，未配置 Key 时为 null，客户端降级经纬度） */
        val address: String? = null
    )

    data class CoupleSpace(
        val bound: Boolean,
        val days: Int = 0,
        val boundAt: Long = 0,
        val partner: Partner? = null,
        val location: LocInfo? = null,
        val photoCount: Int = 0,
        val anniversary: String? = null,
        val weather: Weather? = null,
        val checkin: CheckinStatus? = null
    )

    /** 伴侣所在地实时天气（高德，服务端 10 分钟缓存） */
    data class Weather(
        val city: String,
        val text: String,
        val temp: String,
        val humidity: String,
        val wind: String
    )

    /** 每日打卡状态（我/对方） */
    data class CheckinSide(val today: Boolean, val streak: Int)
    data class CheckinStatus(val me: CheckinSide, val partner: CheckinSide)

    /** 共同愿望清单条目 */
    data class Wish(
        val wishId: String,
        val text: String,
        val done: Boolean,
        val doneBy: String,
        val doneAt: Long,
        val createdAt: Long,
        val authorNickname: String
    )

    data class CoupleMedia(
        val mediaId: String,
        val mediaType: String, // photo | video
        val url: String,
        val thumbUrl: String?,
        val durationMs: Long?,
        val uploader: String,
        val createdAt: Long
    )

    data class VideoSession(val videoId: String, val chunkSize: Int)

    /** JSON null / 缺失 / 空串统一归为 null（org.json 的 optString 遇 JSONObject.NULL 会返回字符串 "null"） */
    private fun JSONObject.strOrNull(name: String): String? =
        if (!has(name) || isNull(name)) null else optString(name).takeIf { it.isNotBlank() }

    suspend fun invite(token: String, friendCode: String): AccountClient.ApiResult<JSONObject> =
        AccountClient.call("POST", "/couple/invite", JSONObject().put("friendCode", friendCode), token)

    suspend fun getInvitations(token: String): AccountClient.ApiResult<List<CoupleInvitation>> =
        AccountClient.callRaw("GET", "/couple/invitations", null, token).parseArr { o ->
            CoupleInvitation(
                invitationId = o.getString("invitationId"),
                fromNickname = o.optJSONObject("from")?.optString("nickname") ?: "",
                fromAvatar = o.optJSONObject("from")?.strOrNull("avatar") ?: "0",
                createdAt = o.optLong("createdAt")
            )
        }

    suspend fun accept(token: String, invitationId: String): AccountClient.ApiResult<JSONObject> =
        AccountClient.call("POST", "/couple/accept", JSONObject().put("invitationId", invitationId), token)

    suspend fun reject(token: String, invitationId: String): AccountClient.ApiResult<JSONObject> =
        AccountClient.call("POST", "/couple/reject", JSONObject().put("invitationId", invitationId), token)

    suspend fun getSpace(token: String): AccountClient.ApiResult<CoupleSpace> =
        when (val r = AccountClient.call("GET", "/couple", null, token)) {
            is AccountClient.ApiResult.Success -> {
                val o = r.data
                val partner = o.optJSONObject("partner")
                val loc = o.optJSONObject("location")
                AccountClient.ApiResult.Success(
                    CoupleSpace(
                        bound = o.optBoolean("bound"),
                        days = o.optInt("days"),
                        boundAt = o.optLong("boundAt"),
                        partner = partner?.let {
                            Partner(
                                userId = it.optString("userId"),
                                nickname = it.optString("nickname"),
                                avatar = it.strOrNull("avatar") ?: "0",
                                online = it.optBoolean("online")
                            )
                        },
                        location = loc?.let {
                            LocInfo(
                                it.optDouble("lat"),
                                it.optDouble("lng"),
                                it.optLong("reportedAt"),
                                it.strOrNull("address")
                            )
                        },
                        photoCount = o.optInt("photoCount"),
                        anniversary = o.strOrNull("anniversary"),
                        weather = o.optJSONObject("weather")?.let {
                            Weather(
                                city = it.optString("city"),
                                text = it.optString("text"),
                                temp = it.optString("temp"),
                                humidity = it.optString("humidity"),
                                wind = it.optString("wind")
                            )
                        },
                        checkin = o.optJSONObject("checkin")?.let { ck ->
                            fun side(s: JSONObject) = CheckinSide(
                                today = s.optBoolean("today"),
                                streak = s.optInt("streak")
                            )
                            CheckinStatus(
                                me = side(ck.optJSONObject("me") ?: JSONObject()),
                                partner = side(ck.optJSONObject("partner") ?: JSONObject())
                            )
                        }
                    )
                )
            }
            is AccountClient.ApiResult.Failure -> r
        }

    suspend fun uploadPhoto(token: String, base64: String, mime: String): AccountClient.ApiResult<JSONObject> =
        AccountClient.call(
            "POST", "/couple/photos",
            JSONObject().put("photo", base64).put("mime", mime), token
        )

    suspend fun createVideo(
        token: String, size: Long, durationMs: Long, thumbBase64: String
    ): AccountClient.ApiResult<VideoSession> =
        when (val r = AccountClient.call(
            "POST", "/couple/videos",
            JSONObject()
                .put("size", size)
                .put("durationMs", durationMs)
                .put("thumb", thumbBase64), token
        )) {
            is AccountClient.ApiResult.Success -> AccountClient.ApiResult.Success(
                VideoSession(r.data.getString("videoId"), r.data.optInt("chunkSize", 3 * 1024 * 1024))
            )
            is AccountClient.ApiResult.Failure -> r
        }

    suspend fun uploadVideoChunk(
        token: String, videoId: String, offset: Int, chunkBase64: String
    ): AccountClient.ApiResult<JSONObject> =
        AccountClient.call(
            "POST", "/couple/videos/$videoId/chunks",
            JSONObject().put("offset", offset).put("chunk", chunkBase64), token
        )

    suspend fun finishVideo(token: String, videoId: String): AccountClient.ApiResult<JSONObject> =
        AccountClient.call("POST", "/couple/videos/$videoId/finish", JSONObject(), token)

    suspend fun getPhotos(token: String): AccountClient.ApiResult<List<CoupleMedia>> =
        AccountClient.callRaw("GET", "/couple/photos", null, token).parseArr { o ->
            CoupleMedia(
                mediaId = o.getString("mediaId"),
                mediaType = o.optString("mediaType", "photo"),
                url = o.optString("url"),
                thumbUrl = o.strOrNull("thumbUrl"),
                durationMs = if (o.has("durationMs") && !o.isNull("durationMs")) o.optLong("durationMs") else null,
                uploader = o.optString("uploader"),
                createdAt = o.optLong("createdAt")
            )
        }

    suspend fun deletePhoto(token: String, mediaId: String): AccountClient.ApiResult<JSONObject> =
        AccountClient.call("DELETE", "/couple/photos/$mediaId", null, token)

    suspend fun reportLocation(token: String, lat: Double, lng: Double): AccountClient.ApiResult<JSONObject> =
        AccountClient.call(
            "POST", "/couple/location",
            JSONObject().put("lat", lat).put("lng", lng), token
        )

    suspend fun dissolve(token: String): AccountClient.ApiResult<JSONObject> =
        AccountClient.call("POST", "/couple/dissolve", JSONObject(), token)

    suspend fun setAnniversary(token: String, date: String): AccountClient.ApiResult<JSONObject> =
        AccountClient.call(
            "PATCH", "/couple/anniversary",
            JSONObject().put("anniversary", date), token
        )

    suspend fun checkin(token: String): AccountClient.ApiResult<JSONObject> =
        AccountClient.call("POST", "/couple/checkin", JSONObject(), token)

    suspend fun getWishes(token: String): AccountClient.ApiResult<List<Wish>> {
        when (val r = AccountClient.callRaw("GET", "/couple/wishes", null, token)) {
            is AccountClient.ApiResult.Success -> {
                return try {
                    val arr = JSONArray(r.data)
                    val list = mutableListOf<Wish>()
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        list.add(
                            Wish(
                                wishId = o.optString("wishId"),
                                text = o.optString("text"),
                                done = o.optBoolean("done"),
                                doneBy = o.optString("doneBy"),
                                doneAt = o.optLong("doneAt"),
                                createdAt = o.optLong("createdAt"),
                                authorNickname = o.optString("authorNickname")
                            )
                        )
                    }
                    AccountClient.ApiResult.Success(list)
                } catch (e: Exception) {
                    AccountClient.ApiResult.Failure("invalid_response", "响应格式错误", -1)
                }
            }
            is AccountClient.ApiResult.Failure -> return r
        }
    }

    suspend fun addWish(token: String, text: String): AccountClient.ApiResult<JSONObject> =
        AccountClient.call("POST", "/couple/wishes", JSONObject().put("text", text), token)

    suspend fun toggleWish(token: String, wishId: String, done: Boolean): AccountClient.ApiResult<JSONObject> =
        AccountClient.call(
            "PATCH", "/couple/wishes/$wishId",
            JSONObject().put("done", done), token
        )

    suspend fun deleteWish(token: String, wishId: String): AccountClient.ApiResult<JSONObject> =
        AccountClient.call("DELETE", "/couple/wishes/$wishId", null, token)

    /** 媒体完整 URL（拼 token query，供图片/视频加载组件直接访问） */
    fun mediaUrl(rel: String, token: String): String =
        AccountClient.baseUrl + rel + if (token.isNotBlank()) "?token=$token" else ""

    /** 列表接口返回 JSON 数组，用 callRaw 拿原文解析 */
    private inline fun <T> AccountClient.ApiResult<String>.parseArr(
        parser: (JSONObject) -> T
    ): AccountClient.ApiResult<List<T>> = when (this) {
        is AccountClient.ApiResult.Success -> {
            try {
                val arr = JSONArray(data)
                val list = mutableListOf<T>()
                for (i in 0 until arr.length()) list.add(parser(arr.getJSONObject(i)))
                AccountClient.ApiResult.Success(list)
            } catch (e: Exception) {
                AccountClient.ApiResult.Failure("invalid_response", "响应格式错误", -1)
            }
        }
        is AccountClient.ApiResult.Failure -> this
    }
}
