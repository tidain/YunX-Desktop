package com.yunx.app.ui.screens

import com.yunx.app.ui.theme.Motion
import com.yunx.app.ui.theme.animateItemIfAllowed
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yunx.app.data.db.DownloadTaskEntity
import com.yunx.app.data.download.DownloadGrouping
import com.yunx.app.data.download.DownloadPlatform
import com.yunx.app.data.download.DownloadSaver
import com.yunx.app.data.download.DownloadStats
import com.yunx.app.data.download.P2PLink
import com.yunx.app.ui.SnackbarController
import com.yunx.app.data.prefs.SettingsRepository
import com.yunx.app.ui.components.FadeAlertDialog
import com.yunx.app.ui.components.rememberDownloadDirPicker
import com.yunx.app.ui.theme.ThemeController
import com.yunx.app.ui.viewmodel.DownloadViewModel
import com.yunx.app.util.DesktopActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 下载页：任务列表（分片多线程下载 / 断点续传）、进度展示、暂停/继续/删除/打开。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadScreen(
    scrollBehavior: TopAppBarScrollBehavior,
    viewModel: DownloadViewModel,
    modifier: Modifier = Modifier
) {
    val tasks by viewModel.tasks.collectAsState()
    val stats by viewModel.stats.collectAsState()
    // 任务列表滚动状态（右侧滚动条 / 定位用）
    val listState = rememberLazyListState()
    val settingsRepo = remember { SettingsRepository() }
    var showAddDialog by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<DownloadTaskEntity?>(null) }
    var showDeleteAllConfirm by remember { mutableStateOf(false) }
    // 下载调试页（设置 → 开发调试 → 下载调试 打开后才有入口）：整页替换任务列表，在「下载」Tab 内展开
    var debugTaskId by remember { mutableStateOf<Long?>(null) }
    val downloadDebug = ThemeController.downloadDebug
    // 按 id 取当前任务（任务在调试页期间仍会变状态：暂停/继续/完成都要跟着刷新）；
    // 任务被删除时 debugTask 变 null，自动退回列表
    val debugTask = debugTaskId?.let { id -> tasks.firstOrNull { it.id == id } }
    if (debugTask != null) {
        DownloadDebugScreen(
            task = debugTask,
            stats = stats[debugTask.id],
            viewModel = viewModel,
            onPause = { viewModel.pause(debugTask.id) },
            onResume = { viewModel.resume(debugTask.id) },
            onBack = { debugTaskId = null }
        )
        return
    }
    val openDebug: ((DownloadTaskEntity) -> Unit)? =
        if (downloadDebug) ({ task -> debugTaskId = task.id }) else null

    Box(modifier = modifier.fillMaxSize()) {
        // 空状态 ↔ 任务列表之间做淡入淡出：此前是硬切，第一条任务入队 / 删完最后一条时画面会「跳」一下。
        // 用 AnimatedContent（而不是 Crossfade）便于后续按状态差异再定制过渡。
        AnimatedContent(
            targetState = tasks.isEmpty(),
            transitionSpec = { Motion.fade(180, 140) },
            label = "downloadListState"
        ) { empty ->
            if (empty) {
                Box(modifier = Modifier.fillMaxSize()) {
                    EmptyDownloadState(modifier = Modifier.align(Alignment.Center))
                }
            } else {
            Column(modifier = Modifier.fillMaxSize()) {
                // 批量操作栏：全部暂停 / 全部开始 / 删除全部
                DownloadBatchBar(
                    hasActive = tasks.any {
                        it.status == DownloadTaskEntity.STATUS_DOWNLOADING ||
                            it.status == DownloadTaskEntity.STATUS_PENDING
                    },
                    hasResumable = tasks.any {
                        it.status == DownloadTaskEntity.STATUS_PAUSED ||
                            it.status == DownloadTaskEntity.STATUS_FAILED
                    },
                    onPauseAll = { viewModel.pauseAll() },
                    onResumeAll = { viewModel.resumeAll() },
                    onDeleteAll = { showDeleteAllConfirm = true }
                )
                // 右侧常驻滚动条：任务多时可拖动定位、一眼看到当前位置，不必一直滚轮（滚轮照常可用）
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .nestedScroll(scrollBehavior.nestedScrollConnection),
                        contentPadding = PaddingValues(start = 16.dp, end = 24.dp, top = 8.dp, bottom = 96.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // 根目录任务（无相对路径）单独显示；下载失败的排到最上方（其余保持原有顺序）
                        val rootTasks = tasks.filter { !it.fileName.contains('/') }
                            .sortedBy { if (it.status == DownloadTaskEntity.STATUS_FAILED) 0 else 1 }
                        // 文件夹下载任务：按「保存根目录 + 顶级目录」分组。
                        // 只看文件夹名会把“同一个文件夹下载到两个不同位置”的任务错误合并，
                        // 展开后每个文件出现两条记录；Gopeed/内置下载器共用这套分组。
                        val globalSaveRoot = DownloadSaver.resolveSaveDir(settingsRepo.downloadDirUri).absolutePath
                        // 含下载失败子任务的文件夹整体排到最上方（其余保持原有分组顺序）
                        val folderGroups = tasks.filter { it.fileName.contains('/') }
                            .groupBy { DownloadGrouping.groupKey(it, globalSaveRoot) }
                            .entries
                            .sortedBy { entry ->
                                if (entry.value.any { it.status == DownloadTaskEntity.STATUS_FAILED }) 0 else 1
                            }

                        items(rootTasks, key = { it.id }) { task ->
                            DownloadTaskCard(
                                modifier = animateItemIfAllowed(),
                                task = task,
                                stats = stats[task.id],
                                onPause = { viewModel.pause(task.id) },
                                onResume = { viewModel.resume(task.id) },
                                onRemove = { pendingDelete = task },
                                onRedownload = { viewModel.redownload(task) },
                                onDebug = openDebug?.let { cb -> { cb(task) } }
                            )
                        }

                        folderGroups.forEach { (groupKey, groupTasks) ->
                            val folder = groupKey.second
                            val saveRoot = DownloadGrouping.folderSaveRoot(groupTasks.first(), globalSaveRoot)
                            item(key = "folder_${groupKey.first}_$folder") {
                                FolderDownloadGroup(
                                    modifier = animateItemIfAllowed(),
                                    folder = folder,
                                    saveRoot = saveRoot,
                                    tasks = groupTasks,
                                    stats = stats,
                                    onPause = { viewModel.pause(it) },
                                    onResume = { viewModel.resume(it) },
                                    onRemove = { pendingDelete = it },
                                    onRedownload = { viewModel.redownload(it) },
                                    onDebug = openDebug
                                )
                            }
                        }
                    }
                    VerticalScrollbar(
                        adapter = rememberScrollbarAdapter(listState),
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .fillMaxHeight()
                            .padding(end = 4.dp, top = 4.dp, bottom = 4.dp),
                        // 默认样式在深色主题下几乎看不见（未悬停时颜色很深），这里改成跟随主题的浅色，亮/暗都清晰
                        style = ScrollbarStyle(
                            minimalHeight = 24.dp,
                            thickness = 8.dp,
                            shape = RoundedCornerShape(4.dp),
                            hoverDurationMillis = 200,
                            unhoverColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                            hoverColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
                        )
                    )
                }
            }
        }
    }

        // 添加任务入口（手动粘贴直链 / 磁力链接的唯一入口）。
        // 注意：空状态文案里写着「也可点击右下角按钮手动添加」，这个按钮一旦缺失，那句说明就成了死指引。
        FloatingActionButton(
            onClick = { showAddDialog = true },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp)
        ) {
            Icon(Icons.Filled.Add, contentDescription = "添加下载任务")
        }
    }

    if (showAddDialog) {
        AddDownloadDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { url, name, saveDir ->
                showAddDialog = false
                viewModel.enqueue(url, name, saveDir = saveDir)
            }
        )
    }

    // 删除二次确认（可选同时删除本地文件）
    pendingDelete?.let { task ->
        DeleteConfirmDialog(
            task = task,
            onDismiss = { pendingDelete = null },
            onConfirm = { deleteLocal ->
                pendingDelete = null
                viewModel.remove(task.id, deleteLocal)
            }
        )
    }

    // 删除全部任务二次确认（可选同时删除本地文件）
    if (showDeleteAllConfirm) {
        var deleteAllLocal by remember { mutableStateOf(false) }
        val hasCompletedFile = tasks.any {
            it.status == DownloadTaskEntity.STATUS_COMPLETED && it.savePath.isNotBlank()
        }
        AlertDialog(
            onDismissRequest = { showDeleteAllConfirm = false },
            title = { Text("删除全部任务") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "确定删除所有下载任务吗？删除后任务记录将被清除，且不可恢复。",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (hasCompletedFile) {
                        // #204：整行可点
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .toggleable(
                                    value = deleteAllLocal,
                                    role = Role.Checkbox,
                                    onValueChange = { deleteAllLocal = it }
                                ),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = deleteAllLocal,
                                onCheckedChange = null
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "同时删除本地文件",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        Text(
                            text = "勾选后将一并删除所有已下载到 Download 目录的文件，且不可恢复。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteAllConfirm = false
                        viewModel.removeAll(deleteAllLocal)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("全部删除") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteAllConfirm = false }) { Text("取消") }
            }
        )
    }
}

