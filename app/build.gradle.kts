plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.devtools.ksp)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
}

import java.util.Properties

// Release signing config from environment variables (secure)
// 本地构建时请在 ~/.gradle/gradle.properties 中配置：
//   PICME_RELEASE_STORE_FILE=/path/to/keystore
//   PICME_RELEASE_STORE_PASSWORD=your_password
//   PICME_RELEASE_KEY_ALIAS=your_alias
//   PICME_RELEASE_KEY_PASSWORD=your_password
val releaseStoreFile: String = System.getenv("PICME_RELEASE_STORE_FILE") ?: ""
val releaseStorePassword: String = System.getenv("PICME_RELEASE_STORE_PASSWORD") ?: ""
val releaseKeyAlias: String = System.getenv("PICME_RELEASE_KEY_ALIAS") ?: ""
val releaseKeyPassword: String = System.getenv("PICME_RELEASE_KEY_PASSWORD") ?: ""

// 飞书远程控制 AppId/AppSecret（编译时从 local.properties 或环境变量注入。默认空字符串）
// local.properties: picme.feishu.app.id=cli_xxxxx, picme.feishu.app.secret=yyyyy
// 环境变量: PICME_FEISHU_APP_ID=cli_xxxxx PICME_FEISHU_APP_SECRET=yyyyy
val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localPropertiesFile.inputStream().use { localProperties.load(it) }
}
val feishuAppId: String = localProperties.getProperty("picme.feishu.app.id")
    ?: System.getenv("PICME_FEISHU_APP_ID") ?: ""
val feishuAppSecret: String = localProperties.getProperty("picme.feishu.app.secret")
    ?: System.getenv("PICME_FEISHU_APP_SECRET") ?: ""
val tencentScfAppToken: String = localProperties.getProperty("tencent.scf.app.token")
    ?: System.getenv("TENCENT_SCF_APP_TOKEN") ?: ""

detekt {
    buildUponDefaultConfig = true
    allRules = false
    config.setFrom("$rootDir/detekt-config.yml")
    baseline = file("$projectDir/detekt-baseline.xml")
}

ktlint {
    version.set("1.3.1")
    android.set(true)
    outputToConsole.set(true)
    filter {
        exclude("**/generated/**")
        exclude("**/build/**")
    }
}

// 使用 ktlint 插件进行代码风格检查
// 如需其他验证任务，请在 buildSrc 中定义并通过 plugins {} 声明
// tasks.named("preBuild").configure {
//     dependsOn("checkNoFullyQualifiedName")
// }

