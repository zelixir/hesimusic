import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.ksp)
}

val releaseVersionCode = providers.gradleProperty("releaseVersionCode").orNull?.toIntOrNull() ?: 1
val releaseVersionName = providers.gradleProperty("releaseVersionName").orNull ?: "0.1"
// 构建时间(配置期采样,关于页展示):本地时区 "yyyy-MM-dd HH:mm:ss"。
// 仅用于「这个包是什么时候构建的」这一诊断口径,不参与任何产物判定。
val buildTime = LocalDateTime.now()
    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
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
        // 关于页「构建时间」;版本名/版本码直接用 AGP 生成的 VERSION_NAME / VERSION_CODE,
        // 渠道用 FLAVOR(prod/demo)。
        buildConfigField("String", "BUILD_TIME", "\"$buildTime\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        
        externalNativeBuild {
            cmake {
                cppFlags("")
                arguments("-DANDROID_STL=c++_shared", "-DANDROID_LINKER_FLAGS=-Wl,-z,max-page-size=16384")
            }
        }
    }

    // 内置 demo / algolab 变体(独立包名/版本名/应用名,均可与正式包并存):
    // - prod:正式包,与加入 flavor 前行为一致(无任何后缀);不含 Lab 入口;
    // - demo:只编译 app/src/demo 源集,自带 assets/demo(内置歌曲 + 预置循环结果),
    //   冷启动由 DemoBootstrap 写入 filesDir/数据库后直达「无缝循环」结果页;
    // - algolab(任务 B,issue 00030):只编译 app/src/algolab 源集,是**独立的算法
    //   实验 app**,applicationId 追加 .algolab。它用同一次解码/分析同时输出
    //   「初版分数/排名」与「接缝融合分数/排名」并导出 JSON/CSV。BuildConfig.ALGO_LAB
    //   只在 algolab 变体为 true;prod/demo 源集与清单里根本不存在 Lab 类/入口,
    //   因此正式包行为(含分析默认值)不受影响。
    // 加入 flavor 后 AGP 的变体名变为 prodDebug/prodRelease、demoDebug/demoRelease、
    // algolabDebug/algolabRelease、v2Debug/v2Release;assembleDebug/assembleRelease 仍是 build-type
    // 聚合任务(会构建全部 flavor),prod 产物由文末的兼容任务复制回旧 APK 路径。
    flavorDimensions += "channel"
    productFlavors {
        create("prod") {
            dimension = "channel"
            buildConfigField("boolean", "ALGO_LAB", "false")
        }
        create("demo") {
            dimension = "channel"
            applicationIdSuffix = ".demo"
            versionNameSuffix = "-demo"
            manifestPlaceholders["appLabel"] = "HesiMusic Demo"
            buildConfigField("boolean", "ALGO_LAB", "false")
        }
        create("algolab") {
            dimension = "channel"
            applicationIdSuffix = ".algolab"
            versionNameSuffix = "-algolab"
            manifestPlaceholders["appLabel"] = "海瑟音乐 AlgoLab"
            buildConfigField("boolean", "ALGO_LAB", "true")
        }
        // v2(任务 C,issue 00030):可并存安装的第二个正式版,applicationId 追加 .v2
        // ("com.zjr.hesimusic.v2"),应用名 "Hesi v2"。代码与 prod 完全一致(同一 main 源集,
        // 同一次扫描/分析/播放器改动),数据目录因 applicationId 不同而天然隔离;
        // 额外只带 app/src/v2 源集里的「导入上一版数据」入口(见 docs/import-legacy-data.md)。
        // 既有包 com.zjr.hesimusic 的安装与数据完全不受影响:不加权限、不改 provider 授权、
        // 不共享 filesDir/数据库(Android 按 applicationId 隔离)。
        create("v2") {
            dimension = "channel"
            applicationIdSuffix = ".v2"
            versionNameSuffix = "-v2"
            manifestPlaceholders["appLabel"] = "Hesi v2"
            buildConfigField("boolean", "ALGO_LAB", "false")
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
        // 关于页读取 VERSION_NAME / VERSION_CODE / FLAVOR / BUILD_TIME。
        buildConfig = true
    }
    // JVM 上的真实 ExoPlayer 集成测试(Robolectric)需要 Android 资源:
    // 测试只读取 src/test/resources 里的 WAV/元数据,不启动解码器,也不触碰设备。
    testOptions {
        unitTests.isIncludeAndroidResources = true
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
    // JVM 集成测试:Robolectric + media3 官方测试工具(FakeClock/TestExoPlayerBuilder/
    // CapturingRenderersFactory),只作用于 unit test,不进 APK。
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("androidx.media3:media3-test-utils:1.8.0")
    testImplementation("androidx.media3:media3-test-utils-robolectric:1.8.0")
    testImplementation("androidx.test:core:1.6.1")
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