/** 批量操作栏：全部暂停 / 全部开始 / 删除全部（Material3 紧凑按钮，无可用操作时禁用） */
@Composable
private fun DownloadBatchBar(
    hasActive: Boolean,
    hasResumable: Boolean,
    onPauseAll: () -> Unit,
    onResumeAll: () -> Unit,
    onDeleteAll: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(onClick = onPauseAll, enabled = hasActive) {
            Icon(
                imageVector = Icons.Outlined.Pause,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text("全部暂停")
        }
        TextButton(onClick = onResumeAll, enabled = hasResumable) {
            Icon(
                imageVector = Icons.Outlined.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text("全部开始")
        }
        Spacer(modifier = Modifier.weight(1f))
        TextButton(onClick = onDeleteAll) {
            Icon(
                imageVector = Icons.Outlined.Delete,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text("删除全部", color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun DeleteConfirmDialog(
    task: DownloadTaskEntity,
    onDismiss: () -> Unit,
    onConfirm: (deleteLocal: Boolean) -> Unit
) {
    var deleteLocal by remember { mutableStateOf(false) }
    val hasLocalFile = task.status == DownloadTaskEntity.STATUS_COMPLETED && task.savePath.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除下载任务") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "确定删除「${task.fileName}」吗？",
                    style = MaterialTheme.typography.bodyMedium
                )
                if (hasLocalFile) {
                    // #204：整行可点（不只是勾选框本身），点文字也能切换
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(
                                value = deleteLocal,
                                role = Role.Checkbox,
                                onValueChange = { deleteLocal = it }
                            ),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = deleteLocal,
                            onCheckedChange = null
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "同时删除本地文件",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
                Text(
                    text = if (hasLocalFile) {
                        "勾选后将一并删除已下载到 Download 目录的文件，且不可恢复。"
                    } else if (task.status == DownloadTaskEntity.STATUS_COMPLETED) {
                        "该任务没有已完成的本地文件。"
                    } else {
                        "该任务尚未完成，删除后将同时清除已下载的临时文件，且不可恢复。"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(deleteLocal) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) { Text("删除") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
private fun EmptyDownloadState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            modifier = Modifier.size(64.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHighest
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Outlined.Download,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "暂无下载任务",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "解析分享后点击文件即可加入下载队列\n也可点击右下角按钮手动添加",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * 文件夹下载组：同一「顶级目录」下的所有任务合并为一个可展开卡片。
 * 收起时显示文件夹名 + 统计 + 总体进度；展开后显示子任务（含子文件夹内文件）紧凑列表。
 */
@Composable
private fun FolderDownloadGroup(
    modifier: Modifier = Modifier,
    folder: String,
    /** 该组的保存根目录（用于区分“同名文件夹下载到不同位置”的多组任务） */
    saveRoot: String,
    tasks: List<DownloadTaskEntity>,
    stats: Map<Long, DownloadStats>,
    onPause: (Long) -> Unit,
    onResume: (Long) -> Unit,
    onRemove: (DownloadTaskEntity) -> Unit,
    onRedownload: (DownloadTaskEntity) -> Unit,
    onDebug: ((DownloadTaskEntity) -> Unit)? = null
) {
    // ★ 用 rememberSaveable（而非 remember）：切到别的 Tab 再切回来时，
    //   下载页组合会被重建，remember 会让折叠状态复位成「展开」。
    // ★ 折叠状态按「保存根目录 + 文件夹名」分别保存：同名文件夹下载到不同位置时互不影响
    var expanded by rememberSaveable(folder, saveRoot) { mutableStateOf(true) }
    val completed = tasks.count { it.status == DownloadTaskEntity.STATUS_COMPLETED }
    val totalSize = tasks.sumOf { it.totalSize }
    // 聚合显示钳制：任何单项竞态残留都不会让"已下载 > 总大小"
    val downloaded = minOf(tasks.sumOf { it.downloadedSize }, totalSize)
    val fraction = if (totalSize > 0) {
        (downloaded.toFloat() / totalSize).coerceIn(0f, 1f)
    } else 0f
    val done = completed == tasks.size

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column {
            // 头部：点击展开/收起
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(start = 14.dp, end = 10.dp, top = 12.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 文件夹图标（圆角方块，主色容器）
                Surface(
                    modifier = Modifier.size(44.dp),
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Outlined.Folder,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = folder,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "${completed}/${tasks.size} 个文件 · ${formatSize(downloaded)} / ${formatSize(totalSize)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "保存到：${saveRoot.ifBlank { "默认下载目录" }}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                // 总体进度徽标（有子任务在合并分片时显示"合并中"，避免停在 100% 像卡死）
                val merging = tasks.any { (stats[it.id]?.mergePercent ?: -1) >= 0 }
                Surface(
                    shape = RoundedCornerShape(50),
                    color = if (done) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHighest
                    }
                ) {
                    Text(
                        text = when {
                            done -> "已完成"
                            merging -> "合并中"
                            else -> "${(fraction * 100).toInt()}%"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (done) {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
                Spacer(modifier = Modifier.width(2.dp))
                Icon(
                    imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = if (expanded) "收起" else "展开",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // 展开区：总体进度条 + 子任务紧凑列表
            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn(tween(200)) + expandVertically(tween(200), expandFrom = Alignment.Top),
                exit = fadeOut(tween(150)) + shrinkVertically(tween(150), shrinkTowards = Alignment.Top)
            ) {
                Column {
                    // 总体进度条（已完成时隐藏）
                    if (!done) {
                        LinearProgressIndicator(
                            progress = { fraction },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                        )
                    }
                    // 子任务列表（紧凑行，含子文件夹内文件）；下载失败的子任务置顶（其余保持原有顺序）
                    val orderedTasks = tasks.sortedBy {
                        if (it.status == DownloadTaskEntity.STATUS_FAILED) 0 else 1
                    }
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        orderedTasks.forEach { task ->
                            DownloadSubTaskRow(
                                task = task,
                                stats = stats[task.id],
                                onPause = { onPause(task.id) },
                                onResume = { onResume(task.id) },
                                onRemove = { onRemove(task) },
                                onRedownload = { onRedownload(task) },
                                onDebug = onDebug?.let { cb -> { cb(task) } }
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 文件夹组内子任务紧凑行：相对路径 + 状态 + 进度条 + 操作按钮。
 * 相比独立任务卡更轻量，适合嵌套在文件夹组内。
 */
@Composable
private fun DownloadSubTaskRow(
    task: DownloadTaskEntity,
    stats: DownloadStats?,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRemove: () -> Unit,
    onRedownload: () -> Unit,
    onDebug: (() -> Unit)? = null
) {
    val isDownloading = task.status == DownloadTaskEntity.STATUS_DOWNLOADING ||
        task.status == DownloadTaskEntity.STATUS_PENDING
    // 合并阶段进度（-1 = 不在合并）：合并时下载早就 100% 了，进度条改用合并百分比，
    // 否则大文件会一直停在 100% 像卡死
    val mergePercent = stats?.mergePercent ?: -1
    val verifying = stats?.verifying == true
    val merging = mergePercent >= 0 && !verifying
    val fraction = when {
        mergePercent >= 0 -> mergePercent / 100f
        task.totalSize > 0 -> (task.downloadedSize.toFloat() / task.totalSize).coerceIn(0f, 1f)
        else -> 0f
    }
    // 显示相对路径（去掉顶级目录前缀，如 "A/B/b.mp4" → "B/b.mp4"）
    val displayName = task.fileName.substringAfter('/')
    // 长按任务行弹出操作菜单（复制直链 / 重新下载 / 删除）
    var showMenu by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = {})
            .onRightClick { showMenu = true },
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 文件小图标
                Surface(
                    modifier = Modifier.size(32.dp),
                    shape = RoundedCornerShape(9.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Outlined.InsertDriveFile,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = when {
                            verifying -> "校验中 · $mergePercent%"
                            merging -> "合并中 · $mergePercent%"
                            isDownloading && stats != null && stats.speed > 0 -> {
                                // 有速度时也要带上 P2P 的 peer 概览（BT 任务靠它判断「有没有在连上」）
                                val peer = p2pPeerText(task, stats)
                                val base = "${DownloadTaskEntity.statusText(task.status)} · ${formatSpeed(stats.speed)}"
                                if (peer.isNotEmpty()) "$base · $peer" else base
                            }
                            task.status == DownloadTaskEntity.STATUS_COMPLETED && task.avgSpeed > 0 ->
                                "${DownloadTaskEntity.statusText(task.status)} · 平均 ${formatSpeed(task.avgSpeed)}"
                            else -> taskStatusLine(task, stats)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (task.status == DownloadTaskEntity.STATUS_FAILED) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
                // 主操作（暂停/继续/重试/打开）
                when (task.status) {
                    DownloadTaskEntity.STATUS_DOWNLOADING,
                    DownloadTaskEntity.STATUS_PENDING -> IconButton(onClick = onPause, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Outlined.Pause, contentDescription = "暂停",
                            tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp)
                        )
                    }
                    DownloadTaskEntity.STATUS_PAUSED -> IconButton(onClick = onResume, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Outlined.PlayArrow, contentDescription = "继续",
                            tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp)
                        )
                    }
                    DownloadTaskEntity.STATUS_FAILED -> IconButton(onClick = onResume, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Outlined.Refresh, contentDescription = "重试",
                            tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp)
                        )
                    }
                    DownloadTaskEntity.STATUS_COMPLETED -> Row {
                        IconButton(
                            onClick = {
                                if (!DesktopActions.revealFile(task.savePath)) {
                                    SnackbarController.show("无法定位该文件")
                                }
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.Outlined.Folder, contentDescription = "在文件夹中显示",
                                tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp)
                            )
                        }
                        IconButton(
                            onClick = { openSavedFile(task.savePath) },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.Outlined.OpenInNew, contentDescription = "打开",
                                tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
                // 删除
                IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Outlined.Delete, contentDescription = "删除",
                        tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp)
                    )
                }
            }
            // 细进度条（完成态折叠，带过渡动画）
            AnimatedVisibility(
                visible = task.status != DownloadTaskEntity.STATUS_COMPLETED,
                enter = expandVertically(tween(200)) + fadeIn(tween(200)),
                exit = shrinkVertically(tween(200)) + fadeOut(tween(150))
            ) {
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                    color = if (task.status == DownloadTaskEntity.STATUS_FAILED) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                )
            }
        }

        // 右键任务行弹出操作菜单（复制分享链接 / 复制直链 / 调试信息 / 重新下载 / 删除）
        TaskContextMenu(
            visible = showMenu,
            title = displayName,
            shareUrl = task.shareUrl,
            directUrl = task.url,
            onDismiss = { showMenu = false },
            onRedownload = onRedownload,
            onRemove = onRemove,
            onDebug = onDebug
        )
    }
}

@Composable
private fun DownloadTaskCard(
    modifier: Modifier = Modifier,
    task: DownloadTaskEntity,
    stats: DownloadStats?,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRemove: () -> Unit,
    onRedownload: () -> Unit,
    /** 「下载调试」开着时由外层传入「打开调试信息页」；关闭时为 null（右键菜单不显示该入口） */
    onDebug: (() -> Unit)? = null
) {
    val isDownloading = task.status == DownloadTaskEntity.STATUS_DOWNLOADING ||
        task.status == DownloadTaskEntity.STATUS_PENDING
    // 合并阶段进度（-1 = 不在合并）：合并时下载早就 100% 了，界面改显示合并百分比
    val mergePercent = stats?.mergePercent ?: -1
    val verifying = stats?.verifying == true
    val merging = mergePercent >= 0 && !verifying
    val fraction = when {
        mergePercent >= 0 -> mergePercent / 100f
        task.totalSize > 0 -> (task.downloadedSize.toFloat() / task.totalSize).coerceIn(0f, 1f)
        else -> 0f
    }
    // 长按任务卡弹出操作菜单（复制直链 / 重新下载 / 删除）
    var showMenu by remember { mutableStateOf(false) }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .clickable(onClick = {})
            .onRightClick { showMenu = true },
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(40.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHighest
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Outlined.InsertDriveFile,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = task.fileName,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = when {
                            verifying -> "校验中 · $mergePercent%"
                            merging -> "合并中 · $mergePercent%"
                            else -> taskStatusLine(task, stats)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // 主操作按钮
                when (task.status) {
                    DownloadTaskEntity.STATUS_DOWNLOADING,
                    DownloadTaskEntity.STATUS_PENDING -> IconButton(onClick = onPause) {
                        Icon(Icons.Outlined.Pause, contentDescription = "暂停", tint = MaterialTheme.colorScheme.primary)
                    }
                    DownloadTaskEntity.STATUS_PAUSED -> IconButton(onClick = onResume) {
                        Icon(Icons.Outlined.PlayArrow, contentDescription = "继续", tint = MaterialTheme.colorScheme.primary)
                    }
                    DownloadTaskEntity.STATUS_FAILED -> IconButton(onClick = onResume) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "重试", tint = MaterialTheme.colorScheme.error)
                    }
                    DownloadTaskEntity.STATUS_COMPLETED -> Row {
                        IconButton(onClick = {
                            if (!DesktopActions.revealFile(task.savePath)) {
                                SnackbarController.show("无法定位该文件")
                            }
                        }) {
                            Icon(
                                Icons.Outlined.Folder,
                                contentDescription = "在文件夹中显示",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        IconButton(onClick = {
                            openSavedFile(task.savePath)
                        }) {
                            Icon(Icons.Outlined.OpenInNew, contentDescription = "打开", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 失败原因（红色小字展示具体错误）
            if (task.status == DownloadTaskEntity.STATUS_FAILED && task.errorMsg.isNotBlank()) {
                Text(
                    text = "失败原因：${task.errorMsg}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(6.dp))
            }

            // 实时统计 + 进度条：完成态整体折叠（带高度过渡动画，不残留空白）
            AnimatedVisibility(
                visible = task.status != DownloadTaskEntity.STATUS_COMPLETED,
                enter = expandVertically(tween(200)) + fadeIn(tween(200)),
                exit = shrinkVertically(tween(200)) + fadeOut(tween(150))
            ) {
                Column {
                    if (isDownloading && stats != null && stats.speed > 0) {
                        Text(
                            text = "${formatSpeed(stats.speed)} · 剩余 ${formatRemain(stats.remainMillis)} · ${stats.chunkCount} 线程",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                    }
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.fillMaxWidth(),
                        color = when (task.status) {
                            DownloadTaskEntity.STATUS_FAILED -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.primary
                        },
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (task.status == DownloadTaskEntity.STATUS_COMPLETED) {
                        if (task.avgSpeed > 0) {
                            "平均 ${formatSpeed(task.avgSpeed)} · ${formatSize(task.totalSize)}"
                        } else {
                            formatSize(task.totalSize)
                        }
                    } else if (verifying) {
                        "正在校验直写文件 · $mergePercent% · ${formatSize(task.totalSize)}"
                    } else if (merging) {
                        // 合并阶段：底部不要再显示"已下载 100%"，明确告知正在合并
                        "正在合并分片 · $mergePercent% · ${formatSize(task.totalSize)}"
                    } else {
                        progressText(task)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onRemove) {
                    Icon(
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            }
        }

        // 右键任务卡弹出操作菜单（复制分享链接 / 复制直链 / 调试信息 / 重新下载 / 删除）
        TaskContextMenu(
            visible = showMenu,
            title = task.fileName,
            shareUrl = task.shareUrl,
            directUrl = task.url,
            onDismiss = { showMenu = false },
            onRedownload = onRedownload,
            onRemove = onRemove,
            onDebug = onDebug
        )
    }
}

private fun openSavedFile(savePath: String) {
    if (savePath.isBlank()) return
    if (!DesktopActions.openFile(savePath)) {
        SnackbarController.show("无法打开该文件")
    }
}

/**
 * 任务状态行文案。
 *
 * BT（磁力 / 种子）/ ed2k 这类 P2P 任务额外拼上 peer 概览（种子 / 连接 / 总数）：
 * 这些是它们「应当显示」的关键信息——HTTP 任务只看速度就够了，P2P 有没有 peer 才决定能不能下得动。
 * peer 数字来自引擎 `stats.runtime`，取不到（-1）就不显示对应片段。
 */
private fun taskStatusLine(task: DownloadTaskEntity, stats: DownloadStats?): String {
    val peerText = p2pPeerText(task, stats)
    val peerSuffix = if (peerText.isNotEmpty()) " · $peerText" else ""
    // 磁力/种子任务在引擎拿到元数据之前 totalSize 一直是 0：这时按进度显示会变成「下载中」却没有任何数字，
    // 看起来像卡死。BT 的元数据要等 DHT/tracker 找到 peer，慢起来是分钟级，明说在解析更诚实。
    if (task.status == DownloadTaskEntity.STATUS_DOWNLOADING &&
        task.totalSize <= 0 &&
        P2PLink.matches(task.url)
    ) {
        return "正在解析（等待元数据，可能需要一两分钟）$peerSuffix"
    }
    val status = DownloadTaskEntity.statusText(task.status)
    val base = if (task.totalSize > 0) {
        // 显示值钳制到 total（防恢复竞态残留导致显示超总大小）
        val shown = minOf(task.downloadedSize, task.totalSize)
        "$status · ${formatSize(shown)} / ${formatSize(task.totalSize)}"
    } else {
        status
    }
    return "$base$peerSuffix"
}

/** P2P 任务的 peer 概览文案（种子 / 连接 / 已知 peer 总数）；非 P2P 或取不到数字时返回空串 */
private fun p2pPeerText(task: DownloadTaskEntity, stats: DownloadStats?): String {
    if (stats == null) return ""
    val isP2P = task.platform == DownloadPlatform.MAGNET || task.platform == DownloadPlatform.ED2K
    if (!isP2P) return ""
    val parts = buildList {
        if (stats.seeds >= 0) add("种子 ${stats.seeds}")
        if (stats.peers >= 0) add("连接 ${stats.peers}")
        if (stats.totalPeers >= 0) add("节点 ${stats.totalPeers}")
    }
    return parts.joinToString(" · ")
}

private fun progressText(task: DownloadTaskEntity): String {
    if (task.totalSize <= 0) return ""
    // 显示值钳制到 total（防恢复竞态残留导致显示超总大小）
    val shown = minOf(task.downloadedSize, task.totalSize)
    val percent = (shown * 100 / task.totalSize).toInt().coerceIn(0, 100)
    return "已下载 ${formatSize(shown)} / ${formatSize(task.totalSize)} · $percent%"
}

private fun formatSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var i = 0
    while (value >= 1024 && i < units.size - 1) {
        value /= 1024
        i++
    }
    return String.format("%.1f %s", value, units[i])
}

private fun formatSpeed(bytesPerSec: Long): String {
    if (bytesPerSec <= 0) return "0 B/s"
    val units = arrayOf("B/s", "KB/s", "MB/s", "GB/s")
    var value = bytesPerSec.toDouble()
    var i = 0
    while (value >= 1024 && i < units.size - 1) {
        value /= 1024
        i++
    }
    return String.format("%.1f %s", value, units[i])
}

private fun formatRemain(millis: Long): String {
    if (millis < 0) return "计算中"
    val sec = millis / 1000
    return when {
        sec < 60 -> "${sec}秒"
        sec < 3600 -> "${sec / 60}分${sec % 60}秒"
        else -> "${sec / 3600}时${(sec % 3600) / 60}分"
    }
}

@Composable
private fun AddDownloadDialog(
    onDismiss: () -> Unit,
    onConfirm: (url: String, name: String, saveDir: String) -> Unit
) {
    var url by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    // 用户是否手动改过「名称」：没改过就随 url 实时重算显示名，改过就不再覆盖
    var nameEdited by remember { mutableStateOf(false) }
    var saveDir by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val pickSaveDir = rememberDownloadDirPicker()
    val defaultDir = remember {
        DownloadSaver.resolveSaveDir(
            com.yunx.app.data.prefs.SettingsRepository().downloadDirUri
        ).absolutePath
    }
    // 磁力 / ed2k / 本地 .torrent 种子文件：这三种 P2P 资源都不能靠 URL 猜出落盘名，
    // 而且只有 Gopeed 引擎能下（DownloadManager 会自己兜底文件名并给出拦截原因）。
    val isP2P = P2PLink.matches(url)
    // 把 url 填好时顺手补一个显示名占位（磁力取 dn=、ed2k 取文件名、种子取文件名）。
    // 只要用户还没手动改过「名称」就随输入实时重算：手动敲链接时中途会出现半截地址，
    // 若只补一次就会把半截串锁成文件名（粘贴整条链接则一次到位）。
    fun syncPlaceholderName(value: String) {
        if (nameEdited) return
        name = if (P2PLink.matches(value)) {
            P2PLink.displayName(value)
        } else {
            value.substringAfterLast('/').take(80)
        }
    }
    // 选择本地 .torrent 文件：直接把绝对路径当作 url（引擎按扩展名识别为种子并 os.Open 读取）
    fun pickTorrentFile() {
        scope.launch {
            val path = withContext(Dispatchers.IO) {
                DesktopActions.pickFile(
                    filters = listOf(
                        "BT 种子文件 (*.torrent)" to "*.torrent",
                        "所有文件 (*.*)" to "*.*"
                    )
                )
            } ?: return@launch
            url = path
            syncPlaceholderName(path)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加下载任务") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "保存到：${saveDir.ifBlank { "$defaultDir（默认）" }}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    TextButton(onClick = { pickSaveDir { saveDir = it } }) {
                        Text(if (saveDir.isBlank()) "选择目录" else "更改")
                    }
                }
                OutlinedTextField(
                    value = url,
                    onValueChange = {
                        url = it
                        syncPlaceholderName(it)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("文件直链 URL / 磁力 / ed2k 链接") },
                    singleLine = true,
                    trailingIcon = {
                        IconButton(onClick = { pickTorrentFile() }) {
                            Icon(Icons.Outlined.InsertDriveFile, contentDescription = "选择 .torrent 种子文件")
                        }
                    }
                )
                if (isP2P) {
                    // 磁力 / ed2k / 种子文件只有 Gopeed 内核能下（内置分片下载器是纯 HTTP 实现），提前说清楚省得白等
                    Text(
                        text = "磁力 / ed2k / BT 种子需要 Gopeed 引擎：内核未导入或未切换时会直接提示，不会被当成普通链接去下载",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        nameEdited = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(if (isP2P) "显示名称（可留空，完成后会用真实资源名覆盖）" else "保存文件名") },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(url.trim(), name.trim(), saveDir) },
                // P2P（磁力 / ed2k / 种子文件）允许留空文件名：真正的名字要等引擎拿到元数据
                // （DownloadManager 会自己兜底）
                enabled = url.isNotBlank() && (name.isNotBlank() || isP2P)
            ) { Text("开始下载") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/**
 * 任务右键菜单（统一弹窗材质）：
 * 复制分享链接 / 复制直链 / 重新下载 / 删除任务。
 * 替代原先的 AlertDialog，遵循主仓库 FadeAlertDialog 弹窗规范
 * （在窗口内以 OverlayDialogHost 渲染，不创建原生窗口、不阻塞 UI 线程）。
 */
@Composable
private fun TaskContextMenu(
    visible: Boolean,
    title: String,
    shareUrl: String,
    directUrl: String,
    onDismiss: () -> Unit,
    onRedownload: () -> Unit,
    onRemove: () -> Unit,
    /** 「调试信息」入口：仅「下载调试」开关打开时非 null（见设置 → 开发调试） */
    onDebug: (() -> Unit)? = null
) {
    FadeAlertDialog(
        visible = visible,
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                ContextMenuRow(icon = Icons.Outlined.Link, label = "复制分享链接") {
                    onDismiss()
                    if (shareUrl.isNotBlank()) {
                        DesktopActions.copyToClipboard(shareUrl)
                        SnackbarController.show("分享链接已复制")
                    } else {
                        SnackbarController.show("获取分享链接失败")
                    }
                }
                ContextMenuRow(icon = Icons.Outlined.ContentCopy, label = "复制直链") {
                    onDismiss()
                    DesktopActions.copyToClipboard(directUrl)
                    SnackbarController.show("直链已复制")
                }
                if (onDebug != null) {
                    ContextMenuRow(icon = Icons.Outlined.BugReport, label = "调试信息") {
                        onDismiss(); onDebug()
                    }
                }
                ContextMenuRow(icon = Icons.Outlined.Refresh, label = "重新下载") {
                    onDismiss(); onRedownload()
                }
                ContextMenuRow(
                    icon = Icons.Outlined.Delete,
                    label = "删除任务",
                    tint = MaterialTheme.colorScheme.error
                ) {
                    onDismiss(); onRemove()
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/** 右键菜单中的一行：图标 + 文字，左对齐，hover 时高亮 */
@Composable
private fun ContextMenuRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Unspecified,
    onClick: () -> Unit
) {
    val contentColor = if (tint == androidx.compose.ui.graphics.Color.Unspecified) {
        MaterialTheme.colorScheme.onSurface
    } else tint
    Surface(
        onClick = onClick,
        color = androidx.compose.ui.graphics.Color.Transparent,
        contentColor = contentColor,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Text(label, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/**
 * 右键点击修饰符：监听次级按键（右键）Press 事件。
 * 替代 combinedClickable 的 onLongClick，桌面端右键更符合直觉。
 */
private fun Modifier.onRightClick(onRightClick: () -> Unit): Modifier = this.pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent()
            if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                onRightClick()
            }
        }
    }
}