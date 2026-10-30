package com.yunx.app.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yunx.app.data.network.video.VideoMedia
import com.yunx.app.data.network.video.VideoMediaKind
import com.yunx.app.data.network.video.VideoParseResult
import com.yunx.app.data.network.video.VideoQuality
import com.yunx.app.ui.components.RemoteImage
import com.yunx.app.ui.items.logoAsset
import com.yunx.app.ui.resolve.formatSize
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 图集网格单格最小宽度（桌面窗口宽，按可用宽度自适应列数，比固定两列更贴合屏宽） */
private val GridMinCellWidth = 180.dp

/** 图集格子 / 媒体行的圆角 */
private val CellShape = RoundedCornerShape(12.dp)

/**
 * 视频/图文解析结果区（桌面版）：顶栏 + 作品信息 + 图集网格（或媒体列表）+ 底部下载栏。
 *
 * 与上游的差异：图片改用桌面自带 [RemoteImage]（点击交给宿主放大）；**不含播放器/预览播放**；
 * **不含 BackHandler**（返回由宿主处理）；**没有长按多选** —— 选中集合由宿主持有，
 * 图集格子的复选框负责切换，有选中项时顶栏切成多选工具条。
 */
@Composable
internal fun VideoResultSection(
    result: VideoParseResult,
    /** 选中的 medias 下标 */
    selected: Set<Int>,
    onToggleSelect: (Int) -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onDownloadSelected: () -> Unit,
    onDownloadMedia: (VideoMedia) -> Unit,
    /** 点击图片：把直链交给宿主放大查看 */
    onPreviewImage: (String, Map<String, String>) -> Unit,
    /** 点击「预览」：把视频媒体交给宿主打开播放器 */
    onPreviewVideo: (VideoMedia) -> Unit,
    /** 切换清晰度（只在平台返回了可选档位时才有意义，如 B 站） */
    onSelectQuality: (Int) -> Unit = {},
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 空直链的图片既预览不了也下载不了：不铺进网格，否则格子里是个灰块
    val images = remember(result) {
        result.medias.withIndex().filter {
            it.value.kind == VideoMediaKind.IMAGE && it.value.url.isNotBlank()
        }
    }
    // 全是图片 = 图片笔记，走网格；只要含视频/音频就仍按行展示（清晰度/大小得用文字说明）
    val isImageNote = images.isNotEmpty() && result.medias.none { it.kind != VideoMediaKind.IMAGE }

    // 「多选态」由选中集合决定（桌面没有长按进入多选这一步）
    val selectionMode = selected.isNotEmpty()
    val selectableTotal = if (isImageNote) images.size else result.medias.size
    val allSelected = selectableTotal > 0 && selected.size >= selectableTotal

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (selectionMode) {
                SelectionTopBar(
                    selectedCount = selected.size,
                    allSelected = allSelected,
                    onExit = onClearSelection,
                    onSelectAll = if (allSelected) onClearSelection else onSelectAll,
                    onDownloadSelected = onDownloadSelected
                )
            } else {
                ResultTopBar(result = result, onBack = onBack)
            }

            if (result.notice.isNotBlank()) {
                NoticeBar(notice = result.notice)
            }

            if (result.qualities.isNotEmpty()) {
                QualityBar(
                    qualities = result.qualities,
                    selectedQn = result.selectedQn,
                    onSelect = onSelectQuality
                )
            }

            if (isImageNote) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    VideoMetaBlock(
                        result = result,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp)
                    )
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(GridMinCellWidth),
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(count = images.size, key = { images[it].index }) { i ->
                            val item = images[i]
                            ImageGridCell(
                                media = item.value,
                                position = i,
                                total = images.size,
                                selected = item.index in selected,
                                onToggleSelect = { onToggleSelect(item.index) },
                                onPreview = { onPreviewImage(item.value.url, item.value.headers) }
                            )
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item { VideoMetaBlock(result) }
                    if (result.medias.isEmpty()) {
                        item { EmptyMediaHint() }
                    }
                    items(count = result.medias.size) { index ->
                        val media = result.medias[index]
                        MediaRow(
                            index = index,
                            media = media,
                            onDownload = { onDownloadMedia(media) },
                            onPreviewImage = onPreviewImage,
                            onPreviewVideo = { onPreviewVideo(media) }
                        )
                    }
                }
            }

            if (result.medias.isNotEmpty()) {
                DownloadBar(
                    total = result.medias.size,
                    selectedCount = selected.size,
                    allSelected = allSelected,
                    onToggleSelectAll = if (allSelected) onClearSelection else onSelectAll,
                    onDownloadSelected = onDownloadSelected
                )
            }
        }
    }
}

