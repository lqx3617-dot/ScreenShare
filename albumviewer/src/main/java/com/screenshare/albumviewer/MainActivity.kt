package com.screenshare.albumviewer

import android.app.Dialog
import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
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
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File
import java.io.FileOutputStream
import java.util.regex.Pattern

class MainActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val api by lazy { AlbumApi(this) }
    private val publishApi by lazy { PublishApi(this) }

    private lateinit var etLink: EditText
    private lateinit var tvError: TextView
    private lateinit var layoutAlbum: View
    private lateinit var tvStatus: TextView
    private lateinit var rvGrid: RecyclerView
    private lateinit var tvEmpty: TextView

    private var currentToken: String? = null
    private var albumStatus: AlbumStatus? = null
    private var refreshJob: Job? = null
    private val adapter = GridAdapter()

    private val TOKEN_REGEX = Pattern.compile("([0-9a-f]{32})")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etLink = findViewById(R.id.et_link)
        tvError = findViewById(R.id.tv_error)
        layoutAlbum = findViewById(R.id.layout_album)
        tvStatus = findViewById(R.id.tv_status)
        rvGrid = findViewById(R.id.rv_grid)
        tvEmpty = findViewById(R.id.tv_empty)

        // 从剪贴板自动填充链接
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.primaryClip?.takeIf { it.itemCount > 0 }?.let { clip ->
            val text = clip.getItemAt(0).coerceToText(this).toString()
            if (text.contains("album") || TOKEN_REGEX.matcher(text).find()) {
                etLink.setText(text.trim())
            }
        }

        findViewById<View>(R.id.btn_open).setOnClickListener { openInput() }

        rvGrid.layoutManager = GridLayoutManager(this, 3)
        rvGrid.adapter = adapter
        adapter.onThumbClick = { index -> showFullScreen(index) }
        adapter.onThumbLongClick = { index -> saveImage(index) }

        findViewById<View>(R.id.btn_back).setOnClickListener {
            showInputView()
        }
        findViewById<View>(R.id.btn_refresh).setOnClickListener {
            if (currentToken != null) {
                refreshStatus(forceReload = true)
            } else {
                loadAggregatedAlbum(forceReload = true)
            }
        }

        // 首次启动如果有 token 参数（从分享链接打开），直接打开
        intent?.data?.toString()?.let { uri ->
            val m = TOKEN_REGEX.matcher(uri)
            if (m.find()) {
                etLink.setText(m.group(1))
            }
        }

        // 隐藏发版入口：顶部标题 2 秒内连点 3 次打开发布面板
        findViewById<View>(R.id.tv_title).setOnClickListener { onTitleTripleTap() }

        // 云更新：启动检查相册 APP 新版本（静默，节流 12h）
        UpdateChecker.check(this)

        // 从分享链接冷启动打开指定相册；否则直接加载聚合相册（无需链接查看全部照片）
        val linkToken = intent?.data?.toString()?.let { uri ->
            TOKEN_REGEX.matcher(uri).let { if (it.find()) it.group(1) else null }
        }
        if (linkToken != null) {
            openAlbum(linkToken)
        } else {
            loadAggregatedAlbum()
        }
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
        dialog.window?.apply {
            setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT
            )
            setBackgroundDrawableResource(android.R.color.transparent)
        }
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

    private fun openInput() {
        val text = etLink.text?.toString()?.trim().orEmpty()
        val m = TOKEN_REGEX.matcher(text)
        if (!m.find()) {
            showError("链接无效：请粘贴完整链接或 32 位链接码")
            return
        }
        val token = m.group(1)
        hideError()
        openAlbum(token)
    }

    private fun showError(msg: String) {
        tvError.text = msg
        tvError.visibility = View.VISIBLE
    }

    private fun hideError() {
        tvError.visibility = View.GONE
    }

    private fun openAlbum(token: String) {
        currentToken = token
        layoutAlbum.visibility = View.VISIBLE
        tvStatus.text = "连接中…"
        adapter.setEmpty()
        refreshStatus(forceReload = true)
    }

    private fun showInputView() {
        layoutAlbum.visibility = View.GONE
        refreshJob?.cancel()
        adapter.setEmpty()
        currentToken = null
    }

    /**
     * 聚合相册：无需链接直接查看全部会话照片，上传中每 5s 自动轮询刷新。
     */
    private fun loadAggregatedAlbum(forceReload: Boolean = false) {
        layoutAlbum.visibility = View.VISIBLE
        currentToken = null
        refreshJob?.cancel()
        refreshJob = scope.launch {
            val photos = api.getAllAlbums()
            if (photos != null) {
                adapter.setAll(photos)
                tvStatus.text = "全部相册 · 共 ${photos.size} 张"
                tvEmpty.visibility = if (photos.isEmpty()) View.VISIBLE else View.GONE
                tvEmpty.text = "还没有照片，共享方上传后会自动归拢到这里"
                // 持续轮询：新照片上传后自动刷新
                refreshJob = launch {
                    delay(5000)
                    loadAggregatedAlbum()
                }
            } else {
                tvStatus.text = "无法连接服务器"
                tvEmpty.visibility = View.VISIBLE
                tvEmpty.text = "网络异常，请检查网络后重试"
            }
        }
    }

    private fun refreshStatus(forceReload: Boolean = false) {
        val token = currentToken ?: return
        refreshJob?.cancel()
        refreshJob = scope.launch {
            val st = api.getStatus(token)
            if (st != null) {
                albumStatus = st
                if (forceReload || adapter.photos.isEmpty() || st.received != adapter.photos.size) {
                    adapter.setCount(st.received, token)
                }
                tvStatus.text = "共 ${st.total} 张 · 已接收 ${st.received}${if (st.done < st.total) " · 上传中…" else ""}"
                tvEmpty.visibility = if (st.received == 0) View.VISIBLE else View.GONE
                tvEmpty.text = if (st.done < st.total) "暂无照片，等待共享方上传…" else "该相册暂无照片"
                // 持续轮询直到上传完成
                if (st.done < st.total) {
                    refreshJob = launch {
                        delay(2000)
                        refreshStatus(forceReload)
                    }
                }
            } else {
                tvStatus.text = "无法连接服务器"
                tvEmpty.visibility = View.VISIBLE
                tvEmpty.text = "网络异常，请检查网络后重试"
            }
        }
    }

    private fun showFullScreen(position: Int) {
        val photo = adapter.photoAt(position) ?: return
        val token = photo.token
        val index = photo.index
        val inflater = LayoutInflater.from(this)
        val content = inflater.inflate(R.layout.dialog_full, null)
        val ivFull = content.findViewById<ImageView>(R.id.iv_full)
        val pb = content.findViewById<ProgressBar>(R.id.pb_loading)
        val tvTip = content.findViewById<TextView>(R.id.tv_orig_tip)
        val tvIdx = content.findViewById<TextView>(R.id.tv_idx)

        tvIdx.text = if (currentToken != null) {
            "$index / ${albumStatus?.total ?: 0}"
        } else {
            "${position + 1} / ${adapter.photos.size}"
        }

        // 缩略图加载期间显示转圈，加载完立即隐藏（保证点开秒出图、不一直转）
        pb.visibility = View.VISIBLE
        ivFull.load(api.thumbUrl(token, index)) {
            listener(
                onSuccess = { _, _ -> pb.visibility = View.GONE },
                onError = { _, _ -> pb.visibility = View.GONE }
            )
        }

        // 全屏 Dialog：大图必须铺满整屏，AlertDialog wrap_content 会把图片压成一条
        val dialog = Dialog(this, R.style.Theme_ScreenShare_Dialog)
        dialog.setContentView(content)
        dialog.window?.apply {
            setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT
            )
            setBackgroundDrawableResource(android.R.color.transparent)
        }

        // 点击空白背景关闭；点图片区域保持不关（避免刚打开就误关）
        content.setOnClickListener { dialog.dismiss() }
        (ivFull as ZoomableImageView).onSingleTap = { dialog.dismiss() }

        dialog.show()
        // 后台轮询原图：不遮屏，用底部文字提示状态；原图到了替换缩略图
        val origJob = CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            tvTip.text = "正在加载高清原图…"
            tvTip.visibility = View.VISIBLE
            val orig = api.pollOriginal(token, index, maxTries = 20)
            if (orig != null) {
                ivFull.setImageBitmap(BitmapFactory.decodeByteArray(orig, 0, orig.size))
                tvTip.visibility = View.GONE
            } else {
                tvTip.text = "共享方不在线，显示预览图"
            }
        }
        dialog.setOnDismissListener { origJob.cancel() }
    }

    private fun saveImage(position: Int) {
        val photo = adapter.photoAt(position) ?: return
        val token = photo.token
        val index = photo.index
        Toast.makeText(this, "正在获取第 $index 张高清原图…", Toast.LENGTH_SHORT).show()
        scope.launch {
            // 优先原图（共享方在线时实时压缩上传），拿不到用缩略图兜底
            val data = api.fetchOriginal(token, index)
                ?: runCatching {
                    val req = okhttp3.Request.Builder().url(api.thumbUrl(token, index)).build()
                    okhttp3.OkHttpClient().newCall(req).execute().use { it.body?.bytes() }
                }.getOrNull()
            if (data != null) {
                val name = "album_${token.take(8)}_$index.jpg"
                val ok = saveToGallery(name, data)
                val fromOrig = data.size > 10000
                val msg = when {
                    !ok -> "保存失败"
                    fromOrig -> "已保存高清原图到相册"
                    else -> "共享方不在线，已保存预览图"
                }
                Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@MainActivity, "下载失败", Toast.LENGTH_SHORT).show()
            }
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

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private class GridAdapter : RecyclerView.Adapter<GridAdapter.VH>() {

        var photos: List<AlbumPhoto> = emptyList()
        var onThumbClick: ((Int) -> Unit)? = null
        var onThumbLongClick: ((Int) -> Unit)? = null
        private val baseUrl = BuildConfig.ALBUM_URL.trimEnd('/')

        fun setEmpty() {
            photos = emptyList()
            notifyDataSetChanged()
        }

        fun setCount(count: Int, token: String) {
            this.photos = (1..count).map { AlbumPhoto(token, it) }
            notifyDataSetChanged()
        }

        fun setAll(list: List<AlbumPhoto>) {
            this.photos = list
            notifyDataSetChanged()
        }
        fun photoAt(position: Int): AlbumPhoto? = photos.getOrNull(position)

        fun thumbUrl(photo: AlbumPhoto): String {
            val pad = photo.index.toString().padStart(4, '0')
            return "$baseUrl/${photo.token}/$pad.jpg"
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_thumb, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val photo = photos[position]
            holder.iv.load(thumbUrl(photo)) {
                crossfade(true)
                placeholder(android.R.color.darker_gray)
                error(android.R.color.darker_gray)
            }
            holder.itemView.setOnClickListener { onThumbClick?.invoke(position) }
            holder.itemView.setOnLongClickListener {
                onThumbLongClick?.invoke(position)
                true
            }
        }

        override fun getItemCount() = photos.size

        class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val iv: ImageView = itemView.findViewById(R.id.iv_thumb)
        }
    }
}
