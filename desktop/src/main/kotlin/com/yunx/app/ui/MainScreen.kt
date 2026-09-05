package com.yunx.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yunx.app.AppContext
import com.yunx.app.data.backup.AuthBackupManager
import com.yunx.app.data.db.AppDatabase
import com.yunx.app.data.download.ChunkDownloader
import com.yunx.app.data.download.DownloadManager
import com.yunx.app.data.network.BaiduApi
import com.yunx.app.data.network.C139Api
import com.yunx.app.data.network.HttpClients
import com.yunx.app.data.network.Pan123Api
import com.yunx.app.data.network.QuarkApi
import com.yunx.app.data.network.UCApi
import com.yunx.app.data.network.XunleiApi
import com.yunx.app.data.prefs.SettingsRepository
import com.yunx.app.data.repository.BaiduAccountRepository
import com.yunx.app.data.repository.BaiduResolveRepository
import com.yunx.app.data.repository.C139AccountRepository
import com.yunx.app.data.repository.C139ResolveRepository
import com.yunx.app.data.repository.Pan123AccountRepository
import com.yunx.app.data.repository.Pan123ResolveRepository
import com.yunx.app.data.repository.QuarkAccountRepository
import com.yunx.app.data.repository.QuarkResolveRepository
import com.yunx.app.data.repository.UCAccountRepository
import com.yunx.app.data.repository.UCResolveRepository
import com.yunx.app.data.repository.XunleiAccountRepository
import com.yunx.app.data.repository.XunleiResolveRepository
import com.yunx.app.ui.components.OverlayDialogHost
import com.yunx.app.ui.login.BaiduLoginScreen
import com.yunx.app.ui.login.C139LoginScreen
import com.yunx.app.ui.login.Pan123LoginScreen
import com.yunx.app.ui.login.QuarkLoginScreen
import com.yunx.app.ui.login.UCLoginScreen
import com.yunx.app.ui.login.XunleiLoginScreen
import com.yunx.app.ui.login.XunleiVerifyWebViewScreen
import com.yunx.app.ui.navigation.MainTab
import com.yunx.app.ui.screens.AboutScreen
import com.yunx.app.ui.screens.BookmarkScreen
import com.yunx.app.ui.screens.DownloadScreen
import com.yunx.app.ui.screens.DriveScreen
import com.yunx.app.ui.screens.OnboardingScreen
import com.yunx.app.ui.screens.ResolveScreen
import com.yunx.app.ui.screens.SettingsScreen
import com.yunx.app.ui.screens.SupportScreen
import com.yunx.app.ui.screens.ThemeScreen
import com.yunx.app.ui.viewmodel.BaiduAccountViewModel
import com.yunx.app.ui.viewmodel.BaiduCloudViewModel
import com.yunx.app.ui.viewmodel.BookmarkViewModel
import com.yunx.app.ui.viewmodel.C139AccountViewModel
import com.yunx.app.ui.viewmodel.C139CloudViewModel
import com.yunx.app.ui.viewmodel.DownloadViewModel
import com.yunx.app.ui.viewmodel.DriveQuotaViewModel
import com.yunx.app.ui.viewmodel.Pan123AccountViewModel
import com.yunx.app.ui.viewmodel.Pan123CloudViewModel
import com.yunx.app.ui.viewmodel.QuarkAccountViewModel
import com.yunx.app.ui.viewmodel.QuarkCloudViewModel
import com.yunx.app.ui.viewmodel.ResolveViewModel
import com.yunx.app.ui.viewmodel.UCAccountViewModel
import com.yunx.app.ui.viewmodel.UCCoudViewModel
import com.yunx.app.ui.viewmodel.XunleiAccountViewModel
import com.yunx.app.ui.viewmodel.XunleiCloudViewModel
import kotlinx.coroutines.launch

