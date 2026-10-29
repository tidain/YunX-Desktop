package com.yunx.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.m3.Markdown
import com.yunx.app.data.network.GitHubMirror
import com.yunx.app.data.prefs.SettingsRepository
import com.yunx.app.data.update.UpdateChecker
import com.yunx.app.ui.components.FadeAlertDialog
import com.yunx.app.ui.components.GitHubMarkdownImageTransformer
import com.yunx.app.ui.theme.compactMarkdownTypography

/**
 * 发现新版本弹窗：标题 + 当前/最新版本 + 更新说明（Markdown 渲染、可滚动限高）+ 下载更新 / 稍后 / 不再提示该版本。
 *
 * 桌面版同时提供「安装版(.exe)」和「便携版(.zip)」两个下载入口，
 * 由用户按需选择；若某个类型在 Release 中不存在，则不显示对应按钮。
 *
 * 弹窗走窗口内覆盖层（[FadeAlertDialog]），不创建原生窗口。
 */
@Composable
fun UpdateDialog(
    currentVersion: String,
    release: UpdateChecker.Release,
    onDownloadAsset: (url: String, name: String) -> Unit,
    onLater: () -> Unit,
    onIgnore: () -> Unit,
    downloading: Boolean = false,
    /** 使用镜像站下载（可选）；为 null 时不显示镜像站按钮 */
    onDownloadMirrorAsset: ((url: String, name: String) -> Unit)? = null,
    /**
     * 强制更新：发布说明里写了「强制更新：1」且当前版本在最低强制更新版本之上。
     * 这时弹窗不可关闭、没有「稍后 / 不再提示」，只能下载更新。
     */
    mandatory: Boolean = false
) {
    val exeAsset = release.assets.firstOrNull { it.name.endsWith(".exe", true) }
    val zipAsset = release.assets.firstOrNull { it.name.endsWith(".zip", true) }
    // 说明里的图片复用 README 的自研加载器（无 Coil，走 GitHubMirror 统一镜像加速）
    GitHubMarkdownImageTransformer.mirrorPrefix = GitHubMirror.activePrefix()
    // 紧凑字号：与 README 共用同一份排版（见 ui/theme/Type.kt）
    val noteTypography = remember { compactMarkdownTypography() }

    FadeAlertDialog(
        visible = true,
        // 强制更新时点遮罩 / 返回都不能关（必须下载更新后才能继续使用）
        onDismissRequest = { if (!mandatory) onLater() },
        icon = {
            Surface(
                modifier = Modifier.size(48.dp),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                androidx.compose.foundation.layout.Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.padding(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.SystemUpdate,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        },
        title = {
            Column {
                Text(
                    text = "发现新版本",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = release.tagName,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                    // 预发布版标记：开启「接受预发布版更新」后可能拿到 Pre-release，弹窗里明确标出来
                    if (release.prerelease) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.tertiaryContainer
                        ) {
                            Text(
                                text = "预发布",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "当前 $currentVersion",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        text = {
            Column {
                if (mandatory) {
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.errorContainer
                    ) {
                        Text(
                            text = "本次为强制更新：当前版本已停止支持，请下载更新后继续使用。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.fillMaxWidth().padding(10.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                }
                Text(
                    text = "更新内容",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(6.dp))
                // 更新说明：走 Markdown 渲染（可滚动 + 限高，长说明不撑爆弹窗）
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainerLow
                ) {
                    Markdown(
                        content = release.body.ifBlank { "暂无更新说明" },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 220.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(12.dp),
                        typography = noteTypography,
                        imageTransformer = GitHubMarkdownImageTransformer
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                // 下载入口（安装版 / 便携版，各自可选镜像站）
                if (exeAsset != null) {
                    Button(
                        onClick = { onDownloadAsset(exeAsset.downloadUrl, exeAsset.name) },
                        enabled = !downloading
                    ) {
                        if (downloading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("下载中…")
                        } else {
                            Icon(
                                imageVector = Icons.Outlined.Download,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("下载安装版 (.exe)")
                        }
                    }
                    if (onDownloadMirrorAsset != null) {
                        TextButton(onClick = { onDownloadMirrorAsset(exeAsset.downloadUrl, exeAsset.name) }) {
                            Text("镜像站下载安装版", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                if (zipAsset != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Button(
                        onClick = { onDownloadAsset(zipAsset.downloadUrl, zipAsset.name) },
                        enabled = !downloading
                    ) {
                        if (downloading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("下载中…")
                        } else {
                            Icon(
                                imageVector = Icons.Outlined.Download,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("下载便携版 (.zip)")
                        }
                    }
                    if (onDownloadMirrorAsset != null) {
                        TextButton(onClick = { onDownloadMirrorAsset(zipAsset.downloadUrl, zipAsset.name) }) {
                            Text("镜像站下载便携版", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                if (exeAsset == null && zipAsset == null) {
                    Text(
                        text = "该版本未提供安装包",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            if (mandatory) {
                // 强制更新：没有「稍后」，只提示必须下载
                Text(
                    text = "必须更新后才能继续使用",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error
                )
            } else {
                TextButton(onClick = onLater) {
                    Text("稍后")
                }
            }
        },
        dismissButton = if (mandatory) {
            null
        } else {
            {
                // 「不再提示该版本」= 按版本记住（写入 ignored_version），只跳过这一个 tag；
                // 将来发布更新的版本仍会提示。文案直说范围，避免被理解成「以后再也不用检查更新」。
                TextButton(onClick = onIgnore) {
                    Text("不再提示该版本", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    )
}
