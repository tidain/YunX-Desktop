@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.yunx.app.ui.resolve

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yunx.app.data.network.BaiduP2pSpec
import com.yunx.app.data.network.model.DownloadLink
import com.yunx.app.ui.SnackbarController
import com.yunx.app.ui.components.DownloadDirRow
import com.yunx.app.ui.components.FadeAlertDialog
import com.yunx.app.util.DesktopActions

/**
 * 下载直链弹窗：展示文件名与直链（长按直链复制），支持「开始下载」（分片多线程下载）。
 * 点「关闭」或弹窗外（管壁）关闭 = 放弃下载，由上层清理临时转存。
 *
 * 使用 FadeAlertDialog（窗口内覆盖层）而非原生 AlertDialog，避免桌面端原生窗口
 * 阻塞 UI 线程和点击事件丢失问题。
 */
@Composable
fun DownloadLinkDialog(
    link: DownloadLink,
    onDownload: () -> Unit,
    onDismiss: () -> Unit,
    /** 本次下载的保存目录；null/空 = 跟随全局设置（沿用旧提示文案） */
    saveDir: String? = null,
    /** 提供后弹窗内显示「选择/更改保存目录」入口 */
    onChangeSaveDir: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val isP2p = BaiduP2pSpec.isP2p(link.downloadUrl)
    FadeAlertDialog(
        visible = true,
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = link.filename,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        text = {
            Column {
                Text(
                    text = if (isP2p) "百度 P2P 下载任务已生成" else "下载直链已生成（有效期约 15-30 分钟）",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                if (isP2p) {
                    Text(
                        text = "此任务通过百度 P2P 节点下载，节点不可用时自动回退 CDN。仅下载，不上传或做种。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    )
                ) {
                    Text(
                        text = link.downloadUrl,
                        modifier = Modifier
                            .padding(12.dp)
                            .combinedClickable(
                                onClick = {},
                                onLongClick = {
                                    DesktopActions.copyToClipboard(link.downloadUrl)
                                    SnackbarController.show("下载链接已复制")
                                }
                            ),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 15.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = when {
                        !saveDir.isNullOrBlank() -> "保存到：$saveDir"
                        onChangeSaveDir != null -> "将保存到默认下载目录（可点击下方更改）"
                        else -> "点击「开始下载」将分片多线程下载并保存到下面的目录"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(6.dp))
                if (onChangeSaveDir != null) {
                    // 解析页支持「只改这一次任务的保存目录」（不覆盖全局设置）
                    TextButton(onClick = onChangeSaveDir) {
                        Text(if (saveDir.isNullOrBlank()) "选择保存目录" else "更改保存目录")
                    }
                } else {
                    // 云盘页没有任务级目录入口：提供就地改全局下载目录（与「设置 → 储存目录」同一项配置）
                    DownloadDirRow()
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onDownload() }
            ) {
                Icon(
                    imageVector = Icons.Outlined.Download,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("开始下载")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        }
    )
}
