package com.yunx.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yunx.app.AppContext
import com.yunx.app.data.announcement.AnnouncementReadStore
import com.yunx.app.data.backup.AuthBackupManager
import com.yunx.app.data.db.AppDatabase
import com.yunx.app.data.download.ChunkDownloader
import com.yunx.app.data.download.DownloadManager
import com.yunx.app.data.download.DownloadPlatform
import com.yunx.app.data.gopeed.GopeedEngine
import com.yunx.app.data.network.BaiduApi
import com.yunx.app.data.network.C139Api
import com.yunx.app.data.network.GitHubApi
import com.yunx.app.data.network.GitHubMirror
import com.yunx.app.data.network.GitHubTokenStore
import com.yunx.app.data.network.GuangYaApi
import com.yunx.app.data.network.HttpClients
import com.yunx.app.data.network.ILanzouApi
import com.yunx.app.data.network.LanzouApi
import com.yunx.app.data.network.Pan115Api
import com.yunx.app.data.network.Pan123Api
import com.yunx.app.data.network.QuarkApi
import com.yunx.app.data.network.TokenCheck
import com.yunx.app.data.network.UCApi
import com.yunx.app.data.network.XunleiApi
import com.yunx.app.data.network.video.VideoPlatform
import com.yunx.app.data.download.VideoDownloads
import com.yunx.app.data.update.UpdateChecker
import com.yunx.app.data.db.DownloadTaskEntity
import com.yunx.app.data.prefs.SettingsRepository
import com.yunx.app.ui.components.CloseChoice
import com.yunx.app.ui.components.CloseConfirmDialog
import com.yunx.app.ui.SnackbarController
import com.yunx.app.util.CookieCleaner
import com.yunx.app.util.DesktopActions
import com.yunx.app.util.TrayManager
import com.yunx.app.data.repository.AccountDirectory
import com.yunx.app.data.repository.BaiduAccountRepository
import com.yunx.app.data.repository.BaiduResolveRepository
import com.yunx.app.data.repository.C139AccountRepository
import com.yunx.app.data.repository.C139ResolveRepository
import com.yunx.app.data.repository.GuangYaAccountRepository
import com.yunx.app.data.repository.GuangYaResolveRepository
import com.yunx.app.data.repository.ILanzouAccountRepository
import com.yunx.app.data.repository.ILanzouResolveRepository
import com.yunx.app.data.repository.LanzouAccountRepository
import com.yunx.app.data.repository.LanzouResolveRepository
import com.yunx.app.data.repository.Pan115AccountRepository
import com.yunx.app.data.repository.Pan115ResolveRepository
import com.yunx.app.data.repository.Pan123AccountRepository
import com.yunx.app.data.repository.Pan123ResolveRepository
import com.yunx.app.data.repository.QuarkAccountRepository
import com.yunx.app.data.repository.QuarkResolveRepository
import com.yunx.app.data.repository.UCAccountRepository
import com.yunx.app.data.repository.UCResolveRepository
import com.yunx.app.data.repository.XunleiAccountRepository
import com.yunx.app.data.repository.XunleiResolveRepository
import com.yunx.app.ui.clipboard.ClipboardLinkController
import com.yunx.app.ui.clipboard.ClipboardLinkDetector
import com.yunx.app.ui.clipboard.ClipboardLinkPopup
import com.yunx.app.ui.components.FadeAlertDialog
import com.yunx.app.ui.components.OverlayDialogHost
import com.yunx.app.ui.login.BaiduLoginScreen
import com.yunx.app.ui.login.C139LoginScreen
import com.yunx.app.ui.login.GuangYaLoginScreen
import com.yunx.app.ui.login.ILanzouLoginScreen
import com.yunx.app.ui.login.LanzouLoginScreen
import com.yunx.app.ui.login.Pan115LoginScreen
import com.yunx.app.ui.login.Pan123LoginScreen
import com.yunx.app.ui.login.QuarkLoginScreen
import com.yunx.app.ui.login.UCLoginScreen
import com.yunx.app.ui.login.VideoLoginScreen
import com.yunx.app.ui.login.XunleiLoginScreen
import com.yunx.app.ui.login.XunleiWebLoginScreen
import com.yunx.app.ui.screens.VideoAccountSheet
import com.yunx.app.ui.login.XunleiVerifyWebViewScreen
import com.yunx.app.ui.navigation.MainTab
import com.yunx.app.ui.theme.Motion
import com.yunx.app.ui.screens.AboutScreen
import com.yunx.app.ui.screens.AnnouncementPopupDialog
import com.yunx.app.ui.screens.AnnouncementScreen
import com.yunx.app.ui.screens.AnnouncementUnreadBadge
import com.yunx.app.ui.screens.BookmarkScreen
import com.yunx.app.ui.screens.DownloadScreen
import com.yunx.app.ui.screens.DownloadEngineScreen
import com.yunx.app.ui.screens.DriveScreen
import com.yunx.app.ui.screens.ExperimentalFeaturesScreen
import com.yunx.app.ui.screens.StorageDirectoryScreen
import com.yunx.app.ui.screens.OnboardingScreen
import com.yunx.app.ui.screens.ResolveScreen
import com.yunx.app.ui.screens.VideoResolveScreen
import com.yunx.app.ui.screens.SettingsScreen
import com.yunx.app.ui.screens.SupportScreen
import com.yunx.app.ui.screens.ThemeScreen
import com.yunx.app.ui.screens.UpdateDialog
import com.yunx.app.ui.viewmodel.AnnouncementViewModel
import com.yunx.app.ui.viewmodel.BaiduAccountViewModel
import com.yunx.app.ui.viewmodel.BaiduCloudViewModel
import com.yunx.app.ui.viewmodel.BookmarkViewModel
import com.yunx.app.ui.viewmodel.C139AccountViewModel
import com.yunx.app.ui.viewmodel.C139CloudViewModel
import com.yunx.app.ui.viewmodel.DownloadViewModel
import com.yunx.app.ui.viewmodel.DriveQuotaViewModel
import com.yunx.app.ui.viewmodel.GuangYaAccountViewModel
import com.yunx.app.ui.viewmodel.GuangYaCloudViewModel
import com.yunx.app.ui.viewmodel.ILanzouAccountViewModel
import com.yunx.app.ui.viewmodel.ILanzouCloudViewModel
import com.yunx.app.ui.viewmodel.LanzouAccountViewModel
import com.yunx.app.ui.viewmodel.LanzouCloudViewModel
import com.yunx.app.ui.viewmodel.MultiAccountViewModel
import com.yunx.app.ui.viewmodel.Pan115AccountViewModel
import com.yunx.app.ui.viewmodel.Pan115CloudViewModel
import com.yunx.app.ui.viewmodel.Pan123AccountViewModel
import com.yunx.app.ui.viewmodel.Pan123CloudViewModel
import com.yunx.app.ui.viewmodel.VideoAccountViewModel
import com.yunx.app.ui.viewmodel.VideoResolveUiState
import com.yunx.app.ui.viewmodel.VideoResolveViewModel
import com.yunx.app.ui.viewmodel.QuarkAccountViewModel
import com.yunx.app.ui.viewmodel.QuarkCloudViewModel
import com.yunx.app.ui.viewmodel.ResolveViewModel
import com.yunx.app.ui.viewmodel.UCAccountViewModel
import com.yunx.app.ui.viewmodel.UCCoudViewModel
import com.yunx.app.ui.viewmodel.XunleiAccountViewModel
import com.yunx.app.ui.viewmodel.XunleiCloudViewModel
import com.yunx.app.util.Log
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private const val TAG = "YunX-Main"

