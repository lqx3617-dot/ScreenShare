package com.screenshare

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import com.screenshare.CoupleClient
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 情侣相册：照片/视频网格展示，支持上传（照片压缩、视频转码分块）与删除。
 */
class CouplePhotosActivity : AppCompatActivity() {

    private val medias = mutableListOf<CoupleClient.CoupleMedia>()
    private lateinit var adapter: MediaAdapter
    private var uploading = false
    /** 照片全屏预览对话框：Activity 销毁时主动 dismiss，避免泄漏 window */
    private var previewDialog: Dialog? = null

    private val pickPhoto = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) uploadPhoto(uri) }

    private val pickVideo = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) uploadVideo(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_couple_photos)
        findViewById<View>(R.id.tvBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnAddPhoto).setOnClickListener {
            if (!uploading) pickPhoto.launch(
                androidx.activity.result.PickVisualMediaRequest(
                    ActivityResultContracts.PickVisualMedia.ImageOnly
                )
            )
        }
        findViewById<View>(R.id.btnAddVideo).setOnClickListener {
            if (!uploading) pickVideo.launch(
                androidx.activity.result.PickVisualMediaRequest(
                    ActivityResultContracts.PickVisualMedia.VideoOnly
                )
            )
        }

        adapter = MediaAdapter(medias) { deleteMedia(it) }
        val rv = findViewById<RecyclerView>(R.id.rvPhotos)
        rv.layoutManager = GridLayoutManager(this, 3)
        rv.adapter = adapter
        loadPhotos()
    }

    private fun token(): String = SessionStore.getToken(this) ?: ""

    private fun loadPhotos() {
        val tk = token()
        if (tk.isBlank()) return
        lifecycleScope.launch {
            when (val r = CoupleClient.getPhotos(tk)) {
                is AccountClient.ApiResult.Success -> {
                    medias.clear(); medias.addAll(r.data); adapter.notifyDataSetChanged()
                    findViewById<View>(R.id.tvEmpty).visibility =
                        if (medias.isEmpty()) View.VISIBLE else View.GONE
                }
                is AccountClient.ApiResult.Failure -> toast("加载失败：${r.message}")
            }
        }
    }

    private fun uploadPhoto(uri: Uri) {
        val tk = token()
        uploading = true
        showProgress(true, "压缩并上传…")
        lifecycleScope.launch {
            var b64 = ""
            val result = try {
                withContext(Dispatchers.IO) {
                    b64 = AlbumUploader.compressToBase64(this@CouplePhotosActivity, uri)
                    CoupleClient.uploadPhoto(tk, b64, "image/jpeg")
                }
            } catch (t: Throwable) {
                // 云相册占位/损坏图/失效 uri 会让压缩抛异常，必须兜住，否则协程崩溃且 uploading 永久卡死
                AppLogger.app("[couple] 照片上传失败：${t.message}")
                AccountClient.ApiResult.Failure("compress_failed", "图片读取失败，请换一张", -1)
            } finally {
                uploading = false
                showProgress(false, null)
            }
            when (result) {
                is AccountClient.ApiResult.Success -> {
                    // 本地存一份：离线可看，解绑时自动清理
                    val mid = result.data.optString("mediaId")
                    if (mid.isNotBlank() && b64.isNotEmpty()) {
                        withContext(Dispatchers.IO) {
                            CoupleMediaCache.save(
                                this@CouplePhotosActivity, "$mid.jpg",
                                android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                            )
                        }
                    }
                    toast("已上传"); loadPhotos()
                }
                is AccountClient.ApiResult.Failure -> toast("上传失败：${result.message}")
            }
        }
    }

    private fun uploadVideo(uri: Uri) {
        val tk = token()
        uploading = true
        showProgress(true, "准备视频…")
        lifecycleScope.launch {
            val ok = try {
                withContext(Dispatchers.IO) { uploadVideoInternal(tk, uri) }
            } catch (t: Throwable) {
                AppLogger.app("[couple] 视频上传异常：${t.message}")
                false
            } finally {
                uploading = false
                showProgress(false, null)
            }
            if (ok) { toast("视频已上传"); loadPhotos() } else toast("视频上传失败")
        }
    }

    private suspend fun uploadVideoInternal(tk: String, uri: Uri): Boolean {
        var tmp: File? = null
        return try {
            // 1. 首帧缩略图
            val thumb = AlbumUploader.videoFrameToBase64(this, uri)
            // 2. 时长 + 转码压缩到 720p 缓存文件
            val duration = videoDuration(uri)
            val cacheDir = File(cacheDir, "couple_video").apply { mkdirs() }
            tmp = File(cacheDir, "couple_${System.currentTimeMillis()}.mp4")
            VideoTranscoder.transcode(this, uri, tmp.absolutePath) { p ->
                runOnUiThread { setProgress((p * 100).toInt(), "转码中…") }
            }
            if (tmp.length() == 0L) return false
            // 3. 创建视频会话
            val session = when (val r = CoupleClient.createVideo(tk, tmp.length(), duration, thumb)) {
                is AccountClient.ApiResult.Success -> r.data
                is AccountClient.ApiResult.Failure -> return false
            }
            // 4. 分块上传（每块 chunkSize 字节，base64）
            val chunkSize = session.chunkSize.coerceAtMost(3 * 1024 * 1024)
            val total = tmp.length().coerceAtLeast(1L)
            var offset = 0L
            tmp.inputStream().use { input ->
                val buf = ByteArray(chunkSize)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    val b64 = android.util.Base64.encodeToString(
                        if (n == buf.size) buf else buf.copyOf(n), android.util.Base64.NO_WRAP
                    )
                    if (CoupleClient.uploadVideoChunk(tk, session.videoId, offset.toInt(), b64)
                        is AccountClient.ApiResult.Failure) return false
                    offset += n
                    runOnUiThread { setProgress((50 + offset * 50 / total).toInt(), "上传中…") }
                }
            }
            // 5. 完成
            CoupleClient.finishVideo(tk, session.videoId) is AccountClient.ApiResult.Success
        } catch (t: Throwable) {
            AppLogger.app("[couple] 视频上传失败：${t.message}")
            false
        } finally {
            // 任何分支（含异常）都清理转码临时文件
            tmp?.delete()
        }
    }

    private fun videoDuration(uri: Uri): Long {
        val mmr = android.media.MediaMetadataRetriever()
        return try {
            mmr.setDataSource(this, uri)
            mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
        } catch (e: Exception) {
            0
        } finally {
            try { mmr.release() } catch (_: Throwable) {}
        }
    }

    private fun deleteMedia(media: CoupleClient.CoupleMedia) {
        AlertDialog.Builder(this)
            .setTitle("删除")
            .setMessage(if (media.mediaType == "video") "确定删除这个视频吗？" else "确定删除这张照片吗？")
            .setPositiveButton("删除") { _, _ -> doDeleteMedia(media) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun doDeleteMedia(media: CoupleClient.CoupleMedia) {
        val tk = token()
        lifecycleScope.launch {
            when (val r = CoupleClient.deletePhoto(tk, media.mediaId)) {
                is AccountClient.ApiResult.Success -> loadPhotos()
                is AccountClient.ApiResult.Failure -> toast("删除失败：${r.message}")
            }
        }
    }

    private fun showProgress(show: Boolean, hint: String?) {
        findViewById<View>(R.id.uploadProgress).visibility = if (show) View.VISIBLE else View.GONE
        findViewById<View>(R.id.tvUploadHint).visibility = if (show && hint != null) View.VISIBLE else View.GONE
        if (hint != null) findViewById<TextView>(R.id.tvUploadHint).text = hint
        if (!show) findViewById<android.widget.ProgressBar>(R.id.uploadProgress).progress = 0
    }

    private fun setProgress(percent: Int, hint: String) {
        findViewById<android.widget.ProgressBar>(R.id.uploadProgress).progress = percent.coerceIn(0, 100)
        findViewById<TextView>(R.id.tvUploadHint).text = hint
    }

    private fun toast(msg: String) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        // 预览对话框由 Activity 窗口管理，销毁时仍 showing 会泄漏 window
        previewDialog?.dismiss()
        previewDialog = null
    }

    /** 网格适配器：协程按 URL 解码位图（photo=原图，video=首帧缩略图），item 由 SquareImageView 保证正方形 */
    private inner class MediaAdapter(
        private val data: List<CoupleClient.CoupleMedia>,
        private val onDelete: (CoupleClient.CoupleMedia) -> Unit
    ) : RecyclerView.Adapter<MediaAdapter.MediaVH>() {

        inner class MediaVH(view: View) : RecyclerView.ViewHolder(view) {
            val iv: SquareImageView = view.findViewById(R.id.ivThumb)
            var job: Job? = null
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MediaVH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_couple_media, parent, false)
            return MediaVH(v)
        }

        override fun getItemCount(): Int = data.size

        override fun onViewRecycled(holder: MediaVH) {
            // 滑出屏幕即取消未完成的解码，避免无用网络与内存占用
            holder.job?.cancel()
            holder.job = null
        }

        override fun onBindViewHolder(holder: MediaVH, position: Int) {
            val m = data[position]
            val iv = holder.iv
            holder.itemView.findViewById<View>(R.id.ivVideoBadge).visibility =
                if (m.mediaType == "video") View.VISIBLE else View.GONE

            holder.itemView.findViewById<View>(R.id.btnDelete).setOnClickListener { onDelete(m) }

            // 本地缓存优先（离线可看，解绑自动清理）：url 形如 /couple_media/<uuid>.jpg
            val localName = m.url.substringAfterLast("/")
            val localFile = CoupleMediaCache.file(this@CouplePhotosActivity, localName)
            val fullSrc = if (localFile.exists()) localFile.absolutePath
                else CoupleClient.mediaUrl(m.url, token())
            holder.itemView.setOnClickListener {
                if (m.mediaType == "video") {
                    // 应用内播放：fullSrc 带会话令牌，交给外部 App（浏览器/播放器）
                    // 会把令牌泄漏给第三方应用与其历史记录
                    startActivity(
                        Intent(this@CouplePhotosActivity, WatchTogetherActivity::class.java).apply {
                            data = Uri.parse(fullSrc)
                        }
                    )
                } else {
                    previewPhoto(fullSrc)
                }
            }

            // 复用前取消上一个位置的加载并清空位图，防止旧图串到新 item
            holder.job?.cancel()
            iv.setImageBitmap(null)
            val rel = m.thumbUrl ?: m.url
            val thumbName = rel.substringAfterLast("/")
            val thumbLocal = CoupleMediaCache.file(this@CouplePhotosActivity, thumbName)
            val thumbSrc = if (thumbLocal.exists()) thumbLocal.absolutePath
                else CoupleClient.mediaUrl(rel, token())
            holder.job = lifecycleScope.launch {
                val bmp = withContext(Dispatchers.IO) { decodeUrl(thumbSrc, 360) }
                if (isActive && bmp != null) iv.setImageBitmap(bmp)
            }
        }
    }

    /** 照片全屏预览（黑底、点击关闭） */
    private fun previewPhoto(url: String) {
        val iv = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(0xFF000000.toInt())
        }
        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.setContentView(
            iv,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        iv.setOnClickListener { dialog.dismiss() }
        dialog.show()
        previewDialog = dialog
        lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) { decodeUrl(url, 1600) }
            if (bmp != null && dialog.isShowing) iv.setImageBitmap(bmp)
        }
    }

    /** 下载并按目标尺寸降采样解码，始终断开连接 */
    /** 解码图片：本地缓存路径（"/"开头）直读文件，否则走网络 */
    private fun decodeUrl(source: String, targetDim: Int): Bitmap? {
        return try {
            val bytes = if (source.startsWith("/")) {
                java.io.File(source).readBytes()
            } else {
                var conn: HttpURLConnection? = null
                try {
                    conn = URL(source).openConnection() as HttpURLConnection
                    conn.connectTimeout = 15000
                    conn.readTimeout = 15000
                    conn.useCaches = true
                    conn.inputStream.use { it.readBytes() }
                } finally {
                    try { conn?.disconnect() } catch (_: Throwable) {}
                }
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= targetDim || bounds.outHeight / (sample * 2) >= targetDim) {
                sample *= 2
            }
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        } catch (e: Exception) {
            if (e !is kotlinx.coroutines.CancellationException) {
                AppLogger.app("[couple] 缩略图解码失败：${e.message}")
            }
            null
        }
    }
}
