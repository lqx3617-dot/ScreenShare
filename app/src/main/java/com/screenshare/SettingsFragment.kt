package com.screenshare

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.screenshare.databinding.FragmentSettingsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * 设置页：音频设置 / 检查更新 / 导出日志 / 关于。
 * 音频偏好持久化在 "audio_settings"，会议中 MainActivity 读取同一份 prefs 生效。
 */
    class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private val audioPrefs by lazy {
        requireContext().getSharedPreferences("audio_settings", Context.MODE_PRIVATE)
    }

    private val glidePrefs by lazy {
        requireContext().getSharedPreferences("glide_settings", Context.MODE_PRIVATE)
    }

    companion object {
        const val GLIDE_LEVEL = "sensitivity_level"

        /** 档位转灵敏度系数：越小越灵敏 */
        fun levelToFactor(level: Int): Float = when (level) {
            0 -> 1.8f   // 慢：需要更长滑动距离
            2 -> 0.5f   // 快：轻滑即触发
            else -> 1f  // 标准
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val versionName = try {
            requireContext().packageManager.getPackageInfo(
                requireContext().packageName, 0
            ).versionName
        } catch (t: Throwable) {
            BuildConfig.VERSION_NAME
        }
        binding.tvVersion.text = "ScreenShare v$versionName"
        binding.tvAboutSub.text = "Android ${Build.VERSION.RELEASE} · ${Build.MANUFACTURER} ${Build.MODEL}"
        binding.tvUpdateSub.text = "当前 v$versionName · 点击检查新版本"

        // 账号区：展示当前登录昵称与好友码
        fillAccount()
        binding.rowAccount.setOnClickListener {
            (requireActivity() as? LiquidHomeActivity)?.navigateToSubPage(ProfileFragment())
        }

        binding.rowLogout.setOnClickListener { logout() }

        // 服务器地址不在此展示（BuildConfig 注入，用户无需感知）

        updateAudioSummary()

        binding.rowAudio.setOnClickListener {
            // 页面切换骨架：进入音频设置子页面（不使用弹窗）
            (requireActivity() as? LiquidHomeActivity)?.navigateToSubPage(AudioSettingsFragment())
        }
        updateGlideSummary()
        binding.rowGlide.setOnClickListener { showGlideSensitivityDialog() }
        binding.rowUpdate.setOnClickListener {
            Toast.makeText(requireContext(), "正在检查更新…", Toast.LENGTH_SHORT).show()
            // UpdateChecker 内部用 context as? Activity 切主线程弹窗，须传 Activity
            UpdateChecker.check(requireActivity(), manual = true)
        }
        binding.rowExportLog.setOnClickListener { exportLogFile() }
        binding.rowAbout.setOnClickListener { copyAboutInfo() }
    }

    /** 账号区摘要：onResume 也调用，从个人资料页返回后立即刷新 */
    private fun fillAccount() {
        SessionStore.getProfile(requireContext())?.let { p ->
            binding.tvAccountEmail.text = p.nickname.ifBlank { p.userId }
            binding.tvAccountCode.text = "好友码 ${p.friendCode}"
        } ?: run {
            binding.tvAccountEmail.text = "未登录"
            binding.tvAccountCode.text = ""
        }
    }

    /** 音频摘要行：供子页面返回时刷新 */
    fun refreshAudioSummary() {
        if (_binding != null) updateAudioSummary()
    }

    override fun onResume() {
        super.onResume()
        if (_binding != null) fillAccount()
    }

    /** 滑动灵敏度摘要行 */
    private fun updateGlideSummary() {
        val names = arrayOf("慢（稳重）", "标准", "快（灵敏）")
        binding.tvGlideSub.text = "导航条横滑切换 · ${names[glidePrefs.getInt(GLIDE_LEVEL, 1)]}"
    }

    private fun showGlideSensitivityDialog() {
        val ctx = requireContext()
        val items = arrayOf("慢（需要更长的滑动距离）", "标准（默认）", "快（轻轻一划即切换）")
        val current = glidePrefs.getInt(GLIDE_LEVEL, 1)
        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle("滑动灵敏度")
            .setSingleChoiceItems(items, current) { d, which ->
                glidePrefs.edit().putInt(GLIDE_LEVEL, which).apply()
                updateGlideSummary()
                (requireActivity() as? LiquidHomeActivity)?.applyGlideSensitivity()
                d.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 退出登录：通知服务端失效本设备令牌，清本地会话回登录页 */
    private fun logout() {
        val ctx = requireContext()
        val token = SessionStore.getToken(ctx).orEmpty()
        lifecycleScope.launch {
            if (token.isNotEmpty()) {
                // 先清推送标识再失效会话：顺序反了清标识会 401
                AccountClient.clearPushToken(token)
                AccountClient.logout(token)
            }
            SessionStore.clear(ctx)
            // 断开账号长连接，避免用旧令牌继续收推送
            App.instance.disconnectPresence()
            withContext(Dispatchers.Main) {
                startActivity(Intent(ctx, LoginActivity::class.java))
                activity?.finish()
            }
        }
    }

    /** 音频设置摘要行 */
    private fun updateAudioSummary() {
        val media = audioPrefs.getInt("media_volume", 100).coerceIn(0, 100)
        val talk = audioPrefs.getInt("talk_volume", 100).coerceIn(0, 100)
        val duck = if (audioPrefs.getBoolean("duck_enabled", true)) "开" else "关"
        binding.tvAudioSub.text = "媒体 $media · 对讲 $talk · 闪避 $duck"
    }

    /** 上传运行日志到云端，便于开发者分析 bug；失败时回退系统分享 */
    private fun exportLogFile() {
        val f = AppLogger.logFile()
        if (f == null || !f.exists() || f.length() == 0L) {
            Toast.makeText(requireContext(), "暂无日志可导出", Toast.LENGTH_SHORT).show()
            return
        }
        val ctx = context ?: return
        Toast.makeText(ctx, "正在上传日志…", Toast.LENGTH_SHORT).show()
        Thread {
            var uploaded = false
            var errMsg: String? = null
            try {
                // 本地日志文件跨启动累积，历史启动内容对当前问题无意义；
                // 从最后一个「应用启动」分隔线切片，只上传本次运行日志
                val raw = f.readText()
                val marker = "==== 应用启动 "
                val cut = raw.lastIndexOf(marker)
                val body = if (cut >= 0) raw.substring(cut) else raw
                // gzip 压缩：纯文本日志重复行多，压缩后通常约 1/8 体积
                val zipped = java.io.ByteArrayOutputStream().use { bos ->
                    java.util.zip.GZIPOutputStream(bos).use { gos ->
                        gos.write(body.toByteArray(Charsets.UTF_8))
                    }
                    bos.toByteArray()
                }
                // UPDATE_URL 形如 https://host/version.json，提取基址拼上传端点
                val u = java.net.URI(BuildConfig.UPDATE_URL)
                val base = buildString {
                    append(u.scheme).append("://").append(u.host)
                    if (u.port != -1) append(":").append(u.port)
                }
                val url = "$base/api/upload-log"
                val client = OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .writeTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .build()
                val req = Request.Builder()
                    .url(url)
                    .header("X-Diag-Token", BuildConfig.DIAG_TOKEN)
                    .header("X-App-Version", "${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE})")
                    .header("Content-Encoding", "gzip")
                    .post(zipped.toRequestBody("application/gzip".toMediaType()))
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        uploaded = true
                        AppLogger.app("用户上传日志到云端 (压缩${zipped.size}B/原文${f.length()}B)")
                    } else {
                        errMsg = "服务器响应 ${resp.code}"
                    }
                }
            } catch (t: Throwable) {
                Log.e("SettingsFragment", "上传日志失败", t)
                errMsg = t.message
            }
            if (!isAdded || _binding == null) return@Thread
            requireActivity().runOnUiThread {
                if (uploaded) {
                    Toast.makeText(ctx, "日志已上传，开发者可查看", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(ctx, "上传失败${errMsg?.let { "：$it" } ?: ""}，可改用分享", Toast.LENGTH_LONG).show()
                    shareLogFile(f)
                }
            }
        }.apply { isDaemon = true }.start()
    }

    /** 回退方案：系统分享发送日志文件 */
    private fun shareLogFile(f: java.io.File) {
        try {
            val uri = FileProvider.getUriForFile(
                requireContext(), "${requireContext().packageName}.fileprovider", f
            )
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "ScreenShare 运行日志")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "导出日志"))
        } catch (t: Throwable) {
            Log.e("SettingsFragment", "分享日志失败", t)
        }
    }

    /** 复制完整诊断信息（版本 / 设备 / 诊断 ID） */
    private fun copyAboutInfo() {
        val versionName = try {
            requireContext().packageManager.getPackageInfo(
                requireContext().packageName, 0
            ).versionName
        } catch (t: Throwable) {
            BuildConfig.VERSION_NAME
        }
        val diagId = BuildConfig.DIAG_TOKEN
        val info = buildString {
            append("ScreenShare v$versionName\n")
            append("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
            append("${Build.MANUFACTURER} ${Build.MODEL}\n")
            if (diagId.isNotEmpty()) append("诊断ID: $diagId")
        }
        copyText("诊断信息", info)
    }

    private fun copyText(label: String, content: String) {
        if (content.isEmpty()) {
            Toast.makeText(requireContext(), "$label 未配置", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val cm = requireContext()
                .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText(label, content))
            Toast.makeText(requireContext(), "已复制$label", Toast.LENGTH_SHORT).show()
        } catch (t: Throwable) {
            Log.e("SettingsFragment", "复制失败", t)
            Toast.makeText(requireContext(), "复制失败", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
