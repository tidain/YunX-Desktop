package com.yunx.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.yunx.app.data.network.video.VideoMedia
import com.yunx.app.data.network.video.VideoMediaKind
import com.yunx.app.ui.BackHandler
import com.yunx.app.ui.components.ImageViewerOverlay
import com.yunx.app.ui.components.VideoPreviewOverlay
import com.yunx.app.ui.viewmodel.VideoResolveUiState
import com.yunx.app.ui.viewmodel.VideoResolveViewModel

/**
 * 分轨媒体（哔哩哔哩 DASH：音视频分离）的下载方式。
 *
 * - [MERGE]：视频轨 + 音轨都下，合轨成一个 MP4（默认）
 * - [AUDIO]：只下音轨（.m4a）
 * - [VIDEO]：只下视频轨（.mp4，无声）
 * - [SEPARATE]：两轨都下但**不合轨**，保存成两个独立文件
 */
enum class VideoStreamMode { MERGE, AUDIO, VIDEO, SEPARATE }

/**
 * 视频解析页宿主：加载 / 错误 / 结果三种状态 + 全屏看图。
 *
 * 结果页本身是纯展示组件（[VideoResultSection]），状态与多选都留在 ViewModel，
 * 这样切 Tab 再回来不会丢选择。
 *
 * @param onDownload 下载回调：媒体（含 DASH 音轨 / 动图资源）交给 [com.yunx.app.data.download.DownloadManager]；
 *   第二个参数是分轨媒体的下载方式（无分轨时恒为 [VideoStreamMode.MERGE]）
 */
@Composable
fun VideoResolveScreen(
    viewModel: VideoResolveViewModel,
    onBack: () -> Unit,
    onDownload: (List<VideoMedia>, VideoStreamMode) -> Unit,
    modifier: Modifier = Modifier
) {
    val state = viewModel.uiState
    // 预览同时带上该图片的请求头（视频图集 CDN 有防盗链）
    var preview by remember { mutableStateOf<Pair<String, Map<String, String>>?>(null) }
    // 视频预览：非空时铺播放器覆盖层
    var videoPreview by remember { mutableStateOf<VideoMedia?>(null) }
    // 动图（Live Photo）下载前的格式选择：非空表示等待用户在弹窗里选格式
    var pendingMotion by remember { mutableStateOf<List<VideoMedia>?>(null) }
    // 分轨媒体（B 站 DASH）下载前的方式选择：非空表示等待用户选「合并 / 仅音频 / 仅视频 / 分别保存」
    var pendingStream by remember { mutableStateOf<List<VideoMedia>?>(null) }

    // 含动图先问格式；含分轨再问下载方式；其余直接进下载队列
    fun startDownload(medias: List<VideoMedia>) {
        when {
            medias.any { it.motionUrl.isNotBlank() } -> pendingMotion = medias
            medias.any { it.audioUrl.isNotBlank() } -> pendingStream = medias
            else -> onDownload(medias, VideoStreamMode.MERGE)
        }
    }

    BackHandler { onBack() }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        when (state) {
            VideoResolveUiState.Idle -> Unit

            VideoResolveUiState.Loading -> Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.5.dp)
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "正在解析…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            is VideoResolveUiState.Error -> Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = state.message,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedButton(onClick = { viewModel.retry() }) { Text("重试") }
                TextButton(onClick = onBack) { Text("返回") }
            }

            is VideoResolveUiState.Success -> VideoResultSection(
                result = state.result,
                selected = viewModel.selected,
                onToggleSelect = viewModel::toggleSelect,
                onSelectAll = viewModel::selectAll,
                onClearSelection = viewModel::clearSelection,
                onDownloadSelected = {
                    val picked = state.result.medias.filterIndexed { index, _ ->
                        index in viewModel.selected
                    }
                    if (picked.isNotEmpty()) startDownload(picked)
                },
                onDownloadMedia = { media -> startDownload(listOf(media)) },
                onPreviewImage = { url, headers -> preview = url to headers },
                onPreviewVideo = { media -> videoPreview = media },
                onSelectQuality = viewModel::selectQuality,
                onBack = onBack
            )
        }

        // 全屏看图（点图集单元格触发）；Esc 关闭由 Overlay 自己注册的 BackHandler 处理
        ImageViewerOverlay(
            url = preview?.first,
            headers = preview?.second ?: emptyMap(),
            onDismiss = { preview = null }
        )

        // 视频预览播放器（JavaFX MediaView）；Esc 先退全屏再关闭，逻辑在 Overlay 内
        VideoPreviewOverlay(
            media = videoPreview,
            onDismiss = { videoPreview = null }
        )

        // 动图（Live Photo）：下载前询问保存格式
        pendingMotion?.let { medias ->
            MotionFormatDialog(
                motionCount = medias.count { it.motionUrl.isNotBlank() },
                totalCount = medias.size,
                onPick = { format ->
                    pendingMotion = null
                    onDownload(medias.map { it.withMotionFormat(format) }, VideoStreamMode.MERGE)
                },
                onDismiss = { pendingMotion = null }
            )
        }

        // 分轨媒体（B 站 DASH）：下载前询问「合并 / 仅音频 / 仅视频 / 分别保存」
        pendingStream?.let { medias ->
            StreamModeDialog(
                hasNonDash = medias.any { it.audioUrl.isBlank() },
                onPick = { mode ->
                    pendingStream = null
                    onDownload(medias, mode)
                },
                onDismiss = { pendingStream = null }
            )
        }
    }
}

