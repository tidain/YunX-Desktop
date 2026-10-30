package com.yunx.app.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yunx.app.AppContext
import com.yunx.app.data.db.AppDatabase
import com.yunx.app.data.db.DownloadTaskEntity
import com.yunx.app.data.download.DownloadCacheMaintenance
import com.yunx.app.data.prefs.SettingsRepository
import com.yunx.app.data.security.DbEncryptionLevel
import com.yunx.app.data.security.DbEncryptionManager
import com.yunx.app.data.storage.StorageMigrator
import com.yunx.app.data.storage.StoragePreset
import com.yunx.app.ui.BackHandler
import com.yunx.app.ui.SnackbarController
import com.yunx.app.ui.components.FadeAlertDialog
import com.yunx.app.ui.GlobalSnackbarHost
import com.yunx.app.util.DesktopActions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 要搬迁的是哪一类目录 */
private enum class StorageKind { CACHE, DB }

/** 一次待确认的搬迁 */
private data class MigrateRequest(val kind: StorageKind, val source: File, val target: File)

/**
 * 储存目录（二级页，设置 → 储存目录）。
 *
 * 三块内容：
 * 1. **下载保存目录**：下载完成后的落盘位置（从设置主页移到这里）；
 * 2. **下载缓存目录**：分片 / 合并临时文件 / JCEF 浏览器数据（含网页版登录态、~200MB Chromium 解包）；
 * 3. **数据库位置**：`yunx.db` 与 `credential.key`（库内凭证用它加密，必须跟着库一起走）。
 *
 * ★ 目录改动**一律需要重启应用生效**：`AppContext` 在启动时只解析一次目录，运行期不再变。
 *   切换时会把旧数据**自动搬到新位置**（拷贝；旧文件留到下次启动再删 —— 本进程还占用着它们，Windows 删不掉）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageDirectoryScreen(
    onBack: () -> Unit,
    /** 迁移完成后「立即重启」：由 MainScreen 提供（拉起新实例 + 退出本进程） */
    onRestartApp: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 列表 / 四宫格切换（记住用户的选择）
    var gridMode by rememberSaveable { mutableStateOf(false) }

    BackHandler { onBack() }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.surface,
        snackbarHost = { GlobalSnackbarHost() },
        topBar = {
            TopAppBar(
                title = { Text("储存目录", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    // 右上角：在「四宫格 / 列表」之间切换
                    IconButton(onClick = { gridMode = !gridMode }) {
                        if (gridMode) {
                            Icon(Icons.AutoMirrored.Outlined.ViewList, contentDescription = "切换为列表")
                        } else {
                            Icon(Icons.Outlined.GridView, contentDescription = "切换为四宫格")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        StorageSettingsPanel(
            onRestartApp = onRestartApp,
            gridMode = gridMode,
            modifier = Modifier.padding(innerPadding)
        )
    }
}

/**
 * 储存设置面板：下载保存目录 / 下载缓存目录 / 数据库位置 / 数据库加密等级。
 *
 * ★ 抽成独立 Composable：「设置 → 储存目录」二级页与「首次启动引导页第 4 页」都用它，
 *   以免两处逻辑分叉。面板自带滚动与全屏遮罩，不含 Scaffold / 顶栏。
 *
 * @param showCacheDir 是否显示「下载缓存目录」区块（引导页只展示保存目录 / 数据库位置 / 加密等级，传 false）
 */
@Composable
fun StorageSettingsPanel(
    onRestartApp: () -> Unit,
    showCacheDir: Boolean = true,
    /** 四宫格（两列）还是列表（单列）；引导页固定列表，设置页由右上角按钮切换 */
    gridMode: Boolean = false,
    /** 内容内边距：二级页用默认 16dp；引导页第 4 页传 horizontal = 28dp 以与页面标题左右对齐 */
    contentPadding: PaddingValues = PaddingValues(16.dp),
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val settingsRepo = remember { SettingsRepository() }

    // 下载保存目录（沿用原有设置项）
    var downloadDir by remember { mutableStateOf(settingsRepo.downloadDirUri) }

    // 默认位置（不受配置影响，用于判定预设）
    val defaultCacheDir = remember { File(AppContext.dataDir, "cache") }
    val defaultDbDir = remember { AppContext.dataDir }
    val appCacheDir = remember { AppContext.appCacheDirPreset() }
    val appDbDir = remember { AppContext.appDataDirPreset() }

    // 当前生效位置（重启后才变）与已配置位置（立刻持久化）
    val effectiveCacheDir = AppContext.cacheDir
    val effectiveDbDir = AppContext.dbDir
    var configuredCache by remember { mutableStateOf(settingsRepo.storageCacheDir) }
    var configuredDb by remember { mutableStateOf(settingsRepo.storageDbDir) }
    val configuredCacheDir = configuredCache?.let { File(it) } ?: defaultCacheDir
    val configuredDbDir = configuredDb?.let { File(it) } ?: defaultDbDir

    var busy by remember { mutableStateOf(false) }
    var busyText by remember { mutableStateOf("正在搬迁数据，请勿关闭应用…") }
    var request by remember { mutableStateOf<MigrateRequest?>(null) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var resultText by remember { mutableStateOf<String?>(null) }
    // 数据库加密等级（立即生效，不需要重启）；显示**实际**档位而非偏好值
    var encLevel by remember { mutableStateOf(DbEncryptionManager.effectiveLevel()) }
    var pendingLevel by remember { mutableStateOf<DbEncryptionLevel?>(null) }

    // 下载缓存占用 / 手动清理（Issue #17：除了自动清理，也给用户一个立刻回收空间的入口）
    var cacheUsage by remember { mutableStateOf<DownloadCacheMaintenance.Usage?>(null) }
    var cleanCacheConfirm by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        cacheUsage = withContext(Dispatchers.IO) { DownloadCacheMaintenance.usage() }
    }

    /** 清理缓存：保留正在下载/等待中任务的分片，暂停/失败任务的分片删掉（它们下次从头下） */
    fun cleanDownloadCache() {
        scope.launch {
            busy = true
            busyText = "正在清理下载缓存…"
            try {
                val dao = AppDatabase.get().downloadTaskDao()
                val keep = dao.listAll()
                    .filter {
                        it.status == DownloadTaskEntity.STATUS_DOWNLOADING ||
                            it.status == DownloadTaskEntity.STATUS_PENDING
                    }
                    .map { it.id }
                    .toSet()
                val result = withContext(Dispatchers.IO) {
                    val chunks = DownloadCacheMaintenance.cleanChunkDirsExcept(keep)
                    // 实验性直写：目标目录里的 .part/.plan/.done 也一并清理（保留进行中的任务）
                    val direct = DownloadCacheMaintenance.cleanDirectParts(dao, keep)
                    DownloadCacheMaintenance.CleanResult(
                        freedBytes = chunks.freedBytes + direct.freedBytes,
                        deletedDirs = chunks.deletedDirs + direct.deletedDirs
                    )
                }
                cacheUsage = withContext(Dispatchers.IO) { DownloadCacheMaintenance.usage() }
                SnackbarController.show(
                    if (result.freedBytes > 0) {
                        "已清理下载缓存，释放 ${DownloadCacheMaintenance.formatBytes(result.freedBytes)}"
                    } else {
                        "没有可清理的下载缓存"
                    }
                )
            } catch (e: Exception) {
                SnackbarController.show("清理失败：${e.message ?: "未知错误"}")
            } finally {
                busy = false
            }
        }
    }

    /** 写配置：默认位置存 null（跟随数据根目录），其余存绝对路径 */
    fun persist(kind: StorageKind, target: File) {
        val isDefault = samePath(target, if (kind == StorageKind.CACHE) defaultCacheDir else defaultDbDir)
        val value = if (isDefault) null else target.absolutePath
        if (kind == StorageKind.CACHE) {
            settingsRepo.storageCacheDir = value
            configuredCache = value
        } else {
            settingsRepo.storageDbDir = value
            configuredDb = value
        }
    }

    /** 点击预设 / 选定自定义目录后的统一入口 */
    fun choose(kind: StorageKind, target: File) {
        val configured = if (kind == StorageKind.CACHE) configuredCacheDir else configuredDbDir
        val effective = if (kind == StorageKind.CACHE) effectiveCacheDir else effectiveDbDir
        if (samePath(target, configured)) {
            SnackbarController.show("已经是该设置")
            return
        }
        // 目标就是当前正在用的目录：不需要搬数据，只把配置写回去（也顺带修掉「搬迁后又切回来」的残留配置）
        if (samePath(target, effective)) {
            persist(kind, target)
            SnackbarController.show("已切回当前生效目录（无需搬迁）")
            return
        }
        val reason = if (kind == StorageKind.DB) {
            StorageMigrator.checkDatabaseTarget(target)
        } else {
            StorageMigrator.checkWritable(target)
        }
        if (reason != null) {
            errorText = reason
            return
        }
        request = MigrateRequest(kind, effective, target)
    }

    /** 执行搬迁（先拷贝、成功后写配置；失败一律不写配置） */
    fun migrate(req: MigrateRequest) {
        request = null
        busy = true
        busyText = "正在搬迁数据，请勿关闭应用…"
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val report = if (req.kind == StorageKind.CACHE) {
                        StorageMigrator.migrateCache(req.source, req.target)
                    } else {
                        StorageMigrator.migrateDatabase(req.source, req.target)
                    }
                    if (req.kind == StorageKind.CACHE) {
                        StorageMigrator.rememberCleanupForCache(req.source)
                    } else {
                        StorageMigrator.rememberCleanupForDatabase(req.source)
                    }
                    report
                }
            }
            busy = false
            result
                .onSuccess { report ->
                    persist(req.kind, req.target)
                    val notes = buildList {
                        if (report.selfHealingEntries > 0) add("内嵌浏览器数据将在启动时重建")
                        if (report.skippedCount > 0) add("${report.skippedCount} 个文件被占用已跳过")
                    }
                    val noteText = if (notes.isEmpty()) "" else "（${notes.joinToString("；")}）"
                    resultText = "已搬迁 ${report.copiedFiles} 个文件（${formatBytes(report.copiedBytes)}）$noteText\n" +
                        req.target.absolutePath
                }
                .onFailure { e ->
                    errorText = "搬迁失败，目录设置未改动：${e.message ?: e.javaClass.simpleName}"
                }
        }
    }

    /** 切换加密等级：只重新包裹主密钥，秒级完成、立即生效（不需要重启） */
    fun switchLevel(target: DbEncryptionLevel) {
        pendingLevel = null
        busy = true
        busyText = "正在重新包裹密钥…"
        scope.launch {
            val result = withContext(Dispatchers.IO) { DbEncryptionManager.switchTo(target) }
            busy = false
            result
                .onSuccess {
                    encLevel = target
                    SnackbarController.show("加密等级已切换为「${target.label}」")
                }
                .onFailure { e ->
                    errorText = "切换加密等级失败，仍保持「${encLevel.label}」：${e.message ?: e.javaClass.simpleName}"
                }
        }
    }

    // ★ 底色交给调用方：设置页由外层 Scaffold 铺 surface，引导页第 4 页铺的是 Blob 流体背景。
    //   这里必须透明，否则会在引导页上盖出一块纯色矩形、把流体背景挡掉。
    Surface(modifier = modifier.fillMaxSize(), color = Color.Transparent) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(contentPadding)
            ) {
                InfoBanner(
                    "目录改动需要**重启应用**生效；切换时会把旧位置的数据自动搬到新位置" +
                        "（旧文件留到下次启动再清理）。"
                )
                Spacer(Modifier.height(16.dp))

                // 四个区块：列表模式纵向铺开，四宫格模式两列两行。
                // ★ 写成 lambda 列表（而不是顺序书写）是为了同一份内容能在两种布局里复用，不必两处维护。
                val downloadSection: @Composable () -> Unit = {
                    // ===== 下载保存目录 =====
                    SectionCard(
                        icon = { Icon(Icons.Outlined.FolderOpen, contentDescription = null) },
                        title = "下载保存目录",
                        subtitle = "下载完成后文件的落盘位置（默认：系统「下载」文件夹）"
                    ) {
                        PathRow(
                            path = downloadDir ?: DesktopActions.defaultDownloadDir.absolutePath,
                            label = if (downloadDir != null) "已自定义" else "系统默认 Download"
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                scope.launch {
                                    // 原生目录选择器在独立线程打开，避免阻塞 UI 线程
                                    val dir = withContext(Dispatchers.IO) { DesktopActions.pickDirectory() }
                                    if (dir != null) {
                                        settingsRepo.downloadDirUri = dir
                                        downloadDir = dir
                                        SnackbarController.show("下载保存目录已更新")
                                    }
                                }
                            }) { Text("选择目录") }
                            if (downloadDir != null) {
                                TextButton(onClick = {
                                    settingsRepo.downloadDirUri = null
                                    downloadDir = null
                                    SnackbarController.show("已恢复默认下载目录")
                                }) { Text("恢复默认") }
                            }
                        }
                    }
                }
                val cacheSection: @Composable () -> Unit = {
                    // ===== 下载缓存目录 =====
                    SectionCard(
                        icon = { Icon(Icons.Outlined.Folder, contentDescription = null) },
                        title = "下载缓存目录",
                        subtitle = "下载分片、合并临时文件与内嵌浏览器数据（含网页版登录态，约 200MB Chromium 解包）"
                    ) {
                        PresetChips(
                            configured = configuredCacheDir,
                            defaultDir = defaultCacheDir,
                            appDir = appCacheDir,
                            onPick = { choose(StorageKind.CACHE, it) },
                            onPickCustom = { pickCustom(scope, StorageKind.CACHE, ::choose) }
                        )
                        Spacer(Modifier.height(10.dp))
                        DirPathRows(configuredCacheDir, effectiveCacheDir)
                        Spacer(Modifier.height(6.dp))
                        TextButton(onClick = {
                            if (!DesktopActions.openDirectory(configuredCacheDir.absolutePath)) {
                                SnackbarController.show("无法打开该目录")
                            }
                        }) { Text("打开所在文件夹") }
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "当前下载分片占用：" +
                                    (cacheUsage?.let { DownloadCacheMaintenance.formatBytes(it.bytes) } ?: "计算中…"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.weight(1f))
                            TextButton(
                                onClick = { cleanCacheConfirm = true },
                                enabled = !busy && (cacheUsage?.bytes ?: 0L) > 0
                            ) { Text("清理缓存") }
                        }
                        HintRow(
                            "清理会删除暂停 / 失败任务的分片，以及实验性直写在目标目录里的 .part 临时文件" +
                                "（这些任务下次开始时从头下载）；正在下载与等待中的任务不受影响。" +
                                "任务完成时也会自动删除对应临时文件。"
                        )
                    }
                }
                val dbSection: @Composable () -> Unit = {
                    // ===== 数据库位置 =====
                    SectionCard(
                        icon = { Icon(Icons.Outlined.Storage, contentDescription = null) },
                        title = "数据库位置",
                        subtitle = "任务、收藏、历史与**网盘账号凭证**（yunx.db + credential.key，两者必须同处一地）"
                    ) {
                        PresetChips(
                            configured = configuredDbDir,
                            defaultDir = defaultDbDir,
                            appDir = appDbDir,
                            onPick = { choose(StorageKind.DB, it) },
                            onPickCustom = { pickCustom(scope, StorageKind.DB, ::choose) }
                        )
                        Spacer(Modifier.height(10.dp))
                        DirPathRows(configuredDbDir, effectiveDbDir)
                        Spacer(Modifier.height(6.dp))
                        TextButton(onClick = {
                            if (!DesktopActions.openDirectory(configuredDbDir.absolutePath)) {
                                SnackbarController.show("无法打开该目录")
                            }
                        }) { Text("打开所在文件夹") }
                        Spacer(Modifier.height(4.dp))
                        HintRow("搬迁时会连同 credential.key 一起搬走 —— 库里的凭证是用它加密的，分开会全部解不开。")
                        if (encLevel.isMachineBound && !samePath(configuredDbDir, defaultDbDir)) {
                            Spacer(Modifier.height(6.dp))
                            HintRow(
                                "⚠ 数据库已不在用户目录，而当前加密等级「${encLevel.label}」绑定本机：" +
                                    "若要把数据当作**便携版**带到别的电脑使用，请把下面的加密等级切回「宽松」，否则新机器上凭证无法解密。"
                            )
                        }
                    }
                }
                val encryptSection: @Composable () -> Unit = {
                    // ===== 数据库加密等级 =====
                    SectionCard(
                        icon = { Icon(Icons.Outlined.Lock, contentDescription = null) },
                        title = "数据库加密等级",
                        subtitle = "控制凭证主密钥（credential.key）怎么保护；改档只重新包裹密钥，不动库内数据、立即生效"
                    ) {
                        DbEncryptionLevel.entries.forEach { level ->
                            val supported = level.isSupported
                            PresetRow(
                                title = if (level == DbEncryptionLevel.NORMAL) "${level.label}（默认）" else level.label,
                                subtitle = levelDescription(level, supported),
                                selected = encLevel == level,
                                enabled = supported && !busy,
                                onClick = { if (supported && level != encLevel) pendingLevel = level }
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        HintRow(
                            "三档都只保护密钥：库内**账号凭证**始终是 AES-256-GCM 密文，" +
                                "但任务文件名 / URL / 保存路径等元数据在三档下都是明文（整库加密需要 SQLCipher，暂未支持）。"
                        )
                    }
                }

                val sections = buildList {
                    add(downloadSection)
                    if (showCacheDir) add(cacheSection)
                    add(dbSection)
                    add(encryptSection)
                }

                if (gridMode) {
                    // 四宫格：两列并排，两行
                    sections.chunked(2).forEach { pair ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            pair.forEach { section ->
                                Box(modifier = Modifier.weight(1f)) { section() }
                            }
                            // 奇数个时补一个占位，避免最后一张卡被拉成整行宽
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                } else {
                    sections.forEachIndexed { index, section ->
                        if (index > 0) Spacer(Modifier.height(16.dp))
                        section()
                    }
                }

                Spacer(Modifier.height(24.dp))
            }

            // 搬迁/改档进行中：就地遮罩 + 卡片（不用原生弹窗，避免挡住 UI 线程）
            if (busy) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Card(shape = MaterialTheme.shapes.large) {
                            Row(
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(22.dp))
                                Spacer(Modifier.width(14.dp))
                                Text(busyText)
                            }
                        }
                    }
                }
            }
        }
    }

    // 搬迁确认
    request?.let { req ->
        val isDb = req.kind == StorageKind.DB
        FadeAlertDialog(
            visible = true,
            onDismissRequest = { request = null },
            title = { Text(if (isDb) "搬迁数据库？" else "搬迁下载缓存？") },
            text = {
                Text(
                    buildString {
                        append(if (isDb) "数据库（含凭证密钥）" else "缓存数据")
                        append("将从\n${req.source.absolutePath}\n搬迁到\n${req.target.absolutePath}\n\n")
                        if (isDb) {
                            append("搬迁完成后需要重启应用才会生效。旧位置的数据库文件会在下次启动时删除。")
                            if (encLevel.isMachineBound) {
                                append(
                                    "\n\n⚠ 当前加密等级「${encLevel.label}」绑定本机：" +
                                        "若要把数据当作便携版带到别的电脑使用，请先把加密等级切回「宽松」。"
                                )
                            }
                        } else {
                            append(
                                "搬迁完成后需要重启应用才会生效。内嵌浏览器的解包文件（约 200MB）不搬、" +
                                    "会由它在启动时自动重建；网页版登录态会保留。旧目录会在下次启动时删除。"
                            )
                        }
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = { migrate(req) }) { Text("开始搬迁") }
            },
            dismissButton = {
                TextButton(onClick = { request = null }) { Text("取消") }
            }
        )
    }

    // 清理下载缓存确认
    if (cleanCacheConfirm) {
        FadeAlertDialog(
            visible = true,
            onDismissRequest = { cleanCacheConfirm = false },
            title = { Text("清理下载缓存？") },
            text = {
                Text(
                    "将删除暂停 / 失败任务的分片缓存（当前占用 " +
                        (cacheUsage?.let { DownloadCacheMaintenance.formatBytes(it.bytes) } ?: "未知") +
                        "）。这些任务下次开始时需要从 0 重新下载；正在下载与等待中的任务不受影响。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    cleanCacheConfirm = false
                    cleanDownloadCache()
                }) { Text("清理") }
            },
            dismissButton = {
                TextButton(onClick = { cleanCacheConfirm = false }) { Text("取消") }
            }
        )
    }

    // 加密等级切换确认：普通/较为严格会绑定本机，必须让用户明确知道后果
    pendingLevel?.let { target ->
        FadeAlertDialog(
            visible = true,
            onDismissRequest = { pendingLevel = null },
            title = { Text("切换为「${target.label}」？") },
            text = { Text(emphasize(levelWarning(target))) },
            confirmButton = {
                TextButton(onClick = { switchLevel(target) }) { Text("切换") }
            },
            dismissButton = {
                TextButton(onClick = { pendingLevel = null }) { Text("取消") }
            }
        )
    }

    // 搬迁成功：提示重启
    resultText?.let { text ->
        FadeAlertDialog(
            visible = true,
            onDismissRequest = { resultText = null },
            title = { Text("搬迁完成，需重启生效") },
            text = { Text(text) },
            confirmButton = {
                TextButton(onClick = {
                    val started = DesktopActions.restartApp()
                    resultText = null
                    if (started) {
                        onRestartApp()
                    } else {
                        errorText = "无法自动启动新实例（开发运行模式不支持），请手动重启应用"
                    }
                }) { Text("立即重启") }
            },
            dismissButton = {
                TextButton(onClick = { resultText = null }) { Text("稍后手动重启") }
            }
        )
    }

    // 失败 / 校验不通过
    errorText?.let { text ->
        FadeAlertDialog(
            visible = true,
            onDismissRequest = { errorText = null },
            title = { Text("无法切换目录") },
            text = { Text(text) },
            confirmButton = {
                TextButton(onClick = { errorText = null }) { Text("知道了") }
            }
        )
    }
}

