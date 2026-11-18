plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.ksp)
}

val releaseVersionCode = providers.gradleProperty("releaseVersionCode").orNull?.toIntOrNull() ?: 1
val releaseVersionName = providers.gradleProperty("releaseVersionName").orNull ?: "0.1"
// 开发/测试构建独立包名:-PdevPackage=.dev → applicationId 追加后缀、应用名加 Dev 后缀,
// 与日常安装隔离(不影响 release 包名与名称)
val devPackageSuffix = providers.gradleProperty("devPackage").orNull
val devAppLabel = if (devPackageSuffix != null) "海瑟音乐 Dev" else null
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
        if (devPackageSuffix != null) {
            applicationIdSuffix = devPackageSuffix
        }
        manifestPlaceholders["appLabel"] = devAppLabel ?: "@string/app_name"
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

    // 内置 demo 变体(独立包名/版本名/应用名,可与正式包并存):
    // - prod:正式包,与加入 flavor 前行为一致(无任何后缀);
    // - demo:只编译 app/src/demo 源集,自带 assets/demo(内置歌曲 + 预置循环结果),
    //   冷启动由 DemoBootstrap 写入 filesDir/数据库后直达「无缝循环」结果页。
    // 加入 flavor 后 AGP 的变体名变为 prodDebug/prodRelease 与 demoDebug/demoRelease;
    // assembleDebug/assembleRelease 仍是 build-type 聚合任务(会构建全部 flavor),
    // prod 产物由文末的兼容任务复制回旧 APK 路径。
    flavorDimensions += "channel"
    productFlavors {
        create("prod") {
            dimension = "channel"
        }
        create("demo") {
            dimension = "channel"
            applicationIdSuffix = ".demo"
            versionNameSuffix = "-demo"
            manifestPlaceholders["appLabel"] = "HesiMusic Demo"
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

// ---------------------------------------------------------------------------
// 构建兼容层(仅因新增 flavor 而存在;所有逻辑与 prod/demo 变体行为无关)
//
// 加入 flavor 后 AGP 仍生成 build-type 聚合任务(assembleDebug/assembleRelease 等),
// docs、tools/loop-e2e.ps1 与 .github/workflows/release.yml 的旧任务名继续可用;
// 但 APK 输出目录变成 outputs/apk/prod/<buildType>、outputs/apk/demo/<buildType>。
// 因此 prod 变体构建完后把 APK 复制回旧输出目录与旧文件名,工装按固定路径找包不受影响
// (assembleDebug 会构建两个 flavor,旧路径里的文件始终取 prod 产物)。
// demo 变体不受影响(仍由 :app:assembleDemoDebug 构建)。
// ---------------------------------------------------------------------------
val copyProdDebugApkToLegacyDir = tasks.register<Copy>("copyProdDebugApkToLegacyDir") {
    from(layout.buildDirectory.dir("outputs/apk/prod/debug")) {
        include("*.apk")
    }
    into(layout.buildDirectory.dir("outputs/apk/debug"))
    rename { "app-debug.apk" }
}

val copyProdReleaseApkToLegacyDir = tasks.register<Copy>("copyProdReleaseApkToLegacyDir") {
    from(layout.buildDirectory.dir("outputs/apk/prod/release")) {
        include("*.apk")
    }
    into(layout.buildDirectory.dir("outputs/apk/release"))
    rename { "app-release.apk" }
}

tasks.matching { it.name == "assembleProdDebug" }.configureEach {
    finalizedBy(copyProdDebugApkToLegacyDir)
}
tasks.matching { it.name == "assembleProdRelease" }.configureEach {
    finalizedBy(copyProdReleaseApkToLegacyDir)
}

// docs 里仍在用的单变体任务名(flavor 化后只剩 prodDebug/demoDebug 版本)。
// demo 源集不带额外单测,因此 testDebugUnitTest 等价于 prodDebug 单测(保持 1134 基线)。
tasks.register("testDebugUnitTest") {
    group = "verification"
    description = "Legacy alias: runs the prodDebug unit tests (product flavors split the old debug variant)."
    dependsOn("testProdDebugUnitTest")
}
tasks.register("compileDebugKotlin") {
    group = "build"
    description = "Legacy alias: compiles the prodDebug Kotlin sources."
    dependsOn("compileProdDebugKotlin")
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
