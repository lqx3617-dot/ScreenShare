package com.screenshare

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * 登录会话本地存储：令牌与资料写入 EncryptedSharedPreferences，不以明文落盘。
 * 令牌是服务端签发的随机串，丢失需重新登录。
 */
object SessionStore {

    private const val TAG = "SessionStore"
    private const val FILE_NAME = "account"
    private const val K_TOKEN = "token"
    private const val K_USER_ID = "userId"
    private const val K_NICKNAME = "nickname"
    private const val K_AVATAR = "avatar"
    private const val K_FRIEND_CODE = "friendCode"

    /** MasterKey.Builder 未指定 alias 时的默认别名 */
    private const val DEFAULT_MASTER_KEY_ALIAS = "_androidx_security_master_key_"

    @Volatile
    private var cached: SharedPreferences? = null

    private fun prefs(context: Context): SharedPreferences {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val appCtx = context.applicationContext
            // EncryptedSharedPreferences.create 会读取 account.xml 里的加密 keyset，
            // 当 Keystore 主密钥被轮换/失效（备份还原、系统锁屏变更等）时 keyset 解不开，
            // 抛 AEADBadTagException 且每次读取都复现——调用方（如启动时的 isLoggedIn）
            // 会被拖崩，应用直接进不去。这里降级为删除损坏存储并重建 keyset：
            // 代价是本地会话丢失需重新登录，但远好于整应用无法打开。
            val created = try {
                openEncrypted(appCtx)
            } catch (t: Throwable) {
                Log.w(TAG, "加密存储不可解密，重置本地会话存储: ${t.message}")
                runCatching { appCtx.deleteSharedPreferences(FILE_NAME) }
                try {
                    openEncrypted(appCtx)
                } catch (t2: Throwable) {
                    // 删除存储文件仍失败：主密钥本身已损坏，连 Keystore 条目一并重置
                    // （该别名仅本应用使用，删除无副作用）
                    Log.w(TAG, "重置存储后仍失败，重置主密钥: ${t2.message}")
                    runCatching {
                        java.security.KeyStore.getInstance("AndroidKeyStore")
                            .apply { load(null) }
                            .deleteEntry(DEFAULT_MASTER_KEY_ALIAS)
                    }
                    openEncrypted(appCtx)
                }
            }
            cached = created
            return created
        }
    }

    private fun openEncrypted(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    data class Profile(
        val userId: String,
        val nickname: String,
        val avatar: String,
        val friendCode: String
    )

    /** 保存登录会话（注册/登录成功后调用） */
    fun saveSession(
        context: Context,
        token: String,
        userId: String,
        nickname: String,
        avatar: String,
        friendCode: String
    ) {
        prefs(context).edit()
            .putString(K_TOKEN, token)
            .putString(K_USER_ID, userId)
            .putString(K_NICKNAME, nickname)
            .putString(K_AVATAR, avatar)
            .putString(K_FRIEND_CODE, friendCode)
            .apply()
    }

    fun getToken(context: Context): String? = prefs(context).getString(K_TOKEN, null)

    fun isLoggedIn(context: Context): Boolean = !getToken(context).isNullOrBlank()

    fun getProfile(context: Context): Profile? {
        val p = prefs(context)
        val userId = p.getString(K_USER_ID, null) ?: return null
        return Profile(
            userId = userId,
            nickname = p.getString(K_NICKNAME, "") ?: "",
            avatar = p.getString(K_AVATAR, "0") ?: "0",
            friendCode = p.getString(K_FRIEND_CODE, "") ?: ""
        )
    }

    /** 更新本地缓存的昵称/头像/好友码（服务端改完后同步本地） */
    fun updateProfile(context: Context, nickname: String?, avatar: String?, friendCode: String? = null) {
        val e = prefs(context).edit()
        if (nickname != null) e.putString(K_NICKNAME, nickname)
        if (avatar != null) e.putString(K_AVATAR, avatar)
        if (friendCode != null) e.putString(K_FRIEND_CODE, friendCode)
        e.apply()
    }

    /** 清除会话（登出/令牌失效时调用） */
    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }
}
