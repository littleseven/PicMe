plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    ndkVersion = "28.2.13676358"
    namespace = "com.mamba.picme.agent.core"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DANDROID_PLATFORM=android-24"
                )
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}


dependencies {
    implementation(project(":beauty-api"))
    implementation(project(":mnn-core"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.retrofit)
    implementation(libs.converter.moshi)
    implementation(libs.moshi.kotlin)
    implementation(libs.okhttp)
    implementation(libs.logging.interceptor)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)

    // sherpa-onnx v1.13.3（2026-06，内置 ONNX Runtime 1.24.3，支持 16KB page size）
    // compileOnly + app 直接依赖：规避 Library 模块打包 AAR 时禁止直接依赖本地 .aar 限制
    compileOnly(files("libs/sherpa-onnx-1.13.3.aar"))

    // agent-core: 合并后的 langchain4j 单库模块（core + open-ai + okhttp）
    api(project(":agent-core"))

    // Koog（JetBrains）Agent 框架：替换自维护 langchain4j fork（:agent-core），分阶段迁移。
    // 排除 serialization-jackson：它传递引入 jackson-module-kotlin:2.21.3（MethodHandle.invokeExact，
    // 需 API 26），minSdk 24 下 D8 拒绝 dex。Koog 主链路用 kotlinx-serialization，不依赖此可选模块。
    //
    // **api 而非 implementation（Phase 4 起）**：runtime-core 的公开类型实现了 Koog 接口
    //（ChatToolService : ai.koog...reflect.ToolSet，Phase 5 起 CameraToolService/
    // RemoteControlToolService 同理）。消费方（:app）持有这些类型时，编译器须能在其类路径解析
    // ToolSet 等超类型，故 Koog 须经 api 传递暴露（与 :agent-core 同为 api）。
    api(libs.koog.agents) {
        exclude(group = "ai.koog", module = "serialization-jackson")
    }
    // Koog 记忆层（KoogMessageMemoryStore）用 kotlinx Json 编解码 Koog Message；
    // 显式声明而非依赖 Koog 传递暴露（Koog 仅 api 暴露 JsonElement）。
    implementation(libs.kotlinx.serialization.json)


    // RecyclerView（ScrollTool 滚动检测）
    implementation(libs.androidx.recyclerview)

    // Activity（BackTool 的 ComponentActivity / onBackPressedDispatcher）
    implementation(libs.androidx.activity.compose)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    // 让 JVM 单元测试能使用真实的 org.json 实现，而非 Android stub
    testImplementation("org.json:json:20231013")
}