// ---------- 局部 UI 组件 ----------

@Composable
private fun SectionCard(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(38.dp),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainerHighest
                ) {
                    Box(contentAlignment = Alignment.Center) { icon() }
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                    Text(
                        text = emphasize(subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

/**
 * 三个预设的并排胶囊（默认 / 应用所在目录 / 自定义）。
 * ★ 不再把三项纵向铺开：纵向三行会把卡片撑得很高，并排一行后卡片立刻变矮。
 */
@Composable
private fun PresetChips(
    configured: File,
    defaultDir: File,
    appDir: File,
    onPick: (File) -> Unit,
    onPickCustom: () -> Unit
) {
    val current = when {
        samePath(configured, appDir) -> StoragePreset.APP_DIR
        samePath(configured, defaultDir) -> StoragePreset.DEFAULT
        else -> StoragePreset.CUSTOM
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        PresetChip(
            label = "默认",
            selected = current == StoragePreset.DEFAULT,
            modifier = Modifier.weight(1f)
        ) { onPick(defaultDir) }
        PresetChip(
            label = "应用目录",
            selected = current == StoragePreset.APP_DIR,
            modifier = Modifier.weight(1f)
        ) { onPick(appDir) }
        PresetChip(
            label = "自定义",
            selected = current == StoragePreset.CUSTOM,
            modifier = Modifier.weight(1f),
            onClick = onPickCustom
        )
    }
}

/** 单个预设胶囊（参考外观页的主题模式胶囊：选中铺 secondaryContainer，未选中用容器灰） */
@Composable
private fun PresetChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val containerColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest
        },
        animationSpec = tween(180),
        label = "presetChipContainer"
    )
    val contentColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.onSecondaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = tween(180),
        label = "presetChipContent"
    )
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(containerColor)
            .selectable(selected = selected, onClick = onClick)
            .padding(vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = contentColor,
            maxLines = 1
        )
    }
}

