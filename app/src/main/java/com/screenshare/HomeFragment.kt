package com.screenshare

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.fragment.app.Fragment
import com.screenshare.BuildConfig
import com.screenshare.databinding.DialogLiquidConfirmBinding
import com.screenshare.databinding.DialogLiquidRoomBinding
import com.screenshare.databinding.FragmentHomeBinding
import com.screenshare.databinding.ItemRecentBinding
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * 液态玻璃首页 Fragment：创建/加入会议 + 专属房间 + 最近会议。
 * 全部接入真实会议流程（MeetingActivity 同一套持久化与信令接口）。
 */
class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private val handler = Handler(Looper.getMainLooper())
    private val codeEdits = ArrayList<EditText>()

    /** 专属房间在线状态：true=在线，false=不在线，null=未知 */
    @Volatile private var favOnline: Boolean? = null
    private var favPolling = false
    private val favHandler = Handler(Looper.getMainLooper())
    private val favPollRunnable = object : Runnable {
        override fun run() {
            val fav = MeetingActivity.getFavoriteRoom(requireContext())
            if (fav != null) {
                queryFavStatus(fav.first, fav.second)
                favHandler.postDelayed(this, 5000)
            }
        }
    }
    private val statusClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .writeTimeout(4, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .build()
    }

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
        renderFavoriteCard()
        renderRecentMeetings()
        setupClicks()
        animateEntrance()
    }

    override fun onResume() {
        super.onResume()
        // 从会议室返回时历史可能已变化，重新渲染
        renderFavoriteCard()
        renderRecentMeetings()
        startFavPolling()
    }

    override fun onPause() {
        super.onPause()
        stopFavPolling()
    }

    private fun toast(msg: String) {
        (activity as? LiquidHomeActivity)?.showToast(msg)
    }

    // ==================== 会议流程 ====================

    /** 跳转会议室（与 MeetingActivity.enterMeeting 同一协议） */
    private fun enterMeeting(action: String, code: String, token: String = "") {
        val intent = Intent(requireContext(), MainActivity::class.java)
            .putExtra(MeetingActivity.EXTRA_MEETING_ACTION, action)
            .putExtra(MeetingActivity.EXTRA_MEETING_CODE, code)
            .putExtra(MeetingActivity.EXTRA_MEETING_TOKEN, token)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        startActivity(intent)
    }

    /** 生成 4 位数字会议号（SecureRandom，与 MeetingActivity 一致） */
    private fun generateMeetingCode(): String {
        val sb = StringBuilder()
        val random = SecureRandom()
        repeat(4) { sb.append(random.nextInt(10)) }
        return sb.toString()
    }

    // ==================== 4 位输入框 ====================

    /** 输满自动跳下一格，Backspace 空格回退；最后一格输满自动加入 */
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
                    // 输完最后一位自动提交（会议号固定 4 位）
                    if (text.length == 1 && i == codeEdits.size - 1) {
                        tryJoin()
                    }
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

    /** 校验输入并加入会议 */
    private fun tryJoin() {
        val code = codeEdits.joinToString("") { it.text.toString() }
        if (!Regex("^[0-9]{4}$").matches(code)) {
            toast("会议号为 4 位数字")
            return
        }
        enterMeeting(MeetingActivity.ACTION_JOIN, code)
    }

    // ==================== 专属房间 ====================

    /** 渲染专属房间卡片：已设置显示房间号+角色+在线状态，未设置显示提示 */
    private fun renderFavoriteCard() {
        val fav = MeetingActivity.getFavoriteRoom(requireContext())
        if (fav == null) {
            binding.tvRoomNumber.text = "未设置"
            binding.tvRoomStatus.text = "点击卡片设置我们的专属房间号"
            binding.viewStatusDot.visibility = View.GONE
            binding.btnCallTa.visibility = View.GONE
            binding.tvSwitchRole.visibility = View.GONE
            binding.tvChangeRoom.visibility = View.GONE
            binding.btnCopy.visibility = View.GONE
            return
        }
        val code = fav.first
        val isHostRole = fav.second == MeetingActivity.ACTION_CREATE
        binding.tvRoomNumber.text = code
        val roleText = if (isHostRole) "你是共享方（TA看你的屏幕）" else "你是观看方（你看TA的屏幕）"
        val online = favOnline
        val statusText = when (online) {
            true -> " · 对方在线"
            false -> " · 对方不在线"
            null -> ""
        }
        binding.tvRoomStatus.text = roleText + statusText
        binding.viewStatusDot.visibility = if (online == null) View.GONE else View.VISIBLE
        if (online == true) {
            binding.viewStatusDot.setBackgroundResource(R.drawable.dot_green)
            pulseStatusDot()
        } else if (online == false) {
            binding.viewStatusDot.setBackgroundResource(R.drawable.dot_gray)
        }
        // 观看方才显示「喊TA」（host 是常驻共享方，不需要喊）
        binding.btnCallTa.visibility = if (!isHostRole) View.VISIBLE else View.GONE
        binding.tvSwitchRole.visibility = View.VISIBLE
        binding.tvChangeRoom.visibility = View.VISIBLE
        binding.btnCopy.visibility = View.VISIBLE
    }

    /** 专属房间点击：已设置直接进入；未设置弹窗输入房间号+选择角色 */
    private fun onFavoriteClicked() {
        val ctx = requireContext()
        val fav = MeetingActivity.getFavoriteRoom(ctx)
        if (fav != null) {
            val isHostRole = fav.second == MeetingActivity.ACTION_CREATE
            val online = favOnline
            if (online == false && !isHostRole) {
                showConfirmDialog(
                    title = "对方不在线",
                    message = "TA 还没有进入房间 ${fav.first}。\n是否先进入等你加入，或喊 TA 一下？",
                    positive = "进入等待",
                    negative = "取消"
                ) { enterMeeting(fav.second, fav.first) }
                return
            }
            enterMeeting(fav.second, fav.first)
            return
        }
        showRoomDialog()
    }

    /** 液态玻璃「专属房间」弹窗：房间号输入 + 角色选择（首次设置 / 换一个 复用） */
    private fun showRoomDialog(
        prefillCode: String? = null,
        prefillRole: String = MeetingActivity.ACTION_CREATE,
        title: String = "设置专属房间",
        positive: String = "进入"
    ) {
        val ctx = requireContext()
        val dialog = Dialog(ctx)
        val dv = DialogLiquidRoomBinding.inflate(layoutInflater)
        dialog.setContentView(dv.root)
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.CENTER)
        }
        dv.tvDialogTitle.text = title
        dv.btnEnter.text = positive
        // 预填房间号：有则沿用，无则随机生成
        dv.etRoomCode.setText(prefillCode ?: generateMeetingCode())
        dv.etRoomCode.setSelection(dv.etRoomCode.text.length)
        // 预选角色
        var chosenRole = prefillRole
        dv.roleCreate.isActivated = prefillRole == MeetingActivity.ACTION_CREATE
        dv.roleJoin.isActivated = prefillRole == MeetingActivity.ACTION_JOIN

        val pickRole = { create: Boolean ->
            chosenRole = if (create) MeetingActivity.ACTION_CREATE else MeetingActivity.ACTION_JOIN
            dv.roleCreate.isActivated = create
            dv.roleJoin.isActivated = !create
        }
        dv.roleCreate.setOnClickListener { pickRole(true) }
        dv.roleJoin.setOnClickListener { pickRole(false) }

        dv.btnEnter.setOnClickListener {
            val code = dv.etRoomCode.text.toString().trim()
            if (!Regex("^[0-9]{4}$").matches(code)) {
                toast("房间号需为 4 位数字")
                return@setOnClickListener
            }
            MeetingActivity.setFavoriteRoom(ctx, chosenRole, code)
            favOnline = null
            dialog.dismiss()
            renderFavoriteCard()
            // 首次设置直接进入会议室；「换一个」只保存不进入
            if (positive == "进入") {
                enterMeeting(chosenRole, code)
            } else {
                toast("已更换房间号 $code")
            }
        }
        dv.btnCancel.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    /** 液态玻璃通用确认弹窗（换角色 / 不在线提示等场景复用） */
    private fun showConfirmDialog(
        title: String,
        message: String,
        positive: String,
        negative: String,
        onPositive: () -> Unit
    ) {
        val dialog = Dialog(requireContext())
        val dv = DialogLiquidConfirmBinding.inflate(layoutInflater)
        dialog.setContentView(dv.root)
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.CENTER)
        }
        dv.tvDialogTitle.text = title
        dv.tvDialogMessage.text = message
        dv.btnPositive.text = positive
        dv.btnNegative.text = negative
        dv.btnPositive.setOnClickListener {
            dialog.dismiss()
            onPositive()
        }
        dv.btnNegative.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    /** 查询专属房间在线状态并刷新 UI */
    private fun queryFavStatus(code: String, role: String) {
        val httpBase = signalHttpBase() ?: return
        val isHostRole = role == MeetingActivity.ACTION_CREATE
        Thread {
            try {
                // 观看方查"对方（host）是否在房间"；共享方查"是否有观看方已加入"
                val url = if (isHostRole)
                    "$httpBase/room-status?code=$code&s=host"
                else
                    "$httpBase/room-status?code=$code"
                val req = Request.Builder().url(url).build()
                statusClient.newCall(req).execute().use { resp ->
                    val body = resp.body?.string() ?: return@use
                    val json = try { JSONObject(body) } catch (e: Exception) { return@use }
                    val onlineHint = json.optBoolean("online", false)
                    if (onlineHint != favOnline) {
                        favOnline = onlineHint
                        activity?.runOnUiThread { renderFavoriteCard() }
                    }
                }
            } catch (t: Throwable) {
                Log.w("HomeFragment", "查询房间状态失败: ${t.message}")
            }
        }.start()
    }

    /** 从 BuildConfig.SIGNAL_URL（wss://.../ws）推导 HTTP base（https://...） */
    private fun signalHttpBase(): String? {
        val s = BuildConfig.SIGNAL_URL
        if (s.isNullOrBlank()) return null
        return when {
            s.startsWith("wss://") -> "https://" + s.removePrefix("wss://").removeSuffix("/ws")
            s.startsWith("ws://") -> "http://" + s.removePrefix("ws://").removeSuffix("/ws")
            else -> null
        }
    }

    /** 「喊TA」：临时 WebSocket 短连接投递 pls-join（观看方发起，host 收到提示） */
    private fun sendPlsJoin(code: String) {
        val url = BuildConfig.SIGNAL_URL
        if (url.isNullOrBlank()) {
            toast("信令服务未配置，无法呼叫")
            return
        }
        toast("已提醒对方，等 TA 来上屏...")
        val client = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).build()
        val wsReq = Request.Builder().url(url).build()
        client.newWebSocket(wsReq, object : okhttp3.WebSocketListener() {
            override fun onOpen(webSocket: okhttp3.WebSocket, response: okhttp3.Response) {
                webSocket.send(JSONObject().apply { put("type", "pls-join"); put("code", code) }.toString())
                Handler(Looper.getMainLooper()).postDelayed({ webSocket.close(1000, "done") }, 1200)
            }
        })
    }

    private fun startFavPolling() {
        stopFavPolling()
        favPolling = true
        favHandler.post(favPollRunnable)
    }

    private fun stopFavPolling() {
        favPolling = false
        favHandler.removeCallbacks(favPollRunnable)
    }

    // ==================== 最近会议 ====================

    /** 渲染最近会议列表（真实历史记录）；空列表时显示占位 */
    private fun renderRecentMeetings() {
        val list = MeetingActivity.loadMeetingHistory(requireContext())
        binding.llRecentList.removeAllViews()
        list.forEach { entry ->
            val item = ItemRecentBinding.inflate(layoutInflater, binding.llRecentList, false)
            val isCreate = entry.action == MeetingActivity.ACTION_CREATE
            item.tvRecentCode.text = entry.code
            item.tvRecentMeta.text = (if (isCreate) "创建" else "加入") + " · " + relativeTime(entry.ts)
            item.ivRecentIcon.setImageResource(if (isCreate) R.drawable.ic_liquid_clock else R.drawable.ic_liquid_login)
            item.root.setOnClickListener { enterMeeting(entry.action, entry.code) }
            item.ivRecentDelete.setOnClickListener {
                MeetingActivity.removeMeetingHistory(requireContext(), entry.code)
                renderRecentMeetings()
                toast("已删除该记录")
            }
            binding.llRecentList.addView(item.root)
        }
        binding.emptyState.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
    }

    /** 相对时间显示 */
    private fun relativeTime(ts: Long): String {
        val diff = System.currentTimeMillis() - ts
        return when {
            diff < 60_000 -> "刚刚"
            diff < 3600_000 -> "${diff / 60_000}分钟前"
            diff < 86400_000 -> "${diff / 3600_000}小时前"
            else -> "${diff / 86400_000}天前"
        }
    }

    // ==================== 点击事件 ====================

    private fun setupClicks() {
        binding.tvCheckUpdate.setOnClickListener { UpdateChecker.check(requireContext(), manual = true) }

        // 创建房间：随机 4 位会议号，直接进入会议室（共享方）
        binding.btnCreate.setOnClickListener {
            val code = generateMeetingCode()
            enterMeeting(MeetingActivity.ACTION_CREATE, code)
        }

        // 加入会议：校验 4 位输入
        binding.btnJoin.setOnClickListener { tryJoin() }

        // 专属房间卡：已设置进入，未设置弹窗设置
        binding.cardRoom.setOnClickListener { onFavoriteClicked() }

        binding.btnCopy.setOnClickListener {
            val fav = MeetingActivity.getFavoriteRoom(requireContext())
            if (fav == null) {
                toast("请先设置专属房间")
                return@setOnClickListener
            }
            val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("房间号", fav.first))
            toast("房间号已复制")
        }

        // 喊TA：观看方投递 pls-join
        binding.btnCallTa.setOnClickListener {
            val fav = MeetingActivity.getFavoriteRoom(requireContext())
            if (fav == null) {
                toast("请先设置专属房间")
                return@setOnClickListener
            }
            if (fav.second != MeetingActivity.ACTION_JOIN) {
                toast("你是共享方，无需喊TA，等对方加入即可")
                return@setOnClickListener
            }
            sendPlsJoin(fav.first)
        }

        // 换角色：保持房间号不变，翻转共享方/观看方
        binding.tvSwitchRole.setOnClickListener {
            val ctx = requireContext()
            val fav = MeetingActivity.getFavoriteRoom(ctx)
            if (fav == null) {
                toast("请先设置专属房间")
                return@setOnClickListener
            }
            val newRole = if (fav.second == MeetingActivity.ACTION_CREATE)
                MeetingActivity.ACTION_JOIN else MeetingActivity.ACTION_CREATE
            val newRoleText = if (newRole == MeetingActivity.ACTION_CREATE)
                "共享方（TA 看我的屏幕）" else "观看方（我看 TA 的屏幕）"
            showConfirmDialog(
                title = "切换角色",
                message = "房间号 ${fav.first} 保持不变\n切换后你成为：$newRoleText",
                positive = "切换",
                negative = "取消"
            ) {
                MeetingActivity.setFavoriteRoom(ctx, newRole, fav.first)
                favOnline = null
                renderFavoriteCard()
                toast("已切换为$newRoleText")
            }
        }

        // 换一个：弹窗自定义新房间号（沿用当前角色）
        binding.tvChangeRoom.setOnClickListener {
            val fav = MeetingActivity.getFavoriteRoom(requireContext())
            if (fav == null) {
                toast("请先设置专属房间")
                return@setOnClickListener
            }
            showRoomDialog(
                prefillCode = fav.first,
                prefillRole = fav.second,
                title = "更换房间号",
                positive = "确定"
            )
        }

        binding.btnClearRecent.setOnClickListener {
            if (MeetingActivity.loadMeetingHistory(requireContext()).isEmpty()) {
                toast("没有可清空的记录")
                return@setOnClickListener
            }
            MeetingActivity.clearMeetingHistory(requireContext())
            renderRecentMeetings()
            toast("已清空历史记录")
        }
    }

    /** 状态点脉冲（在线时呼吸提示） */
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
        stopFavPolling()
        _binding = null
    }
}
