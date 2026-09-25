package com.screenshare

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.launch

/**
 * 好友详情页：好友资料 + 与 TA 的最近共享（从好友列表点击进入）。
 * 最近共享按 peerId 过滤，仅显示与当前好友的记录。
 */
class FriendDetailFragment : Fragment() {

    private var friend: AccountClient.FriendItem? = null
    private val sharesAdapter by lazy {
        RecentSharesAdapter { item ->
            // 点击历史记录再次发起共享；好友关系可能已解除，用最新好友列表校验
            lifecycleScope.launch {
                val t = SessionStore.getToken(requireContext()) ?: return@launch
                val list = (AccountClient.getFriends(t) as? AccountClient.ApiResult.Success)?.data.orEmpty()
                val f = list.firstOrNull { it.userId == item.peerId }
                if (f == null) {
                    (activity as? LiquidHomeActivity)?.showToast("对方已不在你的好友列表")
                    return@launch
                }
                FriendShareStarter.start(requireContext(), f)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val args = requireArguments()
        friend = AccountClient.FriendItem(
            userId = args.getString(ARG_USER_ID).orEmpty(),
            nickname = args.getString(ARG_NICKNAME).orEmpty(),
            avatar = args.getString(ARG_AVATAR).orEmpty(),
            online = args.getBoolean(ARG_ONLINE),
            remark = args.getString(ARG_REMARK).orEmpty()
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_friend_detail, container, false)
        view.findViewById<View>(R.id.tvBack).setOnClickListener {
            (requireActivity() as? LiquidHomeActivity)?.popSubPage()
        }
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvShares).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = sharesAdapter
        }
        friend?.let { fill(it) }
        view.findViewById<View>(R.id.btnShare).setOnClickListener {
            friend?.let { FriendShareStarter.start(requireContext(), it) }
        }
    }

    private fun fill(f: AccountClient.FriendItem) {
        val v = view ?: return
        v.findViewById<android.widget.TextView>(R.id.tvTitle).text = f.remark.ifBlank { f.nickname }.ifBlank { f.userId }
        v.findViewById<android.widget.TextView>(R.id.tvName).text = f.remark.ifBlank { f.nickname }.ifBlank { f.userId }
        v.findViewById<android.widget.TextView>(R.id.tvAvatarInitial).text = initialOf(f.remark.ifBlank { f.nickname })
        v.findViewById<View>(R.id.avatarBox).setBackgroundResource(avatarBg(f.userId))
        updateStatus(f.online, f.nickname)
    }

    private fun updateStatus(online: Boolean, nickname: String) {
        val v = view ?: return
        val tv = v.findViewById<android.widget.TextView>(R.id.tvStatus)
        if (online) {
            tv.text = "在线"
            tv.setTextColor(0xFF34D399.toInt())
        } else {
            tv.text = "离线"
            tv.setTextColor(0xFF94A3B8.toInt())
        }
    }

    override fun onResume() {
        super.onResume()
        loadShares()
        refreshOnline()
    }

    /** 与该好友的最近共享 */
    private fun loadShares() {
        val f = friend ?: return
        val t = SessionStore.getToken(requireContext()) ?: return
        lifecycleScope.launch {
            val r = AccountClient.getRecentShares(t)
            val list = (r as? AccountClient.ApiResult.Success)?.data
                ?.filter { it.peerId == f.userId }
                .orEmpty()
            val v = view ?: return@launch
            sharesAdapter.submitList(list)
            v.findViewById<View>(R.id.tvSharesEmpty).visibility =
                if (list.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    /** 进入与返回时刷新在线状态（快照可能已过时） */
    private fun refreshOnline() {
        val f = friend ?: return
        val t = SessionStore.getToken(requireContext()) ?: return
        lifecycleScope.launch {
            val list = (AccountClient.getFriends(t) as? AccountClient.ApiResult.Success)?.data.orEmpty()
            val latest = list.firstOrNull { it.userId == f.userId } ?: return@launch
            val v = view ?: return@launch
            // 备注可能被改，一并以服务端为准
            fill(latest)
        }
    }

    companion object {
        private const val ARG_USER_ID = "user_id"
        private const val ARG_NICKNAME = "nickname"
        private const val ARG_AVATAR = "avatar"
        private const val ARG_ONLINE = "online"
        private const val ARG_REMARK = "remark"

        fun newInstance(friend: AccountClient.FriendItem): FriendDetailFragment {
            return FriendDetailFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_USER_ID, friend.userId)
                    putString(ARG_NICKNAME, friend.nickname)
                    putString(ARG_AVATAR, friend.avatar)
                    putBoolean(ARG_ONLINE, friend.online)
                    putString(ARG_REMARK, friend.remark)
                }
            }
        }
    }
}
