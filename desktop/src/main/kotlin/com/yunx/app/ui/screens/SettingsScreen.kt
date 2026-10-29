package com.yunx.app.ui.screens
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Minimize
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Power
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.VolunteerActivism
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yunx.app.AppContext
import com.yunx.app.data.backup.AuthBackupManager
import com.yunx.app.data.backup.AuthCrypto
import com.yunx.app.data.download.DownloadDebugLog
import com.yunx.app.data.download.DownloadPlatform
import com.yunx.app.data.download.DownloadSaver
import com.yunx.app.data.network.GitHubMirror
import com.yunx.app.data.network.HttpClients
import com.yunx.app.data.network.SystemProxy
import com.yunx.app.data.prefs.SettingsRepository
import com.yunx.app.data.update.UpdateChecker
import com.yunx.app.ui.SnackbarController
import com.yunx.app.ui.components.FadeAlertDialog
import com.yunx.app.ui.theme.ThemeController
import com.yunx.app.util.DesktopActions
import com.yunx.app.util.DiagnosticLog
import com.yunx.app.util.Log
import com.yunx.app.util.LogExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 可选的下载线程数档位（最高 512） */
private val threadOptions = listOf(1, 2, 4, 8, 16, 32, 64, 128, 256, 512)

/** 「GitHub 下载镜像」弹窗选择模式：自动（实测选最快） */
private const val MIRROR_MODE_AUTO = "auto"

/** 「GitHub 下载镜像」弹窗选择模式：自定义前缀 */
private const val MIRROR_MODE_CUSTOM = "custom"

/** 按平台下载线程数设置项 */
private data class ThreadPlatform(val platform: String, val label: String)

/** 线程数设置的分组（网盘 / 视频平台 / 其他），分组内按两列或一列排布 */
private data class ThreadPlatformSection(val title: String, val items: List<ThreadPlatform>)

private val threadPlatformSections = listOf(
    ThreadPlatformSection(
        "网盘",
        listOf(
            ThreadPlatform(DownloadPlatform.QUARK, "夸克网盘"),
            ThreadPlatform(DownloadPlatform.UC, "UC 网盘"),
            ThreadPlatform(DownloadPlatform.XUNLEI, "迅雷网盘"),
            ThreadPlatform(DownloadPlatform.BAIDU, "百度网盘"),
            ThreadPlatform(DownloadPlatform.C139, "139 网盘"),
            ThreadPlatform(DownloadPlatform.PAN123, "123 云盘"),
            ThreadPlatform(DownloadPlatform.PAN115, "115 网盘"),
            ThreadPlatform(DownloadPlatform.GUANGYA, "光鸭云盘"),
            ThreadPlatform(DownloadPlatform.ILANZOU, "蓝奏云优享版"),
            ThreadPlatform(DownloadPlatform.LANZOU, "蓝奏云"),
        )
    ),
    ThreadPlatformSection(
        "视频平台",
        listOf(
            ThreadPlatform(DownloadPlatform.VIDEO_BILIBILI, "哔哩哔哩"),
            ThreadPlatform(DownloadPlatform.VIDEO_DOUYIN, "抖音"),
            ThreadPlatform(DownloadPlatform.VIDEO_KUAISHOU, "快手"),
            ThreadPlatform(DownloadPlatform.VIDEO_WEIBO, "微博"),
            ThreadPlatform(DownloadPlatform.VIDEO_XHS, "小红书"),
            ThreadPlatform(DownloadPlatform.VIDEO_ACFUN, "AcFun"),
            ThreadPlatform(DownloadPlatform.VIDEO_PIXIV, "Pixiv"),
        )
    ),
    ThreadPlatformSection(
        "其他",
        listOf(
            ThreadPlatform(DownloadPlatform.GENERIC, "通用 / HTTP 下载"),
            ThreadPlatform(DownloadPlatform.GITHUB, "GitHub 下载"),
        )
    ),
)