/**
 * 桌面版主页框架：
 * - 左侧 NavigationRail（桌面窗口始终横向布局）+ 顶部可折叠大标题；
 * - 4 个主 Tab（解析 / 网盘 / 下载 / 设置），SaveableStateHolder 保存各页状态；
 * - 全屏覆盖层：首次引导 / 各平台登录 / 关于 / 支持 / 主题 / 收藏。
 * 相比 Android 版移除：通知权限、电池优化引导、存储权限、更新检测、横竖屏切换。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    var currentTab by rememberSaveable { mutableStateOf(MainTab.Resolve) }
    var showQuarkLogin by rememberSaveable { mutableStateOf(false) }
    var showUCLogin by rememberSaveable { mutableStateOf(false) }
    var showXunleiLogin by rememberSaveable { mutableStateOf(false) }
    var showXunleiVerify by rememberSaveable { mutableStateOf(false) }
    var xunleiVerifyUrl by rememberSaveable { mutableStateOf("") }
    var xunleiVerifyDeviceId by rememberSaveable { mutableStateOf("") }
    var showBaiduLogin by rememberSaveable { mutableStateOf(false) }
    var showC139Login by rememberSaveable { mutableStateOf(false) }
    var showPan123Login by rememberSaveable { mutableStateOf(false) }
    var showAbout by rememberSaveable { mutableStateOf(false) }
    var showSupport by rememberSaveable { mutableStateOf(false) }
    var showTheme by rememberSaveable { mutableStateOf(false) }
    var showBookmarks by rememberSaveable { mutableStateOf(false) }
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
    val db = remember { AppDatabase.get() }
    val settings = remember { SettingsRepository() }
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
    val backupManager = remember {
        AuthBackupManager(
            db.quarkAccountDao(),
            db.ucAccountDao(),
            db.xunleiAccountDao(),
            db.baiduAccountDao(),
            db.c139AccountDao(),
            db.pan123AccountDao()
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
            retryCountProvider = { settings.downloadRetryCount }
        )
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
        else xunleiApi.refreshToken(acc.refreshToken, deviceId)?.also { (at, nrt) ->
            xunleiRepository.updateTokens(at, nrt)
        }
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
            pan123Api, { pan123Repository.getAccount()?.accessToken }
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
                else xunleiApi.refreshToken(acc.refreshToken, acc.deviceId)?.also { (at, nrt) ->
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
            downloadManager,
            db.bookmarkDao()
        )
    )
    val downloadViewModel: DownloadViewModel = viewModel(
        factory = DownloadViewModel.Factory(downloadManager)
    )
    val bookmarkViewModel: BookmarkViewModel = viewModel(
        factory = BookmarkViewModel.Factory(db.bookmarkDao())
    )
    val quarkAccount by viewModel.quarkAccount.collectAsState()
    val ucAccount by ucViewModel.ucAccount.collectAsState()
    val xunleiAccount by xunleiViewModel.xunleiAccount.collectAsState()
    val baiduAccount by baiduViewModel.baiduAccount.collectAsState()
    val c139Account by c139ViewModel.c139Account.collectAsState()
    val pan123Account by pan123ViewModel.pan123Account.collectAsState()

    // 解析页发起下载后，自动切换到「下载」Tab
    LaunchedEffect(resolveViewModel.downloadStarted) {
        if (resolveViewModel.downloadStarted) {
            currentTab = MainTab.Download
            resolveViewModel.consumeDownloadStarted()
        }
    }

    // 首次启动引导页：全屏覆盖（优先级最高）
    if (showOnboarding) {
        OnboardingScreen(
            onFinish = {
                AppContext.miscPrefs.putBoolean("onboarding_shown", true)
                showOnboarding = false
            }
        )
        return
    }

    // 夸克登录页：全屏覆盖
    if (showQuarkLogin) {
        QuarkLoginScreen(
            viewModel = viewModel,
            onBack = { showQuarkLogin = false },
            onSaved = { showQuarkLogin = false }
        )
        return
    }

    // UC 登录页：全屏覆盖
    if (showUCLogin) {
        UCLoginScreen(
            viewModel = ucViewModel,
            onBack = { showUCLogin = false },
            onSaved = { showUCLogin = false }
        )
        return
    }

    // 迅雷登录页：全屏覆盖（账号+密码，可能触发短信验证）
    if (showXunleiLogin) {
        XunleiLoginScreen(
            viewModel = xunleiViewModel,
            onBack = { showXunleiLogin = false },
            onSaved = { showXunleiLogin = false },
            onVerify = { url, deviceId ->
                xunleiVerifyUrl = url
                xunleiVerifyDeviceId = deviceId
                showXunleiLogin = false
                showXunleiVerify = true
            }
        )
        return
    }

    // 迅雷验证页（桌面版：系统浏览器承载验证）
    if (showXunleiVerify) {
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
        return
    }

    // 百度登录页：全屏覆盖（粘贴 Cookie 登录）
    if (showBaiduLogin) {
        BaiduLoginScreen(
            viewModel = baiduViewModel,
            onBack = { showBaiduLogin = false },
            onSaved = { showBaiduLogin = false }
        )
        return
    }

    // 139 登录页：全屏覆盖（粘贴 Cookie 登录）
    if (showC139Login) {
        C139LoginScreen(
            viewModel = c139ViewModel,
            onBack = { showC139Login = false },
            onSaved = { showC139Login = false }
        )
        return
    }

    // 123 登录页：全屏覆盖（账号+密码表单登录换 JWT）
    if (showPan123Login) {
        Pan123LoginScreen(
            viewModel = pan123ViewModel,
            onBack = { showPan123Login = false },
            onSaved = { showPan123Login = false }
        )
        return
    }

    // 折叠标题状态提升到本层：跨页面共享
    val topAppBarState = rememberTopAppBarState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(topAppBarState)

    // 全局 Snackbar 宿主
    val snackbarHostState = rememberGlobalSnackbarHostState()

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
                    // 解析页标题右上角：收藏网盘链接入口
                    if (currentTab == MainTab.Resolve) {
                        IconButton(onClick = { showBookmarks = true }) {
                            Icon(Icons.Outlined.Bookmarks, contentDescription = "收藏网盘链接")
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
                transitionSpec = {
                    val forward = targetState.ordinal > initialState.ordinal
                    if (forward) {
                        (fadeIn(tween(220)) + slideInHorizontally(tween(220)) { it / 4 })
                            .togetherWith(fadeOut(tween(160)) + slideOutHorizontally(tween(160)) { -it / 4 })
                    } else {
                        (fadeIn(tween(220)) + slideInHorizontally(tween(220)) { -it / 4 })
                            .togetherWith(fadeOut(tween(160)) + slideOutHorizontally(tween(160)) { it / 4 })
                    }
                },
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
                            pan123CloudViewModel
                        )
                        MainTab.Drive -> DriveScreen(
                            scrollBehavior = scrollBehavior,
                            quarkAccount = quarkAccount,
                            ucAccount = ucAccount,
                            xunleiAccount = xunleiAccount,
                            baiduAccount = baiduAccount,
                            c139Account = c139Account,
                            pan123Account = pan123Account,
                            quarkCloudViewModel = quarkCloudViewModel,
                            ucCloudViewModel = ucCloudViewModel,
                            xunleiCloudViewModel = xunleiCloudViewModel,
                            baiduCloudViewModel = baiduCloudViewModel,
                            c139CloudViewModel = c139CloudViewModel,
                            pan123CloudViewModel = pan123CloudViewModel,
                            driveQuotaViewModel = driveQuotaViewModel,
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
                            onPan123Logout = { pan123ViewModel.logout() }
                        )
                        MainTab.Download -> DownloadScreen(scrollBehavior, downloadViewModel)
                        MainTab.Settings -> SettingsScreen(
                            scrollBehavior = scrollBehavior,
                            onThemeClick = { showTheme = true },
                            onAboutClick = { showAbout = true },
                            onSupportClick = { showSupport = true },
                            backupManager = backupManager,
                            onDownloadUpdateApk = { url, name ->
                                scope.launch {
                                    downloadManager.enqueue(url = url, fileName = name)
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
                    onTabSelected = { currentTab = it }
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize()
                ) {
                    topBarContent()
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        tabContent()
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

        // 全局弹窗覆盖层（FadeAlertDialog）：窗口内直接绘制，零原生窗口开销。
        // 放在根部最后 → 绘制在所有内容（含全屏覆盖层）之上。
        OverlayDialogHost()
        }
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
