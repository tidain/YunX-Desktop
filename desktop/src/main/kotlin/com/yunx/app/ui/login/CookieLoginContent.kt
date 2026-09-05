package com.yunx.app.ui.login

import com.yunx.app.ui.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yunx.app.ui.SnackbarController
import com.yunx.app.ui.jcef.JcefHolder
import com.yunx.app.ui.rememberGlobalSnackbarHostState
import com.yunx.app.util.DesktopActions
import kotlinx.coroutines.launch

/**
 * 桌面版 Cookie 粘贴登录通用界面：
 * 桌面无 WebView，登录流程改为「系统浏览器登录 → 复制 Cookie → 粘贴保存」。
 * 夸克 / UC / 百度 / 139 四个平台共用。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CookieLoginContent(
    title: String,
    loginUrl: String,
    platform: String,
    cookieRequirement: String,
    validityHint: String,
    onSave: suspend (String) -> Boolean,
    onBack: () -> Unit,
    onSaved: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var isSaving by remember { mutableStateOf(false) }
    var cookieInput by remember { mutableStateOf("") }
    var showTutorial by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { showTutorial = true }
    // JCEF 未就绪（未完成或上次失败）时再次触发后台初始化；
    // 成功后 JcefHolder.browserReady 翻转，登录页自动切回内嵌浏览器模式
    LaunchedEffect(Unit) { JcefHolder.initInBackground() }

    BackHandler(enabled = !isSaving) { onBack() }
    val snackbarHostState = rememberGlobalSnackbarHostState()

    fun doSave() {
        scope.launch {
            isSaving = true
            val saved = onSave(cookieInput.trim())
            isSaving = false
            if (saved) {
                SnackbarController.show("登录成功")
                onSaved()
            } else {
                SnackbarController.show("Cookie 无效：$cookieRequirement")
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(title, style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = { if (!isSaving) onBack() }, enabled = !isSaving) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { if (!isSaving) showTutorial = true }) {
                        Icon(
                            Icons.Outlined.Info,
                            contentDescription = "登录教程",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "桌面版通过「粘贴 Cookie」登录：",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "1. 点击下方「打开网页登录」，在系统浏览器中登录账号\n" +
                            "2. 登录后从浏览器复制 Cookie（F12 开发者工具 → 网络/Application → Cookies）\n" +
                            "3. 粘贴到下方输入框，点击「保存」",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            OutlinedTextField(
                value = cookieInput,
                onValueChange = { cookieInput = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp),
                placeholder = { Text("在此粘贴 Cookie 字符串…") },
                supportingText = { Text("$cookieRequirement；$validityHint") },
                minLines = 8,
                maxLines = 12
            )

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { doSave() },
                    enabled = cookieInput.isNotBlank() && !isSaving,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text("保存")
                    }
                }
                OutlinedButton(
                    onClick = {
                        val text = DesktopActions.readClipboard().orEmpty()
                        if (text.isNotBlank()) cookieInput = text
                        else SnackbarController.show("剪贴板为空")
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Outlined.ContentPaste, contentDescription = null)
                    Spacer(modifier = Modifier.size(6.dp))
                    Text("从剪贴板粘贴")
                }
                com.yunx.app.ui.jcef.AutoImportCookieButton(
                    platform = platform,
                    onImported = onSave,
                    onSaved = onSaved,
                    modifier = Modifier.fillMaxWidth()
                )
                TextButton(
                    onClick = { DesktopActions.openUrl(loginUrl) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Outlined.OpenInNew, contentDescription = null)
                    Spacer(modifier = Modifier.size(6.dp))
                    Text("打开网页登录")
                }
            }
        }
    }

    if (showTutorial) {
        AlertDialog(
            onDismissRequest = { showTutorial = false },
            icon = { Icon(Icons.Outlined.Info, contentDescription = null) },
            title = { Text("登录教程") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "1. 点击「打开网页登录」，在系统浏览器中登录账号",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "2. 登录后打开浏览器开发者工具（F12），在 网络(Network) 请求头或 Application → Cookies 中找到 Cookie 并完整复制",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "3. 粘贴到输入框点击「保存」，应用会自动校验并获取昵称",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "提示：$cookieRequirement；$validityHint",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showTutorial = false }) { Text("知道了") }
            }
        )
    }
}
