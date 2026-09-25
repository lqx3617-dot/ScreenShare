package com.screenshare

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * 登录会话本地存储：令牌与资料写入 EncryptedSharedPreferences，不以明文落盘。
 * 令牌是服务端签发的随机串，丢失需重新登录。
 */
object SessionStore {

    private const val FILE_NAME = "account"
    private const val K_TOKEN = "token"
    private const val K_USER_ID = "userId"
    private const val K_NICKNAME = "nickname"
    private const val K_AVATAR = "avatar"
    private const val K_FRIEND_CODE = "friendCode"

    @Volatile
    private var cached: SharedPreferences? = null

    private fun prefs(context: Context): SharedPreferences {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val masterKey = MasterKey.Builder(context.applicationContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            val created = EncryptedSharedPreferences.create(
                context.applicationContext,
                FILE_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            cached = created
            return created
        }
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
