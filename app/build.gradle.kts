plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.ksp)
}

val releaseVersionCode = providers.gradleProperty("releaseVersionCode").orNull?.toIntOrNull() ?: 1
val releaseVersionName = providers.gradleProperty("releaseVersionName").orNull ?: "0.1"
val releaseStoreFilePath = providers.environmentVariable("HESI_RELEASE_STORE_FILE").orNull
val releaseStorePassword = providers.environmentVariable("HESI_RELEASE_STORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("HESI_RELEASE_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("HESI_RELEASE_KEY_PASSWORD").orNull
val hasReleaseSigningCredentials = listOf(
    releaseStoreFilePath,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword
).all { it != null }

if (hasReleaseSigningCredentials) {
    require(file(releaseStoreFilePath!!).exists()) {
        "HESI_RELEASE_STORE_FILE points to a missing keystore file: $releaseStoreFilePath"
    }
}

android {
    namespace = "com.zjr.hesimusic"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.zjr.hesimusic"
        minSdk = 31
        targetSdk = 36
        versionCode = releaseVersionCode
        versionName = releaseVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        
        externalNativeBuild {
            cmake {
                cppFlags("")
                arguments("-DANDROID_STL=c++_shared", "-DANDROID_LINKER_FLAGS=-Wl,-z,max-page-size=16384")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    signingConfigs {
        if (hasReleaseSigningCredentials) {
            create("release") {
                storeFile = file(releaseStoreFilePath!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (hasReleaseSigningCredentials) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Media3
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.ui)

    // Coil
    implementation(libs.coil.compose)

    // Accompanist
    implementation(libs.accompanist.permissions)

    // Audio & Encoding
    implementation(libs.juniversalchardet)
    implementation(libs.pinyin4j)
}
