import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 与主 App 相同的机密注入：优先 local.properties（git 忽略）→ 环境变量 → gradle.properties
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
    namespace = "com.screenshare.albumviewer"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.screenshare.albumviewer"
        minSdk = 24
        targetSdk = 34
        versionCode = 20
        versionName = "1.198"
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
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
            "PUBLISH_URL",
            "\"${project.findProperty("screenshare.download.url") as String? ?: ""}\""
        )
        // 发版管理接口鉴权 token（与 download-server 的 PUBLISH_TOKEN 对应），
        // 机密只存在 local.properties/环境变量，不进 git；未配置时请求带空 token 由服务端 403 拒绝
        buildConfigField(
            "String",
            "PUBLISH_TOKEN",
            "\"${secret("screenshare.publish.token")}\""
        )
        buildConfigField(
            "String",
            "UPDATE_URL",
            "\"${project.findProperty("screenshare.download.url") as String? ?: ""}/albumviewer-version.json\""
        )
    }

    buildTypes {
        release {
            // R8 混淆（密钥等静态字符串不再明文可反编译直读）
            isMinifyEnabled = true
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil:2.5.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
