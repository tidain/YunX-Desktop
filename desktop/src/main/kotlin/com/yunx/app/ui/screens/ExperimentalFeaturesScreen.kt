package com.yunx.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.yunx.app.data.download.DownloadTuning
import com.yunx.app.data.network.HttpClients
import com.yunx.app.data.prefs.SettingsRepository
import com.yunx.app.ui.BackHandler
import com.yunx.app.ui.SnackbarController
import com.yunx.app.ui.components.FadeAlertDialog
import com.yunx.app.ui.GlobalSnackbarHost

/**
 * 档位列表一律落在「合理范围」内（不会触发超限确认）；想要更高只能走弹窗里的自定义输入。
 * 合理范围：读缓冲 16–1024 KB、慢连接阈值 4–1024 KB/s、慢连接时长 5–120 秒。
 */
private val bufferKbOptions = listOf(16, 32, 64, 128, 256, 512, 1024)

/** 慢连接判定阈值可选档位（KB/s） */
private val preemptMinBpsKbOptions = listOf(4, 8, 12, 16, 32, 64, 128, 256, 512, 1024)

/** 慢连接判定时长可选档位（秒） */
private val preemptMinAgeSecOptions = listOf(5, 10, 15, 20, 30, 45, 60, 90, 120)

/** 超出「合理范围」的自定义取值：交给页面统一弹免责确认后再写入 */
private data class RiskyChange(
    /** 设置项名称（用于弹窗文案） */
    val setting: String,
    val value: Long,
    val unit: String,
    /** 合理上限（弹窗里提示用户正常值到哪里） */
    val recommendedMax: Long,
    /** 用户确认「仍然启用」后真正落库的回调 */
    val apply: (Long) -> Unit
)

/**
 * 需要二次风险确认的两个多账号下载开关：确认弹窗的标题与正文按它取。
 *
 * 用枚举而不是两个布尔状态：弹窗同一时刻只会有一个，语义上不会出现「两个都待确认」的非法态。
 */
private enum class MultiAccountRisk(val title: String, val message: String) {
    MULTI_ACCOUNT(
        "开启「多账号下载」？",
        "请只登录你自己的账号。同一网络下频繁切换多个账号取链，部分网盘（尤其百度、夸克）" +
            "可能触发风控：出现验证码、临时限流，甚至短期限制账号下载。功能仍在实验阶段，" +
            "遇到异常可随时关闭。"
    ),
    MULTI_SOURCE(
        "开启「单文件多源分片」？",
        "同一个文件会被多个账号同时拉取，比多账号下载更容易触发风控；可能出现分片失败率上升、" +
            "下载变慢或账号被临时限制。目前只支持夸克 / UC / 迅雷。功能仍在实验阶段，" +
            "请自行评估风险。"
    )
}

