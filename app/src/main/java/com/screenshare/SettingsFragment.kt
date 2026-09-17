package com.screenshare

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import com.screenshare.databinding.FragmentPlaceholderBinding

/** 设置页（占位 + 日志导出入口，日志功能按用户要求放在设置里） */
class SettingsFragment : Fragment() {

    private var _binding: FragmentPlaceholderBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPlaceholderBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.tvPlaceholderTitle.text = "设置"
        binding.ivPlaceholderIcon.setImageResource(R.drawable.ic_liquid_settings)
        binding.btnExportLog.visibility = View.VISIBLE
        binding.btnExportLog.setOnClickListener { exportLogFile() }
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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
