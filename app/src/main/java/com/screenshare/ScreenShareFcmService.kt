package com.screenshare

import com.google.firebase.messaging.FirebaseMessagingService

/**
 * FCM 令牌轮换回调：应用在后台时令牌也可能刷新，及时上报避免离线推送失效。
 */
class ScreenShareFcmService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        AppLogger.app("[FCM] 令牌轮换")
        FcmRegistrar.upload(this, token)
    }
}
