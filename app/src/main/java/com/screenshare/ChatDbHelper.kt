package com.screenshare

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * 聊天本地库：仅缓存与去重，不承担业务规则。
 *
 * 消息表与服务器 messages 表对齐：
 *  - id：客户端生成的 clientMsgId，与服务端去重键一致（幂等重发安全）
 *  - seq：服务端分配的同步游标，0 表示尚未收到 ack（发送中）
 *  - status：0 发送中 / 1 已发送 / 2 失败，仅对自己发的消息有意义
 *  - read：接收方已读标记。语义=「收到该消息的人已读」：
 *    对方发给我的消息，read=1 表示我已读；我发给对方的，read=1 表示对方已读
 *
 * 会话归一：(a,b) 与 (b,a) 属同一会话，查询用双向 OR。
 * 本地只追加不清理，翻页靠 seq 游标。
 */
class ChatDbHelper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    companion object {
        private const val DB_NAME = "chat.db"
        private const val DB_VERSION = 1
        private const val T = "messages"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $T (
                id        TEXT PRIMARY KEY,
                seq       INTEGER NOT NULL DEFAULT 0,
                from_user TEXT NOT NULL,
                to_user   TEXT NOT NULL,
                text      TEXT NOT NULL,
                ts        INTEGER NOT NULL,
                status    INTEGER NOT NULL DEFAULT 1,
                read      INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_messages_conv ON $T(from_user, to_user)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_messages_seq ON $T(seq)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1.414: 逐版本 ALTER 迁移，禁止 DROP TABLE 重建——本地聊天记录是用户数据，
        // 直接重建会全部丢失。新增字段/表在下方按版本追加；仅在完全不兼容时才允许
        // 先导出再重建。当前仅 v1，循环体为空。
        var v = oldVersion
        while (v < newVersion) {
            when (v) {
                // 1 -> 2: db.execSQL("ALTER TABLE messages ADD COLUMN xxx TEXT DEFAULT ''")
            }
            v++
        }
    }

    /** status=0（发送中）的消息：进程重启后恢复补发队列（仅我发出的） */
    fun pendingOf(me: String): List<ChatMessage> {
        return readableDatabase
            .rawQuery("SELECT * FROM $T WHERE status = 0 AND from_user = ?", arrayOf(me))
            .use { c ->
                val list = ArrayList<ChatMessage>(c.count)
                while (c.moveToNext()) list.add(rowOf(c, me))
                list
            }
    }

