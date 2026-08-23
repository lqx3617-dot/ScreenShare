package com.screenshare.albumviewer

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * 全局共享 OkHttpClient 单例，复用连接池和线程池。
 * 自动附加相册访问密钥 header（x-album-key），避免各组件重复创建实例。
 */
object HttpClientProvider {
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val orig = chain.request()
                val req = if (BuildConfig.ALBUM_KEY.isNotEmpty()) {
                    orig.newBuilder().header("x-album-key", BuildConfig.ALBUM_KEY).build()
                } else orig
                chain.proceed(req)
            }
            .build()
    }
}