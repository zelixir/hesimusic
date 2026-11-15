plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// P0 探针(临时模块,不进正式 APK)。用途:用 PCM 取证判定环回机制能否真无缝。
android {
    namespace = "com.zjr.spike"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.zjr.spike"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
        freeCompilerArgs += listOf(
            "-opt-in=androidx.media3.common.util.UnstableApi"
        )
    }
    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
}
