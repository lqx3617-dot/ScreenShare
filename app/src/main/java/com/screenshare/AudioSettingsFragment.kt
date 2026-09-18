package com.screenshare

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.screenshare.databinding.FragmentAudioSettingsBinding

/**
 * 音频设置页：媒体音量 / 对讲音量 / 说话闪避。
 * 持久化在 "audio_settings"，会议中 MainActivity 读取同一份 prefs 生效。
 */
class AudioSettingsFragment : Fragment() {

    private var _binding: FragmentAudioSettingsBinding? = null
    private val binding get() = _binding!!

    private val audioPrefs by lazy {
        requireContext().getSharedPreferences("audio_settings", Context.MODE_PRIVATE)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAudioSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.rowBack.setOnClickListener {
            (requireActivity() as? LiquidHomeActivity)?.popSubPage()
        }

        binding.sbMedia.progress = audioPrefs.getInt("media_volume", 100).coerceIn(0, 100)
        binding.sbTalk.progress = audioPrefs.getInt("talk_volume", 100).coerceIn(0, 100)
        binding.swDuck.isChecked = audioPrefs.getBoolean("duck_enabled", true)
        binding.tvMedia.text = binding.sbMedia.progress.toString()
        binding.tvTalk.text = binding.sbTalk.progress.toString()

        binding.sbMedia.setOnSeekBarChangeListener(object :
            android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar?, p: Int, fromUser: Boolean) {
                binding.tvMedia.text = p.toString()
            }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {
                audioPrefs.edit().putInt("media_volume", sb?.progress ?: 100).apply()
            }
        })
        binding.sbTalk.setOnSeekBarChangeListener(object :
            android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar?, p: Int, fromUser: Boolean) {
                binding.tvTalk.text = p.toString()
            }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {
                audioPrefs.edit().putInt("talk_volume", sb?.progress ?: 100).apply()
            }
        })
        binding.swDuck.setOnCheckedChangeListener { _, isChecked ->
            audioPrefs.edit().putBoolean("duck_enabled", isChecked).apply()
        }

        binding.btnReset.setOnClickListener {
            audioPrefs.edit().clear().apply()
            binding.sbMedia.progress = 100
            binding.sbTalk.progress = 100
            binding.swDuck.isChecked = true
            (requireActivity() as? LiquidHomeActivity)?.showToast("已恢复默认设置")
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
