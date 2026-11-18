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
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":core"))
    // core 用 implementation 引入 gson，不会传递给本模块编译期；设置页做 JSON 编辑需要显式声明
    implementation("com.google.code.gson:gson:2.11.0")
}

application {
    applicationName = "aigw-desktop"
    mainClass.set("dev.aigw.desktop.MainKt")
    // Swing 用系统 L&F；中文界面需要 UTF-8 输出
    applicationDefaultJvmArgs = listOf("-Dfile.encoding=UTF-8", "-Dawt.useSystemAAFontSettings=on")
}
