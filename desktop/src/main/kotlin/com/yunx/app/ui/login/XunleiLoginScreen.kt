package com.yunx.app.ui.login

import com.yunx.app.ui.BackHandler
import com.yunx.app.ui.SnackbarController
import com.yunx.app.ui.GlobalSnackbarHost
import com.yunx.app.util.DesktopActions
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.yunx.app.ui.viewmodel.XunleiAccountViewModel
import kotlinx.coroutines.delay

/**
 * 迅雷网盘登录页，三个入口：
 * - **账号密码**：App 通道 `v3/login`，新设备/异地必然触发 `review_panel`，要再补一次短信验证码；
 * - **短信登录**（一等入口）：走同一套 sendsms / smslogin（不经过密码），没设过密码 / 忘记密码 /
 *   被风控挡住都能直接用；
 * - **网页登录**：`pan.xunlei.com` 的另一套接口，不受 App 通道风控影响（回调 [onWebLogin]）。
 *
 * 验证码重发冷却 60 秒，记在 ViewModel 的墙上时钟里：只有服务端确认发出才开始计时，切页/重进不丢。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun XunleiLoginScreen(
    viewModel: XunleiAccountViewModel,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    onVerify: (url: String, deviceId: String) -> Unit = { _, _ -> },
    onWebLogin: () -> Unit = {}
) {
    val step = viewModel.loginStep
    val error = viewModel.loginError
    val smsSent = viewModel.smsSent

    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    var smsCode by rememberSaveable { mutableStateOf("") }
    // 是否主动选择「短信登录」：短信是一等入口，不再只能靠密码登录触发风控才走到
    var useSmsLogin by rememberSaveable { mutableStateOf(false) }

    // 进入登录页先重置登录步骤：上次可能在「安全验证」那一步被关掉，creditkey 早已失效，
    // 直接渲染成验证步骤只会让用户提交必然失败的验证码
    LaunchedEffect(Unit) { viewModel.resetLoginStep() }

    // 密码登录触发风控（needSms）或用户主动选择短信，都进入短信步骤
    val smsMode = useSmsLogin || step?.needSms == true

    // 重发冷却倒计时：读 ViewModel 的墙上时钟，切页/重进不会把倒计时弄丢
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(viewModel.smsCooldownUntil) {
        while (true) {
            nowMs = System.currentTimeMillis()
            if (nowMs >= viewModel.smsCooldownUntil) break
            delay(500)
        }
    }
    val cooldownSeconds = ((viewModel.smsCooldownUntil - nowMs + 999) / 1000).coerceAtLeast(0L)

    // 登录错误提示
    LaunchedEffect(error) {
        error?.let {
            SnackbarController.show(it)
            viewModel.consumeLoginError()
        }
    }
    // 登录成功后自动关闭登录页（短信/密码任一方式成功）
    // ★ 不能用「账号非空」判断：「添加账号」时旧账号本来就在，页面刚渲染就会被判成登录成功
    //   → 用户看到「打开闪一下就关闭」。改成监听落库成功计数（进入页面时记下基线）。
    val succeeded = viewModel.loginSucceeded
    val succeededBaseline = remember { succeeded }
    LaunchedEffect(succeeded) {
        if (succeeded > succeededBaseline) onSaved()
    }

    BackHandler { onBack() }

    Scaffold(
        snackbarHost = { GlobalSnackbarHost() },
        topBar = {
            TopAppBar(
                title = { Text("迅雷网盘登录", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = if (smsMode) "短信验证" else "登录迅雷网盘",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
            )
            Text(
                text = when {
                    step?.needSms == true && smsSent -> "账号密码登录触发安全验证，验证码已发送至 $username"
                    step?.needSms == true -> "账号密码登录触发安全验证，请点击下方「发送验证码」"
                    useSmsLogin && smsSent -> "验证码已发送至 $username，请输入后登录"
                    useSmsLogin -> "使用手机号 + 短信验证码登录，无需密码"
                    else -> "使用迅雷账号登录，支持解析与下载分享文件"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // 登录方式分段：短信步骤中不显示，避免中途切换丢掉当前 creditkey
            if (step?.needSms != true) {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                        selected = !useSmsLogin,
                        onClick = {
                            if (useSmsLogin) {
                                useSmsLogin = false
                                viewModel.resetLoginStep()
                            }
                        }
                    ) { Text("账号密码") }
                    SegmentedButton(
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                        selected = useSmsLogin,
                        onClick = {
                            if (!useSmsLogin) {
                                useSmsLogin = true
                                viewModel.resetLoginStep()
                            }
                        }
                    ) { Text("短信登录") }
                }
            }

            if (!smsMode) {
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("手机号 / 邮箱") },
                    leadingIcon = { Icon(Icons.Outlined.Phone, contentDescription = null) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.large
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("密码") },
                    leadingIcon = { Icon(Icons.Outlined.Lock, contentDescription = null) },
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                imageVector = if (passwordVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                contentDescription = if (passwordVisible) "隐藏密码" else "显示密码"
                            )
                        }
                    },
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = if (passwordVisible) KeyboardType.Text else KeyboardType.Password),
                    singleLine = true,
                    shape = MaterialTheme.shapes.large
                )
                Button(
                    onClick = { viewModel.login(username, password) },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    enabled = username.isNotBlank() && password.isNotBlank()
                ) { Text("登录") }
            } else {
                // 短信登录第一步要手机号；密码触发的验证步骤里手机号已在上一页填过
                if (useSmsLogin) {
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("手机号") },
                        leadingIcon = { Icon(Icons.Outlined.Phone, contentDescription = null) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        singleLine = true,
                        shape = MaterialTheme.shapes.large
                    )
                }
                OutlinedTextField(
                    value = smsCode,
                    onValueChange = { smsCode = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("短信验证码") },
                    leadingIcon = { Icon(Icons.Outlined.Shield, contentDescription = null) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    shape = MaterialTheme.shapes.large
                )
                Button(
                    onClick = {
                        viewModel.loginWithSms(
                            username, smsCode,
                            step?.smsCreditKey.orEmpty(), step?.smsToken.orEmpty()
                        )
                    },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    enabled = smsCode.isNotBlank() && !step?.smsCreditKey.isNullOrBlank()
                ) { Text("验证并登录") }
                // 发送验证码：冷却期内禁用并显示剩余秒数；只在服务端确认发出后开始计时
                FilledTonalButton(
                    onClick = { viewModel.sendSms(username) },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    enabled = username.isNotBlank() && !viewModel.sendingSms && cooldownSeconds == 0L
                ) {
                    when {
                        viewModel.sendingSms -> Text("发送中…")
                        cooldownSeconds > 0L -> Text("重新发送（${cooldownSeconds}s）")
                        smsSent -> Text("重新发送验证码")
                        else -> Text("发送验证码")
                    }
                }
                Text(
                    text = "若始终收不到短信，请确认手机号正确，或改用下方「网页登录」",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )

                // 短信发不出时的应用内验证兜底（系统浏览器承载验证页；核心验证仍走自有短信流）
                if (step?.reviewUrl?.isNotBlank() == true) {
                    TextButton(
                        onClick = {
                            // 用与登录请求一致的设备签名（deviceSign = div101.xxx）：
                            // 验证页会把 URL 里的 deviceid 原样当 devicesign 用，
                            // 必须与 v3/login 的 devicesign 字段一致，否则报"登录信息已过期"
                            onVerify(step.reviewUrl, com.yunx.app.data.network.XunleiDeviceFingerprint.deviceSign())
                        },
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    ) {
                        Text(
                            text = "短信收不到？应用内验证",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // 网页登录：另一套接口，不受 App 通道风控影响，收不到短信时最稳的一条路
            OutlinedButton(
                onClick = onWebLogin,
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) { Text("网页登录（不受短信风控影响）") }

            // 未设置密码：跳转迅雷官网设置（浏览器打开）
            TextButton(
                onClick = {
                    DesktopActions.openUrl("https://i.xunlei.com/xluser/validate/findpwd_acc.html")
                },
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                Text(
                    text = "未设置密码，点我前往设置",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
