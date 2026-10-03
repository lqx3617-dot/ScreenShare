package com.screenshare

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.withContext
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue

/**
 * 聊天同步中心（进程级单例，[App.onCreate] 初始化）。
 *
 * 收发链路：UI -> [send] 写本地库(发送中) -> PresenceClient.sendChat ->
 * 服务端入库 -> chat-ack(我)/chat-message(对方) -> 回写本地库 -> 刷新 UI。
 *
 * 重连补发：WS 未就绪时消息留库保持「发送中」并入补发队列，
 * PresenceClient 重连 auth 成功后由 [flushPendingSends] 统一补发，不丢消息。
 * v1.414：进程被杀后内存队列丢失，[init] 启动时重扫库中 status=0 的消息重新入队，
 * 断网发送的消息重启后仍会补发且在聊天页可见。
 *
 * 未读以本地库为准：收到对方新消息 +1，进入会话清零并 REST 同步服务端；
 * 收到上线补推（chat-unread）时增量拉新到本地库后重算，保证本地永远完整。
 *
 * 并发模型（v1.414）：所有本地库访问统一收敛到单线程 [dbScope] 串行执行——
 * 原先 exists→upsert 的 check-then-act 在 WS 线程/IO 协程/主线程并发执行，
 * REPLACAE 覆盖可能把已置 read=1 的记录回退。单线程后竞态自然消除，无需手动加锁。
 *
 * 观察方式：自写 Listener（项目无 LiveData 依赖，避免引入新库），
 * 所有回调切主线程，UI 在 onCreate 注册、onDestroy 反注册即可。
 */
object ChatSync {

    interface Listener {
        /** 当前会话消息列表变化（主线程） */
        fun onMessagesChanged(messages: List<ChatMessage>)
        /** 未读汇总变化（主线程，peerId -> count） */
        fun onUnreadChanged(unread: Map<String, Int>)
    }

    private const val PAGE_SIZE = 50
    private const val MAX_SYNC_PAGES = 20
    const val MAX_TEXT = 500

    private lateinit var db: ChatDbHelper
    @Volatile private var me: String = ""
    @Volatile private var currentPeer: String? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    /** 本地库访问专用单线程：保证所有读写在同一线程串行，避免 check-then-act 竞态 */
    private val dbDispatcher = newSingleThreadContext("chat-db")
    private val dbScope = CoroutineScope(SupervisorJob() + dbDispatcher)
    private val listeners = CopyOnWriteArrayList<Listener>()
    private val pendingSends = LinkedBlockingQueue<ChatMessage>()
    // 仅主线程访问：注册时立即推送当前快照，避免 UI 闪烁空白
    private val messagesCache = mutableListOf<ChatMessage>()
    @Volatile private var unreadCache: Map<String, Int> = emptyMap()

    fun init(context: Context) {
        if (::db.isInitialized) return
        db = ChatDbHelper(context.applicationContext)
        me = SessionStore.getProfile(context)?.userId ?: ""
        dbScope.launch {
            // v1.414: 恢复未投递消息——进程被杀后内存队列丢失，库中 status=0 的消息
            // 若不重入队，将永久卡在"发送中"且不可见
            val pendings = db.pendingOf(me)
            if (pendings.isNotEmpty()) {
                for (m in pendings) pendingSends.offer(m)
                AppLogger.app("[CHAT] 重启恢复待发消息 ${pendings.size} 条")
                flushPendingSends()
            }
            refreshUnread()
        }
    }

    /** 登录/切换账号后更新当前用户 */
    fun setMe(userId: String) {
        me = userId
        dbScope.launch { refreshUnread() }
    }

    fun register(l: Listener) {
        listeners += l
        mainHandler.post {
            l.onMessagesChanged(ArrayList(messagesCache))
            l.onUnreadChanged(unreadCache)
        }
    }

    fun unregister(l: Listener) {
        listeners -= l
    }

    /** 当前未读快照（主线程读，供列表页与服务端无 unread 字段的好友列表合并） */
    fun unreadSnapshot(): Map<String, Int> = unreadCache

    private fun token(): String? = App.instance?.let { SessionStore.getToken(it) }

