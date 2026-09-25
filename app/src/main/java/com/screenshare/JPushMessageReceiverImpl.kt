package com.screenshare

import cn.jpush.android.service.JPushMessageReceiver
import com.screenshare.AppLogger
import com.screenshare.JPushRegistrar

/**
 * 极光推送消息回调：registrationID 变化、通知到达/点击 等。
 *
 * registrationID 是极光维度的设备标识，服务端按它推送。标识可能轮换
 * （应用升级、清数据、极光侧调整），每次 onRegister 都重新上报，覆盖旧值。
 *
 * 注意：该类基于 BroadcastReceiver 但 SDK 内部走进程内对象回调，
 * 不产生组件生命周期，不应在此声明 Handler。
 */
class JPushMessageReceiverImpl : JPushMessageReceiver() {

    override fun onRegister(context: android.content.Context, registrationId: String) {
        super.onRegister(context, registrationId)
        AppLogger.app("[JPush] onRegister registrationId=${registrationId.take(16)}…")
        JPushRegistrar.upload(context, registrationId)
    }
}
