pluginManagement {
    repositories {
        gradlePluginPortal()
        if (System.getenv("JITPACK") != "true") {
            maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") } // 阿里云 Gradle 插件镜像
            maven { url = uri("https://maven.aliyun.com/repository/public") }       // 阿里云公共镜像
            maven { url = uri("https://maven.aliyun.com/repository/google") }       // 阿里云 Google 镜像
        }
        google()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()  // 提前:KMP .klib(SKIE/co.touchlab 等)阿里云镜像未全同步,须 Maven Central 先解析
        if (System.getenv("JITPACK") != "true") {
            maven { url = uri("https://maven.aliyun.com/repository/public") } // 阿里云镜像
            maven { url = uri("https://maven.aliyun.com/repository/central") } // 阿里云 central（dokar3 等 public 未同步的库）
            maven { url = uri("https://maven.aliyun.com/repository/google") } // 阿里云 Google 镜像
        }
        google()
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "polang"
include(":androidApp")
include(":engines:beauty-api")
include(":engines:beauty-engine")
include(":engines:mnn-core")
include(":engines:agent-native")
include(":engines:sentencepiece")
include(":shared")