    // ---------- 发送 ----------

    /**
     * 发送一条消息：先写本地库（发送中），再投 WS。
     * 返回 false 仅在内容非法或未登录时，UI 层据此提示。
     */
    fun send(peer: String, text: String): Boolean {
        // v1.414: 与服务端 normText 一致（空白折叠为单空格），保证本地预览与对方
        // 收到的正文一致；否则我端保留换行、对方被折叠，两端文本不符
        val content = text.replace(Regex("\\s+"), " ").trim()
        if (me.isEmpty() || content.isEmpty() || content.length > MAX_TEXT) return false
        val msg = ChatMessage(
            id = java.util.UUID.randomUUID().toString(),
            seq = 0,
            fromUser = me,
            toUser = peer,
            text = content,
            ts = System.currentTimeMillis(),
            mine = true,
            status = 0,
            read = false
        )
        dbScope.launch {
            db.upsert(msg)
            if (peer == currentPeer) publishMessages(peer)
            val pc = App.instance?.presenceClient
            val ok = pc?.sendChat(peer, msg.id, content, msg.ts) ?: false
            if (!ok) {
                // WS 未就绪：留库保持「发送中」，等重连成功后补发
                pendingSends.offer(msg)
                AppLogger.app("[CHAT] WS 未就绪，消息入补发队列 id=${msg.id.take(8)} -> ${peer.take(8)}")
            }
        }
        return true
    }

    /** PresenceClient 重连 auth 成功后调用：补发未投递的消息 */
    fun flushPendingSends() {
        val pc = App.instance?.presenceClient ?: return
        while (true) {
            val msg = pendingSends.poll() ?: break
            // 库中可能已被 chat-rejected 置为失败，或已被本机 ack，跳过
            if (!db.exists(msg.id)) continue
            if (!pc.sendChat(msg.toUser, msg.id, msg.text, msg.ts)) {
                pendingSends.offer(msg) // 仍未就绪，放回队尾
                break
            }
        }
    }

    // ---------- 服务端推送回调（PresenceClient 调用，WS 线程） ----------

    /** 收到 chat-message：对方发的，或自己其他设备发的（mine=true） */
    fun onServerMessage(seq: Long, id: String, from: String, to: String, text: String, ts: Long, mine: Boolean) {
        dbScope.launch {
            if (db.exists(id)) {
                // 本机刚发的消息被服务端回执（或别的设备先同步到）：补 seq 并置已发送
                if (seq > 0) db.updateStatus(id, 1, seq)
            } else {
                db.upsert(ChatMessage(id, seq, from, to, text, ts, mine, status = 1, read = false))
                if (!mine && from == currentPeer) {
                    // 正打开该会话：直接已读，不累计未读
                    markReadInternal(from)
                }
            }
            // v1.414: currentPeer 一次快照——leaveConversation 可能在判断与使用之间置空，
            // 原先 currentPeer!! 会抛 NPE（聊天页销毁瞬间恰有消息到达）
            val p = currentPeer
            if (from == p || to == p) publishMessages(p!!)
            refreshUnread()
        }
    }

    /** 收到 chat-ack：消息已入库，置已发送并补 seq */
    fun onAck(id: String, seq: Long) {
        dbScope.launch {
            if (seq > 0) db.updateStatus(id, 1, seq)
            currentPeer?.let { publishMessages(it) }
        }
    }

    /** 收到 chat-rejected：非好友/限流/超长，置失败并提示 */
    fun onRejected(id: String, reason: String) {
        dbScope.launch {
            db.updateStatus(id, 2)
            AppLogger.app("[CHAT] 发送被拒 id=${id.take(8)} reason=$reason")
            currentPeer?.let { publishMessages(it) }
        }
        mainHandler.post {
            App.instance?.let { Toast.makeText(it, reason.ifBlank { "发送失败" }, Toast.LENGTH_SHORT).show() }
        }
    }

    /** 收到 chat-read：from 读了我发给 from 的消息 */
    fun onRead(from: String, to: String) {
        dbScope.launch {
            db.markReadByPeer(me, from)
            if (from == currentPeer) publishMessages(from)
        }
    }

