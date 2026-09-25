package com.screenshare

import android.content.Context
import android.content.Intent
import java.security.SecureRandom

/**
 * 向好友发起共享：创建房间并把邀请目标随 Intent 交给 MainActivity，
 * 服务端据此校验邀请方确实是房间 host（防止把好友导向别人的房间）。
 * 离线好友也允许发起：邀请暂存服务端，对方上线后补投。
 */
object FriendShareStarter {

    fun start(context: Context, friend: AccountClient.FriendItem) {
        val code = generateCode()
        AppLogger.app("[FRIENDS] 发起共享 -> ${friend.nickname} room=$code")
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(MeetingActivity.EXTRA_MEETING_ACTION, MeetingActivity.ACTION_CREATE)
            .putExtra(MeetingActivity.EXTRA_MEETING_CODE, code)
            .putExtra(MainActivity.EXTRA_INVITE_FRIEND_ID, friend.userId)
            .putExtra(MainActivity.EXTRA_INVITE_FRIEND_NAME, friend.nickname.ifBlank { friend.userId })
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        context.startActivity(intent)
    }

    private fun generateCode(): String {
        val sb = StringBuilder()
        val random = SecureRandom()
        repeat(4) { sb.append(random.nextInt(10)) }
        return sb.toString()
    }
}
