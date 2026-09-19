package com.screenshare

import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * FCM 推送令牌上报：登录后和应用启动时把本机令牌报给服务端，
 * 离线好友邀请才能推到通知栏。令牌可能轮换，重复上报覆盖旧值。
 */
object FcmRegistrar {

    private val scope = CoroutineScope(Dispatchers.IO)
    // SERVICE_NOT_AVAILABLE 等瞬时可恢复错误：退避重试，避免离线推送长期失效
    private const val MAX_RETRY = 3
    private val RETRY_DELAYS_MS = longArrayOf(5_000, 20_000, 60_000)

    /** 取令牌并上报；未登录时跳过（服务端会 401） */
    fun register(context: android.content.Context) {
        val token = SessionStore.getToken(context) ?: return
        fetchToken(token, 0)
    }

    private fun fetchToken(sessionToken: String, attempt: Int) {
        try {
            FirebaseMessaging.getInstance().token
                .addOnSuccessListener { fcmToken ->
                    if (fcmToken.isNotEmpty()) upload(sessionToken, fcmToken)
                }
                .addOnFailureListener { e ->
                    AppLogger.app("[FCM] 取令牌失败(第${attempt + 1}次)：${e.message}")
                    if (attempt < MAX_RETRY) {
                        scope.launch {
                            kotlinx.coroutines.delay(RETRY_DELAYS_MS[attempt])
                            fetchToken(sessionToken, attempt + 1)
                        }
                    }
                }
        } catch (e: Throwable) {
            // google-services.json 缺失等极端情况下 Firebase 未初始化，不能影响主流程
            AppLogger.app("[FCM] 初始化失败：${e.message}")
        }
    }

    /** 令牌轮换时（Service.onNewToken）直接上报 */
    fun upload(context: android.content.Context, fcmToken: String) {
        val token = SessionStore.getToken(context) ?: return
        upload(token, fcmToken)
    }

    private fun upload(sessionToken: String, fcmToken: String) {
        scope.launch {
            when (val r = AccountClient.setPushToken(sessionToken, fcmToken)) {
                is AccountClient.ApiResult.Success -> AppLogger.app("[FCM] 令牌已上报")
                is AccountClient.ApiResult.Failure -> AppLogger.app("[FCM] 令牌上报失败：${r.message}")
            }
        }
    }
}