/** 顶栏：返回 + 平台徽标 + 平台名 + 标题（固定不滚动，返回入口始终可见） */
@Composable
private fun ResultTopBar(result: VideoParseResult, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
        }
        // 平台 Logo：按图标自身形状原样画（不裁圆、不垫底色）
        Image(
            painter = painterResource(result.platform.logoAsset()),
            contentDescription = null,
            modifier = Modifier.size(40.dp),
            contentScale = ContentScale.Fit
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = result.platform.displayName,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = result.title.ifBlank { "视频内容" },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** 多选态顶栏：措辞与网盘多选一致，右侧直接给「全选 / 下载」，比桌面底部悬浮栏更好用 */
@Composable
private fun SelectionTopBar(
    selectedCount: Int,
    allSelected: Boolean,
    onExit: () -> Unit,
    onSelectAll: () -> Unit,
    onDownloadSelected: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onExit) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "退出多选")
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "已选 $selectedCount 项",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = if (allSelected) "已全选" else "点击图片右上角的勾选框继续选择",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        TextButton(onClick = onSelectAll) { Text(if (allSelected) "取消全选" else "全选") }
        Spacer(modifier = Modifier.width(4.dp))
        Button(onClick = onDownloadSelected, modifier = Modifier.height(40.dp)) { Text("下载") }
    }
}

/** notice 提示条：解析成功但部分内容降级时的一次性说明（不改地址，只讲原因） */
@Composable
private fun NoticeBar(notice: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.tertiaryContainer
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Outlined.Info,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onTertiaryContainer
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = notice,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
        }
    }
}

/**
 * 清晰度选择条：横向滚动的胶囊 chips，标出当前档位（[selectedQn]）。
 * 只在平台返回了可选档位时显示（目前 B 站会填）。切换档位会触发一次重新解析。
 */
@Composable
private fun QualityBar(
    qualities: List<VideoQuality>,
    selectedQn: Int,
    onSelect: (Int) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "清晰度",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        qualities.forEach { quality ->
            FilterChip(
                selected = quality.qn == selectedQn,
                onClick = { onSelect(quality.qn) },
                label = { Text(quality.label) }
            )
        }
    }
}

/** 作品信息块：作者、发布时间、条目数、正文（超长折叠，点击展开） */
@Composable
private fun VideoMetaBlock(result: VideoParseResult, modifier: Modifier = Modifier) {
    val metaLine = buildList {
        if (result.author.isNotBlank()) add(result.author)
        val date = formatVideoDate(result.timestampSeconds)
        if (date.isNotBlank()) add(date)
        if (result.medias.isNotEmpty()) add("共 ${result.medias.size} 项")
    }.joinToString("  ·  ")

    Column(modifier = modifier.padding(bottom = 12.dp)) {
        if (metaLine.isNotBlank()) {
            Text(
                text = metaLine,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (result.text.isNotBlank()) {
            var expanded by remember(result.text) { mutableStateOf(false) }
            val canExpand = result.text.length > 60
            Text(
                text = result.text,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = if (expanded) Int.MAX_VALUE else 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(top = if (metaLine.isNotBlank()) 6.dp else 0.dp)
                    .clickable(enabled = canExpand) { expanded = !expanded }
            )
            if (canExpand) {
                Text(
                    text = if (expanded) "收起" else "展开",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .clickable { expanded = !expanded }
                )
            }
        }
    }
}

/**
 * 图集缩略图：1:1 裁剪 + 左上角「3/9」角标 + 右上角勾选框。
 *
 * 点图片 = 交给宿主放大查看（[onPreview]）；点勾选框 = 切换选中（[onToggleSelect]）。
 * 选中时压一层主色薄纱：亮底图片上只画勾选框很容易看不出来。
 */
@Composable
private fun ImageGridCell(
    media: VideoMedia,
    position: Int,
    total: Int,
    selected: Boolean,
    onToggleSelect: () -> Unit,
    onPreview: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(CellShape)
    ) {
        RemoteImage(
            url = media.url,
            contentDescription = "第 ${position + 1} 张图片",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            fallback = Icons.Outlined.BrokenImage,
            headers = media.headers,
            onClick = onPreview
        )

        if (selected) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.28f))
            )
        }

        CellBadge(
            text = "${position + 1}/$total",
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(6.dp)
        )

        Checkbox(
            checked = selected,
            onCheckedChange = { onToggleSelect() },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(2.dp)
        )
    }
}

