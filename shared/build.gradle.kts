plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    android {
        namespace = "com.mamba.picme.shared"
        compileSdk = 36
        minSdk = 24
    }
    jvm()
    val iosX64 = iosX64()
    val iosArm64 = iosArm64()
    val iosSimulatorArm64 = iosSimulatorArm64()

    // iOS framework 产物（iosApp 消费；Phase 5 Task 1）
    // 注：Kotlin 2.2+ XCFramework DSL 类已改名 XCFrameworkConfig（原 XCFramework 被移除）
    val sharedKit = org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFrameworkConfig(project, "SharedKit")
    listOf(iosX64, iosArm64, iosSimulatorArm64).forEach { target ->
        target.binaries.framework {
            baseName = "SharedKit"
            isStatic = false
            sharedKit.add(this)
        }
    }

    sourceSets {
        commonMain.dependencies {
            // 排除 serialization-jackson：它传递引入 jackson-module-kotlin（MethodHandle.invokeExact，
            // 需 API 26），minSdk 24 下 D8 拒绝 dex（与 :runtime-core 相同的排除理由）。
            // Koog 主链路用 kotlinx-serialization，不依赖此可选模块。
            // 注：KMP KotlinDependencyHandler 不支持 Provider+配置块的重载，故用字符串记法，
            // 版本号仍取自版本目录（libs.versions.koog），不产生第二处版本源。
            implementation("ai.koog:koog-agents:${libs.versions.koog.get()}") {
                exclude(group = "ai.koog", module = "serialization-jackson")
            }
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        androidMain.dependencies {
            implementation(libs.androidx.datastore.preferences)
            implementation(libs.androidx.core.ktx)
            // sherpa-onnx v1.13.3（2026-06，内置 ONNX Runtime 1.24.3，支持 16KB page size）
            // compileOnly + app 直接依赖：规避 Library 模块打包 AAR 时禁止直接依赖本地 .aar 限制
            //（模式自 :runtime-core 平移；运行时 AAR 由 :androidApp 直接依赖本目录同文件打包）
            compileOnly(files("libs/sherpa-onnx-1.13.3.aar"))
            // VLM 引擎（inference/local/llm，Phase 4 Task 12 自 :runtime-core 迁入）：
            // MnnResourceManager/MnnGlobalReleaseLock 来自 :engines:mnn-core；
            // libagent_native.so 由 :engines:agent-native（独立 AGP library 模块）构建，
            // 经 implementation 传递至 :androidApp 打包（AGP 9 KMP 库插件不支持 externalNativeBuild，
            // 官方推荐独立 com.android.library 模块承载 JNI 构建）。
            implementation(project(":engines:mnn-core"))
            implementation(project(":engines:agent-native"))
        }
        // iosMain.dependencies 在后续 Task 按需追加
    }
}
