pluginManagement {
    repositories {
        // 国内镜像优先（本机无法直连 dl.google.com）
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/central")
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/central")
        mavenCentral()
        google()
    }
}

rootProject.name = "YunX-Desktop"
include(":desktop")
