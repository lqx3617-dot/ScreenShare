package com.screenshare

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView

/**
 * 一起看：全屏播放视频。仅由投屏方（host）打开——播放画面本身就在共享的屏幕流里，
 * 对方实时跟随，暂停/拖动也天然同步。屏幕常亮避免熄屏中断观看。
 *
 * 视频来源：相册本地视频 / 粘贴直链 / 内置示例。
 */
class WatchTogetherActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION)

        val uri = intent.data
        if (uri == null) {
            Toast.makeText(this, "视频无法播放", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val video = VideoView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        setContentView(video)

        video.setVideoURI(uri)
        video.setMediaController(MediaController(this).apply {
            setAnchorView(video)
        })
        video.setOnErrorListener { _, _, _ ->
            Toast.makeText(this, "视频无法播放（格式不支持或链接失效）", Toast.LENGTH_SHORT).show()
            finish()
            true
        }
        video.start()
    }

    companion object {
        const val REQUEST_PICK_VIDEO = 0x7751

        // 公开示例视频（Google 桶长期有效），供无相册视频时直接体验
        private val SAMPLES = arrayOf(
            "示例：大兔子（动画短片）" to "https://storage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4",
            "示例：Sintel（龙传说）" to "https://storage.googleapis.com/gtv-videos-bucket/sample/Sintel.mp4",
            "示例：大象之梦（科幻动画）" to "https://storage.googleapis.com/gtv-videos-bucket/sample/ElephantsDream.mp4"
        )

        /**
         * 由共享页调用：校验是否为投屏方，再选择视频来源。
         */
        fun start(host: Activity, isHost: Boolean) {
            if (!isHost) {
                Toast.makeText(host, "请让正在投屏的一方播放视频", Toast.LENGTH_SHORT).show()
                return
            }
            val items = arrayOf("相册视频", "粘贴视频链接") + SAMPLES.map { it.first }
            AlertDialog.Builder(host)
                .setTitle("选择视频")
                .setItems(items) { _, which ->
                    when (which) {
                        0 -> host.startActivityForResult(
                            Intent(Intent.ACTION_PICK).apply { type = "video/*" },
                            REQUEST_PICK_VIDEO
                        )
                        1 -> showUrlDialog(host)
                        else -> launch(host, Uri.parse(SAMPLES[which - 2].second))
                    }
                }
                .show()
        }

        private fun showUrlDialog(host: Activity) {
            val et = EditText(host).apply {
                hint = "https://example.com/video.mp4"
                inputType = InputType.TYPE_TEXT_VARIATION_URI
                setPadding(48, 24, 48, 24)
            }
            AlertDialog.Builder(host)
                .setTitle("粘贴视频链接")
                .setMessage("支持 mp4 等直链，非加密的网页视频可能无法播放")
                .setView(et)
                .setPositiveButton("播放") { _, _ ->
                    val url = et.text?.toString()?.trim().orEmpty()
                    if (!url.startsWith("http://") && !url.startsWith("https://")) {
                        Toast.makeText(host, "链接需以 http 或 https 开头", Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                    launch(host, Uri.parse(url))
                }
                .setNegativeButton("取消", null)
                .show()
        }

        private fun launch(host: Activity, uri: Uri) {
            host.startActivity(Intent(host, WatchTogetherActivity::class.java).apply {
                data = uri
            })
        }
    }
}
