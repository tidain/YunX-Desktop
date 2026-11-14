package com.yunx.app.ui.screens

import com.yunx.app.ui.theme.animateItemIfAllowed
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.mikepenz.markdown.m3.Markdown
import com.yunx.app.data.db.BookmarkEntity
import com.yunx.app.data.network.GitHubLinkParser
import com.yunx.app.data.network.GitHubMirror
import com.yunx.app.data.network.LinkInputRouter
import com.yunx.app.data.network.ShareLinkParser
import com.yunx.app.data.network.video.VideoLinkParser
import com.yunx.app.data.network.SharePlatform
import com.yunx.app.data.network.model.ShareFile
import com.yunx.app.ui.SnackbarController
import com.yunx.app.ui.components.GitHubMarkdownImageTransformer
import com.yunx.app.ui.components.rememberDownloadDirPicker
import com.yunx.app.ui.resolve.DownloadLinkDialog
import com.yunx.app.ui.resolve.LinkHistoryDialog
import com.yunx.app.ui.resolve.ShareDetailScreen
import com.yunx.app.ui.theme.compactMarkdownTypography
import com.yunx.app.ui.viewmodel.BaiduCloudViewModel
import com.yunx.app.ui.viewmodel.C139CloudViewModel
import com.yunx.app.ui.viewmodel.GuangYaCloudViewModel
import com.yunx.app.ui.viewmodel.Pan115CloudViewModel
import com.yunx.app.ui.viewmodel.Pan123CloudViewModel
import com.yunx.app.ui.viewmodel.QuarkCloudViewModel
import com.yunx.app.ui.viewmodel.ResolveUiState
import com.yunx.app.ui.viewmodel.ResolveViewModel
import com.yunx.app.ui.viewmodel.UCCoudViewModel
import com.yunx.app.ui.viewmodel.XunleiCloudViewModel
import com.yunx.app.util.DesktopActions

