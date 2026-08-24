package com.screenshare

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.screenshare.databinding.ActivityMeetingBinding
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 会议连接页（前端入口）：
 * App 启动后的第一个界面，负责「创建房间」或「加入会议」两个动作。
 * 用户在此输入会议号 / 创建房间后跳转到 MainActivity（会议室），本页随即退出。
 *
 * 操作流程：
 * - 创建房间：生成 4 位会议号 → 跳转 MainActivity(action=create)
 * - 加入会议：输入 4 位会议号 → 校验 → 跳转 MainActivity(action=join)
 * - 分享链接：冷启动 screenshare://join?code=XXXX → 直接跳转加入流程
 * - 最近会议：历史（创建/加入）会议快速复用，点击直接进入对应会议
 */
class MeetingActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MEETING_ACTION = "extra_meeting_action"
        const val EXTRA_MEETING_CODE = "extra_meeting_code"
        const val ACTION_CREATE = "create"
        const val ACTION_JOIN = "join"

        private const val PREFS_HISTORY = "meeting_history"
        private const val KEY_LIST = "list"
        private const val MAX_HISTORY = 8

        // ==================== 专属房间（情侣快捷入口） ====================
        private const val PREFS_FAVORITE = "favorite_room"
        private const val KEY_FAV_CODE = "code"
        private const val KEY_FAV_ROLE = "role"

        /** 读取专属房间设置：Pair(房间号, 角色 action) 或 null（未设置） */
        fun getFavoriteRoom(context: Context): Pair<String, String>? {
            return try {
                val p = context.getSharedPreferences(PREFS_FAVORITE, Context.MODE_PRIVATE)
                val code = p.getString(KEY_FAV_CODE, null) ?: return null
                val role = p.getString(KEY_FAV_ROLE, null) ?: ACTION_CREATE
                if (!Regex("^[0-9]{4}$").matches(code)) null else code to role
            } catch (_: Throwable) {
                null
            }
        }

        /** 保存专属房间设置 */
        fun setFavoriteRoom(context: Context, action: String, code: String) {
            context.getSharedPreferences(PREFS_FAVORITE, Context.MODE_PRIVATE)
                .edit().putString(KEY_FAV_CODE, code).putString(KEY_FAV_ROLE, action).apply()
        }

        /** 清除专属房间设置 */
        fun clearFavoriteRoom(context: Context) {
            context.getSharedPreferences(PREFS_FAVORITE, Context.MODE_PRIVATE)
                .edit().remove(KEY_FAV_CODE).remove(KEY_FAV_ROLE).apply()
        }

        /** 会议历史条目 */
        data class MeetingEntry(val code: String, val action: String, val ts: Long)

        /** 记录一次会议（创建/加入）到最近历史：同会议号去重置顶，最多保留 MAX_HISTORY 条 */
        fun recordMeetingHistory(context: Context, action: String, code: String) {
            if (!Regex("^[0-9]{4}$").matches(code)) return
            try {
                val list = loadMeetingHistory(context).filter { it.code != code }.toMutableList()
                list.add(0, MeetingEntry(code, action, System.currentTimeMillis()))
                val arr = JSONArray()
                list.take(MAX_HISTORY).forEach { e ->
                    arr.put(JSONObject().put("code", e.code).put("action", e.action).put("ts", e.ts))
                }
                context.getSharedPreferences(PREFS_HISTORY, Context.MODE_PRIVATE)
                    .edit().putString(KEY_LIST, arr.toString()).apply()
            } catch (t: Throwable) {
                android.util.Log.w("MeetingActivity", "记录会议历史失败: ${t.message}")
            }
        }

        /** 读取会议历史（新→旧） */
        fun loadMeetingHistory(context: Context): List<MeetingEntry> {
            return try {
                val raw = context.getSharedPreferences(PREFS_HISTORY, Context.MODE_PRIVATE)
                    .getString(KEY_LIST, null) ?: return emptyList()
                val arr = JSONArray(raw)
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    val code = o.optString("code")
                    if (!Regex("^[0-9]{4}$").matches(code)) return@mapNotNull null
                    MeetingEntry(code, o.optString("action"), o.optLong("ts"))
                }.sortedByDescending { it.ts }
            } catch (_: Throwable) {
                emptyList()
            }
        }

        /** 清空会议历史 */
        fun clearMeetingHistory(context: Context) {
            context.getSharedPreferences(PREFS_HISTORY, Context.MODE_PRIVATE)
                .edit().remove(KEY_LIST).apply()
        }
    }

    private lateinit var binding: ActivityMeetingBinding

    // ==================== 专属房间在线状态 ====================
    /** 查询「对方是否在线」：GET <信令http>/room-status?code=XXXX */
    private val roomStatusClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .writeTimeout(4, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .build()
    }
    /** 最近一次查询到的在线状态（true=对方在线，false=不在线，null=未知/查询失败）*/
    @Volatile private var favOnline: Boolean? = null
    /** 是否正在针对已设置房间轮询在线状态 */
    private var favPolling = false
    private val favHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val favPollRunnable = object : Runnable {
        override fun run() {
            val fav = getFavoriteRoom(this@MeetingActivity)
            if (fav != null) {
                queryFavStatus(fav.first)
                favHandler.postDelayed(this, 5000)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS,
            WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS
        )
        binding = ActivityMeetingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnCreateRoom.setOnClickListener {
            val code = generateMeetingCode()
            enterMeeting(ACTION_CREATE, code)
        }

        val input = binding.etMeetingCode
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                tryJoin()
                true
            } else false
        }
        // 输入满 4 位自动加入（会议号固定 4 位，输入完成即提交，省去点按钮）
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                if (s?.length == 4) tryJoin()
            }
        })
        binding.btnJoinMeeting.setOnClickListener { tryJoin() }
        binding.tvCheckUpdate.setOnClickListener { UpdateChecker.check(this, manual = true) }
        binding.btnClearRecent.setOnClickListener {
            clearMeetingHistory(this)
            renderRecentMeetings()
        }
        // 专属房间：点击进入/设置，右上角"换一个"重新设置
        binding.llFavBody.setOnClickListener { onFavoriteClicked() }
        binding.btnFavReset.setOnClickListener {
            clearFavoriteRoom(this)
            stopFavPolling()
            renderFavoriteCard()
            onFavoriteClicked()
        }
        // 专属房间「换角色」：不换房间号，仅翻转共享方/观看方
        binding.btnFavSwap.setOnClickListener { onFavoriteSwapRole() }
        // 专属房间「喊TA」：通知对方快上屏
        binding.btnFavCall.setOnClickListener { onCallPeer() }

        // 分享链接唤起：冷启动解析 screenshare://join?code=XXXX
        handleShareLink(intent)
        // 会议未结束：上次会话未主动结束/未失败退出，点开 App 自动重连
        autoResumeMeeting()
        // 专属房间卡片
        renderFavoriteCard()
        // 最近会议列表
        renderRecentMeetings()
        // 入场动画：品牌区、操作卡片、最近会议 依次淡入上滑
        val decel = android.view.animation.DecelerateInterpolator(1.6f)
        binding.llBrand.apply {
            alpha = 0f; translationY = 24f * resources.displayMetrics.density
            animate().alpha(1f).translationY(0f).setDuration(360).setInterpolator(decel).start()
        }
        binding.llActions.apply {
            alpha = 0f; translationY = 30f * resources.displayMetrics.density
            animate().alpha(1f).translationY(0f).setDuration(400).setStartDelay(110)
                .setInterpolator(decel).start()
        }
        binding.llRecent.apply {
            alpha = 0f; translationY = 22f * resources.displayMetrics.density
            animate().alpha(1f).translationY(0f).setDuration(400).setStartDelay(210)
                .setInterpolator(decel).start()
        }
        // 顶部头部条避开系统状态栏（Android 10 及以下内容延伸到系统栏会被遮挡点不到）
        val density = resources.displayMetrics.density
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val status = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            if (status.top > 0) {
                val lp = binding.llBrand.layoutParams as? FrameLayout.LayoutParams
                lp?.topMargin = status.top
                binding.llBrand.layoutParams = lp
            }
            val headerH = (64 * density).toInt()
            binding.svContent.setPadding(
                binding.svContent.paddingLeft,
                headerH + status.top,
                binding.svContent.paddingRight,
                binding.svContent.paddingBottom
            )
            insets
        }
    }

    override fun onResume() {
        super.onResume()
        // 从会议室返回连接页时刷新（历史可能已变化）
        renderRecentMeetings()
        renderFavoriteCard()
        // 重新开始查询专属房间在线状态
        stopFavPolling()
        favPolling = true
        favHandler.post(favPollRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopFavPolling()
    }

    /** 停止专属房间在线状态轮询 */
    private fun stopFavPolling() {
        favPolling = false
        favHandler.removeCallbacks(favPollRunnable)
    }

    // ==================== 专属房间 ====================

    /** 渲染专属房间卡片：已设置显示房间号+角色+对方在线状态，未设置显示提示 */
    private fun renderFavoriteCard() {
        val fav = getFavoriteRoom(this)
        if (fav == null) {
            binding.tvFavCode.text = "未设置"
            binding.tvFavHint.text = "点击设置我们的专属房间号"
            binding.favStatusDot.visibility = View.GONE
            binding.btnFavCall.visibility = View.GONE
            binding.btnFavSwap.visibility = View.GONE
            return
        }
        binding.tvFavCode.text = fav.first
        val isHostRole = fav.second == ACTION_CREATE
        // 角色提示
        val roleText = if (isHostRole) "你是共享方（TA看你的屏幕）" else "你是观看方（你看TA的屏幕）"
        // 在线状态：在线绿色点，不在线灰色点，未知不显示
        val online = favOnline
        val statusText = when (online) {
            true -> " · 对方在线"
            false -> " · 对方不在线"
            null -> ""
        }
        binding.tvFavHint.text = roleText + statusText
        binding.favStatusDot.visibility = if (online == null) View.GONE else View.VISIBLE
        if (online == true) {
            binding.favStatusDot.setBackgroundResource(R.drawable.dot_green)
        } else if (online == false) {
            binding.favStatusDot.setBackgroundResource(R.drawable.dot_gray)
        }
        // 观看方角色才显示"喊TA"（host 是常驻共享方，不需要喊）
        binding.btnFavCall.visibility = if (!isHostRole) View.VISIBLE else View.GONE
        // 切换角色按钮：已设置房间时始终显示
        binding.btnFavSwap.visibility = View.VISIBLE
    }

    /** 查询专属房间在线状态并刷新 UI；同时更新「喊TA」按钮可用性 */
    private fun queryFavStatus(code: String) {
        val httpBase = signalHttpBase() ?: return
        val isHostRole = getFavoriteRoom(this)?.second == ACTION_CREATE
        Thread {
            try {
                // 观看方查"对方（host）是否在房间"；共享方查"是否有观看方已加入"（viewerCount>0）
                val url = if (isHostRole)
                    "$httpBase/room-status?code=$code&s=host"
                else
                    "$httpBase/room-status?code=$code"
                val req = Request.Builder().url(url).build()
                roomStatusClient.newCall(req).execute().use { resp ->
                    val body = resp.body?.string() ?: return@use
                    val json = try { JSONObject(body) } catch (e: Exception) { return@use }
                    val onlineHint = json.optBoolean("online", false)
                    if (onlineHint != favOnline) {
                        favOnline = onlineHint
                        runOnUiThread { renderFavoriteCard() }
                    }
                }
            } catch (_: Throwable) {}
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

    /** 专属房间「换角色」：保持房间号不变，仅翻转共享方/观看方 */
    private fun onFavoriteSwapRole() {
        val fav = getFavoriteRoom(this) ?: return
        val newRole = if (fav.second == ACTION_CREATE) ACTION_JOIN else ACTION_CREATE
        val newRoleText = if (newRole == ACTION_CREATE) "共享方（TA 看我的屏幕）" else "观看方（我看 TA 的屏幕）"
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("切换角色")
            .setMessage("房间号 ${fav.first} 保持不变，切换后你成为：$newRoleText")
            .setPositiveButton("切换") { _, _ ->
                setFavoriteRoom(this, newRole, fav.first)
                favOnline = null
                renderFavoriteCard()
                queryFavStatus(fav.first)
                Toast.makeText(this, "已切换为$newRoleText", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 专属房间「喊TA」：通知对方上屏（观看方发起，host 收到 come-on 提示） */
    private fun onCallPeer() {
        val fav = getFavoriteRoom(this) ?: return
        if (fav.second != ACTION_JOIN) {
            Toast.makeText(this, "你是共享方，无需喊TA，等对方加入即可", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "已提醒对方，等 TA 来上屏...", Toast.LENGTH_LONG).show()
        sendPlsJoin(fav.first)
    }

    /** 专属房间点击：已设置直接进入；未设置弹窗输入房间号+选择角色 */
    private fun onFavoriteClicked() {
        val fav = getFavoriteRoom(this)
        if (fav != null) {
            // 已设置：确认在线状态后进入（在线直接进；不在线提示 + 可选择改角色或仍进入）
            val isHostRole = fav.second == ACTION_CREATE
            val online = favOnline
            if (online == false && !isHostRole) {
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("对方不在线")
                    .setMessage("TA 还没有进入房间 ${fav.first}。\n是否先进入等你加入，或喊 TA 一下？")
                    .setPositiveButton("进入等待") { _, _ -> enterMeeting(fav.second, fav.first) }
                    .setNegativeButton("取消", null)
                    .show()
                return
            }
            enterMeeting(fav.second, fav.first)
            return
        }
        // 未设置：预填一个随机 4 位房间号，双方约定即可
        val input = android.widget.EditText(this).apply {
            hint = "输入 4 位数字房间号（如 1314）"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            maxLines = 1
            setText(generateMeetingCode())
            setSelection(text.length)
        }
        val roles = arrayOf("我是共享方（TA 看我的屏幕）", "我是观看方（我看 TA 的屏幕）")
        var chosenRole = ACTION_CREATE
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("❤️ 设置专属房间")
            .setMessage("双方约定同一个房间号，各自选好角色，之后一键进入")
            .setView(input)
            .setSingleChoiceItems(roles, 0) { _, which -> chosenRole = if (which == 0) ACTION_CREATE else ACTION_JOIN }
            .setPositiveButton("进入") { _, _ ->
                val code = input.text.toString().trim()
                if (!Regex("^[0-9]{4}$").matches(code)) {
                    Toast.makeText(this, "房间号需为 4 位数字", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                setFavoriteRoom(this, chosenRole, code)
                renderFavoriteCard()
                enterMeeting(chosenRole, code)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 渲染最近会议区块：无历史时隐藏整块 */
    private fun renderRecentMeetings() {
        val list = loadMeetingHistory(this)
        binding.llRecent.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
        val container = binding.llRecentList
        container.removeAllViews()
        list.forEach { entry ->
            container.addView(buildRecentItem(entry))
            // 分隔线
            val divider = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1
                ).apply { topMargin = 2.dp(); bottomMargin = 2.dp() }
                setBackgroundColor(Color.parseColor("#1A64748B"))
            }
            container.addView(divider)
        }
    }

    /** 构建单条最近会议条目（点击进入，右侧删除） */
    private fun buildRecentItem(entry: MeetingEntry): View {
        val isCreate = entry.action == ACTION_CREATE
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 10.dp(), 0, 10.dp())
            setOnClickListener { enterMeeting(entry.action, entry.code) }
            // 点击涟漪反馈（minSdk 24 支持 View.foreground）
            val tv = android.util.TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
            foreground = context.getDrawable(tv.resourceId)
        }
        val tvCode = TextView(this).apply {
            text = entry.code
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.15f
            setTextColor(Color.parseColor("#4B8DF9"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val tvMeta = TextView(this).apply {
            text = (if (isCreate) "创建" else "加入") + " · " + relativeTime(entry.ts)
            textSize = 12f
            setTextColor(Color.parseColor("#64748B"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = 10.dp()
            }
        }
        val tvDelete = TextView(this).apply {
            text = "✕"
            textSize = 16f
            setTextColor(Color.parseColor("#64748B"))
            gravity = Gravity.CENTER
            setPadding(8.dp(), 4.dp(), 0, 4.dp())
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            setOnClickListener {
                val rest = loadMeetingHistory(this@MeetingActivity).filter { it.code != entry.code }
                saveHistory(rest)
                renderRecentMeetings()
            }
        }
        row.addView(tvCode)
        row.addView(tvMeta)
        row.addView(tvDelete)
        return row
    }

    /** 直接写回历史列表（供单条删除） */
    private fun saveHistory(list: List<MeetingEntry>) {
        val arr = JSONArray()
        list.forEach { e ->
            arr.put(JSONObject().put("code", e.code).put("action", e.action).put("ts", e.ts))
        }
        getSharedPreferences(PREFS_HISTORY, MODE_PRIVATE)
            .edit().putString(KEY_LIST, arr.toString()).apply()
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

    /** 用临时 WebSocket 短连接直接发 pls-join，随即断开（轻量投递"喊TA"给 host，不进入房间） */
    private fun sendPlsJoin(code: String) {
        val url = BuildConfig.SIGNAL_URL
        if (url.isNullOrBlank()) return
        val client = okhttp3.OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).build()
        val wsReq = okhttp3.Request.Builder().url(url).build()
        val ws = client.newWebSocket(wsReq, object : okhttp3.WebSocketListener() {
            override fun onOpen(webSocket: okhttp3.WebSocket, response: okhttp3.Response) {
                webSocket.send(JSONObject().apply { put("type", "pls-join"); put("code", code) }.toString())
                // 发完稍等即关闭
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ webSocket.close(1000, "done") }, 1200)
            }
            override fun onMessage(webSocket: okhttp3.WebSocket, text: String) {
                val j = try { JSONObject(text) } catch (e: Exception) { return }
                if (j.optString("type") == "error") {
                    val msg = j.optString("message", "")
                    runOnUiThread { Toast.makeText(this@MeetingActivity, if (msg.isBlank()) "提醒失败" else msg, Toast.LENGTH_SHORT).show() }
                    webSocket.close(1000, "err")
                }
            }
            override fun onFailure(webSocket: okhttp3.WebSocket, t: Throwable, response: okhttp3.Response?) {
                runOnUiThread { Toast.makeText(this@MeetingActivity, "提醒失败：无法连接服务器", Toast.LENGTH_SHORT).show() }
            }
        })
        // 兜底延时关闭
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ ws.cancel() }, 3000)
    }

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()

    /**
     * 自动重连上次未结束的会议：
     * 若存在持久化的会议记录（action+code，24h 内），弹出确认框询问是否自动连接，
     * 用户可取消（取消时清除记录，不再自动连接）。避免误入上次未结束的会议
     * （如闪退/异常退出后残留的会议记录导致每次打开都自动连接，且无法取消）。
     * 分享链接优先（handleShareLink 已处理）；无分享链接时生效。
     */
    private fun autoResumeMeeting() {
        val uri = intent?.data
        if (uri != null && uri.scheme == "screenshare") return
        val p = getSharedPreferences("meeting_resume", MODE_PRIVATE)
        val action = p.getString("action", null) ?: return
        val code = p.getString("code", null) ?: return
        if (System.currentTimeMillis() - p.getLong("ts", 0) > 24 * 3600 * 1000L) return
        if (action != ACTION_CREATE && action != ACTION_JOIN) return
        if (!Regex("^[0-9]{4}$").matches(code)) return
        val roleText = if (action == ACTION_CREATE) "共享方" else "观看方"
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("自动连接上次会议")
            .setMessage("检测到上次会议（$code，$roleText）未结束。\n是否自动连接？")
            .setPositiveButton("自动连接") { _, _ -> enterMeeting(action, code) }
            .setNegativeButton("取消", null)
            .setNeutralButton("取消并清除记录") { _, _ ->
                p.edit().clear().apply()
                Toast.makeText(this, "已清除自动连接记录", Toast.LENGTH_SHORT).show()
            }
            .setCancelable(true)
            .setOnCancelListener { /* 用户按返回/点空白关闭：本次不连接，保留记录 */ }
            .show()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareLink(intent)
    }

    /** 解析分享链接并自动加入：screenshare://join?code=XXXX */
    private fun handleShareLink(intent: Intent?) {
        val uri = intent?.data ?: return
        val code = uri.getQueryParameter("code")?.trim()
        if (!code.isNullOrEmpty() && Regex("^[0-9]{4}$").matches(code)) {
            enterMeeting(ACTION_JOIN, code)
        }
    }

    /** 校验并执行加入会议 */
    private fun tryJoin() {
        val code = binding.etMeetingCode.text.toString().trim()
        if (!Regex("^[0-9]{4}$").matches(code)) {
            Toast.makeText(this, "会议号为 4 位数字", Toast.LENGTH_SHORT).show()
            return
        }
        enterMeeting(ACTION_JOIN, code)
    }

    /** 生成 4 位数字会议号 */
    private fun generateMeetingCode(): String {
        val sb = StringBuilder()
        val random = java.security.SecureRandom()
        repeat(4) { sb.append(random.nextInt(10)) }
        return sb.toString()
    }

    /** 跳转会议室并退出连接页 */
    private fun enterMeeting(action: String, code: String) {
        val intent = Intent(this, MainActivity::class.java)
            .putExtra(EXTRA_MEETING_ACTION, action)
            .putExtra(EXTRA_MEETING_CODE, code)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        startActivity(intent)
        finish()
    }
}
