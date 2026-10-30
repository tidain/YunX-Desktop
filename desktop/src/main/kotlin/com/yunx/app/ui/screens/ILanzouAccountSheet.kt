package com.yunx.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yunx.app.data.db.ILanzouAccountEntity
import com.yunx.app.data.db.MultiAccountEntity
import com.yunx.app.ui.SnackbarController
import com.yunx.app.ui.GlobalSnackbarHost
import com.yunx.app.util.DesktopActions
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 已登录蓝奏优享账号的底部弹窗：展示用户信息、登录时间、appToken（可展开/复制），并提供退出登录。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ILanzouAccountSheet(
    account: ILanzouAccountEntity,
    accounts: List<MultiAccountEntity>,
    onSetPrimary: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onRemove: (String) -> Unit,
    onAddAccount: () -> Unit,
    onDismiss: () -> Unit
) {
    var showFullToken by rememberSaveable { mutableStateOf(false) }

    val tokenPreviewLimit = 200
    val tokenTruncated = account.appToken.length > tokenPreviewLimit
    val displayToken = if (showFullToken || !tokenTruncated) {
        account.appToken
    } else {
        account.appToken.take(tokenPreviewLimit) + "…"
    }
    val loginTime = remember(account.updatedAt) {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(account.updatedAt))
    }
    // 账号可能是手机号/邮箱等敏感信息，展示时脱敏（与其它网盘一致，不明文列出账号）
    val displayName = maskILanzouAccount(account.nickname.ifBlank { account.account })
        .ifBlank { "蓝奏优享用户" }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val scrollState = rememberScrollState()
    val sheetNestedScroll = remember(scrollState) {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                val dy = available.y
                if (dy > 0 && scrollState.value >= scrollState.maxValue) {
                    return Offset(0f, dy)
                }
                return Offset.Zero
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (showFullToken) {
                        Modifier
                            .fillMaxHeight()
                            .verticalScroll(scrollState)
                            .nestedScroll(sheetNestedScroll)
                    } else {
                        Modifier
                    }
                )
                .padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 32.dp)
        ) {
            // 用户信息
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(52.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = displayName.take(1),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(
                        text = displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(MaterialTheme.colorScheme.primary, CircleShape)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "蓝奏云优享版 · 已登录",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 登录信息
            Text(
                text = "登录信息",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    if (account.account.isNotBlank()) {
                        ILanzouInfoRow(label = "登录账号", value = maskILanzouAccount(account.account))
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                    ILanzouInfoRow(label = "登录时间", value = loginTime)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "appToken",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = displayToken.ifBlank { "—" },
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 15.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        if (tokenTruncated) {
                            TextButton(onClick = { showFullToken = !showFullToken }) {
                                Text(if (showFullToken) "收起" else "展开全部")
                            }
                        }
                        TextButton(
                            onClick = {
                                DesktopActions.copyToClipboard(account.appToken)
                                SnackbarController.show("appToken 已复制")
                            },
                            enabled = account.appToken.isNotBlank()
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.ContentCopy,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("复制")
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 多账号：同一网盘可以同时登录多个账号，这里管理它们（主账号 / 备注 / 退出 / 添加）
            AccountListSection(
                accounts = accounts,
                onSetPrimary = onSetPrimary,
                onRename = onRename,
                onRemove = onRemove,
                onAddAccount = onAddAccount,
                onEmpty = onDismiss
            )

            // 复制提示（ModalBottomSheet 为独立窗口，需自带 Snackbar 宿主）
            GlobalSnackbarHost()
        }
    }
}

@Composable
private fun ILanzouInfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(72.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/** 账号脱敏：手机号保留前 3 后 4，邮箱保留首字符与域名，其它保留首尾各 1 位。 */
private fun maskILanzouAccount(value: String): String {
    val v = value.trim()
    if (v.isEmpty()) return v
    if (v.contains('@')) {
        val name = v.substringBefore('@')
        val domain = v.substringAfter('@')
        return if (name.length <= 1) v else "${name.first()}***@$domain"
    }
    val digits = v.filter { it.isDigit() }
    if (digits.length == 11 && v.all { it.isDigit() }) {
        return "${digits.take(3)}****${digits.takeLast(4)}"
    }
    return if (v.length <= 2) "${v.first()}*" else "${v.first()}***${v.last()}"
}