@Composable
private fun PresetRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val highlighted = selected && enabled
    // 选中行铺一层柔和底色 + 圆角，比只换一个单选圆点更容易一眼看到当前选择
    val containerColor by animateColorAsState(
        targetValue = if (highlighted) scheme.secondaryContainer else Color.Transparent,
        animationSpec = tween(160),
        label = "presetContainer"
    )
    val titleColor = when {
        !enabled -> scheme.onSurface.copy(alpha = 0.38f)
        highlighted -> scheme.onSecondaryContainer
        else -> scheme.onSurface
    }
    val subtitleColor = when {
        !enabled -> scheme.onSurface.copy(alpha = 0.38f)
        highlighted -> scheme.onSecondaryContainer.copy(alpha = 0.85f)
        else -> scheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(containerColor)
            .heightIn(min = 48.dp)
            .selectable(selected = selected, enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = emphasize(title),
                style = MaterialTheme.typography.bodyLarge,
                color = titleColor
            )
            Text(
                text = emphasize(subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = subtitleColor,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * 目录路径预览：「当前生效」与「重启后生效」分成两条独立展示。
 *
 * ★ 不能只挂一句「重启后生效」：当配置与生效值相同（没有待生效改动）时也那样写，
 *   用户会以为还得重启；反过来把两者挤在一句里同样容易看错。分开列最清楚。
 */
@Composable
private fun DirPathRows(configuredDir: File, effectiveDir: File) {
    if (samePath(configuredDir, effectiveDir)) {
        PathRow(path = configuredDir.absolutePath, label = "当前生效")
    } else {
        PathRow(path = configuredDir.absolutePath, label = "重启后生效")
        Spacer(Modifier.height(6.dp))
        PathRow(path = effectiveDir.absolutePath, label = "当前生效（重启前）")
    }
}

/** 当前路径展示（等宽、单行省略、可选中复制） */
@Composable
private fun PathRow(path: String, label: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHighest
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = path,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun HintRow(text: String) {
    Text(
        text = emphasize(text),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        lineHeight = 18.sp
    )
}

/** 顶部说明条：圆角底 + 信息图标，比裸段落更耐看 */
@Composable
private fun InfoBanner(text: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Outlined.Info,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = emphasize(text),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                lineHeight = 18.sp
            )
        }
    }
}

// ---------- 工具 ----------

/** 各档位的副标题（说明「库文件 / 凭证列 / 密钥」三层各是什么状态） */
private fun levelDescription(level: DbEncryptionLevel, supported: Boolean): String = when (level) {
    DbEncryptionLevel.LOOSE ->
        "库明文 · 凭证列加密 · 密钥明文存放。换机器 / 便携版必须用这档"
    DbEncryptionLevel.NORMAL ->
        "库明文 · 凭证列加密 · 密钥用本机派生密钥包裹（绑定本机，换机器解不开）"
    DbEncryptionLevel.STRICT ->
        "库明文 · 凭证列加密 · 密钥用 Windows DPAPI 保护（绑定当前 Windows 用户）"
    DbEncryptionLevel.STRICTEST ->
        if (supported) "库整体加密 + DPAPI 保护密钥" else "库整体加密（SQLCipher）—— 暂未支持"
}

/** 切换档位前的后果提示 */
private fun levelWarning(target: DbEncryptionLevel): String = when (target) {
    DbEncryptionLevel.LOOSE ->
        "密钥将**以明文**存放在 credential.key 中：任何能读到该文件的人都能解开网盘凭证。" +
            "只在需要用便携版 / 换机器时选择。"
    DbEncryptionLevel.NORMAL ->
        "密钥将用**本机派生密钥**包裹：绑定当前这台机器。\n" +
            "⚠ 之后把数据库/密钥拷到别的电脑、或重装系统，凭证将无法解密（表现为网盘账号全部未登录，需重新登录）。\n" +
            "如果需要便携使用，请保持「宽松」。"
    DbEncryptionLevel.STRICT ->
        "密钥将用 **Windows DPAPI** 保护：绑定当前 Windows 用户。\n" +
            "⚠ 换 Windows 用户、重装系统或换机器后，凭证将无法解密（需重新登录）。\n" +
            "如果需要便携使用，请保持「宽松」。"
    DbEncryptionLevel.STRICTEST ->
        "该档需要 SQLCipher 原生库支持，当前版本暂未实现。"
}

/** 自定义目录：原生目录选择器必须在 IO 线程打开（阻塞式模态对话框，直接跑会卡住 UI） */
private fun pickCustom(scope: CoroutineScope, kind: StorageKind, onChosen: (StorageKind, File) -> Unit) {
    scope.launch {
        val picked = withContext(Dispatchers.IO) { DesktopActions.pickDirectory() }
        if (picked != null) onChosen(kind, File(picked))
    }
}

/**
 * 把字符串里的 `**粗体**` 标记渲染成真正的粗体。
 * Compose 的 Text 不做 markdown 解析，直接传原串会把星号也照原样显示出来（很丑）。
 */
private fun emphasize(text: String): AnnotatedString = buildAnnotatedString {
    var index = 0
    while (index < text.length) {
        val open = text.indexOf("**", index)
        val close = if (open < 0) -1 else text.indexOf("**", open + 2)
        if (open < 0 || close < 0) {
            append(text.substring(index))
            break
        }
        append(text.substring(index, open))
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
            append(text.substring(open + 2, close))
        }
        index = close + 2
    }
}

private fun samePath(a: File, b: File): Boolean =
    runCatching { a.canonicalPath == b.canonicalPath }.getOrDefault(a.absolutePath == b.absolutePath)

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var i = 0
    while (value >= 1024 && i < units.size - 1) {
        value /= 1024
        i++
    }
    return String.format("%.1f %s", value, units[i])
}
