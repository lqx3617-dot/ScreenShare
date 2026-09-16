package com.screenshare.albumviewer

import android.app.Dialog
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.ProgressBar
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import coil.Coil
import coil.ImageLoader
import okhttp3.OkHttpClient
import java.io.File

class MainActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val api by lazy { AlbumApi(this) }
    private val publishApi by lazy { PublishApi(this) }

    private lateinit var tvStatus: TextView
    private lateinit var rvGrid: RecyclerView
    private lateinit var tvEmpty: TextView
    private var refreshJob: Job? = null
    // v1.205: 视频下载协程引用，供进度对话框「取消」终止
    private var saveJob: Job? = null
    private val adapter = GridAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        // Coil 全局配置：图片请求自动附加相册访问密钥 header（key 不再进 URL 查询串，防日志/Referer 泄露）
        Coil.setImageLoader(
            ImageLoader.Builder(this)
                .okHttpClient(
                    OkHttpClient.Builder()
                        .addInterceptor { chain ->
                            val orig = chain.request()
                            val req = if (BuildConfig.ALBUM_KEY.isNotEmpty()) {
                                orig.newBuilder().header("x-album-key", BuildConfig.ALBUM_KEY).build()
                            } else orig
                            chain.proceed(req)
                        }
                        .build()
                )
                .build()
        )

        tvStatus = findViewById(R.id.tv_status)
        rvGrid = findViewById(R.id.rv_grid)
        tvEmpty = findViewById(R.id.tv_empty)

        findViewById<View>(R.id.btn_check_update_album).setOnClickListener { UpdateChecker.check(this, manual = true) }
        findViewById<View>(R.id.btn_dedup).setOnClickListener { onDedupClicked() }

        rvGrid.layoutManager = GridLayoutManager(this, 3)
        rvGrid.adapter = adapter
        adapter.onThumbClick = { index -> onThumbClick(index) }
        adapter.onThumbLongClick = { index -> showPhotoMenu(index) }

        findViewById<View>(R.id.btn_refresh).setOnClickListener {
            startPolling()
        }

        // 隐藏发版入口：顶部标题 2 秒内连点 3 次打开发布面板
        findViewById<View>(R.id.tv_title).setOnClickListener { onTitleTripleTap() }

        // 云更新：启动检查相册 APP 新版本（静默，节流 12h）
        UpdateChecker.check(this)
    }

    override fun onStart() {
        super.onStart()
        // 打开即浏览聚合相册（无需链接/链接码/设备码）；回到前台自动恢复轮询
        startPolling()
    }

    override fun onStop() {
        refreshJob?.cancel()
        refreshJob = null
        super.onStop()
    }

    private var titleTapCount = 0
    private var titleLastTapTime = 0L

    private fun onTitleTripleTap() {
        val now = SystemClock.elapsedRealtime()
        if (now - titleLastTapTime > 2000) {
            titleTapCount = 0
        }
        titleLastTapTime = now
        titleTapCount++
        if (titleTapCount >= 3) {
            titleTapCount = 0
            showPublishPanel()
        }
    }

    private fun showPublishPanel() {
        val dialog = Dialog(this, R.style.Theme_ScreenShare_Dialog)
        val content = LayoutInflater.from(this).inflate(R.layout.dialog_publish, null)
        dialog.setContentView(content)
        dialog.applyFullScreen()
        dialog.setCancelable(true)

        val tvCurrent = content.findViewById<TextView>(R.id.tv_pub_current)
        val etVersion = content.findViewById<EditText>(R.id.et_pub_version)
        val etChangelog = content.findViewById<EditText>(R.id.et_pub_changelog)
        val rgApp = content.findViewById<RadioGroup>(R.id.rg_pub_app)
        val tvStatus = content.findViewById<TextView>(R.id.tv_pub_status)
        val btnPublish = content.findViewById<View>(R.id.btn_pub_publish)
        val btnCancel = content.findViewById<View>(R.id.btn_pub_cancel)

        tvCurrent.text = "当前版本：${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"

        var publishJob: Job? = null
        btnPublish.setOnClickListener {
            if (publishJob?.isActive == true) return@setOnClickListener
            val version = etVersion.text?.toString()?.trim().orEmpty()
            val changelog = etChangelog.text?.toString()?.trim().orEmpty()
            if (!Regex("^\\d+\\.\\d+$").matches(version)) {
                Toast.makeText(this, "版本号格式应为 数字.数字（如 1.183）", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (changelog.isEmpty()) {
                Toast.makeText(this, "请填写更新说明", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val app = when (rgApp.checkedRadioButtonId) {
                R.id.rb_pub_main -> "main"
                R.id.rb_pub_albumviewer -> "albumviewer"
                else -> "both"
            }
            btnPublish.isEnabled = false
            tvStatus.text = "正在提交发布任务…"
            publishJob = scope.launch {
                try {
                    val taskId = publishApi.publish(version, changelog, app)
                    tvStatus.text = "发布任务已提交，构建中…"
                    while (isActive) {
                        delay(2000)
                        val st = publishApi.status(taskId)
                        if (st == null) {
                            tvStatus.text = "任务状态获取失败（服务器可能已重启），请重试"
                            break
                        }
                        when (st.state) {
                            "success" -> {
                                tvStatus.text = "发布成功！新版本 ${st.versionName}"
                                btnPublish.isEnabled = true
                                return@launch
                            }
                            "failed" -> {
                                tvStatus.text = "发布失败：${st.error ?: "未知错误"}"
                                btnPublish.isEnabled = true
                                return@launch
                            }
                            else -> {
                                val phaseText = when (st.phase) {
                                    "bump" -> "修改版本号"
                                    "build" -> "构建 APK"
                                    "sign" -> "签名 APK"
                                    "config" -> "更新版本配置"
                                    else -> "处理中"
                                }
                                tvStatus.text = "发布中（$phaseText）…"
                            }
                        }
                    }
                } catch (e: Exception) {
                    tvStatus.text = "提交失败：${e.message ?: e.javaClass.simpleName}"
                    btnPublish.isEnabled = true
                }
            }
        }
        btnCancel.setOnClickListener {
            publishJob?.cancel()
            dialog.dismiss()
        }
        dialog.show()
    }

    /** 重复照片清理：调服务器去重接口（全局 md5 查重，保留较清晰一份），删除后刷新视图 */
    private fun onDedupClicked() {
        val dlg = Dialog(this, R.style.Theme_ScreenShare_Dialog)
        val content = LayoutInflater.from(this).inflate(R.layout.dialog_dedup, null)
        dlg.setContentView(content)
        dlg.applyFullScreen()
        val tv = content.findViewById<TextView>(R.id.tv_dedup_title)
        val msg = content.findViewById<TextView>(R.id.tv_dedup_msg)
        val btnCancel = content.findViewById<android.widget.Button>(R.id.btn_dedup_cancel)
        val btnOk = content.findViewById<android.widget.Button>(R.id.btn_dedup_ok)
        tv.text = "清理重复照片"
        msg.text = "将检测全部照片中内容完全一致的重复项（按文件内容比对），\n仅保留较清晰的一份（优先保留原图已上传的）。\n\n此操作不可恢复，确定继续？"
        var running = false
        btnCancel.setOnClickListener { dlg.dismiss() }
        btnOk.setOnClickListener {
            if (running) return@setOnClickListener
            running = true
            btnOk.isEnabled = false
            btnCancel.isEnabled = false
            msg.text = "正在扫描并清理重复照片…"
            scope.launch {
                val result = api.dedup()
                if (result != null && result.groups == 0 && result.removed == 0) {
                    msg.text = "未发现重复照片"
                } else if (result != null) {
                    val freed = String.format("%.1f MB", result.freedBytes / 1024.0 / 1024.0)
                    msg.text = "检测到 ${result.groups} 组重复照片，\n已删除 ${result.removed} 张，释放 $freed。"
                } else {
                    msg.text = "无法连接服务器，请检查网络后重试"
                }
                btnOk.isEnabled = true
                btnCancel.isEnabled = true
                btnOk.text = "完成"
                // 刷新当前视图（去重后照片数变化）
                startPolling()
                btnOk.setOnClickListener { dlg.dismiss() }
            }
        }
        dlg.show()
    }

    /**
     * 聚合相册：打开即浏览全部会话照片（无需链接/链接码/设备码）。
     * 单协程循环轮询每 5s 刷新；网络失败自动重试；onStop 取消、onStart 恢复。
     */
    private fun startPolling() {
        refreshJob?.cancel()
        refreshJob = scope.launch {
            while (isActive) {
                val photos = api.getAllAlbums()
                if (photos != null) {
                    adapter.setAll(photos)
                    val videoCount = photos.count { it.isVideo }
                    tvStatus.text = if (videoCount > 0) {
                        "全部相册 · 共 ${photos.size} 项（${videoCount} 个视频）"
                    } else {
                        "全部相册 · 共 ${photos.size} 张照片"
                    }
                    val empty = photos.isEmpty()
                    tvEmpty.visibility = if (empty) View.VISIBLE else View.GONE
                    rvGrid.visibility = if (empty) View.GONE else View.VISIBLE
                    tvEmpty.text = "还没有照片，共享方上传后会自动归拢到这里"
                } else if (adapter.photos.isEmpty()) {
                    // 无缓存数据时才遮屏提示；已有数据则静默重试，避免列表闪烁
                    tvStatus.text = "无法连接服务器，重试中…"
                    tvEmpty.visibility = View.VISIBLE
                    rvGrid.visibility = View.GONE
                    tvEmpty.text = "网络异常，正在自动重试…"
                }
                delay(5000)
            }
        }
    }

    /** 网格点击：视频播放，照片全屏查看 */
    private fun onThumbClick(position: Int) {
        val photo = adapter.photoAt(position) ?: return
        if (photo.isVideo) {
            playVideo(photo)
        } else {
            showFullScreen(position)
        }
    }

    /** 视频全屏播放（内嵌 VideoView，支持流式加载、缓冲指示、进度拖动与暂停） */
    private fun playVideo(photo: AlbumPhoto) {
        val dialog = Dialog(this, R.style.Theme_ScreenShare_Dialog)
        val content = LayoutInflater.from(this).inflate(R.layout.dialog_video, null)
        val vv = content.findViewById<VideoView>(R.id.vv_video)
        val pb = content.findViewById<ProgressBar>(R.id.pb_video)
        val tvTip = content.findViewById<TextView>(R.id.tv_video_tip)
        val btnClose = content.findViewById<View>(R.id.btn_video_close)
        // v1.203: 视频控制条
        val llControls = content.findViewById<View>(R.id.ll_video_controls)
        val btnPlay = content.findViewById<ImageView>(R.id.btn_video_play)
        val tvPos = content.findViewById<TextView>(R.id.tv_video_position)
        val tvDur = content.findViewById<TextView>(R.id.tv_video_duration)
        val sb = content.findViewById<SeekBar>(R.id.sb_video)

        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
        var userSeeking = false
        var controlsVisible = false

        // ms → 可读时间（小时视频也兼容）
        fun fmtTime(ms: Int): String {
            val s = (ms / 1000).coerceAtLeast(0)
            return if (s >= 3600) String.format("%d:%02d:%02d", s / 3600, s % 3600 / 60, s % 60)
            else String.format("%d:%02d", s / 60, s % 60)
        }

        fun syncPlayButton() {
            btnPlay.setImageResource(if (vv.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        }

        // 控制条 3 秒无操作自动隐藏（暂停时常驻，方便用户随时继续）
        val hideControls = Runnable {
            controlsVisible = false
            llControls.visibility = View.GONE
        }
        fun showControls() {
            controlsVisible = true
            llControls.visibility = View.VISIBLE
            mainHandler.removeCallbacks(hideControls)
            if (vv.isPlaying) mainHandler.postDelayed(hideControls, 3000)
        }

        // 每 500ms 刷新进度条与时间（用户拖动期间跳过，避免回弹）
        val progressTick = object : Runnable {
            override fun run() {
                if (!userSeeking) {
                    try {
                        val dur = vv.duration
                        if (dur > 0) {
                            sb.max = dur
                            sb.progress = vv.currentPosition
                            tvPos.text = fmtTime(vv.currentPosition)
                            tvDur.text = fmtTime(dur)
                        }
                    } catch (t: Throwable) {
                        // 视频尚未 prepared，等下一拍
                    }
                }
                mainHandler.postDelayed(this, 500)
            }
        }

        // 拖动：松开时跳转，拖动中只更新时间预览
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) tvPos.text = fmtTime(progress)
            }
            override fun onStartTrackingTouch(bar: SeekBar?) {
                userSeeking = true
                mainHandler.removeCallbacks(hideControls)
            }
            override fun onStopTrackingTouch(bar: SeekBar?) {
                userSeeking = false
                try { vv.seekTo(bar?.progress ?: 0) } catch (t: Throwable) {}
                showControls()
            }
        })

        pb.visibility = View.VISIBLE
        vv.setOnPreparedListener { mp ->
            // prepared 即播放，不再依赖 onVideoSizeChanged（部分视频不触发该回调会一直不播）
            mp.isLooping = false
            pb.visibility = View.GONE
            tvTip.visibility = View.GONE
            sb.max = vv.duration.coerceAtLeast(1)
            tvDur.text = fmtTime(vv.duration)
            syncPlayButton()
            showControls()
            vv.start()
            mainHandler.post(progressTick)
        }
        // 缓冲/渲染开始/结束切换加载指示，避免大视频长时间黑屏无反馈
        // 注：VideoView 未公开 OnBufferingUpdateListener，无法显示精确的已缓冲区间
        // （secondaryProgress）；拖到未缓冲区时由 BUFFERING_START 转圈给出反馈
        vv.setOnInfoListener { _, what, _ ->
            when (what) {
                MediaPlayer.MEDIA_INFO_BUFFERING_START -> pb.visibility = View.VISIBLE
                MediaPlayer.MEDIA_INFO_BUFFERING_END,
                MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START -> pb.visibility = View.GONE
            }
            false
        }
        vv.setOnErrorListener { _, _, _ ->
            pb.visibility = View.GONE
            tvTip.text = "视频加载失败，请检查网络后重试"
            tvTip.visibility = View.VISIBLE
            true
        }
        vv.setOnCompletionListener { dialog.dismiss() }
        // 点击画面：切换播放/暂停并显示控制条
        vv.setOnClickListener {
            if (vv.isPlaying) vv.pause() else vv.start()
            syncPlayButton()
            showControls()
        }
        btnPlay.setOnClickListener {
            if (vv.isPlaying) vv.pause() else vv.start()
            syncPlayButton()
            showControls()
        }
        btnClose.setOnClickListener { dialog.dismiss() }

        dialog.setContentView(content)
        dialog.applyFullScreen()
        dialog.setOnDismissListener {
            mainHandler.removeCallbacks(progressTick)
            mainHandler.removeCallbacks(hideControls)
            try { vv.stopPlayback() } catch (t: Throwable) {}
        }
        dialog.show()
        // VideoView 走平台 MediaPlayer，不会经过 OkHttp/Coil 拦截器，
        // 必须显式携带 x-album-key，否则 /api/video 因无鉴权返回 401（表现为「看不到视频」）
        val headers = if (BuildConfig.ALBUM_KEY.isNotEmpty())
            mapOf("x-album-key" to BuildConfig.ALBUM_KEY) else emptyMap()
        vv.setVideoURI(Uri.parse(api.videoUrl(photo.token, photo.index)), headers)
    }

    private fun showFullScreen(startPosition: Int) {
        if (adapter.photoAt(startPosition) == null) return
        val inflater = LayoutInflater.from(this)
        val content = inflater.inflate(R.layout.dialog_full, null)
        val ivFull = content.findViewById<ImageView>(R.id.iv_full)
        val pb = content.findViewById<ProgressBar>(R.id.pb_loading)
        val tvTip = content.findViewById<TextView>(R.id.tv_orig_tip)
        val tvIdx = content.findViewById<TextView>(R.id.tv_idx)

        // 全屏 Dialog：大图必须铺满整屏，AlertDialog wrap_content 会把图片压成一条
        val dialog = Dialog(this, R.style.Theme_ScreenShare_Dialog)
        dialog.setContentView(content)
        dialog.applyFullScreen()

        var position = startPosition
        var origJob: Job? = null
        var thumbDisposable: coil.request.Disposable? = null

        // 后台轮询原图：不遮屏，用底部文字提示状态；原图到了替换缩略图
        fun pollOriginal(pos: Int) {
            val photo = adapter.photoAt(pos) ?: return
            origJob?.cancel()
            origJob = scope.launch {
                tvTip.setOnClickListener(null)
                tvTip.text = "正在加载高清原图…"
                tvTip.visibility = View.VISIBLE
                val orig = api.pollOriginal(photo.token, photo.index, maxTries = 20)
                if (position != pos || !isActive) return@launch
                if (orig != null) {
                    // IO 线程采样解码：大图先读边界再按目标尺寸降采样，避免主线程全尺寸解码 OOM/ANR
                    val bmp = withContext(Dispatchers.IO) {
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(orig, 0, orig.size, bounds)
                        val sample = sampleSizeFor(bounds.outWidth, bounds.outHeight)
                        BitmapFactory.decodeByteArray(orig, 0, orig.size, BitmapFactory.Options().apply { inSampleSize = sample })
                    }
                    if (position != pos) return@launch
                    if (bmp != null) {
                        // 原图已就绪，释放缩略图请求，避免旧请求继续占用 Coil 缓存
                        thumbDisposable?.dispose()
                        ivFull.setImageBitmap(bmp)
                        tvTip.visibility = View.GONE
                    } else {
                        tvTip.text = "原图加载失败"
                    }
                } else {
                    // v1.205: 超时不等于对方不在线——共享方可能只是压缩慢，给「继续等待」入口
                    tvTip.text = "共享方响应较慢，点此继续等待原图"
                    tvTip.setOnClickListener { pollOriginal(pos) }
                }
            }
        }

        // 加载指定位置的图片（缩略图秒出 + 后台轮询原图替换）
        fun loadPhoto(pos: Int) {
            val photo = adapter.photoAt(pos) ?: return
            origJob?.cancel()
            thumbDisposable?.dispose()
            // 复位缩放与提示，切图瞬间不留旧图放大态
            (ivFull as ZoomableImageView).resetZoom()
            ivFull.setImageBitmap(null)
            tvTip.visibility = View.GONE
            tvIdx.text = "${pos + 1} / ${adapter.photos.size}"
            position = pos

            // 缩略图加载期间显示转圈，加载完立即隐藏（保证点开秒出图、不一直转）
            pb.visibility = View.VISIBLE
            thumbDisposable = ivFull.load(api.thumbUrl(photo.token, photo.index)) {
                listener(
                    onSuccess = { _, _ -> pb.visibility = View.GONE },
                    onError = { _, _ -> pb.visibility = View.GONE }
                )
            }

            pollOriginal(pos)
        }

        // 点击空白背景关闭；点图片区域保持不关（避免刚打开就误关）
        content.setOnClickListener { dialog.dismiss() }
        // v1.204: 左右滑动切换上/下一张（未放大时生效）
        (ivFull as ZoomableImageView).onSingleTap = { dialog.dismiss() }
        (ivFull as ZoomableImageView).onSwipeNext = {
            if (position < adapter.photos.size - 1) loadPhoto(position + 1)
        }
        (ivFull as ZoomableImageView).onSwipePrev = {
            if (position > 0) loadPhoto(position - 1)
        }

        dialog.setOnDismissListener {
            origJob?.cancel()
            thumbDisposable?.dispose()
        }
        dialog.show()
        loadPhoto(startPosition)
    }

    /** 计算采样率：按屏幕短边为目标，长边不超过短边的 2 倍，避免 1080P 和 2K 屏都压到 2048 */
    private fun sampleSizeFor(w: Int, h: Int): Int {
        if (w <= 0 || h <= 0) return 1
        val metrics = resources.displayMetrics
        val shortSide = minOf(metrics.widthPixels, metrics.heightPixels)
        val maxDim = if (shortSide <= 0) 2048 else maxOf(shortSide, 2048)
        var sample = 1
        while (maxOf(w, h) / (sample * 2) >= maxDim) sample *= 2
        return sample
    }

    /** 长按条目弹出菜单：保存 / 删除（视频与照片文案区分） */
    private fun showPhotoMenu(position: Int) {
        val photo = adapter.photoAt(position) ?: return
        val items = if (photo.isVideo) arrayOf("保存视频", "删除视频") else arrayOf("保存到相册", "删除照片")
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(if (photo.isVideo) "第 ${photo.index} 个视频" else "第 ${photo.index} 张照片")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> if (photo.isVideo) saveVideo(photo) else saveImage(position)
                    1 -> confirmDeletePhoto(photo)
                }
            }
            .show()
    }

    /** 删除确认：调服务器删除单张照片/视频，成功后从列表移除并刷新 */
    private fun confirmDeletePhoto(photo: AlbumPhoto) {
        val what = if (photo.isVideo) "视频" else "照片"
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("删除${what}")
            .setMessage("确定删除第 ${photo.index} 个${what}吗？\n将从服务器永久删除，不可恢复。")
            .setPositiveButton("删除") { _, _ ->
                scope.launch {
                    val ok = api.deletePhoto(photo.token, photo.index)
                    if (ok) {
                        Toast.makeText(this@MainActivity, "已删除", Toast.LENGTH_SHORT).show()
                        startPolling()
                    } else {
                        Toast.makeText(this@MainActivity, "删除失败，请检查网络", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun saveImage(position: Int) {
        val photo = adapter.photoAt(position) ?: return
        val token = photo.token
        val index = photo.index
        Toast.makeText(this, "正在获取第 $index 张高清原图…", Toast.LENGTH_SHORT).show()
        scope.launch {
            // 优先原图（共享方在线时实时压缩上传），拿不到用缩略图兜底
            val orig = api.fetchOriginal(token, index)
            val fromOrig = orig != null
            val data = orig ?: withContext(Dispatchers.IO) { api.httpGetBytes(api.thumbUrl(token, index)) }
            if (data == null) {
                Toast.makeText(this@MainActivity, "下载失败", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val name = "album_${token.take(8)}_$index.jpg"
            val ok = withContext(Dispatchers.IO) { saveToGallery(name, data) }
            val msg = when {
                !ok -> "保存失败"
                fromOrig -> "已保存高清原图到相册"
                else -> "共享方不在线，已保存预览图"
            }
            Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
        }
    }

    /** 下载并保存视频到系统相册（Movies）：流式落盘，避免大视频一次性读入内存 OOM */
    private fun saveVideo(photo: AlbumPhoto) {
        val name = "album_${photo.token.take(8)}_${photo.index}.mp4"

        // v1.205: 下载进度对话框——大视频几十秒下载期间给百分比反馈，可随时取消
        val content = LayoutInflater.from(this).inflate(R.layout.dialog_download, null)
        val pb = content.findViewById<ProgressBar>(R.id.pb_download)
        val tvPercent = content.findViewById<TextView>(R.id.tv_download_percent)
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("正在下载视频")
            .setView(content)
            .setNegativeButton("取消", null)
            .setCancelable(false)
            .create()
        dialog.show()
        // 取消按钮：终止协程，downloadToFile 的 isActive 检查会让出循环
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE)
            .setOnClickListener { saveJob?.cancel() }

        val job = scope.launch {
            val ok = withContext(Dispatchers.IO) {
                val tmp = File(cacheDir, "dl_${photo.token.take(8)}_${photo.index}.mp4")
                val bytes = api.downloadToFile(api.videoUrl(photo.token, photo.index), tmp) { percent ->
                    // 回调在 IO 线程，切回主线程刷新 UI
                    runOnUiThread {
                        pb.progress = percent
                        tvPercent.text = "已下载 $percent%"
                    }
                }
                val saved = if (bytes != null && bytes > 0) saveVideoFileToGallery(name, tmp) else false
                tmp.delete()
                saved
            }
            dialog.dismiss()
            Toast.makeText(
                this@MainActivity,
                if (ok) "已保存视频到相册" else if (!isActive) "已取消下载" else "下载失败",
                Toast.LENGTH_SHORT
            ).show()
        }
        saveJob = job
    }

    private fun saveVideoFileToGallery(fileName: String, src: File): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                val values = android.content.ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/相册查看")
                }
                val resolver = contentResolver
                val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                    ?: return false
                resolver.openOutputStream(uri)?.use { out ->
                    src.inputStream().use { it.copyTo(out) }
                } ?: return false
                true
            } else {
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                    "相册查看"
                )
                if (!dir.exists()) dir.mkdirs()
                val dst = File(dir, fileName)
                src.copyTo(dst, overwrite = true)
                sendBroadcast(
                    android.content.Intent(
                        android.content.Intent.ACTION_MEDIA_SCANNER_SCAN_FILE,
                        Uri.fromFile(dst)
                    )
                )
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun saveToGallery(fileName: String, bytes: ByteArray): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                val values = android.content.ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/相册查看")
                }
                val resolver = contentResolver
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: return false
                resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return false
                true
            } else {
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    "相册查看"
                )
                if (!dir.exists()) dir.mkdirs()
                File(dir, fileName).writeBytes(bytes)
                // 通知媒体扫描
                sendBroadcast(
                    android.content.Intent(
                        android.content.Intent.ACTION_MEDIA_SCANNER_SCAN_FILE,
                        Uri.fromFile(File(dir, fileName))
                    )
                )
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    /** 全屏对话框：铺满整屏、透明背景（照片/视频/发布面板/去重弹窗共用） */
    private fun Dialog.applyFullScreen() {
        window?.apply {
            setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT
            )
            setBackgroundDrawableResource(android.R.color.transparent)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private class GridAdapter : RecyclerView.Adapter<GridAdapter.VH>() {

        var photos: List<AlbumPhoto> = emptyList()
        var onThumbClick: ((Int) -> Unit)? = null
        var onThumbLongClick: ((Int) -> Unit)? = null
        private val baseUrl = BuildConfig.ALBUM_URL.trimEnd('/')

        fun setAll(list: List<AlbumPhoto>) {
            val old = this.photos
            this.photos = list
            // DiffUtil 只更新新增/变化的项，避免几千张照片整屏闪烁、滑动位置被重置
            DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize() = old.size
                override fun getNewListSize() = list.size
                override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                    val a = old[oldItemPosition]
                    val b = list[newItemPosition]
                    return a.token == b.token && a.index == b.index
                }
                override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                    return old[oldItemPosition].isVideo == list[newItemPosition].isVideo
                }
            }).dispatchUpdatesTo(this)
        }
        fun photoAt(position: Int): AlbumPhoto? = photos.getOrNull(position)

        fun thumbUrl(photo: AlbumPhoto): String {
            val pad = photo.index.toString().padStart(4, '0')
            // key 走 header（Coil 拦截器自动附加），不再拼进 URL 查询串（防日志/Referer 泄露）
            return "$baseUrl/${photo.token}/$pad.jpg"
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_thumb, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val photo = photos[position]
            holder.retryCount = 0
            holder.boundToken = photo.token
            holder.boundIndex = photo.index
            holder.tvRetry.visibility = View.GONE
            loadThumbWithRetry(holder, thumbUrl(photo), photo.token, photo.index)
            holder.vb.visibility = if (photo.isVideo) View.VISIBLE else View.GONE
            holder.itemView.setOnClickListener { onThumbClick?.invoke(position) }
            holder.itemView.setOnLongClickListener {
                onThumbLongClick?.invoke(position)
                true
            }
            // v1.205: 手动重试入口（自动重试耗尽后显示）
            holder.tvRetry.setOnClickListener {
                if (holder.boundToken != photo.token || holder.boundIndex != photo.index) return@setOnClickListener
                holder.retryCount = 0
                holder.tvRetry.visibility = View.GONE
                loadThumbWithRetry(holder, thumbUrl(photo), photo.token, photo.index)
            }
        }

        /** 缩略图加载：公网偶发超时/断流导致灰块，失败自动重试最多 2 次，指数退避避免立刻重发 */
        private fun loadThumbWithRetry(holder: VH, url: String, token: String, index: Int) {
            holder.iv.load(url) {
                crossfade(true)
                placeholder(android.R.color.darker_gray)
                error(android.R.color.darker_gray)
                listener(
                    onError = { _, _ ->
                        // RecyclerView 复用校验：确认当前 item 仍是同一张照片再重试，避免错图
                        if (holder.boundToken != token || holder.boundIndex != index) return@listener
                        if (holder.retryCount < 2) {
                            holder.retryCount++
                            val backoff = when (holder.retryCount) {
                                1 -> 500L
                                else -> 1500L
                            }
                            holder.iv.postDelayed({
                                if (holder.boundToken == token && holder.boundIndex == index) {
                                    loadThumbWithRetry(holder, url, token, index)
                                }
                            }, backoff)
                        } else {
                            // v1.205: 自动重试耗尽，露出「点击重试」层，用户可手动恢复
                            holder.tvRetry.visibility = View.VISIBLE
                        }
                    },
                    onSuccess = { _, _ ->
                        holder.tvRetry.visibility = View.GONE
                    }
                )
            }
        }

        override fun getItemCount() = photos.size

        class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val iv: ImageView = itemView.findViewById(R.id.iv_thumb)
            val vb: View = itemView.findViewById(R.id.vb_play)
            val tvRetry: TextView = itemView.findViewById(R.id.tv_thumb_retry)
            var retryCount: Int = 0
            var boundToken: String = ""
            var boundIndex: Int = -1
        }
    }
}