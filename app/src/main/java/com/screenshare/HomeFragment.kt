package com.screenshare

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.fragment.app.Fragment
import com.screenshare.databinding.FragmentHomeBinding
import com.screenshare.databinding.ItemRecentBinding
import kotlin.random.Random

/**
 * 液态玻璃首页 Fragment：顶部栏 + 创建/加入卡片 + 专属房间卡片 + 最近会议卡片。
 * 原 activity_liquid 的主体逻辑迁移至此。
 */
class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private val handler = Handler(Looper.getMainLooper())
    private val codeEdits = ArrayList<EditText>()

    /** 最近会议记录（持久化暂未接入，先用本地空列表 —— 不使用原型演示数据） */
    private data class Recent(val code: String, val meta: String, val isCreate: Boolean)
    private val recentList = ArrayList<Recent>()

    /** 当前角色：true=观看方（你看TA的屏幕），false=共享方（TA看你的屏幕） */
    private var isViewer = true

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupCodeInputs()
        setupRecentList()
        setupClicks()
        animateEntrance()
    }

    private fun toast(msg: String) {
        (activity as? LiquidHomeActivity)?.showToast(msg)
    }

    /** 4 位数字输入：输满自动跳下一格，Backspace 空格回退到上一格 */
    private fun setupCodeInputs() {
        codeEdits.apply {
            add(binding.etCode0); add(binding.etCode1); add(binding.etCode2); add(binding.etCode3)
        }
        for (i in codeEdits.indices) {
            val et = codeEdits[i]
            et.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

                override fun afterTextChanged(s: Editable?) {
                    val text = s?.toString().orEmpty()
                    if (text.length == 1 && i < codeEdits.size - 1) {
                        codeEdits[i + 1].requestFocus()
                    }
                    et.isActivated = text.isNotEmpty()
                }
            })
            et.setOnKeyListener { _, keyCode, event ->
                if (keyCode == android.view.KeyEvent.KEYCODE_DEL &&
                    event.action == android.view.KeyEvent.ACTION_DOWN &&
                    et.text.isNullOrEmpty() && i > 0
                ) {
                    codeEdits[i - 1].requestFocus()
                    codeEdits[i - 1].setText("")
                    true
                } else {
                    false
                }
            }
        }
    }

    /** 填充最近会议列表；空列表时显示占位 */
    private fun setupRecentList() {
        binding.llRecentList.removeAllViews()
        for (r in recentList) {
            val item = ItemRecentBinding.inflate(layoutInflater, binding.llRecentList, false)
            item.tvRecentCode.text = r.code
            item.tvRecentMeta.text = r.meta
            item.ivRecentIcon.setImageResource(if (r.isCreate) R.drawable.ic_liquid_clock else R.drawable.ic_liquid_login)
            item.ivRecentDelete.setOnClickListener {
                recentList.remove(r)
                setupRecentList()
                toast("已删除该记录")
            }
            item.root.setOnClickListener { toast("正在加入房间 ${r.code}...") }
            binding.llRecentList.addView(item.root)
        }
        binding.emptyState.visibility =
            if (recentList.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun setupClicks() {
        binding.tvCheckUpdate.setOnClickListener { UpdateChecker.check(requireContext(), manual = true) }

        binding.btnCreate.setOnClickListener {
            binding.btnCreate.text = "创建中..."
            binding.btnCreate.alpha = 0.8f
            handler.postDelayed({
                binding.btnCreate.text = "创建房间"
                binding.btnCreate.alpha = 1f
                toast("创建房间成功！")
            }, 1500)
        }

        binding.btnJoin.setOnClickListener {
            val code = codeEdits.joinToString("") { it.text.toString() }
            if (code.length < 4) {
                toast("请输入 4 位房间号")
                return@setOnClickListener
            }
            toast("正在加入房间 $code...")
        }

        binding.btnCopy.setOnClickListener {
            val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("房间号", binding.tvRoomNumber.text))
            toast("房间号已复制")
        }

        binding.btnCallTa.setOnClickListener {
            toast("已发送呼叫通知")
            handler.postDelayed({
                binding.viewStatusDot.setBackgroundResource(R.drawable.dot_online)
                binding.tvRoomStatus.text = "你是观看方（你看TA的屏幕）· 对方在线"
                pulseStatusDot()
            }, 1500)
        }

        // 换角色：真实切换观看方/共享方身份文案
        binding.tvSwitchRole.setOnClickListener {
            isViewer = !isViewer
            binding.tvRoomStatus.text = if (isViewer) {
                "你是观看方（你看TA的屏幕）· 对方不在线"
            } else {
                "你是共享方（TA看你的屏幕）· 对方不在线"
            }
            toast(if (isViewer) "已切换为观看方" else "已切换为共享方")
        }

        // 换一个：生成新的 4 位房间号
        binding.tvChangeRoom.setOnClickListener {
            val newCode = Random.nextInt(1000, 10000).toString()
            binding.tvRoomNumber.text = newCode
            toast("已更换房间号 $newCode")
        }

        binding.btnClearRecent.setOnClickListener {
            if (recentList.isEmpty()) {
                toast("没有可清空的记录")
                return@setOnClickListener
            }
            recentList.clear()
            setupRecentList()
            toast("已清空历史记录")
        }
    }

    /** 状态点脉冲（对应 @keyframes pulse 的 box-shadow 呼吸） */
    private fun pulseStatusDot() {
        ObjectAnimator.ofPropertyValuesHolder(
            binding.viewStatusDot,
            PropertyValuesHolder.ofFloat("scaleX", 1f, 1.3f, 1f),
            PropertyValuesHolder.ofFloat("scaleY", 1f, 1.3f, 1f)
        ).apply {
            duration = 2000
            repeatMode = ObjectAnimator.REVERSE
            repeatCount = ObjectAnimator.INFINITE
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            start()
        }
    }

    /** 卡片错峰入场（对应 fadeInUp，延迟 0.2/0.35/0.5s） */
    private fun animateEntrance() {
        val cards = arrayOf(binding.cardJoin, binding.cardRoom, binding.cardRecent)
        cards.forEachIndexed { i, card ->
            card.translationY = 48f
            card.alpha = 0f
            ObjectAnimator.ofPropertyValuesHolder(
                card,
                PropertyValuesHolder.ofFloat("translationY", 48f, 0f),
                PropertyValuesHolder.ofFloat("alpha", 0f, 1f)
            ).apply {
                startDelay = 200L + i * 150L
                duration = 600L
                interpolator = android.view.animation.OvershootInterpolator(0.8f)
                start()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        handler.removeCallbacksAndMessages(null)
        _binding = null
    }
}