/**
 * 桌面版主页框架：
 * - 左侧 NavigationRail（桌面窗口始终横向布局）+ 顶部可折叠大标题；
 * - 4 个主 Tab（解析 / 网盘 / 下载 / 设置），SaveableStateHolder 保存各页状态；
 * - 全屏覆盖层：首次引导 / 各平台登录 / 关于 / 支持 / 主题 / 收藏。
 * 相比 Android 版移除：通知权限、电池优化引导、存储权限、更新检测、横竖屏切换。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    /** 主窗口 X 按钮点击信号（由 Main.kt 置 true） */
    closeRequested: Boolean = false,
    /** 关闭逻辑处理完毕后回调（重置信号） */
    onCloseHandled: () -> Unit = {},
    /** 执行退出应用（淡出窗口 → exitApplication） */
    onExitApplication: () -> Unit = {},
    /** 最小化到系统托盘（由 Main.kt 实现，含关闭请求抑制） */
    onMinimizeToTray: () -> Unit = {}
) {
    var currentTab by rememberSaveable { mutableStateOf(MainTab.Resolve) }
    // 网盘页：四宫格 / 列表布局切换
    var driveGridMode by rememberSaveable { mutableStateOf(false) }
    var showQuarkLogin by rememberSaveable { mutableStateOf(false) }
    var showUCLogin by rememberSaveable { mutableStateOf(false) }
    var showXunleiLogin by rememberSaveable { mutableStateOf(false) }
    var showXunleiWebLogin by rememberSaveable { mutableStateOf(false) }
    var showXunleiVerify by rememberSaveable { mutableStateOf(false) }
    var xunleiVerifyUrl by rememberSaveable { mutableStateOf("") }
    var xunleiVerifyDeviceId by rememberSaveable { mutableStateOf("") }
    var showBaiduLogin by rememberSaveable { mutableStateOf(false) }
    var showC139Login by rememberSaveable { mutableStateOf(false) }
    var showPan123Login by rememberSaveable { mutableStateOf(false) }
    var showPan115Login by rememberSaveable { mutableStateOf(false) }
    var showGuangYaLogin by rememberSaveable { mutableStateOf(false) }
    var showILanzouLogin by rememberSaveable { mutableStateOf(false) }
    var showLanzouLogin by rememberSaveable { mutableStateOf(false) }
    var showAbout by rememberSaveable { mutableStateOf(false) }
    var showSupport by rememberSaveable { mutableStateOf(false) }
    var showTheme by rememberSaveable { mutableStateOf(false) }
    var showBookmarks by rememberSaveable { mutableStateOf(false) }
    var showExperimental by rememberSaveable { mutableStateOf(false) }
    var showDownloadEngine by rememberSaveable { mutableStateOf(false) }
    var showStorage by rememberSaveable { mutableStateOf(false) }
    var showAnnouncements by rememberSaveable { mutableStateOf(false) }
    /** 启动公告弹窗点「查看详情」时带进去的公告 id（null = 从图标进来先看列表） */
    var announcementDetailId by rememberSaveable { mutableStateOf<String?>(null) }
    /** 启动自动检查更新：发现新版本且未被用户忽略时非空 → 弹更新弹窗（见下方启动检查） */
    var startupUpdateRelease by remember { mutableStateOf<UpdateChecker.Release?>(null) }
    val saveableStateHolder = rememberSaveableStateHolder()

    val scope = rememberCoroutineScope()

    // 首次启动引导
    var showOnboarding by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        showOnboarding = !AppContext.miscPrefs.getBoolean("onboarding_shown", false)
    }

    // 依赖装配（与 Android 版相同的对象图，去掉 Android 专属注入）
    val api = remember { QuarkApi() }
    val ucApi = remember { UCApi() }
    val xunleiApi = remember { XunleiApi() }
    val baiduApi = remember { BaiduApi() }
    val c139Api = remember { C139Api() }
    val pan123Api = remember { Pan123Api() }
    val pan115Api = remember { Pan115Api() }
    val guangyaApi = remember { GuangYaApi() }
    val ilanzouApi = remember { ILanzouApi() }
    val lanzouApi = remember { LanzouApi() }
    val db = remember { AppDatabase.get() }
    val settings = remember { SettingsRepository() }
    // 主页快捷方式开关（实验性功能，默认关闭）：由实验性功能页回调同步，控制解析页是否显示收藏快捷方式
    var homeShortcutsEnabled by remember { mutableStateOf(settings.homeShortcutEnabled) }
    // 网盘页 GitHub 卡片登录态：保存/清除 Token 后即时刷新卡片文案
    var githubHasTokenState by remember { mutableStateOf(GitHubTokenStore.hasToken()) }
    // GitHub API 封装：Token 从 GitHubTokenStore 动态读取（AES-GCM 加密），仅用于提升 API 限额
    val githubApi = remember {
        GitHubApi(tokenProvider = { GitHubTokenStore.getToken() }).also { api ->
            // Token 被 GitHub 拒绝（HTTP 401）：清除本地 Token 并提示；否则后续所有请求都会失败
            api.onUnauthorized = {
                GitHubTokenStore.setToken(null)
                githubHasTokenState = false
                SnackbarController.show("GitHub Token 已失效，已自动清除")
            }
        }
    }
    // GitHub Token 配置弹窗 / 清除二次确认（网盘页入口）
    var showGitHubTokenDialog by remember { mutableStateOf(false) }
    var showGitHubClearConfirm by remember { mutableStateOf(false) }
    val repository = remember {
        QuarkAccountRepository(db.quarkAccountDao(), api)
    }
    val ucRepository = remember {
        UCAccountRepository(db.ucAccountDao(), ucApi)
    }
    val xunleiRepository = remember {
        XunleiAccountRepository(db.xunleiAccountDao(), xunleiApi)
    }
    val baiduRepository = remember {
        BaiduAccountRepository(db.baiduAccountDao(), baiduApi)
    }
    val c139Repository = remember {
        C139AccountRepository(db.c139AccountDao())
    }
    val pan123Repository = remember {
        Pan123AccountRepository(db.pan123AccountDao(), pan123Api)
    }
    val pan115Repository = remember {
        Pan115AccountRepository(db.pan115AccountDao(), pan115Api)
    }
    val guangyaRepository = remember {
        GuangYaAccountRepository(db.guangyaAccountDao(), guangyaApi)
    }
    val ilanzouRepository = remember {
        ILanzouAccountRepository(db.ilanzouAccountDao(), ilanzouApi)
    }
    val lanzouRepository = remember {
        LanzouAccountRepository(db.lanzouAccountDao(), lanzouApi)
    }
    // 光鸭业务 API 的 did 头需要同步取设备标识：注入账号仓库缓存的 deviceId
    // （getAccount 是挂起函数，仓库另有非挂起 cachedDeviceId() 供这里同步取用）
    guangyaApi.deviceIdProvider = { guangyaRepository.cachedDeviceId() }
    val backupManager = remember {
        AuthBackupManager(
            db.quarkAccountDao(),
            db.ucAccountDao(),
            db.xunleiAccountDao(),
            db.baiduAccountDao(),
            db.c139AccountDao(),
            db.pan123AccountDao(),
            db.pan115AccountDao(),
            db.guangyaAccountDao(),
            db.ilanzouAccountDao(),
            db.lanzouAccountDao(),
            db.videoAccountDao()
        )
    }
    val downloadManager = remember {
        DownloadManager(
            dao = db.downloadTaskDao(),
            downloader = ChunkDownloader({ HttpClients.downloadClient() }),
            threadProvider = { platform -> settings.downloadThreadsFor(platform) },
            saveDirProvider = { settings.downloadDirUri },
            concurrencyProvider = { settings.maxConcurrentDownloads },
            speedLimitProvider = { settings.downloadSpeedLimit },
            retryCountProvider = { settings.downloadRetryCount },
            keepAwakeProvider = { settings.keepAwakeWhileDownloading },
            showSpeedProvider = { settings.notificationShowSpeed },
            // 实验性直写：单线程/多线程分别开关（设置 → 实验性功能）
            directWriteSingleProvider = { settings.directWriteSingleThread },
            directWriteMultiProvider = { settings.directWriteMultiThread },
            // 下载引擎开关：选了 Gopeed 时新任务交给外部引擎（还需内核已导入、平台非 GitHub）
            engineEnabledProvider = { settings.downloadEngine == SettingsRepository.ENGINE_GOPEED }
        )
    }

    // ===== 关闭行为处理 =====
    val tasks by downloadManager.tasks.collectAsState(initial = emptyList())
    // 关闭确认弹窗状态
    var showCloseDialog by remember { mutableStateOf(false) }
    var closeDialogHasDownloads by remember { mutableStateOf(false) }

    // 隐藏主窗口到系统托盘
    val minimizeToTray: () -> Unit = {
        // 委托给 Main.kt 的实现（含关闭请求抑制，避免 hide 触发 onCloseRequest 重跑）
        onMinimizeToTray()
        TrayManager.showNotification("云析", "已最小化到系统托盘，下载仍在后台继续")
    }

    // 监听关闭请求信号
    LaunchedEffect(closeRequested) {
        if (!closeRequested) return@LaunchedEffect
        val behavior = settings.closeBehavior
        val hasActiveDownloads = tasks.any {
            it.status == DownloadTaskEntity.STATUS_DOWNLOADING || it.status == DownloadTaskEntity.STATUS_PENDING
        }
        when (behavior) {
            SettingsRepository.CLOSE_BEHAVIOR_TRAY -> {
                minimizeToTray()
            }
            SettingsRepository.CLOSE_BEHAVIOR_EXIT -> {
                if (hasActiveDownloads) {
                    closeDialogHasDownloads = true
                    showCloseDialog = true
                } else {
                    onExitApplication()
                }
            }
            else -> { // CLOSE_BEHAVIOR_ASK
                closeDialogHasDownloads = hasActiveDownloads
                showCloseDialog = true
            }
        }
        onCloseHandled()
    }

    val viewModel: QuarkAccountViewModel = viewModel(
        factory = QuarkAccountViewModel.Factory(repository)
    )
    val ucViewModel: UCAccountViewModel = viewModel(
        factory = UCAccountViewModel.Factory(ucRepository)
    )
    val xunleiViewModel: XunleiAccountViewModel = viewModel(
        factory = XunleiAccountViewModel.Factory(xunleiRepository)
    )
    val baiduViewModel: BaiduAccountViewModel = viewModel(
        factory = BaiduAccountViewModel.Factory(baiduRepository)
    )
    val c139ViewModel: C139AccountViewModel = viewModel(
        factory = C139AccountViewModel.Factory(c139Repository)
    )
    val pan123ViewModel: Pan123AccountViewModel = viewModel(
        factory = Pan123AccountViewModel.Factory(pan123Repository)
    )
    val pan115ViewModel: Pan115AccountViewModel = viewModel(
        factory = Pan115AccountViewModel.Factory(pan115Repository)
    )
    val guangyaViewModel: GuangYaAccountViewModel = viewModel(
        factory = GuangYaAccountViewModel.Factory(guangyaRepository)
    )
    val ilanzouViewModel: ILanzouAccountViewModel = viewModel(
        factory = ILanzouAccountViewModel.Factory(ilanzouRepository)
    )
    val lanzouViewModel: LanzouAccountViewModel = viewModel(
        factory = LanzouAccountViewModel.Factory(lanzouRepository)
    )
    // 多账号目录：10 个平台统一的「列表 / 设主账号 / 改备注 / 退出单个账号」
    val accountDirectory = remember(db) { AccountDirectory(db) }
    val multiAccountViewModel: MultiAccountViewModel = viewModel(
        factory = MultiAccountViewModel.Factory(accountDirectory)
    )

    // 视频解析账号：各平台 Cookie 的登录态与多账号管理
    val videoAccountViewModel: VideoAccountViewModel = viewModel(factory = VideoAccountViewModel.Factory())
    val videoLoginState by videoAccountViewModel.loginState.collectAsState()
    val videoAccounts by videoAccountViewModel.accounts.collectAsState()
    var videoLoginPlatform by remember { mutableStateOf<VideoPlatform?>(null) }
    var videoLoginNewAccount by remember { mutableStateOf(false) }
    var videoManagePlatform by remember { mutableStateOf<VideoPlatform?>(null) }

    // 视频解析页（全屏覆盖）：非空表示当前正在看某个视频链接的解析结果
    val videoResolveViewModel: VideoResolveViewModel = viewModel(factory = VideoResolveViewModel.Factory())
    var videoResolveText by remember { mutableStateOf<String?>(null) }

    /**
     * 账号弹窗点「添加账号」：先清该平台的内嵌浏览器登录态（否则网页登录会自动登回旧账号），
     * 再标记「这次登录落成新账号」，最后打开对应平台的登录页。
     *
     * ★ 清理是挂起等待的（不阻塞 EDT）：CEF 的 Cookie 回调靠 EDT 上的 timer 泵动，
     *   之前同步等 3 秒会把泵堵死 → 「添加第二个账号时登录页打不开」。
     */
    val onAddAccount: (String) -> Unit = { platform ->
        scope.launch {
            // ★ 清登录态失败也必须继续打开登录页：不然用户看到的就是「点了添加账号没有任何反应」
            val startedAt = System.currentTimeMillis()
            runCatching { multiAccountViewModel.prepareAddAccount(platform) }
                .onFailure { Log.w(TAG, "prepareAddAccount($platform) 失败：${it.message}") }
            Log.d(TAG, "onAddAccount: platform=$platform 清登录态耗时=${System.currentTimeMillis() - startedAt}ms")
            when (platform) {
                DownloadPlatform.QUARK -> { viewModel.beginAddAccount(); showQuarkLogin = true }
                DownloadPlatform.UC -> { ucViewModel.beginAddAccount(); showUCLogin = true }
                DownloadPlatform.XUNLEI -> { xunleiViewModel.beginAddAccount(); showXunleiLogin = true }
                DownloadPlatform.BAIDU -> { baiduViewModel.beginAddAccount(); showBaiduLogin = true }
                DownloadPlatform.C139 -> { c139ViewModel.beginAddAccount(); showC139Login = true }
                DownloadPlatform.PAN123 -> { pan123ViewModel.beginAddAccount(); showPan123Login = true }
                DownloadPlatform.PAN115 -> { pan115ViewModel.beginAddAccount(); showPan115Login = true }
                DownloadPlatform.GUANGYA -> { guangyaViewModel.beginAddAccount(); showGuangYaLogin = true }
                DownloadPlatform.ILANZOU -> { ilanzouViewModel.beginAddAccount(); showILanzouLogin = true }
                DownloadPlatform.LANZOU -> { lanzouViewModel.beginAddAccount(); showLanzouLogin = true }
            }
        }
    }
    val quarkCloudViewModel: QuarkCloudViewModel = viewModel(
        factory = QuarkCloudViewModel.Factory(
            api,
            { repository.getFreshCookie() },
            downloadManager
        )
    )
    val ucCloudViewModel: UCCoudViewModel = viewModel(
        factory = UCCoudViewModel.Factory(
            ucApi,
            { ucRepository.getFreshCookie() },
            downloadManager
        )
    )
    // 迅雷 access_token 过期自动刷新
    xunleiApi.refreshTokenProvider = { deviceId ->
        val acc = xunleiRepository.getAccount()
        if (acc == null || acc.refreshToken.isBlank()) null
        else xunleiApi.refreshToken(acc.refreshToken, deviceId, acc.authType)?.also { (at, nrt) ->
            xunleiRepository.updateTokens(at, nrt)
        }
    }
    // 迅雷网页账号的 captcha 刷新：本地 9 盐签名 → 被拒才回退网页会话
    xunleiApi.webCaptchaRefresher = { deviceId, action ->
        xunleiRepository.refreshWebCaptcha(deviceId, action)
    }
    val xunleiCloudViewModel: XunleiCloudViewModel = viewModel(
        factory = XunleiCloudViewModel.Factory(
            xunleiApi,
            { xunleiRepository.getAccount()?.accessToken },
            { xunleiRepository.getAccount()?.deviceId },
            { xunleiRepository.getAccount()?.captchaToken },
            downloadManager
        )
    )
    val baiduCloudViewModel: BaiduCloudViewModel = viewModel(
        factory = BaiduCloudViewModel.Factory(
            baiduApi,
            { baiduRepository.getAccount()?.cookie },
            downloadManager
        )
    )
    val c139CloudViewModel: C139CloudViewModel = viewModel(
        factory = C139CloudViewModel.Factory(
            c139Api,
            { c139Repository.getAccount()?.cookie },
            downloadManager
        )
    )
    val pan123CloudViewModel: Pan123CloudViewModel = viewModel(
        factory = Pan123CloudViewModel.Factory(
            pan123Api,
            { pan123Repository.getAccount()?.accessToken },
            downloadManager
        )
    )
    val pan115CloudViewModel: Pan115CloudViewModel = viewModel(
        factory = Pan115CloudViewModel.Factory(
            pan115Api,
            { pan115Repository.getAccount()?.cookie },
            downloadManager,
            // 登录态从无到有后自动重载根目录（115 登录走内嵌浏览器，登录完成不重启 VM）
            pan115ViewModel.pan115Account.map { it != null }
        )
    )
    val guangyaCloudViewModel: GuangYaCloudViewModel = viewModel(
        factory = GuangYaCloudViewModel.Factory(
            guangyaApi,
            guangyaRepository,
            downloadManager,
            // 登录态从无到有后自动重载根目录
            guangyaViewModel.guangyaAccount.map { it != null }
        )
    )
    val ilanzouCloudViewModel: ILanzouCloudViewModel = viewModel(
        factory = ILanzouCloudViewModel.Factory(
            ilanzouApi,
            ilanzouRepository,
            downloadManager,
            ilanzouViewModel.ilanzouAccount.map { it != null }
        )
    )
    val lanzouCloudViewModel: LanzouCloudViewModel = viewModel(
        factory = LanzouCloudViewModel.Factory(
            lanzouApi,
            lanzouRepository,
            downloadManager,
            lanzouViewModel.lanzouAccount.map { it != null }
        )
    )
    val driveQuotaViewModel: DriveQuotaViewModel = viewModel(
        factory = DriveQuotaViewModel.Factory(
            api, { repository.getAccount()?.cookie },
            ucApi, { ucRepository.getAccount()?.cookie },
            xunleiApi,
            { xunleiRepository.getAccount()?.accessToken },
            { xunleiRepository.getAccount()?.deviceId },
            { xunleiRepository.getAccount()?.captchaToken },
            baiduApi, { baiduRepository.getAccount()?.cookie },
            c139Api, { c139Repository.getAccount()?.cookie },
            pan123Api, { pan123Repository.getAccount()?.accessToken },
            pan115Api, { pan115Repository.getAccount()?.cookie },
            guangyaApi, { guangyaRepository.getAccount() },
            ilanzouApi, { ilanzouRepository.getAccount() }
        )
    )
    val xunleiResolveRepository = remember {
        XunleiResolveRepository(
            api = xunleiApi,
            accountProvider = { xunleiRepository.getAccount()?.accessToken },
            deviceIdProvider = { xunleiRepository.getAccount()?.deviceId },
            captchaProvider = { xunleiRepository.getAccount()?.captchaToken },
            refreshProvider = {
                val acc = xunleiRepository.getAccount()
                if (acc == null || acc.refreshToken.isBlank()) null
                else xunleiApi.refreshToken(acc.refreshToken, acc.deviceId, acc.authType)?.also { (at, nrt) ->
                    xunleiRepository.updateTokens(at, nrt)
                }
            }
        )
    }
    val baiduResolveRepository = remember {
        BaiduResolveRepository(baiduApi)
    }
    val c139ResolveRepository = remember {
        C139ResolveRepository(c139Api)
    }
    val pan123ResolveRepository = remember {
        Pan123ResolveRepository(
            api = pan123Api,
            tokenProvider = { pan123Repository.getAccount()?.accessToken }
        )
    }
    val pan115ResolveRepository = remember {
        Pan115ResolveRepository(pan115Api)
    }
    val guangyaResolveRepository = remember {
        GuangYaResolveRepository(
            api = guangyaApi,
            tokenProvider = { guangyaRepository.ensureAccessToken() }
        )
    }
    val ilanzouResolveRepository = remember {
        ILanzouResolveRepository(ilanzouApi, ilanzouRepository)
    }
    val lanzouResolveRepository = remember {
        LanzouResolveRepository(lanzouApi)
    }
    val resolveViewModel: ResolveViewModel = viewModel(
        factory = ResolveViewModel.Factory(
            repository,
            QuarkResolveRepository(api),
            ucRepository,
            UCResolveRepository(ucApi),
            xunleiRepository,
            xunleiResolveRepository,
            baiduRepository,
            baiduResolveRepository,
            c139Repository,
            c139ResolveRepository,
            pan123Repository,
            pan123ResolveRepository,
            pan115Repository,
            pan115ResolveRepository,
            guangyaRepository,
            guangyaResolveRepository,
            ilanzouRepository,
            ilanzouResolveRepository,
            lanzouRepository,
            lanzouResolveRepository,
            downloadManager,
            db.bookmarkDao(),
            db.linkHistoryDao(),
            githubApi,
            // GitHub 镜像前缀：自动（实测选最快）或用户手动固定，统一由 GitHubMirror 解析
            { GitHubMirror.activePrefix() },
            // 取链方式开关：设置页「免转存下载」实时生效
            { settings.quarkNoSaveDownload }
        )
    )
    val downloadViewModel: DownloadViewModel = viewModel(
        factory = DownloadViewModel.Factory(downloadManager)
    )
    val bookmarkViewModel: BookmarkViewModel = viewModel(
        factory = BookmarkViewModel.Factory(db.bookmarkDao())
    )
    // 收藏列表：主页快捷方式的数据源（复用既有收藏仓库，不新造存储）
    val bookmarks by bookmarkViewModel.bookmarks.collectAsState()
    val quarkAccount by viewModel.quarkAccount.collectAsState()
    val ucAccount by ucViewModel.ucAccount.collectAsState()
    val xunleiAccount by xunleiViewModel.xunleiAccount.collectAsState()
    val baiduAccount by baiduViewModel.baiduAccount.collectAsState()
    val c139Account by c139ViewModel.c139Account.collectAsState()
    val pan123Account by pan123ViewModel.pan123Account.collectAsState()
    val pan115Account by pan115ViewModel.pan115Account.collectAsState()
    val guangyaAccount by guangyaViewModel.guangyaAccount.collectAsState()
    val ilanzouAccount by ilanzouViewModel.ilanzouAccount.collectAsState()
    val lanzouAccount by lanzouViewModel.lanzouAccount.collectAsState()

    // 解析页发起下载后，自动切换到「下载」Tab
    LaunchedEffect(resolveViewModel.downloadStarted) {
        if (resolveViewModel.downloadStarted) {
            currentTab = MainTab.Download
            resolveViewModel.consumeDownloadStarted()
        }
    }

    // 夸克登录页：全屏覆盖（含内嵌 Chromium，见文件末尾的覆盖层组）
    if (showQuarkLogin) {
        QuarkLoginScreen(
            viewModel = viewModel,
            onBack = { viewModel.cancelAddAccount(); showQuarkLogin = false },
            onSaved = { showQuarkLogin = false }
        )
        return
    }

    // UC 登录页：全屏覆盖
    if (showUCLogin) {
        UCLoginScreen(
            viewModel = ucViewModel,
            onBack = { ucViewModel.cancelAddAccount(); showUCLogin = false },
            onSaved = { showUCLogin = false }
        )
        return
    }

    // 迅雷网页登录页：全屏覆盖（内嵌 JCEF 打开 pan.xunlei.com，读取 localStorage 的网页凭据）
    if (showXunleiWebLogin) {
        XunleiWebLoginScreen(
            viewModel = xunleiViewModel,
            onBack = {
                showXunleiWebLogin = false
                showXunleiLogin = true
            },
            onSaved = { showXunleiWebLogin = false }
        )
        return
    }

    // 迅雷登录页（App 通道：账号密码 / 短信 / 网页登录入口）
    // ★ 与夸克 / UC 一致改成「早期返回」渲染：覆盖层（PageOverlay）在某些状态下不出现时
    //   用户看到的就是「点了添加账号没有任何页面」；早期返回没有这层依赖。
    if (showXunleiLogin) {
        XunleiLoginScreen(
            viewModel = xunleiViewModel,
            onBack = { xunleiViewModel.cancelAddAccount(); showXunleiLogin = false },
            onSaved = { showXunleiLogin = false },
            onVerify = { url, deviceId ->
                xunleiVerifyUrl = url
                xunleiVerifyDeviceId = deviceId
                showXunleiLogin = false
                showXunleiVerify = true
            },
            onWebLogin = {
                // 网页登录：另一套接口，不受 App 通道风控影响，收不到短信时的兜底
                showXunleiLogin = false
                showXunleiWebLogin = true
            }
        )
        return
    }

    // 百度登录页：全屏覆盖（粘贴 Cookie 登录）
    if (showBaiduLogin) {
        BaiduLoginScreen(
            viewModel = baiduViewModel,
            onBack = { baiduViewModel.cancelAddAccount(); showBaiduLogin = false },
            onSaved = { showBaiduLogin = false }
        )
        return
    }

    // 139 登录页：全屏覆盖（粘贴 Cookie 登录）
    if (showC139Login) {
        C139LoginScreen(
            viewModel = c139ViewModel,
            onBack = { c139ViewModel.cancelAddAccount(); showC139Login = false },
            onSaved = { showC139Login = false }
        )
        return
    }

    // 115 登录页：全屏覆盖（内嵌浏览器登录抓 Cookie）
    if (showPan115Login) {
        Pan115LoginScreen(
            viewModel = pan115ViewModel,
            onBack = { pan115ViewModel.cancelAddAccount(); showPan115Login = false },
            onSaved = { showPan115Login = false }
        )
        return
    }

    // 视频平台登录页：全屏覆盖（JCEF 网页登录；无网页登录页时自动落到粘贴 Cookie）
    val pendingVideoLogin = videoLoginPlatform
    if (pendingVideoLogin != null) {
        CompositionLocalProvider(
            LocalContentColor provides MaterialTheme.colorScheme.onBackground
        ) {
            VideoLoginScreen(
                platform = pendingVideoLogin,
                newAccount = videoLoginNewAccount,
                viewModel = videoAccountViewModel,
                onBack = { videoLoginPlatform = null },
                onSaved = { videoLoginPlatform = null }
            )
        }
        return
    }

    /**
     * 视频解析结果页：渲染在**内容区**（左侧导航栏保留），与网盘解析同一套窗口内层级。
     *
     * ★ 原先这里是「整窗早返回」，会带来两个问题：
     *   1. 侧栏被整页盖住，看起来像另开了一个窗口；
     *   2. 早返回跳过了根部 Box 里的全局弹窗层（退出确认 / 提示弹窗），关窗时确认框被挡住。
     *   页面自带页头（返回 + 平台 + 标题），显示时让出全局大标题。
     * ★ 返回时不清空解析结果（[videoResolveViewModel.reset] 由下一次解析覆盖），避免退出瞬间闪空。
     */
    val videoResolvePage: @Composable () -> Unit = {
        // 独立于根部的 LocalContentColor 提供点：M3 默认内容色是黑色，深色模式下
        // 未显式指定颜色的文字（错误提示等）会看不见，这里补上主题内容色
        CompositionLocalProvider(
            LocalContentColor provides MaterialTheme.colorScheme.onBackground
        ) {
            VideoResolveScreen(
                viewModel = videoResolveViewModel,
                onBack = { videoResolveText = null },
                onDownload = { medias ->
                    val result = (videoResolveViewModel.uiState as? VideoResolveUiState.Success)?.result
                    if (result != null && medias.isNotEmpty()) {
                        scope.launch {
                            medias.forEachIndexed { index, media ->
                                downloadManager.enqueue(
                                    url = media.url,
                                    fileName = VideoDownloads.fileNameFor(result, media, index, medias.size),
                                    headers = media.headers,
                                    size = media.size,
                                    platform = DownloadPlatform.forVideoPlatform(result.platform),
                                    // DASH 音轨 / 动图资源随任务落库，由 DownloadManager 决定是否合轨或合成
                                    mergeAudioUrl = media.audioUrl,
                                    mergeAudioHeaders = media.audioHeaders,
                                    motionUrl = media.motionUrl
                                )
                            }
                            SnackbarController.show("已加入下载队列（${medias.size} 项）")
                            currentTab = MainTab.Download
                            videoResolveViewModel.reset()
                            videoResolveText = null
                        }
                    }
                }
            )
        }
    }

    // 视频平台账号管理弹窗（多账号：设主账号 / 改备注 / 退出）
    videoManagePlatform?.let { platform ->
        VideoAccountSheet(
            platform = platform,
            accounts = videoAccounts.filter { it.platform == platform.name },
            onSetPrimary = { id -> videoAccountViewModel.setPrimary(platform, id) },
            onRename = { id, remark -> videoAccountViewModel.updateRemark(id, remark) },
            onRemove = { id -> videoAccountViewModel.remove(id) },
            onAddAccount = {
                videoManagePlatform = null
                videoLoginNewAccount = true
                CookieCleaner.clearCookiesForDomains(listOf(platform.cookieDomain))
                videoLoginPlatform = platform
            },
            onLogoutAll = { videoAccountViewModel.removeAll(platform) },
            onDismiss = { videoManagePlatform = null }
        )
    }

    // 折叠标题状态提升到本层：跨页面共享
    val topAppBarState = rememberTopAppBarState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(topAppBarState)

    // 全局 Snackbar 宿主
    val snackbarHostState = rememberGlobalSnackbarHostState()

    /**
     * 应用内公告：顶栏红点角标 + 启动弹窗。
     *
     * 启动检查（[AnnouncementViewModel.checkStartup]）整个会话只跑一次：一次列表请求同时决定
     * 「角标数字」与「弹窗展示哪一条」—— 有未读的置顶公告就弹它，否则弹最新的一条未读，全读完则不弹。
     * 失败静默（与更新检查同一口径），用户点进公告页时会再拉一次并把错误显示出来。
     */
    val announcementReadStore = remember { AnnouncementReadStore() }
    val announcementViewModel: AnnouncementViewModel = viewModel(
        factory = AnnouncementViewModel.Factory(announcementReadStore)
    )
    val unreadAnnouncementCount by announcementViewModel.unreadCount.collectAsState()
    val popupAnnouncement by announcementViewModel.popup.collectAsState()
    LaunchedEffect(Unit) { announcementViewModel.checkStartup() }

    // 启动时同步一次「Gopeed 内核是否已导入」（进程内状态与磁盘事实对齐，供引擎页与分流判断使用）
    LaunchedEffect(Unit) { GopeedEngine.syncInstalledState() }

    // 启动时按需刷新 GitHub 镜像测速（结果过期或从未测过才跑，避免每次启动都测速）
    LaunchedEffect(Unit) { GitHubMirror.ensureFresh() }

    /**
     * 启动自动检查更新（整个会话只跑一次）。
     * 静默口径：网络失败 / 已是最新 / 用户点过「不再提示该版本」的版本 都不打扰；
     * 只有确实有更新的版本才把 [startupUpdateRelease] 置上，由根部的更新弹窗展示。
     * 「不再提示该版本」写入的 `ignored_version`（按 tag 记住）与设置页手动检查时是同一个键，
     * 只跳过这一个版本，将来发布更新的版本仍会提示。
     */
    LaunchedEffect(Unit) {
        val release = runCatching {
            UpdateChecker.fetchLatestRelease(settings.acceptPrereleaseUpdate)
        }.getOrNull() ?: return@LaunchedEffect
        val current = UpdateChecker.currentVersion()
        if (UpdateChecker.compareVersions(release.tagName, current) <= 0) {
            return@LaunchedEffect
        }
        // 强制更新不受「不再提示该版本」影响（用户之前忽略过也要拦）
        val mandatory = release.isMandatoryFor(current)
        if (!mandatory && AppContext.miscPrefs.get("ignored_version", null) == release.tagName) {
            return@LaunchedEffect
        }
        startupUpdateRelease = release
    }

    // 主框架与全屏覆盖层（关于页等）放在同一 Box：覆盖层带过渡动画
    Box(modifier = Modifier.fillMaxSize()) {
        // 根部提供主题内容色：M3 的 LocalContentColor 默认是 Color.Black（不随主题翻转），
        // 不提供的话所有未显式指定颜色的 Text 在深色模式下会保持黑色（如下载页空状态标题）
        CompositionLocalProvider(
            LocalContentColor provides MaterialTheme.colorScheme.onBackground
        ) {
        val topBarContent: @Composable () -> Unit = {
            LargeTopAppBar(
                title = {
                    Text(
                        text = currentTab.title,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                actions = {
                    // 公告入口：所有 Tab 都显示（收藏只在解析页出现，公告是全局入口）。
                    // 角标画在 IconButton 外面（外层再套一个 48dp 的 Box）：material3 的 IconButton
                    // 内部带 .clip(CircleShape)，角标超出那颗圆会被切掉；外层 Box 同为 48dp 且不裁剪，
                    // 点击事件照旧落到 IconButton。
                    Box(
                        modifier = Modifier.size(48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        IconButton(
                            onClick = {
                                // 从图标进 = 先看列表（清掉上次「查看详情」直接进详情的请求）
                                announcementDetailId = null
                                showAnnouncements = true
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Campaign,
                                contentDescription = if (unreadAnnouncementCount > 0) {
                                    "公告（$unreadAnnouncementCount 条未读）"
                                } else {
                                    "公告"
                                }
                            )
                        }
                        // 未读红点角标：只有主界面显示（公告页自己开着的时候不显示）
                        if (unreadAnnouncementCount > 0 && !showAnnouncements) {
                            AnnouncementUnreadBadge(
                                count = unreadAnnouncementCount,
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .offset(x = (-6).dp, y = 2.dp)
                            )
                        }
                    }
                    // 解析页标题右上角：收藏网盘链接入口
                    if (currentTab == MainTab.Resolve) {
                        IconButton(onClick = { showBookmarks = true }) {
                            Icon(Icons.Outlined.Bookmarks, contentDescription = "收藏网盘链接")
                        }
                    }
                    // 网盘页标题右上角：四宫格 / 列表切换 + 刷新各网盘空间配额（原先是下拉刷新）
                    if (currentTab == MainTab.Drive) {
                        IconButton(onClick = { driveGridMode = !driveGridMode }) {
                            if (driveGridMode) {
                                Icon(Icons.AutoMirrored.Outlined.ViewList, contentDescription = "切换为列表")
                            } else {
                                Icon(Icons.Outlined.GridView, contentDescription = "切换为四宫格")
                            }
                        }
                        val quotaLoading by driveQuotaViewModel.loading.collectAsState()
                        IconButton(
                            onClick = { driveQuotaViewModel.loadAll() },
                            enabled = !quotaLoading
                        ) {
                            if (quotaLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(Icons.Outlined.Refresh, contentDescription = "刷新网盘状态")
                            }
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
        val tabContent: @Composable () -> Unit = {
            AnimatedContent(
                targetState = currentTab,
                // 位移+淡入；「减少动画」/ 软件渲染时只做极短淡入（§3.7）
                transitionSpec = { Motion.tabSwitch(targetState.ordinal > initialState.ordinal) },
                label = "mainTab"
            ) { tab ->
                saveableStateHolder.SaveableStateProvider(tab) {
                    when (tab) {
                        MainTab.Resolve -> ResolveScreen(
                            scrollBehavior,
                            resolveViewModel,
                            quarkCloudViewModel,
                            xunleiCloudViewModel,
                            baiduCloudViewModel,
                            c139CloudViewModel,
                            ucCloudViewModel,
                            pan123CloudViewModel,
                            pan115CloudViewModel,
                            guangyaCloudViewModel,
                            // 主页快捷方式（实验性功能，默认关闭）：复用收藏仓库数据源
                            homeShortcutsEnabled = homeShortcutsEnabled,
                            homeShortcuts = bookmarks,
                            onManageShortcuts = { showBookmarks = true },
                            onVideoLink = { text ->
                                videoResolveViewModel.resolve(text)
                                videoResolveText = text
                            }
                        )
                        MainTab.Drive -> DriveScreen(
                            scrollBehavior = scrollBehavior,
                            gridMode = driveGridMode,
                            quarkAccount = quarkAccount,
                            ucAccount = ucAccount,
                            xunleiAccount = xunleiAccount,
                            baiduAccount = baiduAccount,
                            c139Account = c139Account,
                            pan123Account = pan123Account,
                            pan115Account = pan115Account,
                            guangyaAccount = guangyaAccount,
                            ilanzouAccount = ilanzouAccount,
                            lanzouAccount = lanzouAccount,
                            quarkCloudViewModel = quarkCloudViewModel,
                            ucCloudViewModel = ucCloudViewModel,
                            xunleiCloudViewModel = xunleiCloudViewModel,
                            baiduCloudViewModel = baiduCloudViewModel,
                            c139CloudViewModel = c139CloudViewModel,
                            pan123CloudViewModel = pan123CloudViewModel,
                            pan115CloudViewModel = pan115CloudViewModel,
                            guangyaCloudViewModel = guangyaCloudViewModel,
                            ilanzouCloudViewModel = ilanzouCloudViewModel,
                            lanzouCloudViewModel = lanzouCloudViewModel,
                            driveQuotaViewModel = driveQuotaViewModel,
                            multiAccount = multiAccountViewModel,
                            videoLoginState = videoLoginState,
                            videoAccounts = videoAccounts,
                            onVideoLogin = { platform ->
                                videoLoginNewAccount = false
                                // 与网盘一致：先清该平台的 JCEF 登录态，避免登录页自动登回旧账号
                                CookieCleaner.clearCookiesForDomains(listOf(platform.cookieDomain))
                                videoLoginPlatform = platform
                            },
                            onVideoManage = { videoManagePlatform = it },
                            onAddAccount = onAddAccount,
                            onQuarkLogin = { showQuarkLogin = true },
                            onQuarkLogout = { viewModel.logout() },
                            onDownloadStarted = { currentTab = MainTab.Download },
                            onUCLogin = { showUCLogin = true },
                            onUCLogout = { ucViewModel.logout() },
                            onXunleiLogin = { showXunleiLogin = true },
                            onXunleiLogout = { xunleiViewModel.logout() },
                            onBaiduLogin = { showBaiduLogin = true },
                            onBaiduLogout = { baiduViewModel.logout() },
                            onC139Login = { showC139Login = true },
                            onC139Logout = { c139ViewModel.logout() },
                            onPan123Login = { showPan123Login = true },
                            onPan123Logout = { pan123ViewModel.logout() },
                            onPan115Login = { showPan115Login = true },
                            onPan115Logout = { pan115ViewModel.logout() },
                            onGuangYaLogin = { showGuangYaLogin = true },
                            onGuangYaLogout = { guangyaViewModel.logout() },
                            onILanzouLogin = { showILanzouLogin = true },
                            onILanzouLogout = { ilanzouViewModel.logout() },
                            onLanzouLogin = { showLanzouLogin = true },
                            onLanzouLogout = { lanzouViewModel.logout() },
                            githubHasToken = githubHasTokenState,
                            onGitHubTokenClick = { showGitHubTokenDialog = true },
                            // 已配置 Token 点卡片主体：用 GET /user 取 login，经统一解析入口进入该账号仓库列表
                            onGitHubBrowseHome = {
                                scope.launch {
                                    val login = githubApi.getUserLogin()
                                    if (!login.isNullOrBlank()) {
                                        resolveViewModel.startResolve("https://github.com/$login", "")
                                        currentTab = MainTab.Resolve
                                    }
                                }
                            },
                            // 更多菜单「清除 Token」：先二次确认再清除
                            onGitHubClearToken = { showGitHubClearConfirm = true }
                        )
                        MainTab.Download -> DownloadScreen(scrollBehavior, downloadViewModel)
                        MainTab.Settings -> SettingsScreen(
                            scrollBehavior = scrollBehavior,
                            onThemeClick = { showTheme = true },
                            onAboutClick = { showAbout = true },
                            onSupportClick = { showSupport = true },
                            onExperimentalClick = { showExperimental = true },
                            onDownloadEngineClick = { showDownloadEngine = true },
                            onStorageClick = { showStorage = true },
                            backupManager = backupManager,
                            onDownloadUpdateApk = { url, name, fallbackUrl ->
                                scope.launch {
                                    // 镜像下载传入直连作为回退：镜像主 URL 探测失败时整任务切到原始直连
                                    downloadManager.enqueue(
                                        url = url,
                                        fileName = name,
                                        fallbackUrl = fallbackUrl ?: ""
                                    )
                                    currentTab = MainTab.Download
                                }
                            }
                        )
                    }
                }
            }
        }

        // 桌面固定横向布局：左侧导航栏 + 右侧顶栏与内容
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            Row(modifier = Modifier.fillMaxSize()) {
                MainNavigationRail(
                    currentTab = currentTab,
                    // 切 Tab 视为离开视频解析页（侧栏现在可点，不能让它一直压在新 Tab 上面）
                    onTabSelected = {
                        currentTab = it
                        videoResolveText = null
                    }
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize()
                ) {
                    // 视频解析页自带页头（返回 + 平台 + 标题），打开时让出全局大标题
                    if (videoResolveText == null) topBarContent()
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        tabContent()
                        // 视频解析结果压在本 Tab 内容之上（侧栏与全局弹窗层都留在外层）
                        if (videoResolveText != null) {
                            videoResolvePage()
                        }
                    }
                }
            }
            // 全局 Snackbar（悬浮底部居中）
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }

        // 关于云析：叠加覆盖层（淡入 + 轻微缩放过渡）
        AnimatedVisibility(
            visible = showAbout,
            enter = fadeIn(tween(220)) + scaleIn(tween(220), initialScale = 0.96f),
            exit = fadeOut(tween(160)) + scaleOut(tween(160), targetScale = 0.96f),
            modifier = Modifier.fillMaxSize()
        ) {
            AboutScreen(
                onBack = { showAbout = false },
                onPreviewOnboarding = {
                    AppContext.miscPrefs.putBoolean("onboarding_shown", false)
                    showAbout = false
                    showOnboarding = true
                }
            )
        }

        // 支持开发：叠加覆盖层
        AnimatedVisibility(
            visible = showSupport,
            enter = fadeIn(tween(220)) + scaleIn(tween(220), initialScale = 0.96f),
            exit = fadeOut(tween(160)) + scaleOut(tween(160), targetScale = 0.96f),
            modifier = Modifier.fillMaxSize()
        ) {
            SupportScreen(
                onBack = { showSupport = false }
            )
        }

        // 主题与外观：叠加覆盖层
        AnimatedVisibility(
            visible = showTheme,
            enter = fadeIn(tween(220)) + scaleIn(tween(220), initialScale = 0.96f),
            exit = fadeOut(tween(160)) + scaleOut(tween(160), targetScale = 0.96f),
            modifier = Modifier.fillMaxSize()
        ) {
            ThemeScreen(
                onBack = { showTheme = false }
            )
        }

        // 实验性功能：叠加覆盖层（二级页）
        AnimatedVisibility(
            visible = showExperimental,
            enter = fadeIn(tween(220)) + scaleIn(tween(220), initialScale = 0.96f),
            exit = fadeOut(tween(160)) + scaleOut(tween(160), targetScale = 0.96f),
            modifier = Modifier.fillMaxSize()
        ) {
            ExperimentalFeaturesScreen(
                onBack = { showExperimental = false },
                onHomeShortcutChanged = { homeShortcutsEnabled = it }
            )
        }

        // 收藏网盘链接：叠加覆盖层
        AnimatedVisibility(
            visible = showBookmarks,
            enter = fadeIn(tween(220)) + scaleIn(tween(220), initialScale = 0.96f),
            exit = fadeOut(tween(160)) + scaleOut(tween(160), targetScale = 0.96f),
            modifier = Modifier.fillMaxSize()
        ) {
            BookmarkScreen(
                viewModel = bookmarkViewModel,
                onBack = { showBookmarks = false },
                onResolve = { link, pwd ->
                    showBookmarks = false
                    currentTab = MainTab.Resolve
                    resolveViewModel.startResolve(link, pwd)
                }
            )
        }

        // 下载引擎：叠加覆盖层（二级页，从设置页进入）
        AnimatedVisibility(
            visible = showDownloadEngine,
            enter = fadeIn(tween(220)) + scaleIn(tween(220), initialScale = 0.96f),
            exit = fadeOut(tween(160)) + scaleOut(tween(160), targetScale = 0.96f),
            modifier = Modifier.fillMaxSize()
        ) {
            DownloadEngineScreen(onBack = { showDownloadEngine = false })
        }

        // 储存目录：叠加覆盖层（二级页，从设置页进入）
        AnimatedVisibility(
            visible = showStorage,
            enter = fadeIn(tween(220)) + scaleIn(tween(220), initialScale = 0.96f),
            exit = fadeOut(tween(160)) + scaleOut(tween(160), targetScale = 0.96f),
            modifier = Modifier.fillMaxSize()
        ) {
            StorageDirectoryScreen(
                onBack = { showStorage = false },
                // 目录已改、新实例已拉起：直接退出本进程（走统一的淡出退出路径）
                onRestartApp = onExitApplication
            )
        }

        // 应用内公告：叠加覆盖层（列表 ↔ 详情在页面内部切换）
        AnimatedVisibility(
            visible = showAnnouncements,
            enter = fadeIn(tween(220)) + scaleIn(tween(220), initialScale = 0.96f),
            exit = fadeOut(tween(160)) + scaleOut(tween(160), targetScale = 0.96f),
            modifier = Modifier.fillMaxSize()
        ) {
            AnnouncementScreen(
                viewModel = announcementViewModel,
                onBack = { showAnnouncements = false },
                // 启动弹窗点了「查看详情」就直接落在详情页
                initialDetailId = announcementDetailId
            )
        }

        // ===== 全屏页覆盖层（带过场动画）=====
        // 这些页面此前是「早期返回」的硬切（一帧切换，没有任何过渡），现在统一改成覆盖层 + 淡入/轻微上滑，
        // 返回时反向淡出 —— 与二级页的过渡语言保持一致。
        // ★ 为什么只有这 7 个页面在这里：内嵌 Chromium 的登录页（夸克 / UC / 百度 / 139 / 115 / 迅雷网页登录）
        //   用的是 SwingPanel（真实 AWT 子控件），Compose 侧无法对它做透明度与位移动画，
        //   强行套过渡会出现「页面淡入但浏览器区域瞬间出现」的破绽，所以那几个页面保持原样（仍是早期返回）。
        // 迅雷验证页（桌面版：系统浏览器承载验证）；放在登录页之后 → 交叉切换时它绘制在上层
        PageOverlay(visible = showXunleiVerify) {
            XunleiVerifyWebViewScreen(
                verifyUrl = xunleiVerifyUrl,
                deviceId = xunleiVerifyDeviceId,
                onResult = { success, _ ->
                    showXunleiVerify = false
                    showXunleiLogin = true // 回到登录页
                    if (success) {
                        SnackbarController.show("验证完成，正在自动登录…")
                        xunleiViewModel.retryLoginAfterVerify()
                    } else {
                        SnackbarController.show("验证未完成，请重试")
                    }
                },
                onBack = {
                    showXunleiVerify = false
                    showXunleiLogin = true
                }
            )
        }

        PageOverlay(visible = showPan123Login) {
            Pan123LoginScreen(
                viewModel = pan123ViewModel,
                onBack = { pan123ViewModel.cancelAddAccount(); showPan123Login = false },
                onSaved = { showPan123Login = false }
            )
        }

        PageOverlay(visible = showGuangYaLogin) {
            GuangYaLoginScreen(
                viewModel = guangyaViewModel,
                onBack = { guangyaViewModel.cancelAddAccount(); showGuangYaLogin = false },
                onSaved = { showGuangYaLogin = false }
            )
        }

        PageOverlay(visible = showILanzouLogin) {
            ILanzouLoginScreen(
                viewModel = ilanzouViewModel,
                onBack = { ilanzouViewModel.cancelAddAccount(); showILanzouLogin = false },
                onSaved = { showILanzouLogin = false }
            )
        }

        PageOverlay(visible = showLanzouLogin) {
            LanzouLoginScreen(
                viewModel = lanzouViewModel,
                onBack = { lanzouViewModel.cancelAddAccount(); showLanzouLogin = false },
                onSaved = { showLanzouLogin = false }
            )
        }

        // 首次启动引导页：优先级最高（放在最后 → 绘制在最上层），只做淡入淡出（整页视觉较重，位移会显晃）
        AnimatedVisibility(
            visible = showOnboarding,
            enter = fadeIn(tween(260)),
            exit = fadeOut(tween(200)),
            modifier = Modifier.fillMaxSize()
        ) {
            OnboardingScreen(
                onFinish = {
                    AppContext.miscPrefs.putBoolean("onboarding_shown", true)
                    showOnboarding = false
                },
                // 第 4 页迁移数据库目录后「立即重启」：与设置页共用同一条退出路径
                onRestartApp = onExitApplication
            )
        }

        // 全局弹窗覆盖层（FadeAlertDialog）：窗口内直接绘制，零原生窗口开销。
        // 放在根部最后 → 绘制在所有内容（含全屏覆盖层）之上。
        OverlayDialogHost()

        // 引擎安装提示（视频预览 / DASH 合轨缺 FFmpeg 时询问是否下载）：
        // 合轨在后台线程触发，必须挂在根部常驻组合里才弹得出来。
        EngineInstallPromptHost()

        // 关闭确认弹窗（退出 / 最小化到托盘 / 取消）
        CloseConfirmDialog(
            visible = showCloseDialog,
            hasActiveDownloads = closeDialogHasDownloads,
            onDismiss = { choice, remember ->
                showCloseDialog = false
                if (remember && choice != CloseChoice.CANCEL) {
                    settings.closeBehavior = when (choice) {
                        CloseChoice.TRAY -> SettingsRepository.CLOSE_BEHAVIOR_TRAY
                        CloseChoice.EXIT -> SettingsRepository.CLOSE_BEHAVIOR_EXIT
                        CloseChoice.CANCEL -> SettingsRepository.CLOSE_BEHAVIOR_ASK
                    }
                }
                when (choice) {
                    CloseChoice.TRAY -> {
                        // 等待弹窗淡出完成（140ms）后再隐藏窗口，
                        // 否则窗口恢复时弹窗残留导致闪烁
                        scope.launch {
                            kotlinx.coroutines.delay(150)
                            minimizeToTray()
                        }
                    }
                    CloseChoice.EXIT -> onExitApplication()
                    CloseChoice.CANCEL -> {}
                }
            }
        )

        // GitHub Token 配置弹窗（网盘页入口）：AES-GCM 加密存储，输入用密码可见性切换
        if (showGitHubTokenDialog) {
            var tokenInput by rememberSaveable { mutableStateOf(GitHubTokenStore.getToken() ?: "") }
            var passwordVisible by remember { mutableStateOf(false) }
            // 校验失败提示（显示在输入框下方），修改输入即清除
            var tokenError by remember { mutableStateOf<String?>(null) }
            FadeAlertDialog(
                visible = true,
                onDismissRequest = { showGitHubTokenDialog = false },
                title = { Text("GitHub Token") },
                text = {
                    Column {
                        Text(
                            text = "Token 仅用于提升 API 限额（匿名 60/小时，认证后 5000/小时）。经 AES-GCM 加密存储，不会明文保存。",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = "如何获取 Token：",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "1. 浏览器打开 GitHub，右上角头像 → Settings\n" +
                                "2. 左侧 Developer settings → Personal access tokens → Tokens (classic) → Generate new token\n" +
                                "3. 勾选 public_repo 即可浏览公开仓库；如需在主页看到自己的私有仓库，再勾选 repo\n" +
                                "4. 有效期建议选 90 天或 No expiration，生成后复制粘贴到上方输入框",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "安全提示：仅给最小权限，勿勾选删除/管理类权限；Token 不清空保存即可清除。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = tokenInput,
                            onValueChange = {
                                tokenInput = it
                                tokenError = null
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            isError = tokenError != null,
                            label = { Text("Personal Access Token") },
                            supportingText = { tokenError?.let { Text(it) } },
                            visualTransformation = if (passwordVisible) VisualTransformation.None
                            else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                    Icon(
                                        if (passwordVisible) Icons.Outlined.Visibility
                                        else Icons.Outlined.VisibilityOff,
                                        contentDescription = if (passwordVisible) "隐藏" else "显示"
                                    )
                                }
                            }
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val input = tokenInput.trim()
                        if (input.isBlank()) {
                            // 清空即清除 Token（无需联网校验）
                            GitHubTokenStore.setToken(null)
                            githubHasTokenState = false
                            showGitHubTokenDialog = false
                            SnackbarController.show("已清除 GitHub Token")
                        } else {
                            // 保存前先校验：无效 Token 会让 GitHub 拒绝之后的所有请求（含公开仓库解析）
                            scope.launch {
                                when (val check = githubApi.validateToken(input)) {
                                    is TokenCheck.Valid -> {
                                        GitHubTokenStore.setToken(input)
                                        githubHasTokenState = true
                                        showGitHubTokenDialog = false
                                        SnackbarController.show("GitHub Token 已保存（@${check.login}）")
                                    }
                                    TokenCheck.Invalid -> tokenError = "Token 无效或已过期，请重新生成后再保存"
                                    TokenCheck.Unknown -> tokenError = "无法校验 Token（网络异常），请联网后重试"
                                }
                            }
                        }
                    }) { Text("保存") }
                },
                dismissButton = {
                    TextButton(onClick = { showGitHubTokenDialog = false }) { Text("取消") }
                }
            )
        }

        // 清除 GitHub Token 二次确认（网盘页更多菜单）
        if (showGitHubClearConfirm) {
            FadeAlertDialog(
                visible = true,
                onDismissRequest = { showGitHubClearConfirm = false },
                title = { Text("清除 GitHub Token？") },
                text = { Text("清除后 GitHub API 回退匿名限额（60 次/小时/IP）。") },
                confirmButton = {
                    TextButton(onClick = {
                        GitHubTokenStore.setToken(null)
                        githubHasTokenState = false
                        showGitHubClearConfirm = false
                        SnackbarController.show("已清除 GitHub Token")
                    }) { Text("清除", color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = {
                    TextButton(onClick = { showGitHubClearConfirm = false }) { Text("取消") }
                }
            )
        }

        // 启动公告弹窗：展示未读的置顶公告（没有则最新未读）。两个出口都算已读，见 AnnouncementViewModel.consumePopup
        popupAnnouncement?.let { announcement ->
            AnnouncementPopupDialog(
                announcement = announcement,
                onDetail = {
                    announcementDetailId = announcement.id
                    showAnnouncements = true
                    announcementViewModel.consumePopup()
                },
                onDismiss = { announcementViewModel.consumePopup() }
            )
        }

        // 启动更新弹窗：与公告弹窗互斥（公告优先），避免两个覆盖层叠在一起看不清
        if (popupAnnouncement == null) {
            startupUpdateRelease?.let { release ->
                UpdateDialog(
                    currentVersion = UpdateChecker.currentVersion(),
                    release = release,
                    // 下载走应用内置下载器（与设置页手动检查更新同一条路径），完成后切到下载页看进度
                    onDownloadAsset = { url, name ->
                        startupUpdateRelease = null
                        scope.launch {
                            downloadManager.enqueue(url = url, fileName = name)
                            currentTab = MainTab.Download
                        }
                    },
                    // 镜像站下载：用当前生效的镜像前缀（自动选最快 / 用户固定），GitHub 直连作为失败回退
                    onDownloadMirrorAsset = { url, name ->
                        startupUpdateRelease = null
                        val prefix = GitHubMirror.activePrefix()
                        scope.launch {
                            downloadManager.enqueue(
                                url = UpdateChecker.mirrorUrl(url, prefix),
                                fileName = name,
                                fallbackUrl = url
                            )
                            currentTab = MainTab.Download
                        }
                    },
                    onLater = { startupUpdateRelease = null },
                    onIgnore = {
                        // 与设置页「不再提示该版本」同一个键：按 tag 记住，之后不再为这个版本弹窗
                        // （将来有更新的版本仍会提示，不是全局关闭更新检查）
                        AppContext.miscPrefs.put("ignored_version", release.tagName)
                        startupUpdateRelease = null
                    },
                    mandatory = release.isMandatoryFor(UpdateChecker.currentVersion())
                )
            }
        }

        // 剪贴板分享链接检测器：主窗口失焦时若剪贴板有分享链接，触发右下角弹窗。
        // Detector 在 Box 内部以绑定 MainScreen 生命周期；Popup 是独立顶层 Window。
        // 可在「设置 → 用户体验」中关闭；切换 Tab 返回时重新读取设置值。
        ClipboardLinkDetector(settings = settings)
        ClipboardLinkPopup()

        // 托盘菜单由 Main.kt 的 Tray 组件处理（原生 Win32 菜单）

        // 消费 ClipboardLinkController.openRequest：用户在弹窗点「打开」时切到解析页 + 启动解析 + 主窗口前台。
        // ClipboardLinkController.openRequest 是 mutableStateOf（Compose State），但用轮询消费更可靠。
        LaunchedEffect(Unit) {
            while (true) {
                kotlinx.coroutines.delay(200)
                ClipboardLinkController.consumeOpen()?.let { d ->
                    currentTab = MainTab.Resolve
                    // 链接带提取码才解析得动；没带时解析会失败，失败后 ViewModel 会把原文回填到解析输入框，
                    // 用户只要再补一个提取码即可重试（不必重新粘贴链接）—— 见 ResolveViewModel.prefillRequest
                    resolveViewModel.startResolve(d.text, d.parsed.pwd)
                    // 置前：先「还原」（窗口可能被最小化到任务栏，此时 toFront() 对它无效）再置顶置前
                    DesktopActions.bringWindowToFront(ClipboardLinkController.mainWindow)
                    // 弹窗是独立顶层窗口，此刻正在销毁、可能把前台还回去 → 稍后再补一次置前
                    kotlinx.coroutines.delay(180)
                    DesktopActions.bringWindowToFront(ClipboardLinkController.mainWindow)
                }
            }
        }
        }
    }
}

/**
 * 全屏页的进出过渡：淡入 + 轻微上滑（返回时反向）。
 *
 * 尺度刻意做小（位移只有高度的 1/20、时长 200/140ms）：全屏页位移过大在桌面大窗口上会显得晃，
 * 轻微上滑只是给「页面被推上来」一点方向感，与二级页的淡入淡出（220/160ms）配合不会互相打架。
 *
 * ★ 只适用于**纯 Compose** 的页面：内嵌 SwingPanel（JCEF）的页面不能套这个 —— AWT 子控件不吃
 *   Compose 的 alpha/位移变换，会出现「页面淡入、浏览器区域瞬间出现」的破绽。
 */
@Composable
private fun PageOverlay(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(200)) + slideInVertically(tween(200)) { it / 20 },
        exit = fadeOut(tween(140)) + slideOutVertically(tween(140)) { it / 20 },
        modifier = Modifier.fillMaxSize()
    ) {
        content()
    }
}

/**
 * 侧边导航栏（桌面固定）：4 个主 Tab，未选中项只显示图标。
 */
@Composable
private fun MainNavigationRail(
    currentTab: MainTab,
    onTabSelected: (MainTab) -> Unit
) {
    NavigationRail {
        MainTab.values().forEach { tab ->
            NavigationRailItem(
                selected = currentTab == tab,
                onClick = { onTabSelected(tab) },
                icon = {
                    Icon(
                        imageVector = if (currentTab == tab) tab.selectedIcon else tab.unselectedIcon,
                        contentDescription = tab.title
                    )
                },
                label = { Text(tab.title) },
                alwaysShowLabel = currentTab == tab
            )
        }
    }
}
