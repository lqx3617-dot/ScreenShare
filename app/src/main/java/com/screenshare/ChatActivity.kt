package com.screenshare

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.screenshare.databinding.ActivityChatBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 好友聊天页。消息收发全部委托 ChatSync（本地库 + PresenceClient 长连接），
 * 本页只负责展示与输入。进入即清零该会话未读。
 */
class ChatActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChatBinding
    private val adapter by lazy {
        val my = SessionStore.getProfile(this)
        ChatAdapter(
            mineInitial = initialOf(my?.nickname ?: "我"),
            mineBg = avatarBg(my?.userId ?: ""),
            peerInitial = initialOf(intent.getStringExtra(EXTRA_PEER_NICKNAME).orEmpty()),
            peerBg = avatarBg(peerId)
        )
    }
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var peerId = ""
    private var loadingOlder = false

    private val chatListener = object : ChatSync.Listener {
        override fun onMessagesChanged(messages: List<ChatMessage>) {
            val lm = binding.rvMessages.layoutManager as? LinearLayoutManager ?: return
            // 判断是否贴底：用户在看历史时不抢滚动位置
            val atBottom = lm.findLastVisibleItemPosition() >= adapter.itemCount - 2
            adapter.submitList(messages) {
                if (atBottom) scrollToBottom()
            }
        }

        override fun onUnreadChanged(unread: Map<String, Int>) {
            // 本页已清零，角标由好友列表处理
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        peerId = intent.getStringExtra(EXTRA_PEER_ID).orEmpty()
        val nickname = intent.getStringExtra(EXTRA_PEER_NICKNAME).orEmpty()
        val online = intent.getBooleanExtra(EXTRA_PEER_ONLINE, true)
        if (peerId.isEmpty()) { finish(); return }

        binding.tvPeerName.text = nickname.ifBlank { peerId }
        binding.tvAvatarInitial.text = initialOf(nickname)
        binding.avatarBox.setBackgroundResource(avatarBg(peerId))
        binding.tvPeerStatus.text = if (online) "在线" else "离线"
        binding.tvPeerStatus.setTextColor(if (online) 0xFF34D399.toInt() else 0xFF94A3B8.toInt())

        binding.btnBack.setOnClickListener { finish() }
        binding.btnStartShare.setOnClickListener {
            FriendShareStarter.start(this, AccountClient.FriendItem(peerId, nickname, "0", online, ""))
        }

        val lm = LinearLayoutManager(this).apply { stackFromEnd = true }
        binding.rvMessages.layoutManager = lm
        binding.rvMessages.adapter = adapter
        // 上滑到接近顶部时加载更早消息，并保持原视觉位置
        binding.rvMessages.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (!loadingOlder && adapter.itemCount > 0 && lm.findFirstVisibleItemPosition() <= 3) {
                    loadingOlder = true
                    val anchor = lm.findFirstVisibleItemPosition()
                    scope.launch {
                        val added = ChatSync.loadOlder(peerId)
                        loadingOlder = false
                        if (added > 0) {
                            // 旧消息插入列表头部，锚点后移保持原位置
                            binding.rvMessages.scrollToPosition(anchor + added)
                        }
                    }
                }
            }
        })

        binding.btnSend.setOnClickListener { sendCurrent() }

        ChatSync.register(chatListener)
        ChatSync.enterConversation(peerId)
    }

    private fun sendCurrent() {
        val text = binding.etInput.text?.toString().orEmpty()
        if (ChatSync.send(peerId, text)) {
            binding.etInput.text?.clear()
            scrollToBottom()
        } else {
            // v1.414: 非法输入给出可操作提示，不再静默吞掉（内容为空/超长/未登录）
            val msg = when {
                text.isBlank() -> "消息内容不能为空"
                text.length > ChatSync.MAX_TEXT -> "单条消息最多 ${ChatSync.MAX_TEXT} 字"
                else -> "请先登录后再发送消息"
            }
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
    }

    private fun scrollToBottom() {
        binding.rvMessages.post {
            val n = adapter.itemCount
            if (n > 0) binding.rvMessages.smoothScrollToPosition(n - 1)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        ChatSync.unregister(chatListener)
        ChatSync.leaveConversation()
        scope.cancel()
    }

    companion object {
        private const val EXTRA_PEER_ID = "peer_id"
        private const val EXTRA_PEER_NICKNAME = "peer_nickname"
        private const val EXTRA_PEER_ONLINE = "peer_online"

        fun start(context: Context, peerId: String, nickname: String, online: Boolean) {
            context.startActivity(
                Intent(context, ChatActivity::class.java).apply {
                    putExtra(EXTRA_PEER_ID, peerId)
                    putExtra(EXTRA_PEER_NICKNAME, nickname)
                    putExtra(EXTRA_PEER_ONLINE, online)
                }
            )
        }
    }
}
