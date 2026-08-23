package com.screenshare.albumviewer

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class PublishStatus(
    val state: String,
    val phase: String,
    val versionName: String,
    val error: String?,
)

class PublishApi(private val context: Context) {

    private val baseUrl = BuildConfig.PUBLISH_URL.trimEnd('/')
    private val client = HttpClientProvider.client

    /**
     * 提交发布任务。返回 taskId；失败抛异常（消息即错误原因）。
     */
    suspend fun publish(versionName: String, changelog: String, app: String): String =
        withContext(Dispatchers.IO) {
            val body = JSONObject().apply {
                put("versionName", versionName)
                put("changelog", changelog)
                put("app", app)
            }.toString().toRequestBody("application/json".toMediaType())
            val req = Request.Builder()
                .url("$baseUrl/api/publish")
                .post(body)
                .build()
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (resp.isSuccessful) {
                    JSONObject(text).optString("taskId", "")
                } else {
                    val msg = try {
                        JSONObject(text).optString("error", "")
                    } catch (e: Exception) {
                        ""
                    }
                    throw RuntimeException(msg.ifBlank { "服务器返回 ${resp.code}" })
                }
            }
        }

    /**
     * 查询发布任务状态。返回 null 表示任务不存在（服务器可能已重启）。
     */
    suspend fun status(taskId: String): PublishStatus? = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("$baseUrl/api/publish/status?task=$taskId")
            .build()
        try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val j = JSONObject(resp.body?.string().orEmpty())
                PublishStatus(
                    state = j.optString("state", ""),
                    phase = j.optString("phase", ""),
                    versionName = j.optString("versionName", ""),
                    error = j.optString("error").ifBlank { null },
                )
            }
        } catch (e: Exception) {
            null
        }
    }
}
