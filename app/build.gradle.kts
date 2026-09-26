import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 从 git 忽略的 local.properties 读取私密配置，支持环境变量覆盖；
// 公开地址仍放在 gradle.properties，机密只存在本机。
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun secret(key: String): String {
    val envKey = key.replace(".", "_").uppercase()
    return localProps.getProperty(key)
        ?: System.getenv(envKey)
        ?: (project.findProperty(key) as String?)
        ?: ""
}

android {
    namespace = "com.screenshare"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.screenshare"
        minSdk = 24
        targetSdk = 34
versionCode = 384
versionName = "1.379"
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
            "\"${secret("screenshare.turn.password")}\""
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
            "\"${secret("screenshare.album.key")}\""
        )
        buildConfigField(
            "String",
            "RELAY_URL",
            "\"${project.findProperty("screenshare.relay.url") as String? ?: ""}\""
        )
        buildConfigField(
            "String",
            "DIAG_TOKEN",
            "\"${secret("screenshare.diag.token")}\""
        )
        // 极光推送：AppKey 从 local.properties/环境变量读，不进仓库
        manifestPlaceholders["JPUSH_PKGNAME"] = applicationId ?: "com.screenshare"
        manifestPlaceholders["JPUSH_APPKEY"] = secret("jpush.appkey")
        manifestPlaceholders["JPUSH_CHANNEL"] = "developer-default"
    }

    buildTypes {
        release {
            // R8/minify 全面禁用（结论，非临时关闭）。
            // v1.324~1.327 四轮尝试全部失败：
            //   v1.324 规则文件未加载（缺 proguardFiles），ActivityResult 契约被改名 → 崩
            //   v1.325 加载规则 + keep ActivityResult 全层级，contract 恢复原名仍崩
            //   v1.326 keep 所有 WebRTC 回调实现类，create 房间 native 崩溃依旧
            //   v1.327 -dontobfuscate 零类名改写（mapping 已验证），两种崩溃照旧
            // 根因不在"改名"，而在 minify 的 shrink 裁剪 + R8 desugaring 破坏
            // ActivityResultRegistry 恢复链路与 WebRTC JNI 注册表，keep 无法穷尽。
            // 防破解改走 APK 加固方案（第三方加固服务），不依赖 R8 混淆。
            // proguard-rules.pro 与 proguardFiles 配置保留，供将来重新评估使用。
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android.txt"),
                "proguard-rules.pro"
            )
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
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // 信令 WebSocket / 崩溃上报直接使用
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // 账号系统：加密存储登录令牌
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // 好友二维码：ZXing 编解码 + CameraX 取景
    implementation("com.google.zxing:core:3.5.3")
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")

    // 离线邀请推送：极光推送（5.0.0 起自动拉取 JCore，无需单独配置）
    implementation("cn.jiguang.sdk:jpush:5.6.0")
}