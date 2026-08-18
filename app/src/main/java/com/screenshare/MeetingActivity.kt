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
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.screenshare.databinding.ActivityMeetingBinding
import org.json.JSONArray
import org.json.JSONObject

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

        // 分享链接唤起：冷启动解析 screenshare://join?code=XXXX
        handleShareLink(intent)
        // 会议未结束：上次会话未主动结束/未失败退出，点开 App 自动重连
        autoResumeMeeting()
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
        // 底部「检查更新」避开系统导航栏（Android 10 及以下内容延伸到系统栏会被遮挡点不到）
        val density = resources.displayMetrics.density
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            if (nav.bottom > 0) {
                val lp = binding.tvCheckUpdate.layoutParams as? LinearLayout.LayoutParams
                lp?.bottomMargin = (28 * density).toInt() + nav.bottom
                binding.tvCheckUpdate.layoutParams = lp
            }
            insets
        }
    }

    override fun onResume() {
        super.onResume()
        // 从会议室返回连接页时刷新（历史可能已变化）
        renderRecentMeetings()
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
            setTextColor(Color.parseColor("#00E5FF"))
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

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()

    /**
     * 自动重连上次未结束的会议：
     * 若存在持久化的会议记录（action+code，24h 内），直接进入该会议。
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
        Toast.makeText(this, "自动连接上次会议（$code）...", Toast.LENGTH_SHORT).show()
        enterMeeting(action, code)
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
