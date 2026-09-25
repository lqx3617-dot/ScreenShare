package com.screenshare

import android.content.Context
import java.io.File

/**
 * 情侣相册本地缓存：上传成功的照片在本地存一份，相册页优先读本地（离线可看），
 * 解绑时整体清理。文件名与服务端 URL 末段一致（<mediaId>.<ext>）。
 */
object CoupleMediaCache {

    fun dir(ctx: Context): File = File(ctx.filesDir, "couple_photos").apply { if (!exists()) mkdirs() }

    /** 本地缓存文件（若存在） */
    fun file(ctx: Context, name: String): File = File(dir(ctx), name)

    /** 保存照片字节 */
    fun save(ctx: Context, name: String, bytes: ByteArray) {
        try {
            file(ctx, name).writeBytes(bytes)
        } catch (t: Throwable) {
            AppLogger.app("[couple] 本地缓存写入失败: ${t.message}")
        }
    }

    /** 解绑时整体清理：本地缓存不保留过期关系的内容 */
    fun clear(ctx: Context) {
        try {
            dir(ctx).deleteRecursively()
        } catch (t: Throwable) {
            AppLogger.app("[couple] 本地缓存清理失败: ${t.message}")
        }
    }
}
