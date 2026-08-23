plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.screenshare"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.screenshare"
        minSdk = 24
        targetSdk = 34
        versionCode = 221
        versionName = "1.218"
        // 只保留真机架构（arm64 + armeabi-v7a），砍掉模拟器专用 x86/x86_64，
        // APK 从 ~53MB 缩到 ~25MB，两端同时下载更快
        // 可用 -Pscreenshare.abifilter=arm64-v8a 覆盖为精简版（少 6.8MB，老 32 位机装不了）
        ndk {
            val abiOverride = project.findProperty("screenshare.abifilter") as String?
            abiFilters += if (abiOverride != null && abiOverride.isNotBlank()) {
                abiOverride.split(",")
            } else {
                listOf("arm64-v8a", "armeabi-v7a")
            }
        }
        // 从 gradle.properties 读取 TURN 配置，通过 BuildConfig 注入代码
        buildConfigField(
            "String",
            "TURN_URLS",
            "\"${(project.findProperty("screenshare.turn.urls") as String? ?: "")}\""
        )
        buildConfigField(
            "String",
            "TURN_USERNAME",
            "\"${project.findProperty("screenshare.turn.username") as String? ?: ""}\""
        )
        buildConfigField(
            "String",
            "TURN_PASSWORD",
            "\"${project.findProperty("screenshare.turn.password") as String? ?: ""}\""
        )
        buildConfigField(
            "String",
            "SIGNAL_URL",
            "\"${project.findProperty("screenshare.signal.url") as String? ?: ""}\""
        )
        buildConfigField(
            "String",
            "UPDATE_URL",
            "\"${project.findProperty("screenshare.update.url") as String? ?: ""}\""
        )
        buildConfigField(
            "String",
            "ALBUM_URL",
            "\"${project.findProperty("screenshare.album.url") as String? ?: ""}\""
        )
        buildConfigField(
            "String",
            "ALBUM_KEY",
            "\"${project.findProperty("screenshare.album.key") as String? ?: ""}\""
        )
        buildConfigField(
            "String",
            "RELAY_URL",
            "\"${project.findProperty("screenshare.relay.url") as String? ?: ""}\""
        )
        buildConfigField(
            "String",
            "DIAG_TOKEN",
            "\"${project.findProperty("screenshare.diag.token") as String? ?: ""}\""
        )
    }

    buildTypes {
        release {
            // R8 混淆彻底关闭（v1.213 开启 R8 导致进会议闪退）。
            // 根因：WebRTC 依赖大量 JNI 与运行时反射（PeerConnectionFactory、
            // @CalledByNative 回调），R8 的优化/内联/裁剪会破坏 native 层按名
            // 查找 Java 方法与 JNI 注册表，即使 keep 规则也难完全覆盖，崩溃发生在
            // native 层（Java 崩溃上报捕获不到）。关闭 R8 与 v1.133 及之前稳定版一致。
            // native .so 占体积大头，混淆 dex 收益低（APK 约 +4MB）。
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    // WebRTC —— 核心依赖
    implementation("io.github.webrtc-sdk:android:144.7559.09")

    // AndroidX
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")

    // material/lifecycle 的传递依赖，直接声明以固定已缓存版本
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // 信令 WebSocket / 崩溃上报直接使用
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}