package com.screenshare

import org.webrtc.EglBase

/**
 * 进程级 EGL 上下文单例：跨 Activity 复用同一 EGL context，仅进程结束自动回收。
 *
 * WebRTC 的 PeerConnectionFactory（WebRTCPeer.singletonFactory）是进程级单例，
 * 首次创建时绑定首次传入的 eglBaseContext。若该 context 随 Activity 销毁而 release，
 * 第二次会话复用工厂时会引用已释放的 EGL context 导致 native 崩溃
 * （v1.173 定位：同一进程内「第一次可以，第二次加入即闪退」）。
 * 因此 EGL context 必须由进程持有，Activity onDestroy 不释放。
 */
object AppEglBase {
    @Volatile
    private var eglBase: EglBase? = null

    private val lock = Any()

    fun context(): EglBase.Context {
        eglBase?.let { return it.eglBaseContext }
        synchronized(lock) {
            eglBase?.let { return it.eglBaseContext }
            val created = EglBase.create()
            eglBase = created
            return created.eglBaseContext
        }
    }
}
