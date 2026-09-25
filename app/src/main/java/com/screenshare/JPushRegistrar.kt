package com.screenshare

import android.content.Context
import cn.jpush.android.api.JPushInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 极光推送 registrationID 上报：登录后和应用启动时把本机标识报给服务端，
 * 离线好友邀请才能推到通知栏。标识可能轮换，重复上报覆盖旧值。
 */
object JPushRegistrar {

    private val scope = CoroutineScope(Dispatchers.IO)

    /** 取 registrationID 并上报；未登录时跳过（服务端会 401） */
    fun register(context: Context) {
        if (SessionStore.getToken(context) == null) return
        try {
            val rid = JPushInterface.getRegistrationID(context)
            if (rid.isNullOrEmpty()) {
                // SDK 尚未注册完成（首次安装网络慢）， Receiver 的 onRegister 会补报
                AppLogger.app("[JPush] registrationID 暂未拿到，等待 onRegister 回调")
                return
            }
            upload(context, rid)
        } catch (t: Throwable) {
            AppLogger.app("[JPush] 取 registrationID 失败：${t.message}")
        }
    }

    /** registrationID 轮换时（onRegister）直接上报 */
    fun upload(context: Context, registrationId: String) {
        val token = SessionStore.getToken(context) ?: return
        scope.launch {
            when (val r = AccountClient.setPushToken(token, registrationId)) {
                is AccountClient.ApiResult.Success -> AppLogger.app("[JPush] 标识已上报")
                is AccountClient.ApiResult.Failure -> AppLogger.app("[JPush] 标识上报失败：${r.message}")
            }
        }
    }
}
