package com.yunx.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.m3.Markdown
import com.yunx.app.data.announcement.AnnouncementApi
import com.yunx.app.data.announcement.formatLocalDateTime
import com.yunx.app.data.announcement.parseIsoMillis
import com.yunx.app.data.announcement.relativeTime
import com.yunx.app.ui.components.GitHubMarkdownImageTransformer
import com.yunx.app.ui.components.ImageViewerOverlay
import com.yunx.app.ui.components.RemoteImage
import com.yunx.app.ui.GlobalSnackbarHost
import com.yunx.app.ui.theme.compactMarkdownTypography
import com.yunx.app.ui.viewmodel.AnnouncementViewModel

/**
 * 公告详情页（列表项点击进入）。
 *
 * 正文渲染复用 GitHub README 那一套：mikepenz GFM 渲染器（支持标题 / 列表 / 表格 / 代码 / 链接）
 * + 项目自研的 [GitHubMarkdownImageTransformer] 图片加载器。
 * 刻意**不用 WebView** 渲染：`content` 支持 Markdown/HTML，进 WebView 就必须自己扛 XSS 与 CSP；
 * 走 Compose 渲染则 HTML 标签只是普通文本，不存在脚本执行面（代价是 HTML 片段不解析）。
 *
 * 详情接口会让 viewCount +1，所以 ViewModel 里按 id 缓存，本页重组 / 返回再进都不会重复请求。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnnouncementDetailPage(
    state: AnnouncementViewModel.DetailUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    /** 右上角刷新：保留旧内容重拉（服务端改了正文时不必退出重进） */
    onRefresh: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    // 单击图片放大查看的当前图片 URL（null = 查看器未打开）
    var viewingImage by remember { mutableStateOf<String?>(null) }

    // 外层 Box：图片查看器覆盖整窗（含顶栏），关闭后回到详情页
    Box(modifier = modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            snackbarHost = { GlobalSnackbarHost() },
            topBar = {
                TopAppBar(
                    title = { Text("公告详情", style = MaterialTheme.typography.titleLarge) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    },
                    actions = {
                        IconButton(onClick = onRefresh) {
                            Icon(Icons.Outlined.Refresh, contentDescription = "刷新")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
            }
        ) { innerPadding ->
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                // 图片最大高度：按可用高度取比例（自适应窗口），再夹到 [160dp, 360dp] —— 下限保证
                // 小窗口下图不至于太小，上限保证大窗口下一张长图不会把正文挤到需要滑很久才看到。
                val maxImageHeight = if (maxHeight == Dp.Infinity) {
                    320.dp
                } else {
                    (maxHeight * 0.4f).coerceIn(160.dp, 360.dp)
                }
                when (state) {
                    is AnnouncementViewModel.DetailUiState.Loaded -> AnnouncementDetailContent(
                        item = state.item,
                        maxImageHeight = maxImageHeight,
                        onImageClick = { viewingImage = it }
                    )
                    is AnnouncementViewModel.DetailUiState.Failed -> AnnouncementErrorState(
                        message = state.message,
                        onRetry = onRetry,
                        modifier = Modifier.align(Alignment.Center)
                    )
                    // Idle / Loading：都按加载中处理（宿主一进来就发起请求，Idle 只是一瞬间）
                    else -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
            }
        }

        // 单击封面 / 正文图集 → 全窗口放大查看
        ImageViewerOverlay(url = viewingImage, onDismiss = { viewingImage = null })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AnnouncementDetailContent(
    item: AnnouncementApi.Announcement,
    maxImageHeight: Dp,
    onImageClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    // 正文排版与 README 预览共用同一份紧凑字号（见 ui/theme/Type.kt）
    val typography = remember { compactMarkdownTypography() }
    val publisher = item.publisher.name.ifBlank { item.author }
    // 封面已单独展示：images 里若重复包含封面则不重复渲染（上游 #143 同口径）
    val galleryImages = remember(item.images, item.coverImage) {
        item.images.filter { it != item.coverImage }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp)
    ) {
        item(key = "header") {
            Column {
                if (item.isPinned) {
                    AnnouncementChip(
                        text = "置顶公告",
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                }
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AnnouncementAvatar(
                        name = publisher.ifBlank { "公告" },
                        avatarUrl = item.publisher.avatarUrl,
                        size = 32.dp
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        if (publisher.isNotBlank()) {
                            Text(
                                text = publisher,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // 时间都解析不出来时（脏数据）整段不显示，避免出现 1970-01-01
                            if (item.effectiveMillis > 0L) {
                                Text(
                                    text = formatLocalDateTime(item.effectiveMillis),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                val relative = relativeTime(item.effectiveMillis)
                                if (relative.isNotBlank()) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "· $relative",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            if (item.viewCount > 0) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Icon(
                                    imageVector = Icons.Outlined.Visibility,
                                    contentDescription = null,
                                    modifier = Modifier.size(12.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.width(3.dp))
                                Text(
                                    text = item.viewCount.toString(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                val cover = item.coverImage
                if (!cover.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(16.dp))
                    // 封面按图片自身比例、以 [DetailImageMaxWidth] 为宽度上限展示（不占满整行更耐看），
                    // 高度上限为 maxImageHeight；底框紧贴图片本身（缩放后靠左，见 RemoteImage）
                    RemoteImage(
                        url = cover,
                        contentDescription = null,
                        shape = MaterialTheme.shapes.large,
                        contentScale = ContentScale.Fit,
                        autoHeight = true,
                        onClick = { onImageClick(cover) },
                        modifier = Modifier
                            .widthIn(max = DetailImageMaxWidth)
                            .heightIn(max = maxImageHeight)
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }

        item(key = "content") {
            val content = item.content
            if (content.isNullOrBlank()) {
                Text(
                    text = "（本条公告没有正文）",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Markdown(
                    content = content,
                    typography = typography,
                    imageTransformer = GitHubMarkdownImageTransformer
                )
            }
        }

        // 正文多图（服务端 images 数组）：作图片集附在正文之后，同样是图床直链、直接加载。
        // 多张时统一行高、宽度按各自比例，横向排布、放不下自动换行（FlowRow）；
        // 单张时沿用自适应高度，避免竖长图被压成很窄的一条。
        if (galleryImages.isNotEmpty()) {
            item(key = "images") {
                if (galleryImages.size == 1) {
                    val imageUrl = galleryImages.first()
                    RemoteImage(
                        url = imageUrl,
                        contentDescription = null,
                        shape = MaterialTheme.shapes.large,
                        contentScale = ContentScale.Fit,
                        autoHeight = true,
                        onClick = { onImageClick(imageUrl) },
                        modifier = Modifier
                            .widthIn(max = DetailImageMaxWidth)
                            .padding(top = 12.dp)
                            .heightIn(max = maxImageHeight)
                    )
                } else {
                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        galleryImages.forEach { imageUrl ->
                            RemoteImage(
                                url = imageUrl,
                                contentDescription = null,
                                shape = MaterialTheme.shapes.large,
                                contentScale = ContentScale.Fit,
                                fixedHeight = minOf(GalleryImageHeight, maxImageHeight),
                                onClick = { onImageClick(imageUrl) }
                            )
                        }
                    }
                }
            }
        }

        item(key = "footer") {
            Column {
                Spacer(modifier = Modifier.height(20.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant)
                )
                Spacer(modifier = Modifier.height(10.dp))
                val updatedMillis = parseIsoMillis(item.updatedAt) ?: item.effectiveMillis
                if (updatedMillis > 0L) {
                    Text(
                        text = buildString {
                            append("最后更新 ")
                            append(formatLocalDateTime(updatedMillis))
                            if (item.author.isNotBlank() && item.author != publisher) {
                                append(" · 作者 ")
                                append(item.author)
                            }
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/** 详情页图片宽度上限：不占满整行更耐看（窄窗口下会被可用宽度自动收窄） */
private val DetailImageMaxWidth = 520.dp

/** 图集统一行高：多图等高横向排布（宽度按各自比例），放不下自动换行 */
private val GalleryImageHeight = 160.dp