/**
 * 解析页：输入分享链接与提取码 → 解析 → 展示分享详情 → 获取下载直链。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResolveScreen(
    scrollBehavior: TopAppBarScrollBehavior,
    viewModel: ResolveViewModel,
    /** 夸克云盘浏览 ViewModel（分享文件转存目录选择用） */
    quarkCloudViewModel: QuarkCloudViewModel,
    /** 迅雷网盘云盘浏览 ViewModel（迅雷分享转存目录选择用） */
    xunleiCloudViewModel: XunleiCloudViewModel,
    /** 百度网盘云盘浏览 ViewModel（百度分享转存目录选择用） */
    baiduCloudViewModel: BaiduCloudViewModel,
    /** 139 网盘云盘浏览 ViewModel（139 分享转存目录选择用） */
    c139CloudViewModel: C139CloudViewModel,
    /** UC 网盘云盘浏览 ViewModel（UC 分享转存目录选择用） */
    ucCloudViewModel: UCCoudViewModel,
    /** 123 云盘浏览 ViewModel（123 分享转存目录选择用） */
    pan123CloudViewModel: Pan123CloudViewModel,
    /** 115 网盘浏览 ViewModel（115 分享转存目录选择用） */
    pan115CloudViewModel: Pan115CloudViewModel,
    /** 光鸭云盘浏览 ViewModel（光鸭分享转存目录选择用） */
    guangYaCloudViewModel: GuangYaCloudViewModel,
    /** 实验性功能：主页快捷方式开关（默认关闭；关闭时不显示快捷区域，主页保持现状） */
    homeShortcutsEnabled: Boolean = false,
    /** 主页快捷方式数据源（复用收藏仓库的收藏列表，不新造存储） */
    homeShortcuts: List<BookmarkEntity> = emptyList(),
    /** 打开「收藏网盘链接」页管理收藏 */
    onManageShortcuts: () -> Unit = {},
    /** 识别到视频平台链接时交给宿主的视频解析页（网盘解析器不认识这些域名） */
    onVideoLink: (String) -> Unit = {},
    /** 识别到磁力 / ed2k / 本地种子文件时交给宿主：切到下载页并预填「添加下载任务」 */
    onP2PLink: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val state = viewModel.uiState
    val downloadLink = viewModel.downloadLink
    val downloadError = viewModel.downloadError
    val pickDownloadDir = rememberDownloadDirPicker()

    // 输入框状态提升到页面层：进入详情/文件夹再返回时不清空
    var link by rememberSaveable { mutableStateOf("") }
    var pwd by rememberSaveable { mutableStateOf("") }
    var pwdEdited by rememberSaveable { mutableStateOf(false) }
    // 链接历史弹窗可见性（输入页 / 详情页都可触发，故挂载在父层）
    var showHistory by remember { mutableStateOf(false) }

    // 输入分流：磁力 / ed2k / 种子文件交给下载页；视频链接交给视频解析页；其余走网盘/GitHub 解析
    val resolveLink: (String, String?) -> Unit = { text, code ->
        when (LinkInputRouter.targetOf(text)) {
            LinkInputRouter.Target.P2P -> onP2PLink(text)
            LinkInputRouter.Target.VIDEO -> onVideoLink(text)
            LinkInputRouter.Target.RESOLVE -> viewModel.startResolve(text, code)
        }
    }

    // 剪贴板分享链接提示状态：待提示的剪贴板文本 + 已忽略的文本
    // 用 rememberSaveable：切换 Tab 后返回仍保留（避免「忽略后切页回来又弹」）
    var clipboardSuggestion by rememberSaveable { mutableStateOf<String?>(null) }
    var ignoredClipboard by rememberSaveable { mutableStateOf<String?>(null) }

    // 检测函数：读取剪贴板，满足条件则设置提示（三重触发：组合时 / ON_RESUME / 轮询）
    val maybeSuggestClipboard: () -> Unit = {
        val text = DesktopActions.readClipboard()
        if (text != null &&
            state is ResolveUiState.Idle &&
            text.isNotBlank() &&
            text != link &&
            text != ignoredClipboard &&
            (ShareLinkParser.parse(text) != null || GitHubLinkParser.parse(text) != null ||
                VideoLinkParser.looksLikeVideoLink(text) ||
                LinkInputRouter.targetOf(text) == LinkInputRouter.Target.P2P)
        ) {
            clipboardSuggestion = text
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        // 打开应用 / 从后台切回时检测
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) maybeSuggestClipboard()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        // 冷启动兜底：组合完成立即检测一次（避免 ON_RESUME 早于 observer 注册导致漏检）
        maybeSuggestClipboard()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // 桌面端读取剪贴板无系统限制：轻量轮询兜底（2s 一次），复制分享链接即提示
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(2000)
            maybeSuggestClipboard()
        }
    }

    // 链接变化时自动匹配提取码（用户未手动输入时）
    LaunchedEffect(link) {
        if (!pwdEdited && pwd.isEmpty()) {
            ShareLinkParser.parse(link)?.pwd?.let { pwd = it }
        }
    }

    // 解析失败时把原文回填到输入框。典型场景：剪贴板弹窗点「打开」，但链接没带提取码导致解析失败 ——
    // 回填后用户只要再补一个提取码即可重试，不必把链接重新粘贴一次。
    LaunchedEffect(viewModel.prefillRequest) {
        viewModel.consumePrefill()?.let { text ->
            link = text
            // 让下面的自动匹配提取码重新生效（否则上一次的「手动输入过」会把匹配挡掉）
            pwdEdited = false
        }
    }

    // 下载错误提示
    LaunchedEffect(downloadError) {
        downloadError?.let {
            SnackbarController.show(it)
            viewModel.consumeDownloadError()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        // 状态切换过渡：输入态/加载/详情/错误之间平滑淡入淡出 + 轻微位移
        AnimatedContent(
            targetState = state,
            transitionSpec = {
                (fadeIn(tween(200)) + slideInVertically(tween(200)) { it / 20 })
                    .togetherWith(fadeOut(tween(140)))
            },
            label = "resolveState"
        ) { s ->
            when (s) {
                is ResolveUiState.Detail -> ShareDetailScreen(
            session = s.session,
            files = s.files,
            viewModel = viewModel,
            quarkCloudViewModel = quarkCloudViewModel,
            xunleiCloudViewModel = xunleiCloudViewModel,
            baiduCloudViewModel = baiduCloudViewModel,
            onShowHistory = { showHistory = true },
            c139CloudViewModel = c139CloudViewModel,
            ucCloudViewModel = ucCloudViewModel,
            pan123CloudViewModel = pan123CloudViewModel,
            pan115CloudViewModel = pan115CloudViewModel,
            guangYaCloudViewModel = guangYaCloudViewModel,
            scrollBehavior = scrollBehavior,
                    // 顶部左上角返回：退出文件页回到输入页（输入框内容保留）
                    onExit = { viewModel.backToInput() },
                    // 列表「返回上一级」：子目录回上级，根目录回输入页
                    onBack = { viewModel.navigateBack() },
                    // GitHub 专属：forked from 头部、README 底部（只在仓库首页显示，进子目录/退回账号列表不残留）
                    extraHeaderContent = if (viewModel.githubAtRepoRoot) {
                        {
                            val parent = viewModel.githubParentFullName
                            if (parent != null) {
                                TextButton(
                                    onClick = { viewModel.openGitHubParentRepo() },
                                    contentPadding = PaddingValues(vertical = 4.dp)
                                ) {
                                    Text(
                                        text = "forked from $parent",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    } else null,
                    extraFooterContent = if (viewModel.githubAtRepoRoot) {
                        {
                            val md = viewModel.githubReadme
                            val owner = viewModel.githubRepoOwner
                            val repo = viewModel.githubRepoName
                            val branch = viewModel.githubDefaultBranch
                            if (!md.isNullOrBlank() && owner != null && repo != null && branch != null) {
                                Column(modifier = Modifier.padding(top = 12.dp)) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(1.dp)
                                            .background(MaterialTheme.colorScheme.outlineVariant)
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        text = "README",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    // 与 ui/theme/Theme.kt 一致的明暗判定：README 里的 HTML <picture>
                                    // （如 Star History）需要按当前主题挑选深/浅色变体
                                    val systemDark = isSystemInDarkTheme()
                                    val isDarkTheme = when (com.yunx.app.ui.theme.ThemeController.darkMode) {
                                        1 -> false
                                        2 -> true
                                        else -> if (com.yunx.app.util.WindowsTitleBar.isWindows) {
                                            com.yunx.app.util.WindowsTitleBar.systemIsDarkMode()
                                        } else {
                                            systemDark
                                        }
                                    }
                                    // 预处理：相对链接与相对图片补全为绝对 URL（raw.githubusercontent.com）
                                    val processed = remember(md, owner, repo, branch, isDarkTheme) {
                                        GitHubMarkdownImageTransformer.preprocessReadme(md, owner, repo, branch, isDarkTheme)
                                    }
                                    // 注入自研图片加载器的镜像前缀（自动选最快 / 用户固定，统一走 GitHubMirror）
                                    GitHubMarkdownImageTransformer.mirrorPrefix = GitHubMirror.activePrefix()
                                    // 紧凑字号：与桌面排版体系一致（见 ui/theme/Type.kt）
                                    val compactTypography = remember { compactMarkdownTypography() }
                                    Markdown(
                                        content = processed,
                                        modifier = Modifier.padding(bottom = 8.dp),
                                        typography = compactTypography,
                                        imageTransformer = GitHubMarkdownImageTransformer
                                    )
                                }
                            }
                        }
                    } else null,
                    fileBadge = if (viewModel.isGitHubPlatform) {
                        { file ->
                            val label = viewModel.githubBadges[file.fid]
                            if (!label.isNullOrBlank()) {
                                GitHubBadge(label)
                            }
                        }
                    } else null,
                    // 仅 GitHub 平台启用下拉刷新当前节点
                    onRefresh = if (viewModel.isGitHubPlatform) {
                        { viewModel.refreshGitHubCurrentNode() }
                    } else null,
                    refreshing = viewModel.githubRefreshing
                )
                is ResolveUiState.Loading -> LoadingContent()
                else -> ResolveInputContent(
                    viewModel = viewModel,
                    scrollBehavior = scrollBehavior,
                    state = s,
                    link = link,
                    onLinkChange = { link = it },
                    pwd = pwd,
                    onPwdChange = {
                        pwd = it
                        pwdEdited = true
                    },
                    onClearLink = {
                        link = ""
                        pwd = ""
                        pwdEdited = false
                    },
                    onClearPwd = { pwd = "" },
                    onShowHistory = { showHistory = true },
                    // 主页快捷方式（实验性功能，默认关闭）：点击收藏直达解析
                    showShortcuts = homeShortcutsEnabled,
                    shortcuts = homeShortcuts,
                    onOpenShortcut = { bookmark ->
                        link = bookmark.link
                        pwd = bookmark.pwd
                        pwdEdited = true
                        val gh = GitHubLinkParser.parse(bookmark.link)
                        if (gh != null) {
                            viewModel.startGitHubResolve(gh)
                        } else {
                            resolveLink(bookmark.link, bookmark.pwd.takeIf { it.isNotBlank() })
                        }
                    },
                    onManageShortcuts = onManageShortcuts,
                    onResolve = resolveLink
                )
            }
        }

        // 剪贴板分享链接提示卡片（仅输入页、有待提示内容时显示，带弹出动画）
        // animatedSuggestion 保留最后提示内容，保证退出动画期间卡片不消失
        var animatedSuggestion by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(clipboardSuggestion) {
            clipboardSuggestion?.let { animatedSuggestion = it }
        }
        AnimatedVisibility(
            visible = state is ResolveUiState.Idle && clipboardSuggestion != null,
            enter = fadeIn(tween(200)) +
                slideInVertically(tween(250)) { -it / 2 } +
                scaleIn(tween(250, delayMillis = 60)),
            exit = fadeOut(tween(150)) +
                slideOutVertically(tween(200)) { -it / 2 } +
                scaleOut(tween(200)),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            animatedSuggestion?.let { suggestion ->
                val parsed = ShareLinkParser.parse(suggestion)
                val githubParsed = GitHubLinkParser.parse(suggestion)
                val videoParsed = VideoLinkParser.parse(suggestion)
                val p2pLink = LinkInputRouter.targetOf(suggestion) == LinkInputRouter.Target.P2P
                ClipboardSuggestCard(
                    // 标题按识别结果给（视频链接此前一律显示成「网盘」，用户会误解）
                    title = when {
                        githubParsed != null -> "检测到 GitHub 分享链接"
                        p2pLink -> "检测到磁力 / ed2k 下载链接"
                        parsed != null -> "检测到 ${platformLabel(parsed.platform)}分享链接"
                        videoParsed != null -> "检测到 ${videoParsed.platform.displayName} 视频链接"
                        else -> "检测到分享链接"
                    },
                    onPaste = {
                        clipboardSuggestion = null
                        when {
                            // GitHub 链接走 ViewModel 的 GitHub 解析入口（复用 ShareDetailScreen 框架）
                            githubParsed != null -> {
                                link = suggestion
                                viewModel.startGitHubResolve(githubParsed)
                            }
                            // 磁力 / ed2k / 种子文件进下载页的添加任务弹窗，不发给网盘解析
                            p2pLink -> onP2PLink(suggestion)
                            // 视频链接直接进视频解析页，不污染网盘输入框
                            videoParsed != null -> onVideoLink(suggestion)
                            else -> {
                                link = suggestion
                                pwd = parsed?.pwd.orEmpty()
                                pwdEdited = true
                                resolveLink(suggestion, parsed?.pwd)
                            }
                        }
                    },
                    onDismiss = {
                        ignoredClipboard = suggestion
                        clipboardSuggestion = null
                    }
                )
            }
        }
    }

    // 获取下载直链加载弹窗（转存/取链需要时间，避免无反馈）
    if (viewModel.isFetchingDownloadLink) {
        AlertDialog(
            onDismissRequest = { },
            confirmButton = { },
            title = { Text("获取下载链接") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "正在获取下载链接，请稍候…",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        )
    }

    // 下载直链弹窗
    downloadLink?.let { link ->
        DownloadLinkDialog(
            link = link,
            onDownload = { viewModel.startDownload(link) },
            onDismiss = { viewModel.dismissDownloadDialog() },
            saveDir = viewModel.pendingSaveDir.takeIf { it.isNotBlank() },
            onChangeSaveDir = { pickDownloadDir { viewModel.chooseDownloadDir(it) } }
        )
    }

    // 链接历史弹窗（输入页与详情页都可触发）
    LinkHistoryDialog(
        visible = showHistory,
        historyFlow = viewModel.linkHistoryFlow(),
        onDismiss = { showHistory = false },
        onSelect = { url, historyPwd ->
            showHistory = false
            // 复用剪贴板建议的解析路径：写入输入框 + 启动解析
            link = url
            pwd = historyPwd
            pwdEdited = true
            resolveLink(url, historyPwd.ifBlank { null })
        },
        onDelete = { viewModel.deleteLinkHistory(it) },
        onClear = { viewModel.clearLinkHistory() }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ResolveInputContent(
    viewModel: ResolveViewModel,
    scrollBehavior: TopAppBarScrollBehavior,
    state: ResolveUiState,
    link: String,
    onLinkChange: (String) -> Unit,
    pwd: String,
    onPwdChange: (String) -> Unit,
    onClearLink: () -> Unit,
    onClearPwd: () -> Unit,
    onShowHistory: () -> Unit,
    /** 是否显示主页快捷方式区域（实验性功能开关，默认关闭） */
    showShortcuts: Boolean,
    /** 主页快捷方式数据（收藏列表） */
    shortcuts: List<BookmarkEntity>,
    /** 点击快捷方式：直接解析该收藏链接 */
    onOpenShortcut: (BookmarkEntity) -> Unit,
    /** 打开收藏页管理快捷方式 */
    onManageShortcuts: () -> Unit,
    /** 解析入口：内部已做「视频链接 / 网盘链接」分流 */
    onResolve: (String, String?) -> Unit
) {
    val isLoading = state is ResolveUiState.Loading

    Column(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "粘贴分享链接，一键解析分享内容",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onShowHistory) {
                Icon(
                    Icons.Outlined.History,
                    contentDescription = "解析历史"
                )
            }
        }

        OutlinedTextField(
            value = link,
            onValueChange = onLinkChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("例如：https://pan.quark.cn/s/xxxx 或 迅雷口令") },
            leadingIcon = { Icon(Icons.Outlined.Link, contentDescription = null) },
            trailingIcon = {
                if (link.isNotEmpty()) {
                    IconButton(onClick = onClearLink) {
                        Icon(Icons.Filled.Close, contentDescription = "清空链接")
                    }
                }
            },
            minLines = 3,
            maxLines = 6,
            shape = MaterialTheme.shapes.large
        )

        OutlinedTextField(
            value = pwd,
            onValueChange = onPwdChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("提取码（可选）") },
            placeholder = { Text("自动识别或手动输入") },
            trailingIcon = {
                if (pwd.isNotEmpty()) {
                    IconButton(onClick = onClearPwd) {
                        Icon(Icons.Filled.Close, contentDescription = "清空提取码")
                    }
                }
            },
            singleLine = true,
            shape = MaterialTheme.shapes.large
        )

        Button(
            onClick = {
                // 优先识别 GitHub 链接（仓库 / 账号 / 文件直链），走 ViewModel 的 GitHub 解析入口
                val github = GitHubLinkParser.parse(link)
                if (github != null) {
                    viewModel.startGitHubResolve(github)
                } else {
                    onResolve(link, pwd)
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            enabled = link.isNotBlank() && !isLoading
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("解析中…")
            } else {
                Text("开始解析")
            }
        }

        if (state is ResolveUiState.Error) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = state.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }

        // 主页快捷方式（实验性功能）：收藏以横向快捷方式展示，点击直达解析；关闭时不渲染
        if (showShortcuts) {
            HomeShortcutsSection(
                bookmarks = shortcuts,
                onOpen = onOpenShortcut,
                onManage = onManageShortcuts
            )
        }
    }
}

/** 平台枚举名 → 快捷色块的简称（2 字左右，网格/横向瓦片用） */
private fun shortcutPlatformLabel(platform: String): String = when (platform) {
    "QUARK" -> "夸克"
    "UC" -> "UC"
    "XUNLEI" -> "迅雷"
    "BAIDU" -> "百度"
    "C139" -> "139"
    "PAN123" -> "123"
    "PAN115" -> "115"
    "GITHUB" -> "GitHub"
    else -> "链接"
}

/**
 * 主页快捷方式区块：解析页输入态下方，已收藏的网盘 / GitHub 条目以横向快捷方式展示，点击直达解析。
 * 仅在「设置 → 实验性功能 → 主页快捷方式」开启时渲染（默认关闭 → 主页保持现状）。
 */
@Composable
private fun HomeShortcutsSection(
    bookmarks: List<BookmarkEntity>,
    onOpen: (BookmarkEntity) -> Unit,
    onManage: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "快捷方式",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onManage) { Text("管理") }
        }
        if (bookmarks.isEmpty()) {
            Text(
                text = "还没有收藏；在「收藏网盘链接」页添加后即可在主页一键解析。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(bookmarks, key = { it.id }) { bookmark ->
                    HomeShortcutTile(
                        bookmark = bookmark,
                        modifier = animateItemIfAllowed(),
                        onClick = { onOpen(bookmark) }
                    )
                }
            }
        }
    }
}