    /** 收到 chat-unread（上线补推）：触发增量拉新，本地库完整后未读自然准确 */
    fun onUnreadPush(peerId: String, count: Int) {
        if (me.isEmpty() || peerId.isEmpty()) return
        if (peerId == currentPeer) return // 正打开的会话已在持续同步
        dbScope.launch { syncNewMessages(peerId) }
    }

    // ---------- 会话生命周期（ChatActivity 调用） ----------

    /** 进入会话：本地首屏 + 增量拉新 + 清零未读 */
    fun enterConversation(peer: String) {
        currentPeer = peer
        dbScope.launch {
            publishMessages(peer)
            syncNewMessages(peer)
            markReadInternal(peer)
        }
    }

    fun leaveConversation() {
        currentPeer = null
    }

    /**
     * 上滑加载更早消息，返回新增条数（0 表示已到最早或失败；调用方据此保持滚动位置）。
     *
     * v1.414: 旧消息前插进缓存而非让 publishMessages 重建——原先 publish 固定取
     * 最新 50 条，旧消息入库却永不进列表，上滑分页形同失效。
     */
    suspend fun loadOlder(peer: String): Int = withContext(dbDispatcher) {
        val tk = token() ?: return@withContext 0
        val before = db.minSeq(me, peer).takeIf { it > 0 } ?: return@withContext 0
        val r = ChatClient.history(tk, me, peer, beforeSeq = before)
        if (r !is AccountClient.ApiResult.Success || r.data.messages.isEmpty()) return@withContext 0
        val older = ArrayList<ChatMessage>()
        for (m in r.data.messages) if (!db.exists(m.id)) { db.upsert(m); older.add(m) }
        if (older.isEmpty()) return@withContext 0
        val added = older.size
        mainHandler.post {
            if (currentPeer != peer) return@post
            messagesCache.addAll(0, older)
            // 发副本：ListAdapter 遇到同一引用会跳过 diff
            for (l in listeners) l.onMessagesChanged(ArrayList(messagesCache))
        }
        added
    }

    // ---------- 内部 ----------

    /**
     * 增量拉新到本地库（循环至无更多或达上限页数）。
     * v1.414: 网络挂起段移出 dbDispatcher——单线程里直接 await 网络会阻塞
     * 全部库访问（首屏/收消息都会卡住），db 读改写分段切回 dbDispatcher 仍保持串行。
     */
    private suspend fun syncNewMessages(peer: String) {
        val tk = token() ?: return
        var pages = 0
        while (pages++ < MAX_SYNC_PAGES) {
            val after = withContext(dbDispatcher) { db.maxSeq(me, peer) }
            val r = ChatClient.history(tk, me, peer, afterSeq = after)
            if (r !is AccountClient.ApiResult.Success || r.data.messages.isEmpty()) break
            withContext(dbDispatcher) {
                for (m in r.data.messages) if (!db.exists(m.id)) db.upsert(m)
            }
            if (!r.data.hasMore) break
        }
        withContext(dbDispatcher) {
            publishMessages(peer)
            refreshUnread()
        }
    }

    private suspend fun markReadInternal(peer: String) {
        withContext(dbDispatcher) {
            db.markReadByMe(me, peer)
            refreshUnread()
        }
        val tk = token() ?: return
        ChatClient.markRead(tk, peer)
        withContext(dbDispatcher) { publishMessages(peer) }
    }

    /** 查库 + 推主线程。须在 [dbScope] 调用（与库访问同线程串行） */
    private fun publishMessages(peer: String) {
        val list = db.conversation(me, peer, PAGE_SIZE)
        mainHandler.post {
            if (currentPeer != peer) return@post
            messagesCache.clear()
            messagesCache.addAll(list)
            // 发副本：ListAdapter 遇到同一引用会跳过 diff，导致发送后界面不刷新
            for (l in listeners) l.onMessagesChanged(ArrayList(list))
        }
    }

    private fun refreshUnread() {
        val map = db.unreadCounts(me)
        mainHandler.post {
            unreadCache = map
            for (l in listeners) l.onUnreadChanged(map)
        }
    }
}
