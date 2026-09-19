package com.screenshare

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.screenshare.databinding.ItemFriendCardBinding
import com.screenshare.databinding.ItemFriendRequestBinding

/** 头像渐变档位（粉/紫/青），按 userId 哈希固定分配，同一好友永远是同一颜色 */
private val AVATAR_BG = arrayOf(R.drawable.bg_avatar_pink, R.drawable.bg_avatar_violet, R.drawable.bg_avatar_cyan)
private fun avatarBg(userId: String) = AVATAR_BG[(userId.hashCode() and 0x7fffffff) % AVATAR_BG.size]

/** 昵称首字（中文取第一个字，英文取首字母大写） */
private fun initialOf(name: String): String {
    val s = name.trim()
    if (s.isEmpty()) return "?"
    return s.first().toString().uppercase()
}

/** 好友列表适配器：在线状态点 + 一键发起共享 */
class FriendsAdapter(
    private val onStartShare: (AccountClient.FriendItem) -> Unit
) : ListAdapter<AccountClient.FriendItem, FriendsAdapter.VH>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemFriendCardBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        holder.b.tvFriendName.text = item.nickname.ifBlank { item.userId }
        holder.b.tvAvatarInitial.text = initialOf(item.nickname)
        holder.b.avatarBox.setBackgroundResource(avatarBg(item.userId))
        holder.b.apply {
            if (item.online) {
                tvFriendStatus.text = "在线"
                tvFriendStatus.setTextColor(0xFF34D399.toInt())
                viewStatusDot.setBackgroundResource(R.drawable.bg_dot_online)
                viewDot.setBackgroundResource(R.drawable.bg_dot_online)
                viewDot.visibility = View.VISIBLE
            } else {
                tvFriendStatus.text = "离线"
                tvFriendStatus.setTextColor(0xFF94A3B8.toInt())
                viewStatusDot.setBackgroundResource(R.drawable.bg_dot_offline)
                viewDot.visibility = View.GONE
            }
        }
        holder.b.btnStartShare.setOnClickListener { onStartShare(item) }
        holder.b.btnStartShare.isEnabled = item.online
        holder.b.btnStartShare.alpha = if (item.online) 1f else 0.45f
    }

    class VH(val b: ItemFriendCardBinding) : RecyclerView.ViewHolder(b.root)

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<AccountClient.FriendItem>() {
            override fun areItemsTheSame(o: AccountClient.FriendItem, n: AccountClient.FriendItem) = o.userId == n.userId
            override fun areContentsTheSame(o: AccountClient.FriendItem, n: AccountClient.FriendItem) =
                o.userId == n.userId && o.online == n.online && o.nickname == n.nickname
        }
    }
}

/** 好友申请适配器：接受/拒绝 */
class FriendRequestsAdapter(
    private val onAccept: (AccountClient.FriendRequestItem) -> Unit,
    private val onReject: (AccountClient.FriendRequestItem) -> Unit
) : ListAdapter<AccountClient.FriendRequestItem, FriendRequestsAdapter.VH>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemFriendRequestBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        holder.b.tvRequestName.text = item.from.nickname.ifBlank { item.from.userId }
        holder.b.tvAvatarInitial.text = initialOf(item.from.nickname)
        holder.b.avatarBox.setBackgroundResource(avatarBg(item.from.userId))
        holder.b.btnAccept.setOnClickListener { onAccept(item) }
        holder.b.btnReject.setOnClickListener { onReject(item) }
    }

    class VH(val b: ItemFriendRequestBinding) : RecyclerView.ViewHolder(b.root)

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<AccountClient.FriendRequestItem>() {
            override fun areItemsTheSame(o: AccountClient.FriendRequestItem, n: AccountClient.FriendRequestItem) = o.requestId == n.requestId
            override fun areContentsTheSame(o: AccountClient.FriendRequestItem, n: AccountClient.FriendRequestItem) = o == n
        }
    }
}
