plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.mamba.picme.beauty.api"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    // BeautySettings/FilterType/StyleFilter 已迁 shared commonMain（同包同 FQN），
    // 用 api 透出：这三个类型原本就是本模块的公开 API 面，消费者（beauty-engine 等）
    // 经本模块传递依赖继续解析，引用面零变更。
    api(project(":shared"))
}
