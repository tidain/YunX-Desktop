package com.yunx.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.yunx.app.data.motrix.MotrixClient
import com.yunx.app.data.motrix.MotrixConfig
import com.yunx.app.data.motrix.MotrixLocator
import com.yunx.app.data.prefs.SettingsRepository
import com.yunx.app.ui.BackHandler
import com.yunx.app.ui.GlobalSnackbarHost
import com.yunx.app.ui.SnackbarController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Motrix 外部下载器设置页。
 *
 * Motrix 内部跑的是 aria2c，对外暴露 JSON-RPC（默认 `127.0.0.1:16800`）。
 * 这里的配置只影响「发送到 Motrix」这个动作：用 RPC 把直链连同 Cookie/Referer/UA
 * 一起推给它。文件由 Motrix 自己下载，YunX 不入队、不跟踪进度。
 *
 * 端口与密钥默认自动读 Motrix 的配置文件（2.x 的 `settings.json`、1.x 的 `system.json`），
 * 所以多数人打开这个页面直接就能用。**手动参数始终可编辑**：便携版、改过 APPDATA、
 * 或者自动读取读到的跟 Motrix 实际不一致时，直接在这儿改，改完点「测试连接」验证。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MotrixSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val settingsRepo = remember { SettingsRepository() }

    var enabled by remember { mutableStateOf(settingsRepo.motrixEnabled) }
    var autoDetect by remember { mutableStateOf(settingsRepo.motrixAutoDetect) }
    var askSaveDir by remember { mutableStateOf(settingsRepo.motrixAskSaveDir) }
    var portText by remember { mutableStateOf(settingsRepo.motrixPort.toString()) }
    var secretText by remember { mutableStateOf(settingsRepo.motrixSecret) }
    // 自动探测结果（可能有多份：新版 settings.json + 旧版 system.json）
    var detected by remember { mutableStateOf(MotrixLocator.candidates()) }
    // 连通性测试结果（null = 还没测）
    var testResult by remember { mutableStateOf<TestResult?>(null) }
    var testing by remember { mutableStateOf(false) }
    // 密钥默认打码，点眼睛可查看
    var secretVisible by remember { mutableStateOf(false) }

    BackHandler { onBack() }

    /** 当前实际生效的连接参数（与发直链时用的完全同一套判定） */
    fun effectiveConfig(): MotrixConfig? = settingsRepo.resolveMotrixConfig()

    /** 重新探测一次磁盘 + 清掉测试结果 */
    fun refreshDetect() {
        MotrixLocator.invalidate()
        detected = MotrixLocator.candidates()
        testResult = null
        if (detected.isEmpty()) {
            SnackbarController.show("没找到 Motrix 配置文件，可在下方手动填写")
        } else {
            SnackbarController.show("已读取 ${detected.size} 份 Motrix 配置")
        }
    }

    /** 把某一份自动读到的配置抄进手动参数，并关掉自动读取让它生效 */
    fun adoptDetected(config: MotrixConfig) {
        portText = config.port.toString()
        secretText = config.secret
        settingsRepo.motrixPort = config.port
        settingsRepo.motrixSecret = config.secret
        autoDetect = false
        settingsRepo.motrixAutoDetect = false
        testResult = null
        SnackbarController.show("已填入手动参数：${config.describe()}")
    }

    /**
     * 连通性测试：读 aria2 版本。
     *
     * 自动读取开着时测的是读到的配置，关掉时测的是上面手填的值
     * （输入框改一下就写进设置，所以这里不用再单独传）。
     */
    fun testConnection() {
        val config = effectiveConfig()
        if (config == null) {
            testResult = TestResult(
                ok = false,
                message = "还没有可用的连接参数：把下面「手动参数」填上端口和密钥，再关掉自动读取",
                detail = null
            )
            return
        }
        testing = true
        testResult = null
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching { MotrixClient.version(config) }
            }
            testing = false
            testResult = outcome.fold(
                onSuccess = {
                    TestResult(ok = true, message = "连接成功：aria2 $it", detail = config.describe())
                },
                onFailure = {
                    TestResult(
                        ok = false,
                        message = it.message ?: it.javaClass.simpleName,
                        detail = config.describe()
                    )
                }
            )
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { GlobalSnackbarHost() },
        topBar = {
            TopAppBar(
                title = { Text("Motrix 下载器", style = MaterialTheme.typography.titleLarge) },
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
                .padding(16.dp)
        ) {
            Text(
                text = "把解析出来的直链连同 Cookie / Referer / UA 一起发送到本机 Motrix，" +
                    "由它下载。文件不经过本应用，也不在这里显示进度。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))

            MotrixCard(title = "启用") {
                MotrixSwitchRow(
                    title = "显示「发送到 Motrix」入口",
                    description = "关掉后下载弹窗与任务右键菜单都不再出现该按钮",
                    checked = enabled,
                    onCheckedChange = {
                        enabled = it
                        settingsRepo.motrixEnabled = it
                    }
                )
                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))
                MotrixSwitchRow(
                    title = "发送前询问保存目录",
                    description = "打开后每次发送先选目录，选中的路径作为本次任务的下载位置下发给 Motrix；" +
                        "关掉则沿用 Motrix 自己的默认目录。只影响这一次，不改 Motrix 的全局设置",
                    checked = askSaveDir,
                    onCheckedChange = {
                        askSaveDir = it
                        settingsRepo.motrixAskSaveDir = it
                    }
                )
            }

            Spacer(Modifier.height(12.dp))

            // ---- 自动读取 ----
            MotrixCard(title = "自动读取") {
                MotrixSwitchRow(
                    title = "从 Motrix 配置文件读取端口与密钥",
                    description = "自动识别新版 settings.json 与旧版 system.json；" +
                        "Motrix 里改了配置，这里会自动跟着变",
                    checked = autoDetect,
                    onCheckedChange = {
                        autoDetect = it
                        settingsRepo.motrixAutoDetect = it
                        testResult = null
                    }
                )
                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))

                val dir = MotrixLocator.configDir()
                MotrixKeyValueRow("配置目录", dir?.absolutePath ?: "未找到（Motrix 没装过？）")

                Spacer(Modifier.height(8.dp))
                if (detected.isEmpty()) {
                    Text(
                        text = "没读到配置文件。可以：① 先在 Motrix 里改一次设置让它生成文件再点「重新读取」；" +
                            "② 直接在下方「手动参数」填写。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    detected.forEachIndexed { index, config ->
                        if (index > 0) Spacer(Modifier.height(8.dp))
                        DetectedConfigRow(
                            config = config,
                            active = autoDetect && index == 0
                        ) {
                            adoptDetected(config)
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = { refreshDetect() }) {
                    Icon(
                        imageVector = Icons.Outlined.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("重新读取")
                }
            }

            Spacer(Modifier.height(12.dp))

            // ---- 手动参数（始终可编辑） ----
            MotrixCard(title = "手动参数") {
                Text(
                    text = if (autoDetect) {
                        "自动读取优先，这里填写的内容暂不生效。想让手动参数生效，" +
                            "要么关掉上面的自动读取，要么点某一行的「用这个」。"
                    } else {
                        "当前使用这里的参数。改完点下方「测试连接」验证。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = portText,
                    onValueChange = { input ->
                        portText = input.filter { it.isDigit() }.take(5)
                        portText.toIntOrNull()?.takeIf { it in 1..65535 }?.let {
                            settingsRepo.motrixPort = it
                        }
                        testResult = null
                    },
                    label = { Text("RPC 端口") },
                    placeholder = { Text(SettingsRepository.MOTRIX_DEFAULT_PORT.toString()) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = secretText,
                    onValueChange = {
                        secretText = it
                        settingsRepo.motrixSecret = it
                        testResult = null
                    },
                    label = { Text("RPC 密钥（rpc-secret）") },
                    placeholder = { Text("Motrix 没设密钥就留空") },
                    singleLine = true,
                    visualTransformation = if (secretVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        TextButton(onClick = { secretVisible = !secretVisible }) {
                            Text(
                                text = if (secretVisible) "隐藏" else "显示",
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(8.dp))
                Text(
                    text = "密钥在 Motrix 的「偏好设置 → 进阶 → RPC 密钥」里；" +
                        "填错会得到 HTTP 400，测试连接会直接报出来。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { testConnection() }, enabled = !testing) {
                        Text(if (testing) "测试中…" else "测试连接")
                    }
                    if (autoDetect) {
                        OutlinedButton(
                            onClick = {
                                autoDetect = false
                                settingsRepo.motrixAutoDetect = false
                                testResult = null
                                SnackbarController.show("已切换为手动参数")
                            }
                        ) {
                            Text("改用手动参数")
                        }
                    }
                }

                testResult?.let { result ->
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = result.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (result.ok) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        }
                    )
                    result.detail?.let { detail ->
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "测试的是：$detail",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // ---- 当前生效状态 ----
            MotrixCard(title = "当前生效") {
                val config = effectiveConfig()
                if (config == null) {
                    Text(
                        text = "没有生效的连接参数（功能已关闭，或既没读到配置文件也没填手动参数）。" +
                            "此状态下界面不会出现「发送到 Motrix」入口。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    MotrixKeyValueRow("端点", config.describe())
                    Spacer(Modifier.height(6.dp))
                    MotrixKeyValueRow("下载目录", config.defaultDir.ifBlank { "（跟随 Motrix 自己的设置）" })
                    Spacer(Modifier.height(6.dp))
                    MotrixKeyValueRow("来源", config.source)
                }
                Spacer(Modifier.height(12.dp))
                Button(onClick = { testConnection() }, enabled = !testing) {
                    Icon(
                        imageVector = Icons.Outlined.OpenInNew,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("检查 Motrix 是否在线")
                }
            }

            Spacer(Modifier.height(12.dp))

            MotrixCard(title = "说明") {
                Text(
                    text = "Motrix 需要正在运行。端口/密钥在 Motrix 里改过之后，如果这里仍报 400，" +
                        "把上面的密钥改成 Motrix 里显示的那串即可（或关掉自动读取后手填）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 连通性测试结果 */
private data class TestResult(
    val ok: Boolean,
    val message: String,
    val detail: String?
)

/**
 * 密钥打码：只留前 2 位和后 2 位。
 *
 * 两份配置（新版 settings.json / 旧版 system.json）常常端口一样，只靠「已带密钥」
 * 分不清哪份是新的 —— 露出法首尾几位就够用户对上 Motrix 里显示的那串。
 */
private fun maskedSecret(secret: String): String = when {
    secret.isBlank() -> "（未设）"
    secret.length <= 4 -> "*".repeat(secret.length)
    else -> secret.take(2) + "*".repeat((secret.length - 4).coerceAtMost(8)) + secret.takeLast(2)
}

/** 自动读到的一份配置：显示端点、目录、来源，右侧「用这个」抄进手动参数 */
@Composable
private fun DetectedConfigRow(
    config: MotrixConfig,
    active: Boolean,
    onAdopt: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = config.describe(),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                if (active) {
                    Text(
                        text = "使用中",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    TextButton(onClick = onAdopt) { Text("用这个") }
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = "密钥：${maskedSecret(config.secret)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "下载目录：${config.defaultDir.ifBlank { "（未写，跟随 Motrix）" }}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = config.source,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 设置页卡片外壳：标题 + 内容 */
@Composable
private fun MotrixCard(
    title: String,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

/** 卡片内的一行开关：左标题右开关 */
@Composable
private fun MotrixSwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(2.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** 卡片内的「标签：值」一行（值可换行） */
@Composable
private fun MotrixKeyValueRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f)
        )
    }
}
