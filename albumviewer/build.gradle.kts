plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.screenshare.albumviewer"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.screenshare.albumviewer"
        minSdk = 24
        targetSdk = 34
        versionCode = 14
        versionName = "1.192"
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
            "\"${project.findProperty("screenshare.album.key") as String? ?: ""}\""
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