/**
 * 设置页：下载线程数设置 + 主题外观 + 检查更新 + 日志与网盘认证。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SettingsScreen(
    scrollBehavior: TopAppBarScrollBehavior,
    onThemeClick: () -> Unit,
    onAboutClick: () -> Unit,
    onSupportClick: () -> Unit,
    /** 打开「实验性功能」二级页 */
    onExperimentalClick: () -> Unit,
    /** 打开「引擎管理」二级页（下载引擎 / 视频预览引擎） */
    onDownloadEngineClick: () -> Unit,
    /** 打开「储存目录」二级页（下载保存目录 / 下载缓存 / 数据库位置） */
    onStorageClick: () -> Unit,
    backupManager: AuthBackupManager,
    /** 用应用内置下载器下载更新包；fallbackUrl 非空时作为镜像下载失败后的直连回退（URL + 文件名 + 回退直连） */
    onDownloadUpdateApk: (url: String, fileName: String, fallbackUrl: String?) -> Unit,
    modifier: Modifier = Modifier
) {
    var showThreadsDialog by remember { mutableStateOf(false) }
    var showLogDialog by remember { mutableStateOf(false) }
    // 检查更新结果（非空时弹更新对话框）
    var updateRelease by remember { mutableStateOf<UpdateChecker.Release?>(null) }
    // 网盘认证导出弹窗（AES 加密 + 导出范围）
    var showExportAuthDialog by remember { mutableStateOf(false) }
    // 网盘认证导入：加密文件内容（非空时弹解密密码框）
    var pendingImportContent by remember { mutableStateOf<String?>(null) }
    var showImportAuthDialog by remember { mutableStateOf(false) }
    // 导出/导入处理中（PBKDF2 21万次迭代派生密钥，偶发 1~3s，期间显示加载弹窗）
    var isExporting by remember { mutableStateOf(false) }
    var isImporting by remember { mutableStateOf(false) }
    // 按平台线程数：二级弹窗当前选择的平台
    var selectedThreadPlatform by remember { mutableStateOf(threadPlatformSections.first().items.first()) }
    var showPlatformThreadDialog by remember { mutableStateOf(false) }
    // 线程数弹窗的排布：一列 / 两列（右上角切换）
    var threadGridTwoColumns by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // 下载保存目录：本地状态驱动 UI 刷新，同时同步 Preferences
    val settingsRepo = remember { SettingsRepository() }
    var downloadDirUri by remember { mutableStateOf(settingsRepo.downloadDirUri) }
    var showDevMenu by remember { mutableStateOf(false) }
    // 诊断模式开关（开发调试菜单里）：本地状态驱动 UI，运行态在 DiagnosticLog 里（改完立刻生效）
    var diagnosticOn by remember { mutableStateOf(settingsRepo.diagnosticMode) }
    // 下载调试开关（开发调试菜单里）：开启后下载页右键任务可查看「调试信息」；关闭会清空日志，故需二次确认
    var downloadDebugOn by remember { mutableStateOf(settingsRepo.downloadDebug) }
    var showDisableDownloadDebugConfirm by remember { mutableStateOf(false) }
    // 全量 SHA-256 校验开关（开发调试菜单里）：默认关，开启后保存文件时顺带算整文件摘要写进调试日志
    var fullShaOn by remember { mutableStateOf(settingsRepo.fullFileSha256) }
    // 网络与下载策略（本地状态驱动 UI，同时同步 Preferences）
    var maxConcurrent by remember { mutableStateOf(settingsRepo.maxConcurrentDownloads) }
    var speedLimitBps by remember { mutableStateOf(settingsRepo.downloadSpeedLimit) }
    var retryCount by remember { mutableStateOf(settingsRepo.downloadRetryCount) }
    // 夸克取链方式：免转存（默认，不写入网盘）↔ 转存（先存临时目录再取链）
    var quarkNoSave by remember { mutableStateOf(settingsRepo.quarkNoSaveDownload) }
    var showConcurrencyDialog by remember { mutableStateOf(false) }
    var showSpeedDialog by remember { mutableStateOf(false) }
    var showRetryDialog by remember { mutableStateOf(false) }
    // GitHub 下载镜像前缀：null/空 = 自动（由 GitHubMirror 实测选最快）；非空 = 手动固定
    var githubMirror by remember { mutableStateOf(settingsRepo.githubMirrorPrefix) }
    var showMirrorDialog by remember { mutableStateOf(false) }
    // 接受预发布版更新：检查更新时包含 GitHub Pre-release（默认关闭）
    var acceptPrerelease by remember { mutableStateOf(settingsRepo.acceptPrereleaseUpdate) }
    // 网络代理：三选一模式（直连 / 系统代理 / 手动配置），本地状态驱动副标题，弹窗内使用临时变量编辑
    var proxyMode by remember { mutableStateOf(settingsRepo.proxyMode) }
    var proxyHost by remember { mutableStateOf(settingsRepo.proxyHost) }
    var proxyPort by remember { mutableStateOf(settingsRepo.proxyPort.toString()) }
    var showProxyDialog by remember { mutableStateOf(false) }
    // system 模式下展示当前实际解析到的系统代理（仅在进入该模式时读一次注册表，不随运行中变化实时刷新）
    val systemProxyInfo = remember(proxyMode) {
        if (proxyMode == SettingsRepository.PROXY_MODE_SYSTEM) SystemProxy.inspect() else null
    }
    // 用户体验与系统适配：下载时阻止休眠 / 通知中心进度
    var keepAwake by remember { mutableStateOf(settingsRepo.keepAwakeWhileDownloading) }
    var showSpeed by remember { mutableStateOf(settingsRepo.notificationShowSpeed) }
    var clipboardDetect by remember { mutableStateOf(settingsRepo.clipboardLinkDetection) }
    // 关闭主窗口时的行为：ask / exit / tray
    var closeBehavior by remember { mutableStateOf(settingsRepo.closeBehavior) }
    var showCloseBehaviorDialog by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection)
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        SectionLabel("下载")
        SettingsItem(
            icon = Icons.Outlined.Tune,
            title = "下载线程数",
            description = "按平台分别设置分片并发数（默认 32，最高 512）",
            onClick = { showThreadsDialog = true }
        )

        Spacer(modifier = Modifier.height(8.dp))

        // 储存目录（二级页）：下载保存目录 / 下载缓存目录 / 数据库位置
        SettingsItem(
            icon = Icons.Outlined.FolderOpen,
            title = "储存目录",
            description = downloadDirUri?.let { "下载保存已自定义：${DownloadSaver.safDirDisplay(it)}" }
                ?: "下载保存目录、下载缓存、数据库位置",
            onClick = onStorageClick
        )

        Spacer(modifier = Modifier.height(8.dp))

        // 网络与下载策略
        SettingsItem(
            icon = Icons.Outlined.Layers,
            title = "最大同时下载任务数",
            description = "同时下载 $maxConcurrent 个任务，超出的排队等待（内置下载器与 Gopeed 引擎共用）",
            onClick = { showConcurrencyDialog = true }
        )

        Spacer(modifier = Modifier.height(8.dp))

        SettingsItem(
            icon = Icons.Outlined.Speed,
            title = "下载速度限制",
            description = speedLimitText(speedLimitBps),
            onClick = { showSpeedDialog = true }
        )

        Spacer(modifier = Modifier.height(8.dp))

        SettingsItem(
            icon = Icons.Outlined.Refresh,
            title = "失败自动重试",
            description = if (retryCount == 0) "失败后不自动重试" else "失败后自动重试 $retryCount 次（断点续传）",
            onClick = { showRetryDialog = true }
        )

        Spacer(modifier = Modifier.height(8.dp))

        // 取链方式：免转存（默认）↔ 转存。只影响夸克 —— 其分享文件可以「用分享凭证直接换直链」，
        // 不必先转存进用户网盘；关掉后回到「转存到 YunX临时转存 再取链」的老流程。
        SettingsItem(
            icon = Icons.Outlined.SwapHoriz,
            title = "免转存下载",
            description = if (quarkNoSave) {
                "夸克：解析出直链后直接下载，不把文件转存到自己的网盘"
            } else {
                "夸克：先转存到临时目录再取链（下载完成后自动清理）"
            },
            onClick = {
                quarkNoSave = !quarkNoSave
                settingsRepo.quarkNoSaveDownload = quarkNoSave
            },
            trailing = { Switch(checked = quarkNoSave, onCheckedChange = null) }
        )

        Spacer(modifier = Modifier.height(8.dp))

        // 用户体验与系统适配：下载时阻止休眠 / 通知中心进度
        SettingsItem(
            icon = Icons.Outlined.Power,
            title = "下载时阻止电脑休眠",
            description = "下载期间系统不会自动进入睡眠，任务结束后恢复",
            onClick = {
                keepAwake = !keepAwake
                settingsRepo.keepAwakeWhileDownloading = keepAwake
            },
            trailing = { Switch(checked = keepAwake, onCheckedChange = null) }
        )

        Spacer(modifier = Modifier.height(8.dp))

        SettingsItem(
            icon = Icons.Outlined.Notifications,
            title = "通知中心下载进度",
            description = if (showSpeed) "Windows 通知中心进度条 + 下载速度" else "Windows 通知中心仅显示进度条（隐藏速度）",
            onClick = {
                showSpeed = !showSpeed
                settingsRepo.notificationShowSpeed = showSpeed
            },
            trailing = { Switch(checked = showSpeed, onCheckedChange = null) }
        )

        Spacer(modifier = Modifier.height(8.dp))

        SettingsItem(
            icon = Icons.Outlined.ContentPaste,
            title = "剪贴板分享链接检测",
            description = if (clipboardDetect) "主窗口不在前台时检测剪贴板，发现网盘链接自动弹窗提示" else "关闭后台剪贴板检测",
            onClick = {
                clipboardDetect = !clipboardDetect
                settingsRepo.clipboardLinkDetection = clipboardDetect
            },
            trailing = { Switch(checked = clipboardDetect, onCheckedChange = null) }
        )

        Spacer(modifier = Modifier.height(8.dp))

        SettingsItem(
            icon = Icons.Outlined.Minimize,
            title = "关闭窗口时",
            description = when (closeBehavior) {
                SettingsRepository.CLOSE_BEHAVIOR_TRAY -> "最小化到系统托盘（下载继续）"
                SettingsRepository.CLOSE_BEHAVIOR_EXIT -> "直接退出应用"
                else -> "每次询问（默认）"
            },
            onClick = { showCloseBehaviorDialog = true }
        )

        Spacer(modifier = Modifier.height(24.dp))

        SectionLabel("外观")
        SettingsItem(
            icon = Icons.Outlined.Palette,
            title = "主题与外观",
            description = "主题色、动态色彩与深色模式",
            onClick = onThemeClick
        )

        Spacer(modifier = Modifier.height(24.dp))

        SectionLabel("通用")
        SettingsItem(
            icon = Icons.Outlined.SystemUpdate,
            title = "检查更新",
            description = "检查 GitHub 是否有新版本可用",
            onClick = {
                scope.launch {
                    SnackbarController.show("正在检查更新…")
                    val release = runCatching { UpdateChecker.fetchLatestRelease(acceptPrerelease) }.getOrNull()
                    val current = UpdateChecker.currentVersion()
                    if (release == null) {
                        SnackbarController.show("检查更新失败，请检查网络")
                    } else if (UpdateChecker.compareVersions(release.tagName, current) > 0) {
                        updateRelease = release
                    } else {
                        SnackbarController.show("已是最新版本")
                    }
                }
            }
        )

        Spacer(modifier = Modifier.height(8.dp))

        // 接受预发布版更新：开启后检查更新包含 GitHub Pre-release，切换后下次检查才生效
        SettingsItem(
            icon = Icons.Outlined.SystemUpdate,
            title = "接受预发布版更新",
            description = if (acceptPrerelease) {
                "检查更新时包含 GitHub Pre-release（可能不稳定）"
            } else {
                "只接收正式版更新"
            },
            onClick = {
                acceptPrerelease = !acceptPrerelease
                settingsRepo.acceptPrereleaseUpdate = acceptPrerelease
            },
            trailing = { Switch(checked = acceptPrerelease, onCheckedChange = null) }
        )

        Spacer(modifier = Modifier.height(8.dp))

        // GitHub 下载镜像：自动（实测选最快）/ 内置预选 / 自定义前缀
        SettingsItem(
            icon = Icons.Outlined.Cloud,
            title = "GitHub 下载镜像",
            description = githubMirror?.takeIf { it.isNotBlank() }
                ?.let { "已固定：${it.removePrefix("https://").removeSuffix("/")}" }
                ?: "自动选择最快镜像",
            onClick = { showMirrorDialog = true }
        )

        Spacer(modifier = Modifier.height(8.dp))

        // 网络代理：三选一模式（不代理 / 使用系统代理 / 手动配置）
        SettingsItem(
            icon = Icons.Outlined.Security,
            title = "网络代理",
            description = when (proxyMode) {
                SettingsRepository.PROXY_MODE_MANUAL ->
                    if (proxyHost.isNotBlank()) "手动配置代理：$proxyHost:$proxyPort"
                    else "手动配置代理（未填写主机地址）"
                SettingsRepository.PROXY_MODE_SYSTEM -> "使用系统代理：${describeSystemProxy(systemProxyInfo)}"
                else -> "不使用代理（直连）"
            },
            onClick = { showProxyDialog = true }
        )

        Spacer(modifier = Modifier.height(8.dp))

        // 实验性功能二级页：HTTP/2、下载读缓冲、慢连接抢占、主页快捷方式（集中管理 + 一键重置）
        SettingsItem(
            icon = Icons.Outlined.Bolt,
            title = "实验性功能",
            description = "HTTP/2、下载读缓冲、慢连接抢占、主页快捷方式等高风险参数",
            onClick = onExperimentalClick
        )

        Spacer(modifier = Modifier.height(8.dp))

        // 引擎管理二级页：下载引擎（内置分片下载器 ↔ 外部 Gopeed 引擎，仅影响新任务）
        // + 视频预览引擎（自动 / 内置 JavaFX / FFplay 在线预览）
        SettingsItem(
            icon = Icons.Outlined.Speed,
            title = "引擎管理",
            description = "下载引擎（内置分片下载器 / Gopeed）与视频预览引擎（FFplay / 内置）",
            onClick = onDownloadEngineClick
        )

        Spacer(modifier = Modifier.height(8.dp))
        SettingsItem(
            icon = Icons.Outlined.Article,
            title = "导出日志",
            description = "导出崩溃日志与应用信息，便于排查问题",
            onClick = { showLogDialog = true }
        )

        Spacer(modifier = Modifier.height(24.dp))

        SectionLabel("认证备份")
        SettingsItem(
            icon = Icons.Outlined.Backup,
            title = "导出认证信息",
            description = "使用至少 8 位口令加密网盘与视频平台的 Cookie/JWT 后导出",
            onClick = { showExportAuthDialog = true }
        )

        Spacer(modifier = Modifier.height(8.dp))
        SettingsItem(
            icon = Icons.Outlined.Restore,
            title = "导入认证信息",
            description = "选择加密或明文的认证备份文件，恢复网盘与视频平台登录",
            onClick = {
                val path = DesktopActions.pickFile(
                    filters = listOf(
                        "认证备份文件 (*.yunx;*.json)" to "*.yunx;*.json",
                        "所有文件 (*.*)" to "*.*"
                    )
                )
                if (path != null) {
                    scope.launch {
                        isImporting = true
                        try {
                            // 读取文件内容：先判断是否加密备份，加密则弹密码框
                            val text = runCatching { java.io.File(path).readText() }.getOrNull()
                            if (text == null) {
                                SnackbarController.show("读取文件失败")
                                return@launch
                            }
                            if (AuthCrypto.isEncrypted(text)) {
                                // 加密备份：关闭加载弹窗，弹解密密码框（解密在确认后执行）
                                pendingImportContent = text
                                showImportAuthDialog = true
                            } else {
                                // 明文备份：直接导入
                                val count = runCatching {
                                    withContext(Dispatchers.IO) { backupManager.importJson(text) }
                                }.getOrElse { e ->
                                    SnackbarController.show("导入失败：${e.message}")
                                    return@launch
                                }
                                SnackbarController.show("已恢复 $count 个平台的认证信息")
                            }
                        } finally {
                            isImporting = false
                        }
                    }
                }
            }
        )

        Spacer(modifier = Modifier.height(24.dp))

        SectionLabel("关于")
        SettingsItem(
            icon = Icons.Outlined.Info,
            title = "关于云析",
            description = "版本信息、支持平台与技术说明",
            onClick = onAboutClick,
            onLongClick = { showDevMenu = true } // 长按打开隐藏开发调试菜单
        )

        Spacer(modifier = Modifier.height(8.dp))
        SettingsItem(
            icon = Icons.Outlined.VolunteerActivism,
            title = "支持开发",
            description = "微信扫码捐赠，支持项目持续维护",
            onClick = onSupportClick
        )
    }

    // 导出日志方式选择弹窗
    FadeAlertDialog(
        visible = showLogDialog,
        onDismissRequest = { showLogDialog = false },
        title = { Text("导出日志") },
        text = {
            Column {
                Text(
                    text = "选择日志导出方式：",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(
                    onClick = {
                        showLogDialog = false
                        scope.launch {
                            val file = withContext(Dispatchers.IO) { LogExporter.export() }
                            if (file != null && DesktopActions.revealFile(file.absolutePath)) {
                                SnackbarController.show("日志已导出，已在文件夹中显示")
                            } else {
                                SnackbarController.show("导出日志失败")
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("导出日志（在文件夹中显示）")
                }
                TextButton(
                    onClick = {
                        showLogDialog = false
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) {
                                LogExporter.saveToDownloads()
                            }
                            SnackbarController.show(if (ok) "已保存到下载目录" else "保存失败")
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("保存到下载目录")
                }
                TextButton(
                    onClick = {
                        showLogDialog = false
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) {
                                LogExporter.clearLog()
                            }
                            SnackbarController.show(if (ok) "日志缓存已清空" else "清空失败")
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("清空日志缓存")
                }
                // 诊断模式开着时额外提供 zip：把 diagnostic_logs 下的分模块日志一次带走
                if (diagnosticOn) {
                    TextButton(
                        onClick = {
                            showLogDialog = false
                            scope.launch {
                                val zip = withContext(Dispatchers.IO) { LogExporter.exportDiagnosticZip() }
                                if (zip != null && DesktopActions.revealFile(zip.absolutePath)) {
                                    SnackbarController.show("诊断日志已打包（${zip.name}）")
                                } else {
                                    SnackbarController.show("暂时没有可导出的诊断日志")
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("导出诊断日志（zip）")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { showLogDialog = false }) { Text("取消") }
        }
    )

    // 隐藏开发调试菜单（长按「关于云析」打开）
    FadeAlertDialog(
        visible = showDevMenu,
        onDismissRequest = { showDevMenu = false },
        title = { Text("开发调试") },
        text = {
            Column {
                Button(
                    onClick = {
                        showDevMenu = false
                        // 调试用途：直接弹出更新弹窗（不判断是否已是最新版），预览弹窗 UI
                        scope.launch {
                            val release = runCatching { UpdateChecker.fetchLatestRelease(acceptPrerelease) }.getOrNull()
                            updateRelease = release ?: UpdateChecker.Release(
                                tagName = "v1.2.4（预览）",
                                body = "这是调试预览弹窗，用于查看更新弹窗 UI（含镜像站下载按钮）。",
                                assets = emptyList(),
                                publishedAt = ""
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("显示检查更新弹窗") }

                Spacer(modifier = Modifier.height(12.dp))

                // 诊断模式：开启后 db / crypto / download / network / operation 等模块的详细日志
                // 写进数据目录 diagnostic_logs（按模块分文件），关闭时一行都不写。
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("诊断模式", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = if (diagnosticOn) {
                                "详细日志写入 ${DiagnosticLog.dirOf().absolutePath}"
                            } else {
                                "关闭：不写任何诊断日志"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = diagnosticOn,
                        onCheckedChange = { on ->
                            diagnosticOn = on
                            settingsRepo.diagnosticMode = on
                            // 动态生效：setEnabled 会立刻起/停写线程，关闭时先 flush
                            DiagnosticLog.setEnabled(on)
                            SnackbarController.show(if (on) "诊断模式已开启" else "诊断模式已关闭")
                        }
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // 下载调试：开启后按下载任务记录事件流（主要变更 / 错误节点），下载页右键任务可进调试信息页。
                // ★ 关闭会清空全部日志，所以不直接关，先弹二次确认（见下方 FadeAlertDialog）。
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("下载调试", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = if (downloadDebugOn) {
                                "已在记录：下载页右键任务 → 调试信息（关闭会清空日志）"
                            } else {
                                "关闭：不记录下载调试日志"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = downloadDebugOn,
                        onCheckedChange = { on ->
                            if (on) {
                                downloadDebugOn = true
                                ThemeController.updateDownloadDebug(true)
                                DownloadDebugLog.setEnabled(true)
                                SnackbarController.show("下载调试已开启")
                            } else {
                                // 关闭会清空全部调试日志：先二次确认再真正关闭
                                showDisableDownloadDebugConfirm = true
                            }
                        }
                    )
                }

                // 全量 SHA-256 只在「下载调试」开启时才有意义（摘要只写进调试日志），
                // 所以调试关着时整行隐藏；关掉下载调试会把它一并强制关闭，下次要手动重新开。
                if (downloadDebugOn) {
                    Spacer(modifier = Modifier.height(12.dp))

                    // 全量 SHA-256（默认关）：保存文件时顺带算整文件摘要并写进下载调试日志。
                    // 与合并复用同一遍 IO，所以不额外读盘，只多一点 CPU —— 大文件想省 CPU 就关着。
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("全量 SHA-256", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = if (fullShaOn) {
                                    "已开启：保存后记录整文件 SHA-256（写入调试日志）"
                                } else {
                                    "关闭：不算整文件摘要（默认，省 CPU）"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = fullShaOn,
                            onCheckedChange = { on ->
                                fullShaOn = on
                                settingsRepo.fullFileSha256 = on
                                SnackbarController.show(if (on) "已开启全量 SHA-256 校验" else "已关闭全量 SHA-256 校验")
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // 导出入口（zip 名 yunx_diagnostic_logs_yyyyMMdd_HHmmss.zip，导出前会先 flush）
                TextButton(
                    onClick = {
                        showDevMenu = false
                        scope.launch {
                            val zip = withContext(Dispatchers.IO) { LogExporter.exportDiagnosticZip() }
                            if (zip != null && DesktopActions.revealFile(zip.absolutePath)) {
                                SnackbarController.show("诊断日志已打包（${zip.name}）")
                            } else {
                                SnackbarController.show("暂时没有可导出的诊断日志")
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("导出诊断日志（zip）") }
            }
        },
        confirmButton = {
            TextButton(onClick = { showDevMenu = false }) { Text("关闭") }
        }
    )

    // 关闭「下载调试」的二次确认：关闭会清空已记录的全部调试日志，且不可恢复
    FadeAlertDialog(
        visible = showDisableDownloadDebugConfirm,
        onDismissRequest = { showDisableDownloadDebugConfirm = false },
        title = { Text("关闭下载调试？") },
        text = {
            Text("关闭后会清空已记录的全部下载调试日志，且无法恢复；「全量 SHA-256」也会被一并关闭（下次开启下载调试后需手动重新开启）。确定要关闭吗？")
        },
        confirmButton = {
            TextButton(onClick = {
                showDisableDownloadDebugConfirm = false
                downloadDebugOn = false
                ThemeController.updateDownloadDebug(false)
                // 关闭即清空：DownloadDebugLog.setEnabled(false) 内部会 clear()
                DownloadDebugLog.setEnabled(false)
                // 全量 SHA-256 依赖下载调试（摘要只写调试日志）：一并强制关闭，且不记忆，
                // 下次重新开启下载调试后需要用户手动再开一次。
                fullShaOn = false
                settingsRepo.fullFileSha256 = false
                SnackbarController.show("下载调试已关闭，日志已清空；全量 SHA-256 已一并关闭")
            }) { Text("关闭并清空") }
        },
        dismissButton = {
            TextButton(onClick = { showDisableDownloadDebugConfirm = false }) { Text("取消") }
        }
    )

    // 检查更新结果弹窗（发现新版本时展示，下载走应用内置下载器）
    updateRelease?.let { release ->
        UpdateDialog(
            currentVersion = UpdateChecker.currentVersion(),
            release = release,
            onDownloadAsset = { url, name ->
                updateRelease = null
                onDownloadUpdateApk(url, name, null)
                SnackbarController.show("已加入下载 $name")
            },
            // 镜像站下载：用当前生效的镜像前缀（自动选最快 / 用户固定），并把 GitHub 直连 URL
            // 作为 fallbackUrl —— 镜像站失效/失败时自动回退直连（与 GitHub 浏览下载行为一致）
            onDownloadMirrorAsset = { url, name ->
                updateRelease = null
                val prefix = GitHubMirror.activePrefix()
                onDownloadUpdateApk(UpdateChecker.mirrorUrl(url, prefix), name, url)
                SnackbarController.show("已通过镜像站加入下载 $name")
            },
            onLater = { updateRelease = null },
            onIgnore = {
                // 「不再提示该版本」：按 tag 记住，之后启动检查也会跳过它（将来有更新的版本仍会提示）
                AppContext.miscPrefs.put("ignored_version", release.tagName)
                updateRelease = null
            }
        )
    }

    // 线程数选择弹窗（按平台；右上角可在一列 / 两列之间切换）
    if (showThreadsDialog) {
        FadeAlertDialog(
            visible = true,
            onDismissRequest = { showThreadsDialog = false },
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "下载线程数", modifier = Modifier.weight(1f))
                    TextButton(onClick = { threadGridTwoColumns = !threadGridTwoColumns }) {
                        Text(if (threadGridTwoColumns) "一列" else "两列")
                    }
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = "按平台分别设置分片并发数；线程数不是越多越好，适当调整",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    threadPlatformSections.forEach { section ->
                        Text(
                            text = section.title,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 12.dp, bottom = 2.dp)
                        )
                        ThreadPlatformGrid(
                            items = section.items,
                            twoColumns = threadGridTwoColumns,
                            currentOf = { settingsRepo.downloadThreadsFor(it) },
                            onClick = { item ->
                                selectedThreadPlatform = item
                                showPlatformThreadDialog = true
                            }
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showThreadsDialog = false }) { Text("关闭") }
            }
        )
    }

    // 单个平台线程数选择（二级弹窗）
    FadeAlertDialog(
        visible = showPlatformThreadDialog,
        onDismissRequest = { showPlatformThreadDialog = false },
        title = { Text("${selectedThreadPlatform.label}线程数") },
        text = {
            val current = settingsRepo.downloadThreadsFor(selectedThreadPlatform.platform)
            Column(
                modifier = Modifier
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                threadOptions.chunked(2).forEach { rowValues ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        rowValues.forEach { value ->
                            RadioThreadRow(
                                value = value,
                                threads = current,
                                onSelect = { v ->
                                    settingsRepo.setDownloadThreads(selectedThreadPlatform.platform, v)
                                    showPlatformThreadDialog = false
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        // 奇数个时补空占位，保持两列对齐
                        if (rowValues.size == 1) Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { showPlatformThreadDialog = false }) { Text("取消") }
        }
    )

    // 导出网盘认证弹窗（AES 加密密码 + 导出范围）
    ExportAuthDialog(
        visible = showExportAuthDialog,
        onDismiss = { showExportAuthDialog = false },
        onConfirm = { password, onlyLoggedIn ->
            showExportAuthDialog = false
            isExporting = true
            scope.launch {
                try {
                    val content = runCatching {
                        withContext(Dispatchers.IO) { backupManager.export(password, onlyLoggedIn) }
                    }.onFailure { Log.e("YunX-Auth", "export failed", it) }.getOrNull()
                    if (content == null) {
                        SnackbarController.show("导出失败")
                        return@launch
                    }
                    val encrypted = true
                    // 加载弹窗先关闭，再弹系统「另存为」对话框（EDT 上模态阻塞）
                    isExporting = false
                    val target = DesktopActions.saveFile(
                        defaultName = backupManager.defaultBackupFileName(encrypted),
                        title = "导出认证信息",
                        filters = listOf("云析认证备份 (*.yunx)" to "*.yunx", "所有文件 (*.*)" to "*.*"),
                        defaultExtension = "yunx"
                    ) ?: return@launch
                    val saved = withContext(Dispatchers.IO) {
                        backupManager.saveTo(content, java.io.File(target))
                    }
                    SnackbarController.show(
                        if (saved) "已导出到 ${java.io.File(target).parent}" else "导出失败"
                    )
                } finally {
                    isExporting = false
                }
            }
        }
    )

    // 导入加密备份弹窗（解密密码）
    ImportAuthDialog(
        visible = showImportAuthDialog,
        onDismiss = {
            showImportAuthDialog = false
            pendingImportContent = null
        },
        onConfirm = { password ->
            showImportAuthDialog = false
            val content = pendingImportContent
            pendingImportContent = null
            if (content != null) {
                isImporting = true
                scope.launch {
                    try {
                        val count = try {
                            withContext(Dispatchers.IO) { backupManager.import(content, password) }
                        } catch (e: javax.crypto.AEADBadTagException) {
                            SnackbarController.show("密码错误，解密失败")
                            return@launch
                        } catch (e: Exception) {
                            SnackbarController.show("导入失败：${e.message}")
                            return@launch
                        }
                        SnackbarController.show("已恢复 $count 个平台的认证信息")
                    } finally {
                        isImporting = false
                    }
                }
            }
        }
    )

    // 导出/导入处理中：转圈加载弹窗（PBKDF2 派生密钥耗时较长，避免用户以为界面卡死）
    OperationLoadingDialog(visible = isExporting, message = "正在导出认证…")
    OperationLoadingDialog(visible = isImporting, message = "正在导入认证…")

    // 最大同时下载任务数
    FadeAlertDialog(
        visible = showConcurrencyDialog,
        onDismissRequest = { showConcurrencyDialog = false },
        title = { Text("最大同时下载任务数") },
        text = {
            val options = listOf(1, 2, 3, 5, 8)
            Column {
                options.forEach { v ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = maxConcurrent == v,
                            onClick = {
                                maxConcurrent = v
                                settingsRepo.maxConcurrentDownloads = v
                                showConcurrencyDialog = false
                            }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("同时下载 $v 个任务", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { showConcurrencyDialog = false }) { Text("取消") }
        }
    )

    // 下载速度限制：预设档位 + 自定义（KB/s）
    val speedPresets = listOf(0L, 1L * 1024 * 1024, 2L * 1024 * 1024, 5L * 1024 * 1024, 10L * 1024 * 1024)
    // 临时状态提升到弹窗外，供 text 与 confirmButton 共同访问；每次打开弹窗时重置
    var tempSelected by remember { mutableStateOf<Long?>(null) }
    var customKb by remember { mutableStateOf("") }
    LaunchedEffect(showSpeedDialog) {
        if (showSpeedDialog) {
            tempSelected = null
            // 自定义输入：打开时若当前是自定义档位，带出原值（重新打开保留）
            customKb = if (speedLimitBps > 0 && speedLimitBps !in speedPresets) (speedLimitBps / 1024).toString() else ""
        }
    }
    // 自定义选中态：显式识别「-1=自定义」哨兵；未操作时按当前值是否为自定义档位判断
    val isCustom = when {
        tempSelected == -1L -> true
        tempSelected == null -> speedLimitBps > 0 && speedLimitBps !in speedPresets
        else -> false
    }
    FadeAlertDialog(
        visible = showSpeedDialog,
        onDismissRequest = { showSpeedDialog = false },
        title = { Text("下载速度限制") },
        text = {
            val effective = tempSelected ?: speedLimitBps
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                speedPresets.forEach { v ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = !isCustom && effective == v,
                            onClick = { tempSelected = v }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (v == 0L) "不限速" else speedLimitText(v),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
                // 自定义档位：点击单选即可选中（进入自定义模式）
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = isCustom,
                        onClick = {
                            tempSelected = -1L
                            // 当前已是自定义值时带出原值，便于修改
                            if (speedLimitBps > 0 && speedLimitBps !in speedPresets && customKb.isBlank()) {
                                customKb = (speedLimitBps / 1024).toString()
                            }
                        }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    OutlinedTextField(
                        value = customKb,
                        onValueChange = {
                            customKb = it.filter(Char::isDigit).take(6)
                            // 输入即视为选择自定义
                            tempSelected = -1L
                        },
                        modifier = Modifier.weight(1f),
                        label = { Text("自定义 KB/s") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    // 以当前选中项为准：选自定义则应用输入；选预设则应用预设值
                    if (isCustom) {
                        val kb = customKb.toLongOrNull()?.coerceAtLeast(1L)
                        if (kb != null) {
                            speedLimitBps = kb * 1024
                            settingsRepo.downloadSpeedLimit = kb * 1024
                        }
                        // 自定义输入为空：保持原值
                    } else if (tempSelected != null) {
                        val v = tempSelected ?: speedLimitBps
                        speedLimitBps = v
                        settingsRepo.downloadSpeedLimit = v
                    }
                    // 未做任何选择：保持当前值
                    showSpeedDialog = false
                }
            ) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = { showSpeedDialog = false }) { Text("取消") }
        }
    )

    // 失败自动重试次数
    FadeAlertDialog(
        visible = showRetryDialog,
        onDismissRequest = { showRetryDialog = false },
        title = { Text("失败自动重试") },
        text = {
            val options = listOf(0, 1, 2, 3, 5, 8, 10)
            Column {
                options.forEach { v ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = retryCount == v,
                            onClick = {
                                retryCount = v
                                settingsRepo.downloadRetryCount = v
                                showRetryDialog = false
                            }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (v == 0) "不自动重试" else "失败后自动重试 $v 次",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { showRetryDialog = false }) { Text("取消") }
        }
    )

    // 关闭窗口行为选择
    FadeAlertDialog(
        visible = showCloseBehaviorDialog,
        onDismissRequest = { showCloseBehaviorDialog = false },
        title = { Text("关闭窗口时") },
        text = {
            val options = listOf(
                SettingsRepository.CLOSE_BEHAVIOR_ASK to "每次询问",
                SettingsRepository.CLOSE_BEHAVIOR_EXIT to "直接退出应用",
                SettingsRepository.CLOSE_BEHAVIOR_TRAY to "最小化到系统托盘"
            )
            Column {
                options.forEach { (value, label) ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = closeBehavior == value,
                            onClick = {
                                closeBehavior = value
                                settingsRepo.closeBehavior = value
                                showCloseBehaviorDialog = false
                            }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "提示：选择「直接退出」时，若有下载任务进行中会再次询问。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { showCloseBehaviorDialog = false }) { Text("取消") }
        }
    )

    // GitHub 下载镜像设置弹窗：自动（实测选最快）/ 五个内置预选 / 自定义前缀
    if (showMirrorDialog) {
        val mirrorSnapshot by GitHubMirror.snapshot.collectAsState()
        val fastest = GitHubMirror.fastestPrefix()
        // 当前选择：空 = 自动；命中预选 = 该预选；其余 = 自定义
        var mode by remember {
            mutableStateOf(
                when {
                    githubMirror.isNullOrBlank() -> MIRROR_MODE_AUTO
                    githubMirror in GitHubMirror.PRESETS -> githubMirror!!
                    else -> MIRROR_MODE_CUSTOM
                }
            )
        }
        // 自定义输入框初值：仅当当前保存值是自定义前缀时才带出
        var customInput by remember {
            mutableStateOf(githubMirror?.takeIf { it.isNotBlank() && it !in GitHubMirror.PRESETS } ?: "")
        }
        // 打开时若还没测过速，自动跑一轮
        LaunchedEffect(Unit) { if (mirrorSnapshot.probes.isEmpty()) GitHubMirror.refresh() }
        FadeAlertDialog(
            visible = true,
            onDismissRequest = { showMirrorDialog = false },
            title = { Text("GitHub 下载镜像") },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 380.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = "当前生效：${GitHubMirror.activePrefix().removePrefix("https://").removeSuffix("/")}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "内置镜像（按下载速度排序）",
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            onClick = { GitHubMirror.refresh() },
                            enabled = !mirrorSnapshot.testing
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(if (mirrorSnapshot.testing) "测速中…" else "刷新测速")
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    // 自动：由实测结果决定使用哪个镜像
                    MirrorOptionRow(
                        title = "自动（选最快）",
                        subtitle = when {
                            mirrorSnapshot.testing -> "正在测速…"
                            fastest != null -> "当前最快：${fastest.removePrefix("https://").removeSuffix("/")}"
                            else -> "尚未测速，点「刷新测速」测一次"
                        },
                        selected = mode == MIRROR_MODE_AUTO,
                        badge = null,
                        onClick = { mode = MIRROR_MODE_AUTO }
                    )
                    GitHubMirror.PRESETS.forEach { preset ->
                        val probe = mirrorSnapshot.probes.firstOrNull { it.prefix == preset }
                        MirrorOptionRow(
                            title = preset.removePrefix("https://").removeSuffix("/"),
                            subtitle = mirrorSubtitle(mirrorSnapshot.testing, probe),
                            selected = mode == preset,
                            badge = if (fastest == preset) "最快" else null,
                            onClick = { mode = preset }
                        )
                    }
                    // 自定义前缀：手动填一个（保留原有能力）
                    MirrorOptionRow(
                        title = "自定义前缀",
                        subtitle = "使用你自己的 GitHub 加速前缀",
                        selected = mode == MIRROR_MODE_CUSTOM,
                        badge = null,
                        onClick = { mode = MIRROR_MODE_CUSTOM }
                    )
                    if (mode == MIRROR_MODE_CUSTOM) {
                        OutlinedTextField(
                            value = customInput,
                            onValueChange = { customInput = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                            label = { Text("镜像前缀 URL") },
                            placeholder = { Text("https://gh.ddlc.top/") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            singleLine = true
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        when {
                            mode == MIRROR_MODE_AUTO -> {
                                settingsRepo.githubMirrorPrefix = null
                                githubMirror = null
                                showMirrorDialog = false
                                SnackbarController.show("已切换为自动选择最快镜像")
                            }
                            mode == MIRROR_MODE_CUSTOM -> {
                                val raw = customInput.trim()
                                if (raw.isBlank()) {
                                    SnackbarController.show("请填写镜像前缀，或改选内置镜像")
                                } else if (!raw.startsWith("http://") && !raw.startsWith("https://")) {
                                    // 必须是 http/https 开头，否则报错不保存
                                    SnackbarController.show("镜像前缀需以 http:// 或 https:// 开头")
                                } else {
                                    // 规范化：统一以 / 结尾，拼接原直链时不会粘连
                                    val normalized = if (raw.endsWith("/")) raw else "$raw/"
                                    settingsRepo.githubMirrorPrefix = normalized
                                    githubMirror = normalized
                                    showMirrorDialog = false
                                    SnackbarController.show("GitHub 镜像已更新")
                                }
                            }
                            else -> {
                                // 固定某个内置预选镜像
                                settingsRepo.githubMirrorPrefix = mode
                                githubMirror = mode
                                showMirrorDialog = false
                                SnackbarController.show("已固定镜像：${mode.removePrefix("https://").removeSuffix("/")}")
                            }
                        }
                    }
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showMirrorDialog = false }) { Text("取消") }
            }
        )
    }

    // 网络代理设置弹窗（三选一：不使用代理 / 使用系统代理 / 手动配置代理）
    if (showProxyDialog) {
        // 弹窗内临时变量：取消时不回写已保存值
        var tempMode by remember { mutableStateOf(proxyMode) }
        var tempHost by remember { mutableStateOf(proxyHost) }
        var tempPort by remember { mutableStateOf(proxyPort) }
        // 弹窗内实时预览 system 模式解析结果（切换到该选项时读一次注册表）
        val previewInfo = remember(tempMode) {
            if (tempMode == SettingsRepository.PROXY_MODE_SYSTEM) SystemProxy.inspect() else null
        }
        FadeAlertDialog(
            visible = true,
            onDismissRequest = { showProxyDialog = false },
            title = { Text("网络代理") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ProxyModeOption(
                        label = "不使用代理（直连）",
                        selected = tempMode == SettingsRepository.PROXY_MODE_DIRECT,
                        onClick = { tempMode = SettingsRepository.PROXY_MODE_DIRECT }
                    )
                    ProxyModeOption(
                        label = "使用系统代理",
                        selected = tempMode == SettingsRepository.PROXY_MODE_SYSTEM,
                        onClick = { tempMode = SettingsRepository.PROXY_MODE_SYSTEM }
                    )
                    // 系统代理模式：展示实际解析结果（读 Windows 系统代理设置）
                    if (tempMode == SettingsRepository.PROXY_MODE_SYSTEM) {
                        Text(
                            text = "当前系统代理：${describeSystemProxy(previewInfo)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    ProxyModeOption(
                        label = "手动配置代理",
                        selected = tempMode == SettingsRepository.PROXY_MODE_MANUAL,
                        onClick = { tempMode = SettingsRepository.PROXY_MODE_MANUAL }
                    )
                    // 仅手动模式才需要主机 / 端口输入
                    if (tempMode == SettingsRepository.PROXY_MODE_MANUAL) {
                        OutlinedTextField(
                            value = tempHost,
                            onValueChange = { tempHost = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("代理主机地址（如 127.0.0.1）") },
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = tempPort,
                            onValueChange = { tempPort = it.filter(Char::isDigit).take(5) },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("代理端口（如 7890）") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true
                        )
                    }
                    Text(
                        text = "代理用于加速 GitHub 等海外资源；不使用代理时所有请求直连。" +
                            "系统代理取自 Windows 系统设置，仅在启动或切换设置时读取一次。" +
                            "（暂不支持 PAC 脚本与绕过列表）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        when (tempMode) {
                            SettingsRepository.PROXY_MODE_MANUAL -> {
                                val host = tempHost.trim()
                                val port = tempPort.toIntOrNull()
                                when {
                                    // 校验失败仅提示，不关闭弹窗
                                    host.isBlank() ->
                                        SnackbarController.show("请填写代理主机地址")
                                    port == null || port !in 1..65535 ->
                                        SnackbarController.show("代理端口需为 1-65535 之间的数字")
                                    else -> {
                                        settingsRepo.proxyMode = SettingsRepository.PROXY_MODE_MANUAL
                                        settingsRepo.proxyHost = host
                                        settingsRepo.proxyPort = port
                                        HttpClients.setProxy(host, port)
                                        proxyMode = SettingsRepository.PROXY_MODE_MANUAL
                                        proxyHost = host
                                        proxyPort = port.toString()
                                        showProxyDialog = false
                                        SnackbarController.show("已启用代理：$host:$port")
                                    }
                                }
                            }
                            SettingsRepository.PROXY_MODE_SYSTEM -> {
                                settingsRepo.proxyMode = SettingsRepository.PROXY_MODE_SYSTEM
                                proxyMode = SettingsRepository.PROXY_MODE_SYSTEM
                                when (val info = SystemProxy.inspect()) {
                                    is SystemProxy.Inspect.Proxy -> {
                                        HttpClients.setProxy(info.host, info.port)
                                        SnackbarController.show("已使用系统代理：${info.host}:${info.port}")
                                    }
                                    SystemProxy.Inspect.PacUnsupported -> {
                                        HttpClients.setProxy(null, 0)
                                        SnackbarController.show("检测到 PAC 脚本，暂不支持，已按直连处理")
                                    }
                                    else -> {
                                        HttpClients.setProxy(null, 0)
                                        SnackbarController.show("未检测到系统代理，已按直连处理")
                                    }
                                }
                                showProxyDialog = false
                            }
                            else -> {
                                settingsRepo.proxyMode = SettingsRepository.PROXY_MODE_DIRECT
                                HttpClients.setProxy(null, 0)
                                proxyMode = SettingsRepository.PROXY_MODE_DIRECT
                                showProxyDialog = false
                                SnackbarController.show("已切换为不使用代理（直连）")
                            }
                        }
                    }
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showProxyDialog = false }) { Text("取消") }
            }
        )
    }
}

/** system 模式解析结果的简明文案（用于副标题与弹窗预览） */
private fun describeSystemProxy(info: SystemProxy.Inspect?): String = when (info) {
    is SystemProxy.Inspect.Proxy -> "${info.host}:${info.port}"
    SystemProxy.Inspect.PacUnsupported -> "检测到 PAC 脚本，暂不支持，按直连处理"
    SystemProxy.Inspect.Unavailable -> "当前平台不支持，按直连处理"
    else -> "未检测到系统代理，按直连处理"
}

/** 导出网盘认证弹窗：AES 加密密码 + 导出范围（仅已登录 / 全部绑定） */
@Composable
private fun ExportAuthDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (password: String, onlyLoggedIn: Boolean) -> Unit
) {
    var password by remember { mutableStateOf("") }
    var onlyLoggedIn by remember { mutableStateOf(true) }
    FadeAlertDialog(
        visible = visible,
        onDismissRequest = onDismiss,
        title = { Text("导出认证信息") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "设置至少 8 位密码对认证文件进行 AES 加密。密码请务必牢记，丢失无法找回。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("加密密码（至少 8 位）") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true
                )
                Text(
                    text = "导出范围",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = onlyLoggedIn,
                        onClick = { onlyLoggedIn = true }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("仅导出当前已登录的账号", style = MaterialTheme.typography.bodyMedium)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = !onlyLoggedIn,
                        onClick = { onlyLoggedIn = false }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("导出全部绑定的账号", style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(password, onlyLoggedIn) },
                enabled = password.length >= 8
            ) { Text("导出") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/** 导入加密备份弹窗：输入解密密码 */
@Composable
private fun ImportAuthDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (password: String) -> Unit
) {
    var password by remember { mutableStateOf("") }
    FadeAlertDialog(
        visible = visible,
        onDismissRequest = onDismiss,
        title = { Text("导入认证信息") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "该备份文件已加密，请输入导出时设置的密码进行解密。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("解密密码") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(password) },
                enabled = password.isNotBlank()
            ) { Text("解密并导入") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/** 操作处理中弹窗：转圈加载 + 提示文案，禁止关闭（防止中途取消导致导入/导出状态不一致） */
@Composable
private fun OperationLoadingDialog(visible: Boolean, message: String) {
    FadeAlertDialog(
        visible = visible,
        onDismissRequest = {},
        title = { Text(message) },
        text = {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        },
        confirmButton = {},
        dismissButton = {}
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SettingsItem(
    icon: ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit,
    /** 长按回调（隐藏菜单等）；null 时不启用长按 */
    onLongClick: (() -> Unit)? = null,
    /** 自定义尾部内容（如「恢复默认」操作）；null 时显示默认 ChevronRight */
    trailing: @Composable (() -> Unit)? = null
) {
    val shape = MaterialTheme.shapes.large
    val interactionSource = remember { MutableInteractionSource() }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .combinedClickable(
                interactionSource = interactionSource,
                indication = ripple(bounded = true),
                onClick = onClick,
                onLongClick = onLongClick
            ),
        shape = shape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (trailing != null) {
                trailing()
            } else {
                Icon(
                    imageVector = Icons.Outlined.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}

/** 代理模式单选行（三选一） */
@Composable
private fun ProxyModeOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(modifier = Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

/** 线程数设置网格：按 [twoColumns] 排成一列或两列（两列时最后一行补空占位，保持左对齐） */
@Composable
private fun ThreadPlatformGrid(
    items: List<ThreadPlatform>,
    twoColumns: Boolean,
    currentOf: (String) -> Int,
    onClick: (ThreadPlatform) -> Unit
) {
    val columns = if (twoColumns) 2 else 1
    items.chunked(columns).forEach { rowItems ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            rowItems.forEach { item ->
                ThreadPlatformCell(
                    item = item,
                    threads = currentOf(item.platform),
                    modifier = Modifier.weight(1f),
                    onClick = { onClick(item) }
                )
            }
            repeat(columns - rowItems.size) { Spacer(modifier = Modifier.weight(1f)) }
        }
    }
}

/** 单个平台线程数卡片：平台名 + 当前线程数（迅雷固定 8，不可点） */
@Composable
private fun ThreadPlatformCell(
    item: ThreadPlatform,
    threads: Int,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val fixed = item.platform == DownloadPlatform.XUNLEI
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    ) {
        Column(
            modifier = Modifier
                .clickable(enabled = !fixed, onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Text(
                text = item.label,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = if (fixed) "固定 $threads 线程" else "$threads 线程",
                style = MaterialTheme.typography.bodySmall,
                color = if (fixed) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.primary
                }
            )
        }
    }
}

/** 线程数单选行（用于弹窗两列布局，每行占半宽） */
@Composable
private fun RadioThreadRow(
    value: Int,
    threads: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = threads == value,
            onClick = { onSelect(value) }
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "$value 线程",
            style = MaterialTheme.typography.bodyLarge
        )
    }
}

/** 速度限制展示文案：0=不限速；>=1MB/s 显示 MB/s，否则 KB/s */
private fun speedLimitText(bps: Long): String {
    if (bps <= 0) return "不限速"
    return if (bps >= 1024 * 1024) {
        val mb = bps / (1024.0 * 1024.0)
        if (mb >= 10) String.format("%.0f MB/s", mb) else String.format("%.1f MB/s", mb)
    } else {
        "${bps / 1024} KB/s"
    }
}

/** 「GitHub 下载镜像」弹窗的单选项行：单选圆点 + 标题（可带徽标）+ 副标题（ping/速度等） */
@Composable
private fun MirrorOptionRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    badge: String?,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                )
                if (badge != null) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = badge,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 单个镜像的测速副标题：测速中 / 未测速 / 不可用 / 延迟 + 下载速度 */
private fun mirrorSubtitle(testing: Boolean, probe: GitHubMirror.Probe?): String = when {
    testing -> "正在测速…"
    probe == null -> "未测速"
    !probe.ok -> "不可用"
    else -> "延迟 ${probe.pingMs?.let { "$it ms" } ?: "未知"} · 下载 ${formatMirrorSpeed(probe.speedBps)}"
}

/** 测速速度文案：>=1MB/s 显示 MB/s，否则 KB/s */
private fun formatMirrorSpeed(bps: Long?): String {
    if (bps == null || bps <= 0L) return "未知"
    val mb = bps / 1024.0 / 1024.0
    return if (mb >= 1.0) String.format("%.1f MB/s", mb) else String.format("%.0f KB/s", bps / 1024.0)
}
