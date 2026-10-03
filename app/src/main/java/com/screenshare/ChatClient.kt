package com.screenshare

import org.json.JSONArray
import org.json.JSONObject

/**
 * 聊天消息模型（本地库 / UI / 同步层共用）。
 * @param seq 服务端同步游标，0 表示尚未收到 ack（发送中）
 * @param status 0 发送中 / 1 已发送 / 2 失败，仅对本人发送的消息有意义
 * @param read 「收到该消息的人已读」：对方发给我的=我已读；我发的=对方已读
 */
data class ChatMessage(
    val id: String,
    val seq: Long,
    val fromUser: String,
    val toUser: String,
    val text: String,
    val ts: Long,
    val mine: Boolean,
    val status: Int,
    val read: Boolean
)

/**
 * 聊天 REST 客户端：历史分页 / 标记已读 / 未读汇总。
 * 复用 AccountClient 的 okhttp 栈与 ApiResult，base URL 同源。
 */
object ChatClient {

    data class HistoryResult(val messages: List<ChatMessage>, val hasMore: Boolean)

    /** 会话历史：afterSeq>0 增量拉新，beforeSeq>0 翻页拉旧，两者皆空取最新一屏 */
    suspend fun history(
        token: String,
        me: String,
        peer: String,
        afterSeq: Long = 0,
        beforeSeq: Long = 0,
        limit: Int = 50
    ): AccountClient.ApiResult<HistoryResult> {
        val sb = StringBuilder("/chat/history?peer=")
            .append(java.net.URLEncoder.encode(peer, "UTF-8"))
        if (afterSeq > 0) sb.append("&afterSeq=").append(afterSeq)
        if (beforeSeq > 0) sb.append("&beforeSeq=").append(beforeSeq)
        sb.append("&limit=").append(limit)

        return when (val r = AccountClient.callRaw("GET", sb.toString(), null, token)) {
            is AccountClient.ApiResult.Success -> {
                val o = if (r.data.isNotEmpty()) JSONObject(r.data) else JSONObject()
                val arr = o.optJSONArray("messages") ?: JSONArray()
                val list = ArrayList<ChatMessage>(arr.length())
                for (i in 0 until arr.length()) {
                    val m = arr.getJSONObject(i)
                    val from = m.optString("from")
                    list.add(
                        ChatMessage(
                            id = m.optString("id"),
                            seq = m.optLong("seq"),
                            fromUser = from,
                            toUser = m.optString("to"),
                            text = m.optString("text"),
                            ts = m.optLong("ts"),
                            mine = from == me,
                            status = 1, // 服务端历史必已入库
                            read = m.optBoolean("read")
                        )
                    )
                }
                AccountClient.ApiResult.Success(HistoryResult(list, o.optBoolean("hasMore")))
            }
            is AccountClient.ApiResult.Failure -> r
        }
    }

    /** 标记某会话全部已读（进入聊天页清零未读） */
    suspend fun markRead(token: String, peer: String): AccountClient.ApiResult<JSONObject> {
        val body = JSONObject().put("peer", peer)
        return AccountClient.call("POST", "/chat/read", body, token)
    }

    /** 未读汇总：peerId -> count */
    suspend fun unread(token: String): AccountClient.ApiResult<Map<String, Int>> {
        return when (val r = AccountClient.call("GET", "/chat/unread", null, token)) {
            is AccountClient.ApiResult.Success -> {
                val o = r.data.optJSONObject("unread") ?: JSONObject()
                val map = HashMap<String, Int>()
                val keys = o.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    map[k] = o.optInt(k)
                }
                AccountClient.ApiResult.Success(map)
            }
            is AccountClient.ApiResult.Failure -> r
        }
    }
}
