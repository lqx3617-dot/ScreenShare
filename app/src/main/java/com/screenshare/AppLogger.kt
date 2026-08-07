package com.screenshare

import android.util.Log

/**
 * V3.1: 统一日志工具——按模块打 tag，便于真机排查时按 WEBRTC/NETWORK/CAPTURE 过滤。
 *
 * 排查示例：
 *   WEBRTC: connected / ICE restart offer sent
 *   NETWORK: loss=2% rtt=80ms
 *   CAPTURE: fps=30 1920x1080
 */
object AppLogger {
    private const val TAG_WEBRTC = "WEBRTC"
    private const val TAG_NETWORK = "NETWORK"
    private const val TAG_CAPTURE = "CAPTURE"

    fun webrtc(msg: String) {
        Log.d(TAG_WEBRTC, msg)
    }

    fun network(msg: String) {
        Log.d(TAG_NETWORK, msg)
    }

    fun capture(msg: String) {
        Log.d(TAG_CAPTURE, msg)
    }
}