/**
 * 「实验性功能」二级页：集中管理高风险 / 可调下载参数，支持一键重置为默认值。
 *
 * 本页参数会影响下载行为，改动后对新任务生效；异常时点底部「重置为默认值」恢复。
 * 二级页导航沿用项目现有方式（MainScreen 的全屏覆盖层 + showXxx 状态），未引入新导航框架。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExperimentalFeaturesScreen(
    onBack: () -> Unit,
    /** 主页快捷方式开关变化回调（MainScreen 持有解析页所需状态） */
    onHomeShortcutChanged: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    BackHandler { onBack() }
    val settingsRepo = remember { SettingsRepository() }
    // 本地状态驱动 UI，改动同时同步 Preferences 与运行时（DownloadTuning / HttpClients）
    var http2Enabled by remember { mutableStateOf(settingsRepo.http2Enabled) }
    var bufferSize by remember { mutableStateOf(settingsRepo.downloadBufferSize) }
    var preemptEnabled by remember { mutableStateOf(settingsRepo.slowPreemptEnabled) }
    var preemptMinBps by remember { mutableStateOf(settingsRepo.slowPreemptMinBps) }
    var preemptMinAgeMs by remember { mutableStateOf(settingsRepo.slowPreemptMinAgeMs) }
    var directSingle by remember { mutableStateOf(settingsRepo.directWriteSingleThread) }
    var directMulti by remember { mutableStateOf(settingsRepo.directWriteMultiThread) }
    var homeShortcut by remember { mutableStateOf(settingsRepo.homeShortcutEnabled) }
    // 多账号下载（C1）/ 单文件多源分片（C2）：两个实验性开关，默认全关
    var multiAccount by remember { mutableStateOf(settingsRepo.multiAccountDownload) }
    // 读取用口径（C1 关 ⇒ C2 无效），避免手改偏好后出现「C1 关着、C2 还显示开启」的错觉
    var multiSource by remember { mutableStateOf(settingsRepo.multiSourceActive()) }
    // 非 null = 正在二次确认哪一项（只有「开启」才需要确认，关闭直接生效）
    var pendingMultiAccountRisk by remember { mutableStateOf<MultiAccountRisk?>(null) }

    var showBufferDialog by remember { mutableStateOf(false) }
    var showMinBpsDialog by remember { mutableStateOf(false) }
    var showMinAgeDialog by remember { mutableStateOf(false) }
    var showResetConfirm by remember { mutableStateOf(false) }
    // 各弹窗里的「自定义数值」输入（每次打开弹窗清空，避免残留上次的输入）
    var bufferCustomInput by remember { mutableStateOf("") }
    var minBpsCustomInput by remember { mutableStateOf("") }
    var minAgeCustomInput by remember { mutableStateOf("") }

    // 超出合理范围的自定义取值：非空时弹「超出合理范围」确认，用户确认后才写入
    var riskyChange by remember { mutableStateOf<RiskyChange?>(null) }

    // ---- 三个数值项的写入逻辑（档位选择与自定义输入共用同一条路径）----
    /** 读缓冲：写设置 + 同步运行时 */
    fun applyBufferKb(kb: Long) {
        val bytes = (kb * 1024).toInt()
        bufferSize = bytes
        settingsRepo.downloadBufferSize = bytes
        DownloadTuning.applyFrom(settingsRepo)
        showBufferDialog = false
        SnackbarController.show("读缓冲已设为 $kb KB（新任务生效）")
    }

    /** 慢连接判定阈值 */
    fun applyMinBpsKb(kbps: Long) {
        val bps = kbps * 1024L
        preemptMinBps = bps
        settingsRepo.slowPreemptMinBps = bps
        DownloadTuning.applyFrom(settingsRepo)
        showMinBpsDialog = false
        SnackbarController.show("慢连接判定阈值已设为 $kbps KB/s")
    }

    /** 慢连接判定时长 */
    fun applyMinAgeSec(sec: Long) {
        val ms = sec * 1000L
        preemptMinAgeMs = ms
        settingsRepo.slowPreemptMinAgeMs = ms
        DownloadTuning.applyFrom(settingsRepo)
        showMinAgeDialog = false
        SnackbarController.show("慢连接判定时长已设为 $sec 秒")
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { GlobalSnackbarHost() },
        topBar = {
            TopAppBar(
                title = { Text("实验性功能", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp)
        ) {
            // 顶部说明
            Text(
                text = "以下参数会影响下载行为，改动后对新任务生效；若出现异常，可点底部「重置为默认值」恢复。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))

            // HTTP/2 开关（从「网络」分组移动至此，逻辑不变）
            SettingsItem(
                icon = Icons.Outlined.Bolt,
                title = "启用 HTTP/2",
                description = if (http2Enabled) {
                    "已启用：允许协商 HTTP/2（理论上更快，实测差异通常不大）"
                } else {
                    "默认仅使用 HTTP/1.1（HTTP/2 理论上更快，但实测差异通常不大）"
                },
                onClick = {
                    http2Enabled = !http2Enabled
                    settingsRepo.http2Enabled = http2Enabled
                    HttpClients.setHttp2Enabled(http2Enabled)
                    SnackbarController.show(
                        if (http2Enabled) "已启用 HTTP/2（允许协商 h2）" else "已切换为仅使用 HTTP/1.1"
                    )
                },
                trailing = { Switch(checked = http2Enabled, onCheckedChange = null) }
            )
            Spacer(modifier = Modifier.height(8.dp))

            // 读缓冲大小
            SettingsItem(
                icon = Icons.Outlined.Layers,
                title = "下载读缓冲大小",
                description = "当前 ${bufferSize / 1024} KB（合理范围 16–1024 KB；影响每个在飞分片的内存占用，新任务生效）",
                onClick = {
                    bufferCustomInput = ""
                    showBufferDialog = true
                }
            )
            Spacer(modifier = Modifier.height(8.dp))

            // 慢连接抢占开关
            SettingsItem(
                icon = Icons.Outlined.Speed,
                title = "慢连接抢占",
                description = if (preemptEnabled) {
                    "已开启：把远低于同伴的慢分片换新连接续传（阈值 ${preemptMinBps / 1024} KB/s、判定 ${preemptMinAgeMs / 1000} 秒）"
                } else {
                    "已关闭：不做慢连接抢占（等价于该功能引入之前的行为）"
                },
                onClick = {
                    preemptEnabled = !preemptEnabled
                    settingsRepo.slowPreemptEnabled = preemptEnabled
                    DownloadTuning.applyFrom(settingsRepo)
                    SnackbarController.show(if (preemptEnabled) "已开启慢连接抢占" else "已关闭慢连接抢占")
                },
                trailing = { Switch(checked = preemptEnabled, onCheckedChange = null) }
            )
            Spacer(modifier = Modifier.height(8.dp))

            // 慢连接判定阈值
            SettingsItem(
                icon = Icons.Outlined.Tune,
                title = "慢连接判定阈值",
                description = "当前 ${preemptMinBps / 1024} KB/s（低于此速率的连接才判定为慢；合理范围 4–1024 KB/s）",
                onClick = {
                    minBpsCustomInput = ""
                    showMinBpsDialog = true
                }
            )
            Spacer(modifier = Modifier.height(8.dp))

            // 慢连接判定时长
            SettingsItem(
                icon = Icons.Outlined.Refresh,
                title = "慢连接判定时长",
                description = "当前 ${preemptMinAgeMs / 1000} 秒（分片至少跑这么久才允许被抢占；合理范围 5–120 秒）",
                onClick = {
                    minAgeCustomInput = ""
                    showMinAgeDialog = true
                }
            )
            Spacer(modifier = Modifier.height(8.dp))

            // 多账号下载（实验性 C1）：批量下载按文件轮换该网盘的不同账号取链
            SettingsItem(
                icon = Icons.Outlined.Person,
                title = "多账号下载（实验性）",
                description = if (multiAccount) {
                    "已开启：批量下载按文件轮换不同账号取链（每个文件仍只用一个账号下载）"
                } else {
                    "默认关闭：批量下载只用主账号取链；开启前需二次确认风险"
                },
                onClick = {
                    if (multiAccount) {
                        multiAccount = false
                        settingsRepo.multiAccountDownload = false
                        // ★ C2 依赖 C1：关 C1 必须同时关 C2（prefs + 本地状态），
                        //   否则会留下「多账号下载关了、单文件还在多账号取链」的不一致状态
                        val multiSourceWasOn = multiSource
                        settingsRepo.disableMultiSource()
                        multiSource = false
                        SnackbarController.show(
                            if (multiSourceWasOn) {
                                "已关闭多账号下载，单文件多源分片同步关闭"
                            } else {
                                "已关闭多账号下载"
                            }
                        )
                    } else {
                        // 开启前二次风险确认：取消时什么都没写，状态不变
                        pendingMultiAccountRisk = MultiAccountRisk.MULTI_ACCOUNT
                    }
                },
                trailing = { Switch(checked = multiAccount, onCheckedChange = null) }
            )
            Spacer(modifier = Modifier.height(8.dp))

            // 单文件多源分片（实验性 C2）：依赖 C1，C1 关闭时这一项不可操作
            SettingsItem(
                icon = Icons.Outlined.Layers,
                title = "单文件多源分片（实验性）",
                description = when {
                    !multiAccount -> "需先开启「多账号下载」"
                    multiSource -> "已开启：同一文件多账号各取直链、分片轮流下载"
                    else -> "默认关闭：单个文件只用一个源下载；开启前需二次确认风险"
                },
                onClick = {
                    when {
                        !multiAccount -> SnackbarController.show("请先开启「多账号下载」")
                        multiSource -> {
                            multiSource = false
                            settingsRepo.multiSourceDownload = false
                            SnackbarController.show("已关闭单文件多源分片")
                        }
                        else -> pendingMultiAccountRisk = MultiAccountRisk.MULTI_SOURCE
                    }
                },
                trailing = { Switch(checked = multiSource, onCheckedChange = null, enabled = multiAccount) }
            )
            Spacer(modifier = Modifier.height(8.dp))

            // 实验性：单线程直写
            SettingsItem(
                icon = Icons.Outlined.Download,
                title = "单线程直写（实验性）",
                description = if (directSingle) {
                    "已开启：线程数为 1 时直接写目标目录的 .part，完成后改名；缓存盘占用 0，目标盘峰值 1 份"
                } else {
                    "默认关闭：线程数为 1 时仍写缓存分片，再合并到目标目录"
                },
                onClick = {
                    directSingle = !directSingle
                    settingsRepo.directWriteSingleThread = directSingle
                    SnackbarController.show(
                        if (directSingle) "已开启单线程直写（实验性）" else "已关闭单线程直写"
                    )
                },
                trailing = { Switch(checked = directSingle, onCheckedChange = null) }
            )
            Spacer(modifier = Modifier.height(8.dp))

            // 实验性：多线程直写
            SettingsItem(
                icon = Icons.Outlined.Bolt,
                title = "多线程直写（实验性）",
                description = if (directMulti) {
                    "已开启：预分配目标目录的单个 .part，各线程按偏移并行写；不占缓存盘，提交前逐片校验"
                } else {
                    "默认关闭：多线程仍写缓存分片，再合并到目标目录"
                },
                onClick = {
                    directMulti = !directMulti
                    settingsRepo.directWriteMultiThread = directMulti
                    SnackbarController.show(
                        if (directMulti) "已开启多线程直写（实验性）" else "已关闭多线程直写"
                    )
                },
                trailing = { Switch(checked = directMulti, onCheckedChange = null) }
            )
            if (directSingle || directMulti) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "⚠ 直写为实验性功能，仅对「内置下载器」生效（Gopeed 引擎本身直接落盘，不受此开关影响）。" +
                        "临时文件（.yunx-*.part / .plan / .done）会出现在下载保存目录；暂停/失败后会保留以便续传。" +
                        "若目标盘是移动硬盘/网络盘，断开后该任务无法续传。对已开始的任务，开关变更不会改变其已选择的模式。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Spacer(modifier = Modifier.height(8.dp))

            // 主页快捷方式
            SettingsItem(
                icon = Icons.Outlined.Bookmarks,
                title = "主页快捷方式",
                description = if (homeShortcut) {
                    "已开启：解析页以横向快捷方式展示收藏，点击直达解析"
                } else {
                    "默认关闭：解析页不显示收藏快捷方式"
                },
                onClick = {
                    homeShortcut = !homeShortcut
                    settingsRepo.homeShortcutEnabled = homeShortcut
                    onHomeShortcutChanged(homeShortcut)
                    SnackbarController.show(if (homeShortcut) "已开启主页快捷方式" else "已关闭主页快捷方式")
                },
                trailing = { Switch(checked = homeShortcut, onCheckedChange = null) }
            )
            Spacer(modifier = Modifier.height(24.dp))

            // 底部重置按钮：二次确认后恢复本页全部默认值
            Button(
                onClick = { showResetConfirm = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Text("重置为默认值")
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "默认值：HTTP/2 关闭、读缓冲 64 KB、慢连接抢占开启（12 KB/s · 15 秒）、" +
                    "多账号下载与单文件多源分片关闭、两种直写关闭、主页快捷方式关闭。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    // 读缓冲档位选择
    FadeAlertDialog(
        visible = showBufferDialog,
        onDismissRequest = { showBufferDialog = false },
        title = { Text("下载读缓冲大小") },
        text = {
            Column {
                Text(
                    text = "缓冲越大单路吞吐略高、内存占用越高；默认 64 KB。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                bufferKbOptions.forEach { kb ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = bufferSize == kb * 1024,
                            onClick = { applyBufferKb(kb.toLong()) }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("$kb KB", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                CustomNumberRow(
                    value = bufferCustomInput,
                    onValueChange = { bufferCustomInput = it },
                    label = "自定义（KB）",
                    fallbackHint = "${bufferSize / 1024}",
                    min = (SettingsRepository.MIN_DOWNLOAD_BUFFER_SIZE / 1024).toLong(),
                    recommendedMax = (SettingsRepository.RECOMMENDED_MAX_DOWNLOAD_BUFFER_SIZE / 1024).toLong(),
                    hardMax = (SettingsRepository.MAX_DOWNLOAD_BUFFER_SIZE / 1024).toLong(),
                    unit = "KB",
                    onValidated = { kb ->
                        val recommended = (SettingsRepository.RECOMMENDED_MAX_DOWNLOAD_BUFFER_SIZE / 1024).toLong()
                        if (kb > recommended) {
                            // 超合理范围：先关掉设置弹窗，再弹「超出合理范围」免责确认
                            showBufferDialog = false
                            riskyChange = RiskyChange("下载读缓冲大小", kb, "KB", recommended) { applyBufferKb(it) }
                        } else {
                            applyBufferKb(kb)
                        }
                    }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { showBufferDialog = false }) { Text("取消") }
        }
    )

    // 慢连接判定阈值选择
    FadeAlertDialog(
        visible = showMinBpsDialog,
        onDismissRequest = { showMinBpsDialog = false },
        title = { Text("慢连接判定阈值") },
        text = {
            Column {
                Text(
                    text = "低于该速率的在飞分片才可能被换连接（实际阈值取本值与任务平均单连接速度一半的较大者）。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                preemptMinBpsKbOptions.forEach { kb ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = preemptMinBps == kb * 1024L,
                            onClick = { applyMinBpsKb(kb.toLong()) }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("$kb KB/s", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                CustomNumberRow(
                    value = minBpsCustomInput,
                    onValueChange = { minBpsCustomInput = it },
                    label = "自定义（KB/s）",
                    fallbackHint = "${preemptMinBps / 1024}",
                    min = (SettingsRepository.MIN_SLOW_PREEMPT_MIN_BPS / 1024).toLong(),
                    recommendedMax = (SettingsRepository.RECOMMENDED_MAX_SLOW_PREEMPT_MIN_BPS / 1024).toLong(),
                    hardMax = (SettingsRepository.MAX_SLOW_PREEMPT_MIN_BPS / 1024).toLong(),
                    unit = "KB/s",
                    onValidated = { kbps ->
                        val recommended = (SettingsRepository.RECOMMENDED_MAX_SLOW_PREEMPT_MIN_BPS / 1024).toLong()
                        if (kbps > recommended) {
                            showMinBpsDialog = false
                            riskyChange = RiskyChange("慢连接判定阈值", kbps, "KB/s", recommended) { applyMinBpsKb(it) }
                        } else {
                            applyMinBpsKb(kbps)
                        }
                    }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { showMinBpsDialog = false }) { Text("取消") }
        }
    )

    // 慢连接判定时长选择
    FadeAlertDialog(
        visible = showMinAgeDialog,
        onDismissRequest = { showMinAgeDialog = false },
        title = { Text("慢连接判定时长") },
        text = {
            Column {
                Text(
                    text = "分片至少跑这么久才允许被抢占（避开建连与爬坡期，避免误杀刚起步的正常分片）。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                preemptMinAgeSecOptions.forEach { sec ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = preemptMinAgeMs == sec * 1000L,
                            onClick = { applyMinAgeSec(sec.toLong()) }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("$sec 秒", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                CustomNumberRow(
                    value = minAgeCustomInput,
                    onValueChange = { minAgeCustomInput = it },
                    label = "自定义（秒）",
                    fallbackHint = "${preemptMinAgeMs / 1000}",
                    min = (SettingsRepository.MIN_SLOW_PREEMPT_MIN_AGE_MS / 1000).toLong(),
                    recommendedMax = (SettingsRepository.RECOMMENDED_MAX_SLOW_PREEMPT_MIN_AGE_MS / 1000).toLong(),
                    hardMax = (SettingsRepository.MAX_SLOW_PREEMPT_MIN_AGE_MS / 1000).toLong(),
                    unit = "秒",
                    onValidated = { sec ->
                        val recommended = (SettingsRepository.RECOMMENDED_MAX_SLOW_PREEMPT_MIN_AGE_MS / 1000).toLong()
                        if (sec > recommended) {
                            showMinAgeDialog = false
                            riskyChange = RiskyChange("慢连接判定时长", sec, "秒", recommended) { applyMinAgeSec(it) }
                        } else {
                            applyMinAgeSec(sec)
                        }
                    }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { showMinAgeDialog = false }) { Text("取消") }
        }
    )

    // 多账号下载 / 单文件多源分片：开启前二次风险确认（只有「开启」需要确认）
    pendingMultiAccountRisk?.let { risk ->
        FadeAlertDialog(
            visible = true,
            onDismissRequest = { pendingMultiAccountRisk = null },
            title = { Text(risk.title) },
            text = {
                Text(
                    text = risk.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        when (risk) {
                            MultiAccountRisk.MULTI_ACCOUNT -> {
                                multiAccount = true
                                settingsRepo.multiAccountDownload = true
                                SnackbarController.show("已开启多账号下载")
                            }
                            MultiAccountRisk.MULTI_SOURCE -> {
                                multiSource = true
                                settingsRepo.multiSourceDownload = true
                                SnackbarController.show("已开启单文件多源分片")
                            }
                        }
                        pendingMultiAccountRisk = null
                    }
                ) { Text("确认开启") }
            },
            dismissButton = {
                TextButton(onClick = { pendingMultiAccountRisk = null }) { Text("取消") }
            }
        )
    }

    // 重置二次确认（窗口内覆盖层，遵循项目既有弹窗约定）
    FadeAlertDialog(
        visible = showResetConfirm,
        onDismissRequest = { showResetConfirm = false },
        title = { Text("重置实验性功能？") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "将把本页所有设置恢复为默认值：",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "· HTTP/2：关闭\n" +
                        "· 读缓冲：64 KB\n" +
                        "· 慢连接抢占：开启（12 KB/s · 15 秒）\n" +
                        "· 多账号下载：关闭\n" +
                        "· 单文件多源分片：关闭\n" +
                        "· 单线程直写 / 多线程直写：关闭\n" +
                        "· 主页快捷方式：关闭",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    showResetConfirm = false
                    // 1) 持久化恢复默认
                    settingsRepo.resetExperimentalFeatures()
                    // 2) 运行时同步：HTTP/2 客户端重建 + 下载引擎调优参数
                    HttpClients.setHttp2Enabled(settingsRepo.http2Enabled)
                    DownloadTuning.applyFrom(settingsRepo)
                    // 3) UI 本地状态与主页快捷方式状态同步
                    http2Enabled = settingsRepo.http2Enabled
                    bufferSize = settingsRepo.downloadBufferSize
                    preemptEnabled = settingsRepo.slowPreemptEnabled
                    preemptMinBps = settingsRepo.slowPreemptMinBps
                    preemptMinAgeMs = settingsRepo.slowPreemptMinAgeMs
                    directSingle = settingsRepo.directWriteSingleThread
                    directMulti = settingsRepo.directWriteMultiThread
                    homeShortcut = settingsRepo.homeShortcutEnabled
                    // 多账号下载 / 单文件多源分片一并恢复默认（全关）
                    multiAccount = settingsRepo.multiAccountDownload
                    multiSource = settingsRepo.multiSourceActive()
                    onHomeShortcutChanged(settingsRepo.homeShortcutEnabled)
                    SnackbarController.show("已重置为默认值")
                }
            ) { Text("重置") }
        },
        dismissButton = {
            TextButton(onClick = { showResetConfirm = false }) { Text("取消") }
        }
    )

    // 超出合理范围：二次确认 + 免责提示（点「仍然启用」才按输入值写入）
    riskyChange?.let { rc ->
        FadeAlertDialog(
            visible = true,
            onDismissRequest = { riskyChange = null },
            title = { Text("超出合理范围") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "「${rc.setting}」的合理上限是 ${rc.recommendedMax} ${rc.unit}，" +
                            "你输入的是 ${rc.value} ${rc.unit}。",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "仍然可以启用，但超出合理范围可能带来内存占用升高、速度反而下降、" +
                            "连接异常或任务失败等问题。因自行调整实验性功能造成的后果由使用者自行承担，" +
                            "开发者不对此负责。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val v = rc.value
                        riskyChange = null
                        rc.apply(v)
                    }
                ) { Text("仍然启用") }
            },
            dismissButton = {
                TextButton(onClick = { riskyChange = null }) { Text("取消") }
            }
        )
    }
}

/**
 * 弹窗里的「自定义数值」输入行：输入框 + 「应用」按钮。
 *
 * 与上方的档位选择**并存**：既可以直接点档位，也可以输入一个具体数值；
 * 只接受 `[min, max]` 闭区间内的整数，非法/越界只提示、不写入 —— 这个区间就是"合理范围"。
 */
@Composable
private fun CustomNumberRow(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    /** 输入框为空时的占位提示（一般填当前值） */
    fallbackHint: String,
    min: Long,
    /** 合理上限：超过它由调用方弹二次确认（本行不拦截） */
    recommendedMax: Long,
    /** 硬上限：超过它直接拒绝，不写入 */
    hardMax: Long,
    unit: String,
    /** 已通过「数字 + 硬范围」校验的取值 */
    onValidated: (Long) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "或直接输入数值（$min–$recommendedMax $unit；超过 $recommendedMax 会二次确认）",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = value,
                // 只收数字、最长 7 位：够用，也挡掉粘贴进来的超长串
                onValueChange = { raw -> onValueChange(raw.filter { it.isDigit() }.take(7)) },
                modifier = Modifier.weight(1f),
                label = { Text(label) },
                placeholder = { Text(fallbackHint) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true
            )
            Spacer(modifier = Modifier.width(8.dp))
            TextButton(
                onClick = {
                    val v = value.trim().toLongOrNull()
                    when {
                        v == null -> SnackbarController.show("请输入数字")
                        v < min || v > hardMax ->
                            SnackbarController.show("可设置范围 $min–$hardMax $unit（超过 $recommendedMax 会二次确认）")
                        else -> onValidated(v)
                    }
                }
            ) { Text("应用") }
        }
    }
}