/** 动图下载格式 */
private enum class MotionFormat { LIVE, STATIC_IMAGE, VIDEO }

/**
 * 按用户选择的格式改造媒体：
 * - Live 图：保持 `${url}` + `motionUrl`，由 DownloadManager 合成；
 * - 静态图：丢掉动图资源，只下静图；
 * - 视频：把动图资源当**普通视频**下载 —— 类型必须是 `VIDEO`，否则文件名仍走图集规则
 *   （动图直链多数没有扩展名，会回退成 `.jpg`）；扩展名由直链后缀决定，没有就给 `.mp4`。
 */
private fun VideoMedia.withMotionFormat(format: MotionFormat): VideoMedia = when (format) {
    MotionFormat.LIVE -> this
    MotionFormat.STATIC_IMAGE -> copy(motionUrl = "")
    MotionFormat.VIDEO -> copy(
        url = motionUrl,
        kind = VideoMediaKind.VIDEO,
        motionUrl = "",
        quality = ""
    )
}

/**
 * 动图格式选择弹窗（窗口内覆盖层；本页是全屏覆盖层、不在 OverlayDialogHost 之下，
 * 注册式 FadeAlertDialog 不会渲染，所以就地绘制）。
 */
@Composable
private fun MotionFormatDialog(
    motionCount: Int,
    totalCount: Int,
    onPick: (MotionFormat) -> Unit,
    onDismiss: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onDismiss() },
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
            modifier = Modifier
                .widthIn(min = 300.dp, max = 560.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {}
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text("保存动图", style = MaterialTheme.typography.titleLarge)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (totalCount > motionCount) {
                        "所选中有 $motionCount 项是动图（Live 图），其余按原格式保存。要保存成哪种格式？"
                    } else {
                        "这是动图（Live 图），要保存成哪种格式？"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))
                MotionFormatOption(
                    title = "Live 图（推荐）",
                    desc = "单张图片 + 内嵌动图，iOS/部分相册可播；桌面上按普通图片显示",
                    onClick = { onPick(MotionFormat.LIVE) }
                )
                MotionFormatOption(
                    title = "静态图片",
                    desc = "只保存封面静图（JPG/PNG）",
                    onClick = { onPick(MotionFormat.STATIC_IMAGE) }
                )
                MotionFormatOption(
                    title = "视频",
                    desc = "只保存动图部分（MP4/MOV），桌面播放器可直接播放",
                    onClick = { onPick(MotionFormat.VIDEO) }
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                }
            }
        }
    }
}

@Composable
private fun MotionFormatOption(title: String, desc: String, onClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(onClick = onClick)
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 分轨媒体下载方式弹窗（同样就地绘制，理由见 [MotionFormatDialog]）。
 *
 * 只在哔哩哔哩高清（DASH 音视频分离）时出现：合并 / 仅音频 / 仅视频 / 分别保存。
 */
@Composable
private fun StreamModeDialog(
    hasNonDash: Boolean,
    onPick: (VideoStreamMode) -> Unit,
    onDismiss: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onDismiss() },
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
            modifier = Modifier
                .widthIn(min = 320.dp, max = 560.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {}
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text("下载方式", style = MaterialTheme.typography.titleLarge)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (hasNonDash) {
                        "该视频是 DASH 分轨（音视频分离）。所选里还有普通媒体，它们会按原方式下载。要下载哪部分？"
                    } else {
                        "该视频是 DASH 分轨（音视频分离）。要下载哪部分？"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))
                MotionFormatOption(
                    title = "音视频合并（默认）",
                    desc = "视频轨 + 音轨都下载，自动合轨成一个 MP4",
                    onClick = { onPick(VideoStreamMode.MERGE) }
                )
                MotionFormatOption(
                    title = "仅下载音频",
                    desc = "只下载音轨，保存为 .m4a（体积最小）",
                    onClick = { onPick(VideoStreamMode.AUDIO) }
                )
                MotionFormatOption(
                    title = "仅下载视频",
                    desc = "只下载视频轨，保存为 .mp4（无声）",
                    onClick = { onPick(VideoStreamMode.VIDEO) }
                )
                MotionFormatOption(
                    title = "分别保存（不合并）",
                    desc = "音轨、视频轨各存一个文件（-video.mp4 / -audio.m4a），方便自行剪辑",
                    onClick = { onPick(VideoStreamMode.SEPARATE) }
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                }
            }
        }
    }
}