    /** 插入或按 id 更新（收到 ack / 多设备同步时 seq/status/read 可能变化） */
    fun upsert(msg: ChatMessage) {
        val cv = ContentValues().apply {
            put("id", msg.id)
            put("seq", msg.seq)
            put("from_user", msg.fromUser)
            put("to_user", msg.toUser)
            put("text", msg.text)
            put("ts", msg.ts)
            put("status", msg.status)
            put("read", if (msg.read) 1 else 0)
        }
        writableDatabase.insertWithOnConflict(T, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** 仅更新状态列，避免覆盖已有 seq（ack 到达时 seq 可能仍是 0 的早期写入） */
    fun updateStatus(id: String, status: Int, seq: Long = 0) {
        val cv = ContentValues().apply {
            put("status", status)
            if (seq > 0) put("seq", seq)
        }
        writableDatabase.update(T, cv, "id = ?", arrayOf(id))
    }

    /** 该 clientMsgId 是否已存在（去重判断） */
    fun exists(id: String): Boolean {
        return readableDatabase.rawQuery("SELECT 1 FROM $T WHERE id = ? LIMIT 1", arrayOf(id)).use { it.moveToFirst() }
    }

    /** 标记对方已读我的消息（chat-read 到达时） */
    fun markReadByPeer(me: String, peer: String) {
        val cv = ContentValues().apply { put("read", 1) }
        writableDatabase.update(T, cv, "from_user = ? AND to_user = ?", arrayOf(me, peer))
    }

    /** 标记我已读对方消息（进入聊天页清零未读） */
    fun markReadByMe(me: String, peer: String) {
        val cv = ContentValues().apply { put("read", 1) }
        writableDatabase.update(T, cv, "to_user = ? AND from_user = ?", arrayOf(me, peer))
    }

    /**
     * 会话消息（升序）。limit 为条数上限，beforeSeq>0 时只取比它更早的（翻页）。
     *
     * v1.414: 首屏查询含 seq=0 的本地消息（断网时发送中、未收到 ack）。
     * 否则进程被杀后这些消息虽在库中却在聊天页不可见（且补发成功前 seq 一直是 0）。
     * 排序：已确认消息按 seq 倒序在前，seq=0 的本地消息置后（它们是最新发出的）。
     */
    fun conversation(me: String, peer: String, limit: Int, beforeSeq: Long = 0): List<ChatMessage> {
        val sql = if (beforeSeq > 0) {
            "SELECT * FROM $T WHERE ((from_user=? AND to_user=?) OR (from_user=? AND to_user=?)) AND seq < ? AND seq > 0 ORDER BY seq DESC LIMIT ?"
        } else {
            "SELECT * FROM $T WHERE ((from_user=? AND to_user=?) OR (from_user=? AND to_user=?)) AND (seq > 0 OR status = 0) ORDER BY (seq = 0), seq DESC, ts DESC LIMIT ?"
        }
        val args = if (beforeSeq > 0) arrayOf(me, peer, peer, me, beforeSeq.toString(), limit.toString())
        else arrayOf(me, peer, peer, me, limit.toString())
        // 倒序取最新一屏，再翻回升序展示
        return readableDatabase.rawQuery(sql, args).use { c ->
            val list = ArrayList<ChatMessage>(c.count)
            while (c.moveToNext()) list.add(rowOf(c, me))
            list.reversed()
        }
    }

    /** 本地最大 seq（增量拉新游标）；无消息返回 0 */
    fun maxSeq(me: String, peer: String): Long {
        val sql = "SELECT MAX(seq) m FROM $T WHERE ((from_user=? AND to_user=?) OR (from_user=? AND to_user=?))"
        return readableDatabase.rawQuery(sql, arrayOf(me, peer, peer, me)).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L
        }
    }

    /** 本地最小 seq（翻页游标）；仅统计已分配 seq 的消息 */
    fun minSeq(me: String, peer: String): Long {
        val sql = "SELECT MIN(seq) m FROM $T WHERE ((from_user=? AND to_user=?) OR (from_user=? AND to_user=?)) AND seq > 0"
        return readableDatabase.rawQuery(sql, arrayOf(me, peer, peer, me)).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L
        }
    }

    /** 各会话未读数：对方发给我且我未读。返回 peerId -> count */
    fun unreadCounts(me: String): Map<String, Int> {
        val sql = "SELECT from_user, COUNT(*) c FROM $T WHERE to_user = ? AND read = 0 GROUP BY from_user"
        return readableDatabase.rawQuery(sql, arrayOf(me)).use { c ->
            val map = HashMap<String, Int>()
            while (c.moveToNext()) map[c.getString(0)] = c.getInt(1)
            map
        }
    }

    private fun rowOf(c: android.database.Cursor, me: String): ChatMessage {
        val fromUser = c.getString(c.getColumnIndexOrThrow("from_user"))
        return ChatMessage(
            id = c.getString(c.getColumnIndexOrThrow("id")),
            seq = c.getLong(c.getColumnIndexOrThrow("seq")),
            fromUser = fromUser,
            toUser = c.getString(c.getColumnIndexOrThrow("to_user")),
            text = c.getString(c.getColumnIndexOrThrow("text")),
            ts = c.getLong(c.getColumnIndexOrThrow("ts")),
            mine = fromUser == me,
            status = c.getInt(c.getColumnIndexOrThrow("status")),
            read = c.getInt(c.getColumnIndexOrThrow("read")) == 1
        )
    }
}
