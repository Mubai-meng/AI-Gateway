pluginManagement {
    repositories {
        maven { url = uri("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/") }
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        maven { url = uri("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/") }
        mavenCentral()
    }
}

rootProject.name = "aigw-windows"

include(":core")
include(":cli")
include(":desktop")
// WebView2 壳的后端：同一份 core 引擎，对外提供 /ui/* 管理 API + 静态前端资源
include(":server")
