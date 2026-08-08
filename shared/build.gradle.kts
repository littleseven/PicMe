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
    iosX64()
    iosArm64()
    iosSimulatorArm64()

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
        }
        // iosMain.dependencies 在后续 Task 按需追加
    }
}
