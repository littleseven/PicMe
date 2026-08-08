plugins {
    alias(libs.plugins.android.library)
}

android {
    ndkVersion = "28.2.13676358"
    namespace = "com.mamba.picme.agentnative"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
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
    // 头文件/链接目标来自 :engines:mnn-core 的 jniLibs（CMakeLists 内按相对路径引用）
    implementation(project(":engines:mnn-core"))
}