/** 缩略图角标（半透明胶囊）：压在明暗不一的图片上都读得清 */
@Composable
private fun CellBadge(text: String, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 单条媒体行：序号 + 类型 + 清晰度 + 大小，右侧「预览」（可播时）与「下载」。
 * 与上游的差异：混合结果里的图片仍可点缩略图放大；DASH 分轨靠 [VideoMedia.previewUrl] 预览。
 */
@Composable
private fun MediaRow(
    index: Int,
    media: VideoMedia,
    onDownload: () -> Unit,
    onPreviewImage: (String, Map<String, String>) -> Unit,
    onPreviewVideo: () -> Unit
) {
    val subtitle = buildList {
        if (media.quality.isNotBlank()) add(media.quality)
        // size <= 0 表示未知：不显示大小，而不是显示 "0 B"
        if (media.size > 0) add(formatSize(media.size))
        if (media.isHls) add("HLS")
    }.joinToString("  ·  ")

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = CellShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "${index + 1}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            if (media.kind == VideoMediaKind.IMAGE && media.url.isNotBlank()) {
                RemoteImage(
                    url = media.url,
                    contentDescription = "第 ${index + 1} 项图片",
                    modifier = Modifier.size(48.dp),
                    shape = RoundedCornerShape(8.dp),
                    contentScale = ContentScale.Crop,
                    fallback = Icons.Outlined.BrokenImage,
                    headers = media.headers,
                    onClick = { onPreviewImage(media.url, media.headers) }
                )
                Spacer(modifier = Modifier.width(12.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = videoMediaKindLabel(media.kind),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (media.isPreviewable()) {
                TextButton(onClick = onPreviewVideo) {
                    Icon(
                        imageVector = Icons.Outlined.PlayCircleOutline,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("预览")
                }
            }
            TextButton(onClick = onDownload) {
                Icon(
                    imageVector = Icons.Outlined.Download,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("下载")
            }
        }
    }
}

/**
 * 能否在线预览：
 * - 单文件视频直链（含 HLS）可以；
 * - DASH 分轨（[VideoMedia.audioUrl] 非空）只有在解析时另给了单文件预览直链
 *   （[VideoMedia.previewUrl]，B 站就是这么做的）时才可预览 —— 否则 JavaFX 无法把两路流串起来。
 */
private fun VideoMedia.isPreviewable(): Boolean =
    kind == VideoMediaKind.VIDEO && url.isNotBlank() &&
        (audioUrl.isBlank() || previewUrl.isNotBlank())

/** 媒体列表为空（通常意味着解析没拿到直链）时的占位说明 */
@Composable
private fun EmptyMediaHint() {
    Text(
        text = "未解析到可下载的媒体",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp)
    )
}

/**
 * 底部下载栏：共 N 项 + 全选开关 + 「下载所选」。
 * 上游这里是「全部下载」；桌面宿主只提供 `onDownloadSelected`，故改成「先全选、再下载所选」。
 */
@Composable
private fun DownloadBar(
    total: Int,
    selectedCount: Int,
    allSelected: Boolean,
    onToggleSelectAll: () -> Unit,
    onDownloadSelected: () -> Unit
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (selectedCount > 0) "已选 $selectedCount / $total 项" else "共 $total 项媒体",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onToggleSelectAll) { Text(if (allSelected) "取消全选" else "全选") }
            Spacer(modifier = Modifier.width(8.dp))
            Button(onClick = onDownloadSelected, enabled = selectedCount > 0, modifier = Modifier.height(44.dp)) {
                Text("下载所选（$selectedCount）")
            }
        }
    }
}

/** 媒体类型中文标签 */
private fun videoMediaKindLabel(kind: VideoMediaKind): String = when (kind) {
    VideoMediaKind.VIDEO -> "视频"
    VideoMediaKind.IMAGE -> "图片"
    VideoMediaKind.AUDIO -> "音频"
}

/** 发布时间戳（秒）→ `yyyy-MM-dd`；为 0 或非法时返回空串（调用方据此不显示） */
private fun formatVideoDate(timestampSeconds: Long): String {
    if (timestampSeconds <= 0L) return ""
    return runCatching {
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(timestampSeconds * 1000L))
    }.getOrDefault("")
}
