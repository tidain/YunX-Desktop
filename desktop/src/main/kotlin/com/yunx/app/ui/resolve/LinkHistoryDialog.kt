package com.yunx.app.ui.resolve

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yunx.app.data.db.LinkHistoryEntity
import com.yunx.app.ui.components.FadeAlertDialog
import kotlinx.coroutines.flow.Flow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 链接历史弹窗：展示 link_history 表记录，支持搜索 / 单条删除 / 清空 / 重新解析。
 *
 * 用 FadeAlertDialog（窗口内覆盖层）而非 AlertDialog —— 主仓库弹窗规范，
 * 避免独立原生窗口阻塞 UI 线程 / 卡顿水波动画（详见 OverlayDialogRegistry KDoc）。
 *
 * @param visible 是否显示
 * @param historyFlow 历史记录数据源（observeAll 或 search 结果 Flow）
 * @param onDismiss 关闭弹窗
 * @param onSelect 选择某条记录，回调 (url, pwd)；调用方负责解析 + 关闭弹窗
 * @param onDelete 单条删除
 * @param onClear 清空全部
 */
@Composable
fun LinkHistoryDialog(
    visible: Boolean,
    historyFlow: Flow<List<LinkHistoryEntity>>,
    onDismiss: () -> Unit,
    onSelect: (url: String, pwd: String) -> Unit,
    onDelete: (Long) -> Unit,
    onClear: () -> Unit
) {
    if (!visible) return
    val list by historyFlow.collectAsState(initial = emptyList())
    var query by remember { mutableStateOf("") }

    // 本地过滤：DAO 的 search 是 Flow<List>，但弹窗内搜索框为本地实时过滤更跟手
    val filtered = remember(list, query) {
        if (query.isBlank()) list
        else list.filter {
            it.url.contains(query, ignoreCase = true) ||
                it.title.contains(query, ignoreCase = true) ||
                it.platform.contains(query, ignoreCase = true)
        }
    }

    val df = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }

    FadeAlertDialog(
        visible = visible,
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.History, contentDescription = null) },
        title = { Text("解析历史") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                // 搜索框
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("搜索链接 / 标题 / 平台") },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) {
                                Text("✕")
                            }
                        }
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    textStyle = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.size(8.dp))
                // 列表 + 清空按钮
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "共 ${filtered.size} 条",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (list.isNotEmpty()) {
                        TextButton(onClick = onClear) {
                            Icon(
                                Icons.Outlined.DeleteSweep,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.size(4.dp))
                            Text("清空")
                        }
                    }
                }
                Spacer(Modifier.size(4.dp))
                if (filtered.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 120.dp)
                            .padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (list.isEmpty()) "暂无历史记录" else "无匹配结果",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(filtered, key = { it.id }) { item ->
                            HistoryItem(
                                item = item,
                                timeText = df.format(Date(item.createTime)),
                                onClick = { onSelect(item.url, item.pwd) },
                                onDelete = { onDelete(item.id) }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

@Composable
private fun HistoryItem(
    item: LinkHistoryEntity,
    timeText: String,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick
                )
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title.ifBlank { item.url },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.size(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = item.platform,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.size(6.dp))
                    Text(
                        text = timeText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (item.pwd.isNotBlank()) {
                        Spacer(Modifier.size(6.dp))
                        Text(
                            text = "提取码 ${item.pwd}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    Icons.Outlined.Delete,
                    contentDescription = "删除",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
