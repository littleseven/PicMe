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
