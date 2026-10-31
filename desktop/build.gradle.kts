import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    // ★ 图标：**不要**改回 compose.materialIconsExtended —— 完整包有 36 MB（5000+ 图标），
    //   本项目只用到 92 个。libs/material-icons-extended-trimmed-1.7.3.jar 是从官方
    //   material-icons-extended-desktop-1.7.3.jar 里按实际引用裁出来的（219 KB）。
    //   新增/删除图标后需要重新生成：见 docs/BUILD.md「图标裁剪包」一节。
    //   （material-icons-core 仍由 compose.material3 传递引入，核心图标不受影响）
    implementation(files("libs/material-icons-extended-trimmed-1.7.3.jar"))

    // 多平台版 lifecycle（Compose Desktop 可用 viewModel()）
    implementation("org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
    // BackHandler 由本项目自带桌面实现（ui/BackHandler.kt，Escape 键）

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")

    // Room -> 纯 JDBC SQLite
    // ★ 用裁剪后的本地 jar：官方包 13.6 MB，含六个平台的原生库，本项目只跑 Windows。
    //   只保留 org/sqlite/native/Windows（x86/x86_64/arm/arm64），其余代码与 META-INF 原样保留。
    implementation(files("libs/sqlite-jdbc-3.47.1.0-win.jar"))
    // Android 内置 org.json 的 JVM 等价物
    implementation("org.json:json:20240303")
    // 主题种子色（material-color-utilities 无公开仓库坐标；从 material 1.14.0 AAR 解包 classes.jar 本地引入）
    implementation(files("libs/material-color-utilities-1.0.0.jar"))

    // JavaFX MediaView 播放器（M6）：JFXPanel 经 SwingPanel 嵌入 Compose。
    // 版本跟随 jvmToolchain(17)；发布目标只有 Windows，直接用 :win classifier（jar 内含原生库，
    // 首次运行由 JavaFX 自动解到用户目录缓存，jpackage 无需额外处理）
    implementation("org.openjfx:javafx-base:17.0.13:win")
    implementation("org.openjfx:javafx-graphics:17.0.13:win")
    implementation("org.openjfx:javafx-media:17.0.13:win")
    implementation("org.openjfx:javafx-swing:17.0.13:win")

    // GFM Markdown 渲染（README / 更新说明）。注意：必须停留在 0.33.x ——
    // 0.34.0 起改用 Compose 1.8+ 的 BasicText 签名（含 TextAutoSize），与项目锁定的
    // Compose Multiplatform 1.7.3 二进制不兼容，会在运行时抛 NoSuchMethodError
    implementation("com.mikepenz:multiplatform-markdown-renderer-m3:0.33.0")

    // DASH 合轨 / MP4 重封装（纯 Java，无原生库）。
    // 注意：1.9.x 把库拆成了两个 artifact —— isoparser 只有 box 层，
    // MovieCreator / DefaultMp4Builder 在 muxer 里，且 muxer 会传递依赖同版本 isoparser。
    implementation("org.mp4parser:muxer:1.9.56") {
        // muxer 的 POM 把 junit 声明成 compile 依赖，运行期用不到，排除以免进安装包
        exclude(group = "junit", module = "junit")
    }

    // 方案 A：一键导入浏览器 Cookie（DPAPI 解密 Chromium 系浏览器的加密 Cookie）
    implementation("net.java.dev.jna:jna:5.14.0")
    implementation("net.java.dev.jna:jna-platform:5.14.0")

    // 方案 B：内嵌 Chromium 浏览器（JCEF），jcefmaven 负责原生库装载
    implementation("me.friwi:jcefmaven:132.3.1") {
        // jogl / gluegen 只服务于 JCEF 的离屏渲染（OSR）；本项目用窗口态浏览器
        // （createBrowser + createImmediately），不打进安装包可省约 5.4 MB。
        // ★ 若将来改用 OSR（无窗口渲染），必须去掉这两条排除。
        exclude(group = "me.friwi", module = "jogl-all")
        exclude(group = "me.friwi", module = "gluegen-rt")
    }
    // ★ 原生库只在开发运行（gradle run）时由 jcefmaven 从 classpath 里取；
    //   发布包不随带这个 120MB 的 jar，改由打包脚本用 JcefNativesPacker 重打成
    //   app\jcef-natives.tar.gz（裁剪语言包），首次使用时由 JcefNativesInstaller 解开。
    //   见 portable-package.ps1 [2c/5] 与 ui/jcef/JcefNativesInstaller.kt。
    implementation("me.friwi:jcef-natives-windows-amd64:jcef-1770317+cef-132.3.1+g144febe+chromium-132.0.6834.83")

    // tar.gz 读写：CEF 原生库发布包（打包期重打成 gzip，运行期自解包）
    // jcefmaven 已传递引入 commons-compress，这里显式声明版本以保证稳定
    implementation("org.apache.commons:commons-compress:1.27.1")

    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

kotlin {
    jvmToolchain(17)
}

// ---- 版本号单一来源：仓库根目录 version.txt（jpackage / 便携包 / 安装包 / 应用内版本全部由它派生）----
// 格式：第一行版本号（如 1.3.1）；第二行 Beta=1 / Beta=0（1=预览版，0 或缺省=正式版）
val versionLines = rootProject.file("version.txt").readLines().map { it.trim() }.filter { it.isNotEmpty() }
val appVersion = versionLines.firstOrNull().orEmpty()
require(appVersion.matches(Regex("""\d+(\.\d+)*"""))) { "version.txt 内容非法: '$appVersion'" }
val appIsPreview = versionLines.drop(1).firstOrNull()?.let { line ->
    when (line.substringAfter('=', "").trim()) {
        "1" -> true
        "0" -> false
        // 兼容旧写法（preview / beta）
        else -> line.lowercase() in setOf("preview", "beta")
    }
} ?: false

// 生成 Kotlin 常量供 UpdateChecker 使用，避免应用内再维护一份硬编码
val generateAppVersion by tasks.registering {
    val outDir = layout.buildDirectory.dir("generated/appVersion/kotlin")
    val version = appVersion
    val isPreview = appIsPreview.toString()
    outputs.dir(outDir)
    inputs.property("version", version)
    inputs.property("isPreview", isPreview)
    doLast {
        val pkgDir = outDir.get().asFile.resolve("com/yunx/app")
        pkgDir.mkdirs()
        pkgDir.resolve("AppVersion.kt").writeText(
            """
            |package com.yunx.app
            |
            |/** 由根目录 version.txt 生成，请勿手改 */
            |internal const val APP_VERSION = "$version"
            |
            |/** 是否为预览版（version.txt 第二行 Beta=1）；预览版在关于页显示 Beta 标记 */
            |internal const val APP_IS_PREVIEW = $isPreview
            |""".trimMargin() + "\n"
        )
    }
}
kotlin.sourceSets["main"].kotlin.srcDir(generateAppVersion)

// ---- 遮罩选位器 EXE 打进 jar 资源 ----
// picker\PositionPicker.cs 由 run.ps1 / portable-package.ps1 用 csc 编到仓库根 build\ 下，
// 这里把它作为资源塞进 jar 的 yunx\ 前缀；运行期由 util/PositionPicker.kt 释放到 %TEMP% 再执行，
// 因此发布包目录里不再出现这个原生 exe。（文件不存在时 from() 会静默跳过）
tasks.named<ProcessResources>("processResources") {
    val pickerExe = rootProject.layout.projectDirectory.file("build/YunX-PositionPicker.exe")
    from(pickerExe) { into("yunx") }
}

tasks.withType<Test> {
    useJUnit()
    jvmArgs("-Dfile.encoding=UTF-8", "-Dsun.jnu.encoding=UTF-8")
}

// 本机工作目录含非 ASCII 字符时，Gradle 测试 worker 的 classpath 传递会乱码（测试类 ClassNotFound），
// `gradle test` 在 E:\桌面 下不可用。这个任务在**当前 JVM 内**直接跑 JUnit（不分叉），
// 作为本机的替代入口：`gradle :desktop:testInProcess`。CI（ASCII 路径）仍走标准 `test`。
val testInProcessClasspath = the<SourceSetContainer>()["test"].runtimeClasspath
tasks.register<JavaExec>("testInProcess") {
    group = "verification"
    description = "在当前 JVM 内运行 JUnit 测试（绕开非 ASCII 路径下测试 worker 的 classpath 问题）"
    dependsOn("compileTestKotlin")
    mainClass.set("org.junit.runner.JUnitCore")
    classpath = testInProcessClasspath
    args = listOf(
        "com.yunx.app.data.network.VideoLinkParserTest",
        "com.yunx.app.data.network.ShareLinkParserTest",
        "com.yunx.app.data.network.StarSeaApiTest",
        "com.yunx.app.data.download.HlsRequestPolicyTest",
        "com.yunx.app.data.download.HttpRangePolicyTest",
        "com.yunx.app.data.download.DownloadPathPolicyTest",
        "com.yunx.app.data.download.DownloadGroupingTest",
        "com.yunx.app.data.download.DirectPartManifestTest",
        "com.yunx.app.data.download.DownloadSaverTest",
        "com.yunx.app.data.download.ChunkDownloaderDirectTest",
        "com.yunx.app.data.download.DownloadCacheMaintenanceTest",
        "com.yunx.app.data.download.DownloadManagerDirectIntegrationTest",
        "com.yunx.app.data.db.SecureAccountDaosTest",
        "com.yunx.app.data.security.DbKeyStoreTest",
        "com.yunx.app.ui.login.XunleiVerificationPolicyTest",
        "com.yunx.app.data.network.XunleiWebCredentialTest",
        "com.yunx.app.util.LogRedactorTest"
    )
}

// 编译 native/darkmode.cpp → build/native/darkmode.dll（托盘原生菜单暗色桥接层）
val nativeSrcDir = file("native")
val nativeOutDir = layout.buildDirectory.dir("native").get().asFile
val darkModeSrc = File(nativeSrcDir, "darkmode.cpp")
val darkModeOut = File(nativeOutDir, "darkmode.dll")

// 定位 MSVC 的 vcvars64.bat（cl 直接调会找不到 windows.h / CRT 库，必须先 call 它）
fun findMsvcVcvars(): File? {
    val roots = listOf(
        "C:/Program Files/Microsoft Visual Studio/2022",
        "C:/Program Files (x86)/Microsoft Visual Studio/2022",
        "C:/Program Files/Microsoft Visual Studio/2019",
        "C:/Program Files (x86)/Microsoft Visual Studio/2019"
    )
    for (root in roots) {
        val f = File(root)
        if (!f.isDirectory) continue
        val editions = f.listFiles() ?: continue
        for (ed in editions) {
            val bat = File(ed, "VC/Auxiliary/Build/vcvars64.bat")
            if (bat.isFile) return bat
        }
    }
    return null
}

val compileDarkMode by tasks.registering(Exec::class) {
    val vcvars = findMsvcVcvars()
    inputs.file(darkModeSrc)
    outputs.file(darkModeOut)
    doFirst {
        nativeOutDir.mkdirs()
    }
    if (vcvars != null) {
        commandLine(
            "cmd", "/c",
            "call \"${vcvars.absolutePath}\" >nul && " +
                "cl /nologo /utf-8 /LD \"${darkModeSrc.absolutePath}\" " +
                "/Fe:\"${darkModeOut.absolutePath}\" " +
                "/Fo:\"${nativeOutDir.absolutePath}\\\\\" " +
                "/link /NOENTRY kernel32.lib user32.lib"
        )
    } else {
        // 回退 MinGW
        commandLine(
            "g++", "-shared", "-o", darkModeOut.absolutePath,
            darkModeSrc.absolutePath, "-lkernel32", "-luser32"
        )
    }
}

// 开发运行（:desktop:run）时把 DLL 目录并入 java.library.path，并保留 JDK bin（jawt 等原生库）
tasks.withType<JavaExec> {
    dependsOn(compileDarkMode)
    val jvmBin = File(System.getProperty("java.home"), "bin").absolutePath
    systemProperty("java.library.path", "${nativeOutDir.absolutePath};$jvmBin")
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
// ★ 排除 jcef 原生包：发布包改用它重打出来的 jcef-natives.tar.gz（见 JcefNativesPacker），
//   开发运行（:desktop:run）仍直接从运行时 classpath 取该 jar，不受影响。
tasks.register<Sync>("exportRuntimeLibs") {
    from(configurations.runtimeClasspath) { exclude("jcef-natives-*.jar") }
    into(layout.projectDirectory.dir("build/exportLibs"))
}

// 单独导出 jcef 原生包，供打包脚本重打成 tar.gz
tasks.register<Sync>("exportJcefNatives") {
    from(configurations.runtimeClasspath) { include("jcef-natives-*.jar") }
    into(layout.projectDirectory.dir("build/jcefNatives"))
}

compose.desktop {
    application {
        mainClass = "com.yunx.app.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Exe)
            // sqlite-jdbc 通过反射加载 java.sql.Driver，jlink 自动检测不到，必须显式声明；
            // jdk.httpserver 是视频预览的本地回环代理（com.sun.net.httpserver），
            // jdk.unsupported.desktop 是 JavaFX 与 Swing 互操作（JFXPanel 里的 jdk.swing.interop）——
            // 两者 jdeps 都探测不到，漏了它们安装包里的预览会一律失败（无法预览 / 播放器初始化失败）
            modules("java.sql", "java.naming", "jdk.httpserver", "jdk.unsupported.desktop")
            // 每用户安装（%LOCALAPPDATA%\Programs），无需管理员权限
            windows {
                perUserInstall = true
            }
            packageName = "YunX-Desktop"
            packageVersion = appVersion
            // 注意：jpackage 参数文件解析不支持非 ASCII 描述（本机报 "Input length = 1"），描述保持纯英文
            description = "YunX-Desktop - netdisk share-link parser and high-speed downloader (desktop port of YunX for Android)"
            vendor = "YunX-Desktop"
            copyright = "Copyright (C) 2026 tidain"
        }
    }
}
