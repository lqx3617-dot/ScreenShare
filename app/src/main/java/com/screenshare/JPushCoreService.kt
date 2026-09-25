package com.screenshare

import cn.jpush.android.service.JCommonService

/**
 * 极光推送核心 Service：保持与极光云的长连接。
 * 官方要求自定义一个继承 JCommonService 的 Service，放在独立进程 :pushcore，
 * 在更多手机平台上推送通道保持得更稳定。
 */
class JPushCoreService : JCommonService()
