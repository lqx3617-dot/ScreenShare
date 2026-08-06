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
        versionCode = 85
        versionName = "1.84"
        // 只保留真机架构（arm64 + armeabi-v7a），砍掉模拟器专用 x86/x86_64，
        // APK 从 ~53MB 缩到 ~25MB，两端同时下载更快
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
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
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // OkHttp —— 口令模式 WebSocket 信令
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // 单元测试（CoordinateMapper 纯函数）
    testImplementation("junit:junit:4.13.2")
}