android {
    ndkVersion = "28.2.13676358"
    namespace = "com.mamba.picme"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.mamba.picme"
        minSdk = 24
        targetSdk = 35
        versionCode = 10009
        versionName = "1.0.9"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        // 飞书远程控制默认值（从环境变量注入）
        buildConfigField("String", "FEISHU_APP_ID", "\"${feishuAppId}\"")
        buildConfigField("String", "FEISHU_APP_SECRET", "\"${feishuAppSecret}\"")
        buildConfigField("String", "TENCENT_SCF_APP_TOKEN", "\"${tencentScfAppToken}\"")
        buildConfigField("String", "CLOUDFLARE_GATEWAY_TOKEN", "\"${System.getenv("CLOUDFLARE_GATEWAY_TOKEN") ?: ""}\"")
    }

    androidResources {
        noCompress += "task"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    signingConfigs {
        create("release") {
            if (releaseStoreFile.isNotBlank()) {
                storeFile = file(releaseStoreFile)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            // 通过 project property 控制是否启用混淆，release-plain 模式不混淆
            isMinifyEnabled = !(project.findProperty("picme.release.plain")?.toString()?.toBoolean() ?: false)
            isShrinkResources = isMinifyEnabled
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Release 包默认使用正式签名；AAB 构建时会通过注入参数覆盖
            signingConfig = if (releaseStoreFile.isNotBlank()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
        debug {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    bundle {
        // AAB 统一使用 release 签名配置；如未配置环境变量则回退 debug 签名
        storeArchive {
            enable = false
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            // `:app` 直接使用 ONNX Runtime Java API（MobileCLIP / OPUS-MT），需要保留 `onnxruntime-android` 坐标。
            // Sherpa-ONNX AAR 同时内置同名 `libonnxruntime.so`，导致打包冲突。
            // 当前两个来源均为 ONNX Runtime 1.24.3，ABI 兼容；仅支持 arm64-v8a，故只保留该 ABI 的 pickFirst。
            // 升级任一依赖时，必须确保 `libonnxruntime.so` 版本一致，否则会出现 UnsatisfiedLinkError。
            pickFirsts += "lib/arm64-v8a/libonnxruntime.so"
        }
        resources {
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "/META-INF/LICENSE"
            excludes += "/META-INF/LICENSE.txt"
            excludes += "/META-INF/NOTICE"
            excludes += "/META-INF/NOTICE.txt"
        }
    }
}

base {
    archivesName.set("picme")
}

dependencies {
    // Force vision-common to 17.2.0 to match image-labeling-common:18.1.0
    // (text-recognition prefers 17.3.0 but is compatible with 17.2.0)
    constraints {
        implementation("com.google.mlkit:vision-common:17.2.0") {
            version {
                strictly("17.2.0")
            }
            because("image-labeling-common:18.1.0 is incompatible with vision-common:17.3.0 (AbstractMethodError on DetectorCreator.create)")
        }
        implementation("com.google.mlkit:vision-interfaces:16.1.0") {
            version {
                strictly("16.1.0")
            }
            because("image-labeling-common:18.1.0 was compiled against vision-interfaces:16.1.0")
        }
    }

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.coil.compose)
    implementation(libs.coil.video)
    implementation(libs.compose.markdown)
    implementation(libs.retrofit)
    implementation(libs.converter.moshi)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.accompanist.permissions)
    implementation(libs.play.services.location)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.video)
    implementation(libs.logging.interceptor)
    implementation(libs.okhttp)
    implementation(libs.moshi.kotlin)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.material)
    implementation(libs.oapi.sdk)

    // Media3 dependencies
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.common)

    implementation(libs.google.mlkit.text.recognition)
    implementation(libs.google.mlkit.text.recognition.chinese)
    // image-labeling:17.0.9 自动传递 image-labeling-common/vision-common/vision-interfaces
    implementation(libs.google.mlkit.image.labeling)
    implementation(libs.google.mlkit.face.detection)

    // MediaPipe Face Landmarker（Gallery 调试用，直接显示 468 点原始数据）
    implementation(libs.mediapipe.face.landmarker)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.core)
    testImplementation(libs.androidx.junit)
    testImplementation(libs.mockk)
    // 让 JVM 单元测试能使用真实的 org.json 实现，而非 Android stub
    testImplementation("org.json:json:20231013")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.runner)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    // 美颜 API 接口模块（纯数据类型，被 beauty-engine 和 agent-core 共享）
    implementation(project(":beauty-api"))
    // 美颜引擎模块
    implementation(project(":beauty-engine"))
    implementation(project(":runtime-core"))
    implementation(project(":mnn-core"))
    // sherpa-onnx: runtime-core 编译期依赖，app 模块提供运行时 AAR 打包
    implementation(files("../runtime-core/libs/sherpa-onnx-1.13.3.aar"))
    // Agent 核心模块（将来提取独立库）
    // GPUPixel 已移除，全部能力由自研引擎提供

    // SentencePiece tokenizer（OPUS-MT 编码解码 + tokenizer.json 词表映射）
    implementation(project(":sentencepiece"))

    // ONNX Runtime（OPUS-MT 翻译模型推理后端）
    // 版本必须与 sherpa-onnx-1.13.3 内置的 ONNX Runtime 一致（1.24.3）
    // 否则 libonnxruntime4j_jni.so 与 libonnxruntime.so ABI 不匹配
    implementation(libs.onnxruntime.android)
    // Core library desugaring（mamba-agent 依赖需要）
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.4")

    "ksp"(libs.androidx.room.compiler)
    "ksp"(libs.moshi.kotlin.codegen)
}

// 修复 KSP 增量缓存损坏（file-to-id.tab is already registered）的根因：
// 让 clean 任务同时删除 KSP 缓存目录，避免历史损坏状态残留。
tasks.register<Delete>("cleanKspCaches") {
    group = "build"
    description = "Deletes KSP incremental caches to prevent 'file-to-id.tab is already registered' corruption."
    delete(layout.buildDirectory.dir("kspCaches"))
}

tasks.named<Delete>("clean").configure {
    dependsOn("cleanKspCaches")
}
