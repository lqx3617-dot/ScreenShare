package com.screenshare

import android.app.Dialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.screenshare.databinding.DialogLiquidRoomBinding
import com.screenshare.databinding.FragmentFriendsBinding
import kotlinx.coroutines.launch
import java.security.SecureRandom

/**
 * 好友页：真实好友列表 + 待处理申请 + 添加好友（好友码）。
 * 在线状态与申请由 PresenceClient 实时推送，列表数据来自 /friends REST。
 */
class FriendsFragment : Fragment() {

    private var _binding: FragmentFriendsBinding? = null
    private val binding get() = _binding!!

    private val friendsAdapter by lazy {
        FriendsAdapter(
            onStartShare = { friend -> startShareWith(friend) },
            onEditRemark = { friend -> showRemarkDialog(friend) }
        )
    }
    private val requestsAdapter by lazy {
        FriendRequestsAdapter(
            onAccept = { item -> respondRequest(item.requestId, accept = true) },
            onReject = { item -> respondRequest(item.requestId, accept = false) }
        )
    }
    private val sharesAdapter by lazy {
        RecentSharesAdapter { item -> reshareWith(item) }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFriendsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val sb = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            v.setPadding(v.paddingLeft, sb.top + 10, v.paddingRight, v.paddingBottom)
            insets
        }

        binding.rvFriends.layoutManager = LinearLayoutManager(requireContext())
        binding.rvFriends.adapter = friendsAdapter
        binding.rvRequests.layoutManager = LinearLayoutManager(requireContext())
        binding.rvRequests.adapter = requestsAdapter
        binding.rvShares.layoutManager = LinearLayoutManager(requireContext())
        binding.rvShares.adapter = sharesAdapter

        binding.btnAddFriend.setOnClickListener { showAddFriendDialog() }
        binding.layoutEmpty.setOnClickListener { showAddFriendDialog() }

