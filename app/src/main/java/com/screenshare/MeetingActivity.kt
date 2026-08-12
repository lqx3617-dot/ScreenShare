package com.screenshare

import android.content.Intent
import android.os.Bundle
import android.view.Window
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.screenshare.databinding.ActivityMeetingBinding

/**
 * 会议连接页（前端入口）：
 * App 启动后的第一个界面，负责「创建房间」或「加入会议」两个动作。
 * 用户在此输入会议号 / 创建房间后跳转到 MainActivity（会议室），本页随即退出。
 *
 * 操作流程：
 * - 创建房间：生成 4 位会议号 → 跳转 MainActivity(action=create)
 * - 加入会议：输入 4 位会议号 → 校验 → 跳转 MainActivity(action=join)
 * - 分享链接：冷启动 screenshare://join?code=XXXX → 直接跳转加入流程
 */
class MeetingActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MEETING_ACTION = "extra_meeting_action"
        const val EXTRA_MEETING_CODE = "extra_meeting_code"
        const val ACTION_CREATE = "create"
        const val ACTION_JOIN = "join"
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
        binding.btnJoinMeeting.setOnClickListener { tryJoin() }
        binding.tvCheckUpdate.setOnClickListener { UpdateChecker.check(this, manual = true) }

        // 分享链接唤起：冷启动解析 screenshare://join?code=XXXX
        handleShareLink(intent)
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
