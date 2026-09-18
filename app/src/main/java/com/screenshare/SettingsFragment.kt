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
import com.screenshare.databinding.FragmentSettingsBinding

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

        // 服务器地址不在此展示（BuildConfig 注入，用户无需感知）

        updateAudioSummary()

        binding.rowAudio.setOnClickListener {
            // 页面切换骨架：进入音频设置子页面（不使用弹窗）
            (requireActivity() as? LiquidHomeActivity)?.navigateToSubPage(AudioSettingsFragment())
        }
        binding.rowUpdate.setOnClickListener {
            Toast.makeText(requireContext(), "正在检查更新…", Toast.LENGTH_SHORT).show()
            // UpdateChecker 内部用 context as? Activity 切主线程弹窗，须传 Activity
            UpdateChecker.check(requireActivity(), manual = true)
        }
        binding.rowExportLog.setOnClickListener { exportLogFile() }
        binding.rowAbout.setOnClickListener { copyAboutInfo() }
    }

    /** 音频摘要行：供子页面返回时刷新 */
    fun refreshAudioSummary() = updateAudioSummary()

    /** 音频设置摘要行 */
    private fun updateAudioSummary() {
        val media = audioPrefs.getInt("media_volume", 100).coerceIn(0, 100)
        val talk = audioPrefs.getInt("talk_volume", 100).coerceIn(0, 100)
        val duck = if (audioPrefs.getBoolean("duck_enabled", true)) "开" else "关"
        binding.tvAudioSub.text = "媒体 $media · 对讲 $talk · 闪避 $duck"
    }

    /** 导出运行日志：系统分享面板发送，便于反馈崩溃等问题 */
    private fun exportLogFile() {
        try {
            val f = AppLogger.logFile()
            if (f == null || !f.exists() || f.length() == 0L) {
                Toast.makeText(requireContext(), "暂无日志可导出", Toast.LENGTH_SHORT).show()
                return
            }
            AppLogger.app("用户导出日志文件 (${f.length()}B)")
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
            Log.e("SettingsFragment", "导出日志失败", t)
            Toast.makeText(requireContext(), "导出日志失败，请稍后重试", Toast.LENGTH_SHORT).show()
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
