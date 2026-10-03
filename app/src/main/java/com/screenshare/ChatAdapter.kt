package com.screenshare

import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.screenshare.databinding.ItemChatMessageBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 聊天消息适配器：mine 右侧玫瑰气泡 + 右侧头像 / 对方左侧灰白气泡 + 左侧头像，气泡内显示时间与发送状态 */
class ChatAdapter(
    private val mineInitial: String,
    private val mineBg: Int,
    private val peerInitial: String,
    private val peerBg: Int
) : ListAdapter<ChatMessage, ChatAdapter.VH>(DIFF) {

    private val timeFmt = SimpleDateFormat("HH:mm", Locale.CHINA)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemChatMessageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val m = getItem(position)
        holder.b.apply {
            tvMessage.text = m.text
            // 头像：mine 显示右端、对方显示左端（两个位都设内容，holder 会跨 mine/other 复用）
            val initial = if (m.mine) mineInitial else peerInitial
            val bg = if (m.mine) mineBg else peerBg
            avatarStart.visibility = if (m.mine) View.GONE else View.VISIBLE
            avatarEnd.visibility = if (m.mine) View.VISIBLE else View.GONE
            avatarStart.setBackgroundResource(bg)
            avatarEnd.setBackgroundResource(bg)
            tvAvatarStartInitial.text = initial
            tvAvatarEndInitial.text = initial
            // 气泡贴向有头像的一侧，并留出头像宽 34dp + 间距 8dp，避免重叠
            val avatarGap = (42 * holder.itemView.resources.displayMetrics.density).toInt()
            val lp = bubble.layoutParams as FrameLayout.LayoutParams
            if (m.mine) {
                lp.gravity = Gravity.END
                lp.marginStart = 0
                lp.marginEnd = avatarGap
            } else {
                lp.gravity = Gravity.START
                lp.marginStart = avatarGap
                lp.marginEnd = 0
            }
            bubble.layoutParams = lp
            bubble.setBackgroundResource(if (m.mine) R.drawable.bg_chat_bubble_mine else R.drawable.bg_chat_bubble_other)
            tvMeta.text = if (m.mine) metaMine(m) else timeFmt.format(Date(m.ts))
        }
    }

    /** 我的消息状态行：发送中 / 失败 / 已读 / 已送达 */
    private fun metaMine(m: ChatMessage): String {
        val t = timeFmt.format(Date(m.ts))
        return when (m.status) {
            STATUS_SENDING -> "$t · 发送中…"
            STATUS_FAILED -> "$t · 未送达"
            else -> if (m.read) "$t · 已读" else "$t · 已送达"
        }
    }

    class VH(val b: ItemChatMessageBinding) : RecyclerView.ViewHolder(b.root)

    companion object {
        const val STATUS_SENDING = 0
        const val STATUS_SENT = 1
        const val STATUS_FAILED = 2

        private val DIFF = object : DiffUtil.ItemCallback<ChatMessage>() {
            override fun areItemsTheSame(o: ChatMessage, n: ChatMessage) = o.id == n.id
            override fun areContentsTheSame(o: ChatMessage, n: ChatMessage) =
                o.id == n.id && o.seq == n.seq && o.status == n.status && o.read == n.read && o.text == n.text
        }
    }
}
