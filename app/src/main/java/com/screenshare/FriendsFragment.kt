package com.screenshare

import android.app.Dialog
import android.os.Bundle
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
        FriendsAdapter { friend -> startShareWith(friend) }
    }
    private val requestsAdapter by lazy {
        FriendRequestsAdapter(
            onAccept = { item -> respondRequest(item.requestId, accept = true) },
            onReject = { item -> respondRequest(item.requestId, accept = false) }
        )
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

    /** 拉取好友列表 + 待处理申请 */
    private fun loadAll() {
        val t = token() ?: return
        lifecycleScope.launch {
            val friends = AccountClient.getFriends(t)
            val reqs = AccountClient.getFriendRequests(t)
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
        // 隐藏角色选择（加好友不需要）
        dv.roleCreate.visibility = View.GONE
        dv.roleJoin.visibility = View.GONE
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
            val t = token() ?: run { dialog.dismiss(); return@setOnClickListener }
            lifecycleScope.launch {
                val r = AccountClient.sendFriendRequest(t, code)
                when (r) {
                    is AccountClient.ApiResult.Success -> {
                        toast("申请已发送")
                        dialog.dismiss()
                    }
                    is AccountClient.ApiResult.Failure -> {
                        sent = false
                        if (r.http == 401) {
                            dialog.dismiss()
                            requireSessionExpired()
                        } else {
                            toast(r.message)
                            // 重复申请等错误保留弹窗，便于修改
                            if (r.code == "already_friends" || r.code == "self") dialog.dismiss()
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
        val act = activity as? LiquidHomeActivity
        // 先进入会议室建房，再投递邀请（观看端接受后凭码加入）
        act?.presenceClient?.sendShareInvite(friend.userId, code)
        toast("已向 ${friend.nickname.ifBlank { friend.userId }} 发送共享邀请")
        val intent = android.content.Intent(requireContext(), MainActivity::class.java)
            .putExtra(MeetingActivity.EXTRA_MEETING_ACTION, MeetingActivity.ACTION_CREATE)
            .putExtra(MeetingActivity.EXTRA_MEETING_CODE, code)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
        startActivity(intent)
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
