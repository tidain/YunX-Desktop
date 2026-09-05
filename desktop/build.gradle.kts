import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)

    // 多平台版 lifecycle（Compose Desktop 可用 viewModel()）
    implementation("org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
    // BackHandler 由本项目自带桌面实现（ui/BackHandler.kt，Escape 键）

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")

    // Room -> 纯 JDBC SQLite
    implementation("org.xerial:sqlite-jdbc:3.47.1.0")
    // Android 内置 org.json 的 JVM 等价物
    implementation("org.json:json:20240303")
    // 主题种子色（material-color-utilities 无公开仓库坐标；从 material 1.14.0 AAR 解包 classes.jar 本地引入）
    implementation(files("libs/material-color-utilities-1.0.0.jar"))

    // 方案 A：一键导入浏览器 Cookie（DPAPI 解密 Chromium 系浏览器的加密 Cookie）
    implementation("net.java.dev.jna:jna:5.14.0")
    implementation("net.java.dev.jna:jna-platform:5.14.0")

    // 方案 B：内嵌 Chromium 浏览器（JCEF），jcefmaven 负责原生库装载
    implementation("me.friwi:jcefmaven:132.3.1")
    implementation("me.friwi:jcef-natives-windows-amd64:jcef-1770317+cef-132.3.1+g144febe+chromium-132.0.6834.83")

    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

kotlin {
    jvmToolchain(17)
}

tasks.withType<Test> {
    useJUnit()
    jvmArgs("-Dfile.encoding=UTF-8", "-Dsun.jnu.encoding=UTF-8")
}

// 收集运行时依赖 jar 路径（供便携版 app-image 打包）
tasks.register("printRuntimeClasspath") {
    doLast {
        val cp = configurations.runtimeClasspath.get().files
            .joinToString(";") { it.absolutePath }
        println("RUNTIME_CP=$cp")
    }
}

// 导出全部运行时依赖 jar 到 build/exportLibs（供便携版打包脚本使用，规避 gradle 缓存路径漂移）
tasks.register<Sync>("exportRuntimeLibs") {
    from(configurations.runtimeClasspath)
    into(layout.projectDirectory.dir("build/exportLibs"))
}

compose.desktop {
    application {
        mainClass = "com.yunx.app.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Exe)
            // sqlite-jdbc 通过反射加载 java.sql.Driver，jlink 自动检测不到，必须显式声明
            modules("java.sql", "java.naming")
            // 每用户安装（%LOCALAPPDATA%\Programs），无需管理员权限
            windows {
                perUserInstall = true
            }
            packageName = "YunX-Desktop"
            packageVersion = "1.1.2"
            // 注意：jpackage 参数文件解析不支持非 ASCII 描述（本机报 "Input length = 1"），描述保持纯英文
            description = "YunX-Desktop - netdisk share-link parser and high-speed downloader (desktop port of YunX for Android)"
            vendor = "YunX-Desktop"
        }
    }
}
