package com.screenshare

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.text.InputFilter
import android.text.InputType
import android.widget.LinearLayout
import android.widget.Toast

/**
 * 情侣共享房间：弹出房间号编辑框（预填上次记住的房间号），确认后以伴侣为邀请对象
 * 跳转 MainActivity 建情侣房（couple 标记）。服务端在邀请/加入环节校验情侣关系。
 */
object CoupleShareStarter {
    private const val PREFS = "couple_room"
    private const val KEY_CODE = "code"
    private const val CODE_REGEX = "^\\d{6}$"

    fun start(context: Context, partnerId: String, partnerName: String) {
        val saved = savedCode(context)
        val et = android.widget.EditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "输入 6 位数字房间号"
            text = android.text.Editable.Factory.getInstance().newEditable(saved)
            setSelection(text.length)
            filters = arrayOf(InputFilter.LengthFilter(6))
        }
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 30, 50, 10)
            addView(et)
        }
        val dialog = AlertDialog.Builder(context)
            .setTitle("共享房间")
            .setMessage("设置你们的共享房间号，对方可凭此号直接加入${if (saved.isNotEmpty()) "\n上次使用：$saved" else ""}")
            .setView(container)
            .setPositiveButton("开始共享", null)
            .setNegativeButton("取消", null)
            .create()
        // 自行接管确定按钮：格式不合法时保留弹窗与输入，便于直接改号重试
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val code = et.text?.toString()?.trim().orEmpty()
                if (!code.matches(Regex(CODE_REGEX))) {
                    Toast.makeText(context, "请输入 6 位数字房间号", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                launch(context, code, partnerId, partnerName)
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun launch(context: Context, code: String, partnerId: String, partnerName: String) {
        AppLogger.app("[COUPLE] 发起共享房间 -> $partnerName room=$code")
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(MeetingActivity.EXTRA_MEETING_ACTION, MeetingActivity.ACTION_CREATE)
            .putExtra(MeetingActivity.EXTRA_MEETING_CODE, code)
            .putExtra(MainActivity.EXTRA_INVITE_FRIEND_ID, partnerId)
            .putExtra(MainActivity.EXTRA_INVITE_FRIEND_NAME, partnerName)
            .putExtra(MainActivity.EXTRA_COUPLE_ROOM, true)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        context.startActivity(intent)
    }

    fun savedCode(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_CODE, "").orEmpty()

    fun saveCode(context: Context, code: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_CODE, code).apply()
    }
}
