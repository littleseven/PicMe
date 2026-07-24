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
        if (System.getenv("JITPACK") != "true") {
            maven { url = uri("https://maven.aliyun.com/repository/public") } // 阿里云镜像
            maven { url = uri("https://maven.aliyun.com/repository/central") } // 阿里云 central（dokar3 等 public 未同步的库）
            maven { url = uri("https://maven.aliyun.com/repository/google") } // 阿里云 Google 镜像
        }
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "langchain4android"
include(":app")
include(":beauty-api")
include(":beauty-engine")
include(":runtime-core")
include(":agent-core")
include(":mnn-core")
include(":sentencepiece")
