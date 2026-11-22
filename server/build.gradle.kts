import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))
    // 复用桌面侧的加密存储与 Auto 路由管理（数据格式必须与旧版完全一致，
    // 否则已登录的 11 个账号在迁移后读不出来）
    implementation(project(":desktop"))
    // core 用 implementation 引入，不会传递到本模块编译期，必须显式声明
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("org.nanohttpd:nanohttpd:2.3.1")

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

application {
    applicationName = "aigw-server"
    mainClass.set("dev.aigw.server.MainKt")
    applicationDefaultJvmArgs = listOf("-Dfile.encoding=UTF-8")
}
