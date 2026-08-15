package com.screenshare.albumviewer

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class AlbumStatus(
    val token: String,
    val total: Int,
    val done: Int,
    val received: Int,
)

/** 聚合相册中的一张照片：所属会话 token + 该会话内的照片序号 + 是否为视频 */
data class AlbumPhoto(
    val token: String,
    val index: Int,
    val isVideo: Boolean = false,
)

/** 远程相册同步：有照片的设备（按设备分组查看） */
data class AlbumDevice(
    val device: String,
    val sessions: Int,
    val photos: Int,
)

class AlbumApi(private val context: Context) {

    private val baseUrl = BuildConfig.ALBUM_URL.trimEnd('/')
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    fun thumbUrl(token: String, index: Int): String {
        val pad = index.toString().padStart(4, '0')
        return "$baseUrl/$token/$pad.jpg"
    }

    suspend fun getStatus(token: String): AlbumStatus? = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("$baseUrl/api/status?token=$token")
            .build()
        try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val j = JSONObject(resp.body?.string() ?: return@withContext null)
                AlbumStatus(
                    token = token,
                    total = j.optInt("total", 0),
                    done = j.optInt("done", 0),
                    received = j.optInt("received", 0),
                )
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 拉取聚合相册全部照片（所有会话混排，无需链接）。
     * 返回 null 表示请求失败；成功返回照片列表（按会话创建时间正序，会话内按序号）。
     */
    suspend fun getAllAlbums(): List<AlbumPhoto>? = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("$baseUrl/api/albums")
            .build()
        try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val j = JSONObject(resp.body?.string() ?: return@withContext null)
                parseAlbums(j)
            }
        } catch (e: Exception) {
            null
        }
    }

    /** 拉取指定设备（设备码）的相册照片。 */
    suspend fun getAlbumsByDevice(device: String): List<AlbumPhoto>? = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("$baseUrl/api/albums?device=${java.net.URLEncoder.encode(device, "UTF-8")}")
            .build()
        try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val j = JSONObject(resp.body?.string() ?: return@withContext null)
                parseAlbums(j)
            }
        } catch (e: Exception) {
            null
        }
    }

    /** 拉取有照片的设备列表（远程相册同步按设备查看）。 */
    suspend fun getDevices(): List<AlbumDevice>? = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("$baseUrl/api/devices")
            .build()
        try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val j = JSONObject(resp.body?.string() ?: return@withContext null)
                val arr = j.optJSONArray("devices") ?: return@withContext emptyList()
                (0 until arr.length()).mapNotNull { i ->
                    val d = arr.optJSONObject(i) ?: return@mapNotNull null
                    AlbumDevice(
                        device = d.optString("device", ""),
                        sessions = d.optInt("sessions", 0),
                        photos = d.optInt("photos", 0),
                    )
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun parseAlbums(j: JSONObject): List<AlbumPhoto> {
        val arr = j.optJSONArray("albums") ?: return emptyList()
        val list = mutableListOf<AlbumPhoto>()
        for (i in 0 until arr.length()) {
            val a = arr.optJSONObject(i) ?: continue
            val token = a.optString("token", "")
            val recv = a.optJSONArray("received") ?: continue
            // 视频 index 集合（该会话内哪些序号是视频）
            val videos = mutableSetOf<Int>()
            val vArr = a.optJSONArray("videos")
            if (vArr != null) {
                for (k in 0 until vArr.length()) videos.add(vArr.optInt(k))
            }
            for (k in 0 until recv.length()) {
                val idx = recv.optInt(k)
                list.add(AlbumPhoto(token, idx, idx in videos))
            }
        }
        return list
    }

    /** 视频播放地址（支持 Range 拖动） */
    fun videoUrl(token: String, index: Int): String {
        return "$baseUrl/api/video?token=$token&index=$index"
    }

    /**
     * 请求原图。返回字节数组表示成功拿到原图；null 表示当前 pending（共享方尚未上传该张原图）。
     */
    suspend fun fetchOriginal(token: String, index: Int): ByteArray? = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("$baseUrl/api/original?token=$token&index=$index")
            .build()
        try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val ct = resp.header("Content-Type") ?: ""
                if (ct.contains("image")) {
                    resp.body?.bytes()
                } else {
                    null
                }
            }
        } catch (e: IOException) {
            null
        }
    }

    /**
     * 轮询原图直到可用或超时。超时返回 null。
     */
    suspend fun pollOriginal(token: String, index: Int, maxTries: Int = 30): ByteArray? {
        for (i in 0 until maxTries) {
            val b = fetchOriginal(token, index)
            if (b != null) return b
            if (i < maxTries - 1) {
                kotlinx.coroutines.delay(1500)
            }
        }
        return null
    }
}
