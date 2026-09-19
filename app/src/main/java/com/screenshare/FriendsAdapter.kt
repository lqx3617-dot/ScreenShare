package com.screenshare

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.screenshare.databinding.ItemFriendCardBinding
import com.screenshare.databinding.ItemFriendRequestBinding
import com.screenshare.databinding.ItemRecentShareBinding

/** 头像渐变档位（粉/紫/青），按 userId 哈希固定分配，同一好友永远是同一颜色 */
private val AVATAR_BG = arrayOf(R.drawable.bg_avatar_pink, R.drawable.bg_avatar_violet, R.drawable.bg_avatar_cyan)
private fun avatarBg(userId: String) = AVATAR_BG[(userId.hashCode() and 0x7fffffff) % AVATAR_BG.size]

/** 昵称首字（中文取第一个字，英文取首字母大写） */
private fun initialOf(name: String): String {
    val s = name.trim()
    if (s.isEmpty()) return "?"
    return s.first().toString().uppercase()
}

/** 好友列表适配器：在线状态点 + 一键发起共享 + 长按改备注 */
class FriendsAdapter(
    private val onStartShare: (AccountClient.FriendItem) -> Unit,
    private val onEditRemark: (AccountClient.FriendItem) -> Unit = {}
) : ListAdapter<AccountClient.FriendItem, FriendsAdapter.VH>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemFriendCardBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        // 有备注名优先显示备注，昵称降级到状态行，避免和备注重复
        holder.b.tvFriendName.text = item.remark.ifBlank { item.nickname.ifBlank { item.userId } }
        holder.b.tvAvatarInitial.text = initialOf(item.remark.ifBlank { item.nickname })
        holder.b.avatarBox.setBackgroundResource(avatarBg(item.userId))
        holder.b.apply {
            // 后缀昵称：仅在设了备注且与昵称不同时出现
            val suffix = if (item.remark.isNotBlank() && item.remark != item.nickname) " · ${item.nickname}" else ""
            if (item.online) {
                tvFriendStatus.text = "在线$suffix"
                tvFriendStatus.setTextColor(0xFF34D399.toInt())
                viewStatusDot.setBackgroundResource(R.drawable.bg_dot_online)
                viewDot.setBackgroundResource(R.drawable.bg_dot_online)
                viewDot.visibility = View.VISIBLE
            } else {
                tvFriendStatus.text = "离线$suffix"
                tvFriendStatus.setTextColor(0xFF94A3B8.toInt())
                viewStatusDot.setBackgroundResource(R.drawable.bg_dot_offline)
                viewDot.visibility = View.GONE
            }
        }
        holder.b.btnStartShare.setOnClickListener { onStartShare(item) }
        holder.b.btnStartShare.isEnabled = item.online
        holder.b.btnStartShare.alpha = if (item.online) 1f else 0.45f
        holder.itemView.setOnLongClickListener { onEditRemark(item); true }
    }

    class VH(val b: ItemFriendCardBinding) : RecyclerView.ViewHolder(b.root)

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<AccountClient.FriendItem>() {
            override fun areItemsTheSame(o: AccountClient.FriendItem, n: AccountClient.FriendItem) = o.userId == n.userId
            override fun areContentsTheSame(o: AccountClient.FriendItem, n: AccountClient.FriendItem) =
                o.userId == n.userId && o.online == n.online && o.nickname == n.nickname && o.remark == n.remark
        }
    }
}

/** 最近共享记录适配器：点击可再次向对方发起共享 */
class RecentSharesAdapter(
    private val onClick: (AccountClient.ShareItem) -> Unit
) : ListAdapter<AccountClient.ShareItem, RecentSharesAdapter.VH>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemRecentShareBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        holder.b.tvSharePeer.text = item.peerNickname.ifBlank { item.peerId }
        holder.b.tvAvatarInitial.text = initialOf(item.peerNickname)
        holder.b.avatarBox.setBackgroundResource(avatarBg(item.peerId))
        val roleText = if (item.role == "host") "共享给 TA" else "观看 TA 的共享"
        val dur = item.durationMs
        val durText = if (dur != null && dur > 0) " · ${formatDuration(dur)}" else ""
        holder.b.tvShareMeta.text = "$roleText$durText · 房间 ${item.roomCode}"
        holder.b.tvShareTime.text = formatRelative(item.startedAt)
        holder.itemView.setOnClickListener { onClick(item) }
    }

    class VH(val b: ItemRecentShareBinding) : RecyclerView.ViewHolder(b.root)

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<AccountClient.ShareItem>() {
            override fun areItemsTheSame(o: AccountClient.ShareItem, n: AccountClient.ShareItem) =
                o.sessionId == n.sessionId
            override fun areContentsTheSame(o: AccountClient.ShareItem, n: AccountClient.ShareItem) = o == n
        }

        private fun formatDuration(ms: Long): String {
            val s = ms / 1000
            if (s < 60) return "${s}秒"
            val m = s / 60
            if (m < 60) return "${m}分${s % 60}秒"
            return "${m / 60}小时${m % 60}分"
        }

        private fun formatRelative(ts: Long): String {
            val diff = System.currentTimeMillis() - ts
            return when {
                diff < 60_000 -> "刚刚"
                diff < 3_600_000 -> "${diff / 60_000}分钟前"
                diff < 86_400_000 -> "${diff / 3_600_000}小时前"
                diff < 30L * 86_400_000 -> "${diff / 86_400_000}天前"
                else -> java.text.SimpleDateFormat("MM-dd", java.util.Locale.CHINA).format(java.util.Date(ts))
            }
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