/** 主页快捷方式单个瓦片：圆角色块（平台简称）+ 标题，点击直达解析 */
@Composable
private fun HomeShortcutTile(
    bookmark: BookmarkEntity,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Column(
        modifier = modifier
            .width(84.dp)
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = shortcutPlatformLabel(bookmark.platform),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = bookmark.title.ifBlank { bookmark.link },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** 全屏加载中（进入文件夹/解析中展示，避免闪回输入页） */
@Composable
private fun LoadingContent() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "加载中…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 平台名称（提示卡片展示） */
private fun platformLabel(platform: SharePlatform): String = when (platform) {
    SharePlatform.QUARK -> "夸克网盘"
    SharePlatform.UC -> "UC 网盘"
    SharePlatform.XUNLEI -> "迅雷网盘"
    SharePlatform.BAIDU -> "百度网盘"
    SharePlatform.C139 -> "139 网盘"
    SharePlatform.PAN123 -> "123云盘"
    SharePlatform.PAN115 -> "115网盘"
    SharePlatform.GUANGYA -> "光鸭云盘"
    SharePlatform.ILANZOU -> "蓝奏云优享版"
    SharePlatform.LANZOU -> "蓝奏云"
    SharePlatform.GITHUB -> "GitHub"
}

/** 剪贴板分享链接提示卡片：检测到分享链接时，询问是否粘贴解析 */
@Composable
private fun ClipboardSuggestCard(
    title: String,
    onPaste: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.Link,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        text = "是否粘贴到解析框并开始解析？",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss) {
                    Text("忽略", color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Spacer(modifier = Modifier.width(4.dp))
                Button(
                    onClick = onPaste,
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Text("粘贴并解析")
                }
            }
        }
    }
}

/**
 * GitHub 文件徽章：紧凑彩色标签。
 * 最新=绿、预发布=橙、草稿=灰、其余（Fork/语言）=中性次要色。
 */
@Composable
private fun GitHubBadge(label: String) {
    // 统一走主题容器色：硬编码的绿/橙/灰在深色模式下对比度不足（且与主题种子色不协调）
    val (bg, fg) = when (label) {
        "最新" -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        "预发布" -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        "草稿" -> MaterialTheme.colorScheme.surfaceContainerHighest to MaterialTheme.colorScheme.onSurface
        else -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
    }
    Box(
        modifier = Modifier
            .background(bg, shape = RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = fg
        )
    }
}