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


    // RecyclerView（ScrollTool 滚动检测）
    implementation(libs.androidx.recyclerview)

    // Activity（BackTool 的 ComponentActivity / onBackPressedDispatcher）
    implementation(libs.androidx.activity.compose)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