        loadAll()
    }

    override fun onResume() {
        super.onResume()
        // 返回页面时刷新（可能在别处接受了申请）
        if (_binding != null) loadAll()
    }

    private fun token(): String? = SessionStore.getToken(requireContext())

    private fun toast(msg: String) {
        (activity as? LiquidHomeActivity)?.showToast(msg)
    }

    /** 扫码结果：交给同一套好友码申请逻辑 */
    private val scanLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val code = result.data?.getStringExtra(ScanFriendCodeActivity.EXTRA_FRIEND_CODE)
                ?.uppercase()?.trim().orEmpty()
            if (code.length == 6 && code.all { it.isLetterOrDigit() }) {
                sendFriendRequest(code)
            } else {
                toast("扫码内容不是好友码：$code")
            }        }
    }

    /** 拉取好友列表 + 待处理申请 + 最近共享 */
    private fun loadAll() {
        val t = token() ?: return
        lifecycleScope.launch {
            val friends = AccountClient.getFriends(t)
            val reqs = AccountClient.getFriendRequests(t)
            val shares = AccountClient.getRecentShares(t)
            if (_binding == null) return@launch
            when (friends) {
                is AccountClient.ApiResult.Success -> {
                    val list = friends.data
                    friendsAdapter.submitList(list)
                    binding.layoutEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                }
                is AccountClient.ApiResult.Failure -> {
                    if (friends.http == 401) requireSessionExpired()
                    else toast(friends.message)
                }
            }
            when (reqs) {
                is AccountClient.ApiResult.Success -> {
                    val list = reqs.data
                    requestsAdapter.submitList(list)
                    binding.layoutRequests.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
                }
                is AccountClient.ApiResult.Failure -> if (reqs.http != 401) toast(reqs.message)
            }
            when (shares) {
                is AccountClient.ApiResult.Success -> {
                    val list = shares.data
                    sharesAdapter.submitList(list)
                    binding.layoutShares.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
                }
                is AccountClient.ApiResult.Failure -> if (shares.http != 401) toast(shares.message)
            }
        }
    }

    /** 401：令牌失效，清会话回登录页 */
    private fun requireSessionExpired() {
        toast("登录已失效，请重新登录")
        val act = activity ?: return
        SessionStore.clear(act)
        startActivity(android.content.Intent(act, LoginActivity::class.java))
        act.finish()
    }

    /** 接受/拒绝好友申请 */
    private fun respondRequest(requestId: String, accept: Boolean) {
        val t = token() ?: return
        lifecycleScope.launch {
            val r = if (accept) AccountClient.acceptFriend(t, requestId)
            else AccountClient.rejectFriend(t, requestId)
            if (_binding == null) return@launch
            when (r) {
                is AccountClient.ApiResult.Success -> {
                    toast(if (accept) "已添加为好友" else "已拒绝申请")
                    loadAll()
                }
                is AccountClient.ApiResult.Failure -> {
                    if (r.http == 401) requireSessionExpired() else toast(r.message)
                }
            }
        }
    }

    /** 添加好友弹窗：输入好友码（自己的好友码展示在顶部） */
    private fun showAddFriendDialog() {
        val ctx = requireContext()
        val profile = SessionStore.getProfile(ctx)
        val dialog = Dialog(ctx)
        val dv = DialogLiquidRoomBinding.inflate(layoutInflater)
        dialog.setContentView(dv.root)
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.CENTER)
            setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or
                        WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            )
        }
        dv.tvDialogTitle.text = "添加好友"
        dv.btnEnter.text = "发送申请"
        // 复用角色行做「扫码 / 我的二维码」入口（加好友不需要角色选择）
        dv.tvRoleLabel.visibility = View.GONE
        dv.roleCreate.visibility = View.VISIBLE
        dv.roleJoin.visibility = View.VISIBLE
        dv.roleCreate.setOnClickListener {
            dialog.dismiss()
            scanLauncher.launch(android.content.Intent(requireContext(), ScanFriendCodeActivity::class.java))
        }
        dv.roleJoin.setOnClickListener {
            showMyQrDialog()
        }
        // 角色行的文案改成加好友语境
        (dv.roleCreate.getChildAt(1) as? android.widget.TextView)?.text = "扫码加好友"
        (dv.roleCreate.getChildAt(2) as? android.widget.TextView)?.text = "扫对方二维码"
        (dv.roleJoin.getChildAt(1) as? android.widget.TextView)?.text = "我的二维码"
        (dv.roleJoin.getChildAt(2) as? android.widget.TextView)?.text = "让对方扫我"
        // 输入提示改为好友码
        dv.etRoomCode.hint = "输入对方好友码"
        dv.etRoomCode.filters = arrayOf(android.text.InputFilter.LengthFilter(6))
        dv.etRoomCode.inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        profile?.let {
            dv.etRoomCode.setHint("对方好友码（你的：${it.friendCode}）")
        }

        var sent = false
        dv.btnEnter.setOnClickListener {
            if (sent) return@setOnClickListener
            val code = dv.etRoomCode.text?.toString()?.trim()?.uppercase().orEmpty()
            if (code.length != 6) {
                toast("好友码为 6 位")
                return@setOnClickListener
            }
            sent = true
            sendFriendRequest(code,
                onSuccess = { dialog.dismiss() },
                onFail = { sent = false }
            )
        }
        dv.btnCancel.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    /** 发送好友申请，成功/失败分别回调（扫码路径无弹窗可关，onSuccess 默认空操作） */
    private fun sendFriendRequest(
        code: String,
        onSuccess: () -> Unit = {},
        onFail: () -> Unit = {}
    ) {
        val t = token() ?: run { onFail(); return }
        lifecycleScope.launch {
            val r = AccountClient.sendFriendRequest(t, code)
            if (_binding == null) return@launch
            when (r) {
                is AccountClient.ApiResult.Success -> {
                    toast("申请已发送")
                    onSuccess()
                }
                is AccountClient.ApiResult.Failure -> {
                    if (r.http == 401) {
                        requireSessionExpired()
                        onSuccess()
                    } else {
                        toast(r.message)
                        // 重复申请/加自己：算业务终态，关弹窗；其余错误保留弹窗便于修改
                        if (r.code == "already_friends" || r.code == "self") onSuccess()
                    }
                    onFail()
                }
            }
        }
    }

    /** 我的二维码弹窗：好友码 + 可扫描二维码 */
    private fun showMyQrDialog() {
        val ctx = requireContext()
        val profile = SessionStore.getProfile(ctx) ?: return
        val dialog = Dialog(ctx)
        val container = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            setPadding(40, 48, 40, 48)
            setBackgroundResource(R.drawable.bg_card)
        }
        val codeText = android.widget.TextView(ctx).apply {
            text = "我的好友码 ${profile.friendCode}"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = android.view.Gravity.CENTER
        }
        // 二维码白底，否则透明像素在深色弹窗上不可见
        val qrWrap = android.widget.FrameLayout(ctx).apply {
            setBackgroundColor(0xFFFFFFFF.toInt())
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        val size = (220 * resources.displayMetrics.density).toInt()
        val qr = android.widget.ImageView(ctx).apply {
            setImageBitmap(QrEncoder.encode(profile.friendCode, size))
        }
        qrWrap.addView(qr)
        val hint = android.widget.TextView(ctx).apply {
            text = "让对方在「添加好友」里扫这个二维码"
            setTextColor(0x99FFFFFF.toInt())
            textSize = 12.5f
            gravity = android.view.Gravity.CENTER
            setPadding(0, (18 * resources.displayMetrics.density).toInt(), 0, 0)
        }
        container.addView(codeText)
        container.addView(qrWrap)
        container.addView(hint)
        dialog.setContentView(container)
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.CENTER)
        }
        container.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    /** 备注名弹窗：复用房号弹窗的输入框，预填当前备注，保存后本地增量更新 */
    private fun showRemarkDialog(friend: AccountClient.FriendItem) {
        val ctx = requireContext()
        val dialog = Dialog(ctx)
        val dv = DialogLiquidRoomBinding.inflate(layoutInflater)
        dialog.setContentView(dv.root)
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.CENTER)
            setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or
                        WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            )
        }
        dv.tvDialogTitle.text = "备注名"
        dv.btnEnter.text = "保存"
        dv.roleCreate.visibility = View.GONE
        dv.roleJoin.visibility = View.GONE
        dv.etRoomCode.hint = "给 ${friend.nickname.ifBlank { friend.userId }} 设个备注"
        dv.etRoomCode.setText(friend.remark)
        dv.etRoomCode.filters = arrayOf(android.text.InputFilter.LengthFilter(20))
        dv.etRoomCode.inputType = android.text.InputType.TYPE_CLASS_TEXT

        var saved = false
        dv.btnEnter.setOnClickListener {
            if (saved) return@setOnClickListener
            val remark = dv.etRoomCode.text?.toString()?.trim().orEmpty()
            if (remark.length > 20) {
                toast("备注名最多 20 个字符")
                return@setOnClickListener
            }
            // 没变化直接关
            if (remark == friend.remark) {
                dialog.dismiss()
                return@setOnClickListener
            }
            saved = true
            val t = token() ?: run { dialog.dismiss(); return@setOnClickListener }
            lifecycleScope.launch {
                val r = AccountClient.setRemark(t, friend.userId, remark)
                if (_binding == null) return@launch
                when (r) {
                    is AccountClient.ApiResult.Success -> {
                        // 本地增量更新，避免整列表刷新闪烁
                        val list = friendsAdapter.currentList.toMutableList()
                        val idx = list.indexOfFirst { it.userId == friend.userId }
                        if (idx >= 0) {
                            list[idx] = list[idx].copy(remark = remark)
                            friendsAdapter.submitList(list)
                        }
                        toast(if (remark.isEmpty()) "已清除备注" else "备注已保存")
                        dialog.dismiss()
                    }
                    is AccountClient.ApiResult.Failure -> {
                        saved = false
                        if (r.http == 401) {
                            dialog.dismiss()
                            requireSessionExpired()
                        } else {
                            toast(r.message)
                        }
                    }
                }
            }
        }
        dv.btnCancel.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    /** 一键发起共享：生成本地房间号 → 定向邀请好友 → 进会议室 */
    private fun startShareWith(friend: AccountClient.FriendItem) {
        if (!friend.online) {
            toast("对方不在线，无法发起共享")
            return
        }
        val code = generateCode()
        // PresenceClient 是进程级的，不依赖当前 Activity 是否存活
        val pc = App.instance.presenceClient
        if (pc == null) {
            AppLogger.app("[FRIENDS] 发起共享失败：PresenceClient 未建立（未登录？）")
            toast("账号连接未建立，请重新登录后重试")
            return
        }
        AppLogger.app("[FRIENDS] 发起共享 -> ${friend.nickname} room=$code ready=${pc.isReady}")
        // 发送可能落在 WS 重连窗口期：短退避重试，成功后再进会议室
        val handler = Handler(Looper.getMainLooper())
        var tries = 0
        fun trySend() {
            tries++
            val ok = pc.sendShareInvite(friend.userId, code)
            if (ok) {
                toast("已向 ${friend.nickname.ifBlank { friend.userId }} 发送共享邀请")
                val intent = android.content.Intent(requireContext(), MainActivity::class.java)
                    .putExtra(MeetingActivity.EXTRA_MEETING_ACTION, MeetingActivity.ACTION_CREATE)
                    .putExtra(MeetingActivity.EXTRA_MEETING_CODE, code)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
                startActivity(intent)
            } else if (tries < MAX_INVITE_TRIES && context != null) {
                if (tries == 1) toast("账号连接未就绪，正在重连…")
                handler.postDelayed({ if (context != null) trySend() }, INVITE_RETRY_MS)
            } else {
                AppLogger.app("[FRIENDS] 邀请投递失败 ${tries} 次，放弃")
                toast("发送失败：账号连接未就绪，请检查网络后重试")
            }
        }
        trySend()
    }

    /** 最近共享记录：再次向对方发起共享（好友关系可能已解除，需校验） */
    private fun reshareWith(item: AccountClient.ShareItem) {
        val friend = friendsAdapter.currentList.firstOrNull { it.userId == item.peerId }
        if (friend == null) {
            toast("对方已不在你的好友列表")
            return
        }
        startShareWith(friend)
    }

    private companion object {
        const val MAX_INVITE_TRIES = 8
        const val INVITE_RETRY_MS = 1500L
    }

    private fun generateCode(): String {
        val sb = StringBuilder()
        val random = SecureRandom()
        repeat(4) { sb.append(random.nextInt(10)) }
        return sb.toString()
    }

    // ==================== PresenceClient 实时回调（由 LiquidHomeActivity 转发） ====================

    /** 好友上下线：本地增量更新，避免整列表刷新闪烁 */
    fun onPresenceChanged(userId: String, online: Boolean) {
        val list = friendsAdapter.currentList.toMutableList()
        val idx = list.indexOfFirst { it.userId == userId }
        if (idx >= 0) {
            list[idx] = list[idx].copy(online = online)
            friendsAdapter.submitList(list)
        }
    }

    /** 收到好友申请：追加到待处理列表 */
    fun onFriendRequestReceived(requestId: String, fromUserId: String, fromNickname: String) {
        val list = requestsAdapter.currentList.toMutableList()
        if (list.none { it.requestId == requestId }) {
            list.add(
                AccountClient.FriendRequestItem(
                    requestId = requestId,
                    from = AccountClient.Profile(
                        userId = fromUserId,
                        nickname = fromNickname,
                        avatar = "0",
                        friendCode = ""
                    ),
                    createdAt = System.currentTimeMillis()
                )
            )
            requestsAdapter.submitList(list)
            binding.layoutRequests.visibility = View.VISIBLE
        }
    }

    /** 好友关系建立：刷新列表 */
    fun refresh() {
        if (_binding != null) loadAll()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
