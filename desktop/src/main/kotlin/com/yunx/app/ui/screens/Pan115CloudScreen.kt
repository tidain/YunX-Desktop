package com.yunx.app.ui.screens

import com.yunx.app.ui.components.UploadDialogs
import com.yunx.app.ui.components.UploadDropHint
import com.yunx.app.ui.components.UploadIconButton
import com.yunx.app.ui.components.rememberUploadUi
import com.yunx.app.ui.components.uploadDropTarget

import com.yunx.app.ui.SnackbarController
import com.yunx.app.ui.BackHandler
import com.yunx.app.ui.theme.Motion
import com.yunx.app.ui.theme.animateItemIfAllowed
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yunx.app.data.network.model.ShareExpire
import com.yunx.app.data.network.model.ShareFile
import com.yunx.app.ui.components.FadeAlertDialog
import com.yunx.app.ui.items.MultiSelectAction
import com.yunx.app.ui.items.MultiSelectBar
import com.yunx.app.ui.components.ScrollToTopButton
import com.yunx.app.ui.components.rememberDownloadDirPicker
import com.yunx.app.ui.resolve.DownloadLinkDialog
import com.yunx.app.ui.resolve.BackToParentItem
import com.yunx.app.ui.resolve.CrumbBar
import com.yunx.app.ui.resolve.ShareFileRow
import com.yunx.app.ui.viewmodel.Pan115CloudUiState
import com.yunx.app.ui.viewmodel.Pan115CloudViewModel

/** 新建文件夹名称本地校验：非空、不是 `.` / `..`、不含 `/` `\` 与控制字符、长度上限 255 */
private fun pan115NameError(raw: String): String? {
    val name = raw.trim()
    return when {
        name.isEmpty() -> "名称不能为空"
        name == "." || name == ".." -> "名称不能是 . 或 .."
        name.length > 255 -> "名称最多 255 个字符"
        name.any { it == '/' || it == '\\' || it.code < 0x20 } -> "名称不能含 / \\ 或控制字符"
        else -> null
    }
}

/**
 * 115 网盘云盘浏览页（对齐 123/139 云盘页）：
 * - 目录浏览 + 下拉刷新 + 面包屑回退
 * - 长按多选（批量下载/分享/移动/删除）
 * - 文件/文件夹操作菜单（下载/重命名/移动/分享/删除）+ 新建文件夹
 * 认证走整串登录 Cookie，目录用 cid（根="0"）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Pan115CloudScreen(
    viewModel: Pan115CloudViewModel,
    scrollBehavior: TopAppBarScrollBehavior,
    onExit: () -> Unit,
    onDownloadStarted: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()
    val uploadDirectory = state as? Pan115CloudUiState.Loaded
    val uploadEnabled = uploadDirectory != null && !viewModel.multiSelectMode && !viewModel.isOperating
    val uploadUi = rememberUploadUi(
        destinationKey = uploadDirectory?.dirId,
        destination = (listOf("115网盘") + uploadDirectory?.pathNames.orEmpty()).joinToString(" / "),
        enabled = uploadEnabled,
        progress = viewModel.uploadProgress,
        onAppend = viewModel::appendUploadFiles,
        onUpload = viewModel::uploadFiles
    )
    BackHandler {
        val s = state
        if (s is Pan115CloudUiState.Loaded && s.pathNames.isNotEmpty()) viewModel.back() else onExit()
    }
    UploadDialogs(uploadUi, viewModel.uploadProgress, viewModel::cancelUpload)
    val listState = rememberLazyListState()
    val pickDownloadDir = rememberDownloadDirPicker()
    var showActionSheet by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var showMove by remember { mutableStateOf(false) }
    var showShare by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    // 新建文件夹弹窗（窗口内覆盖层，不使用 material3 AlertDialog）
    var showCreateFolder by remember { mutableStateOf(false) }

    LaunchedEffect(viewModel.cloudMessage) {
        viewModel.cloudMessage?.let {
            SnackbarController.show(it)
            viewModel.consumeMessage()
        }
    }

    LaunchedEffect(viewModel.downloadTriggered) {
        if (viewModel.downloadTriggered > 0) {
            viewModel.consumeDownloadTriggered()
            onDownloadStarted()
        }
    }

    viewModel.downloadLink?.let { link ->
        DownloadLinkDialog(
            link = link,
            onDownload = { viewModel.startDownload() },
            onDismiss = { viewModel.dismissDownloadDialog() },
            saveDir = viewModel.pendingSaveDir.takeIf { it.isNotBlank() }
        )
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface
    ) {
        AnimatedContent(
            targetState = state,
            transitionSpec = {
                Motion.fade(200, 140)
            },
            label = "pan115CloudState"
        ) { s ->
            when (s) {
                is Pan115CloudUiState.Loading -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }

                is Pan115CloudUiState.Error -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = s.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 32.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton(onClick = onExit) { Text("返回") }
                            TextButton(onClick = { viewModel.loadRoot() }) { Text("重试") }
                        }
                    }
                }

                is Pan115CloudUiState.Loaded -> Box(modifier = Modifier.fillMaxSize().uploadDropTarget(uploadUi)) {
                    LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxSize()
                                .nestedScroll(scrollBehavior.nestedScrollConnection),
                            contentPadding = PaddingValues(
                                start = 16.dp, end = 16.dp, top = 16.dp,
                                bottom = if (viewModel.multiSelectMode) 96.dp else 16.dp
                            ),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            item {
                                Column {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (viewModel.multiSelectMode) {
                                            IconButton(onClick = { viewModel.exitMultiSelect() }) {
                                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "取消选择")
                                            }
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = "已选 ${viewModel.selected.size} 项",
                                                    style = MaterialTheme.typography.titleMedium,
                                                    fontWeight = FontWeight.Medium
                                                )
                                                Text(
                                                    text = if (viewModel.selected.size == s.files.size) "已全选" else "点击选择更多文件",
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            TextButton(onClick = { viewModel.toggleSelectAll(s.files) }) {
                                                Text(if (viewModel.selected.size == s.files.size) "取消全选" else "全选")
                                            }
                                        } else {
                                            IconButton(onClick = onExit) {
                                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                                            }
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = "115网盘",
                                                    style = MaterialTheme.typography.titleMedium,
                                                    fontWeight = FontWeight.Medium,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    text = "共 ${s.files.size} 项",
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            // 新建文件夹入口（窗口内覆盖层输入）
                                            UploadIconButton(onClick = uploadUi::open, enabled = uploadEnabled)
                                            IconButton(onClick = { showCreateFolder = true }) {
                                                Icon(
                                                    imageVector = Icons.Outlined.CreateNewFolder,
                                                    contentDescription = "新建文件夹",
                                                    tint = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                            IconButton(onClick = { viewModel.refresh() }) {
                                                Icon(imageVector = Icons.Outlined.Refresh, contentDescription = "刷新")
                                            }
                                        }
                                    }
                                    if (!viewModel.multiSelectMode) {
                                        CrumbBar(
                                            rootTitle = "115网盘",
                                            pathNames = s.pathNames,
                                            onNavigate = { viewModel.navigateToLevel(it) }
                                        )
                                        UploadDropHint(uploadUi, uploadEnabled)
                                    }
                                }
                            }

                            if (s.pathNames.isNotEmpty()) {
                                item {
                                    BackToParentItem(onClick = { viewModel.back() })
                                }
                            }

                            if (s.files.isEmpty()) {
                                item {
                                    Text(
                                        text = "此目录为空",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 32.dp),
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }

                            items(s.files, key = { it.fid }) { file ->
                                ShareFileRow(
                                    file = file,
                                    modifier = animateItemIfAllowed(),
                                    onClick = {
                                        if (viewModel.multiSelectMode) {
                                            viewModel.toggleSelect(file)
                                        } else if (file.isdir) {
                                            viewModel.openFolder(file)
                                        } else {
                                            viewModel.openActions(file)
                                            showActionSheet = true
                                        }
                                    },
                                    onMore = if (!viewModel.multiSelectMode) {
                                        {
                                            viewModel.openActions(file)
                                            showActionSheet = true
                                        }
                                    } else {
                                        null
                                    },
                                    onContextMenu = if (!viewModel.multiSelectMode) {
                                        {
                                            viewModel.openActions(file)
                                            showActionSheet = true
                                        }
                                    } else {
                                        null
                                    },
                                    selected = viewModel.selected.contains(file),
                                    showCheckbox = viewModel.multiSelectMode
                                )
                            }
                        }

                    ScrollToTopButton(
                        listState = listState,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(
                                end = 16.dp,
                                bottom = if (viewModel.multiSelectMode) 104.dp else 16.dp
                            )
                    )

                    AnimatedVisibility(
                        visible = viewModel.multiSelectMode,
                        enter = slideInVertically(tween(220)) { it } + fadeIn(tween(220)),
                        exit = slideOutVertically(tween(180)) { it } + fadeOut(tween(180)),
                        modifier = Modifier.align(Alignment.BottomCenter)
                    ) {
                        MultiSelectBar(
                            busy = viewModel.isOperating,
                            count = viewModel.selected.size,
                            actions = listOf(
                                MultiSelectAction("下载", Icons.Outlined.Download, MaterialTheme.colorScheme.primary) {
                                    viewModel.downloadSelected()
                                },
                                MultiSelectAction("下载到…", Icons.Outlined.FolderOpen, MaterialTheme.colorScheme.primary) {
                                    pickDownloadDir { viewModel.downloadSelected(it) }
                                },
                                MultiSelectAction("分享", Icons.Outlined.Share, MaterialTheme.colorScheme.primary) {
                                    showShare = true
                                },
                                MultiSelectAction("移动", Icons.Outlined.DriveFileMove, MaterialTheme.colorScheme.primary) {
                                    viewModel.openMoveRoot()
                                    showMove = true
                                },
                                MultiSelectAction("删除", Icons.Outlined.Delete, MaterialTheme.colorScheme.error) {
                                    showDeleteConfirm = true
                                }
                            )
                        )
                    }
                }
            }
        }
    }

    // 文件操作菜单
    if (showActionSheet && viewModel.actionFile != null) {
        Pan115ActionSheet(
            file = viewModel.actionFile!!,
            onDownload = {
                showActionSheet = false
                viewModel.downloadFile()
            },
            onDownloadTo = {
                pickDownloadDir { dir ->
                    showActionSheet = false
                    viewModel.downloadFileTo(dir)
                }
            },
            onDownloadFolder = {
                showActionSheet = false
                viewModel.downloadFolder()
            },
            onDownloadFolderTo = {
                pickDownloadDir { dir ->
                    showActionSheet = false
                    viewModel.downloadFolder(dir)
                }
            },
            onRename = {
                showActionSheet = false
                showRename = true
            },
            onMove = {
                showActionSheet = false
                viewModel.openMoveRoot()
                showMove = true
            },
            onShare = {
                showActionSheet = false
                showShare = true
            },
            onDelete = {
                showActionSheet = false
                showDeleteConfirm = true
            },
            onMultiSelect = {
                showActionSheet = false
                viewModel.actionFile?.let { viewModel.enterMultiSelect(it) }
            },
            onDismiss = {
                showActionSheet = false
                viewModel.dismissActions()
            }
        )
    }

    if (showRename && viewModel.actionFile != null) {
        Pan115RenameDialog(
            file = viewModel.actionFile!!,
            onConfirm = { name ->
                showRename = false
                viewModel.renameFile(name)
            },
            onDismiss = { showRename = false }
        )
    }

    if (showMove) {
        Pan115MoveSheet(
            viewModel = viewModel,
            onDismiss = { showMove = false }
        )
    }

    if (showShare) {
        Pan115ShareSheet(
            viewModel = viewModel,
            onDismiss = { showShare = false }
        )
    }

    viewModel.shareResult?.let { info ->
        ShareResultDialog(
            info = info,
            onDismiss = { viewModel.dismissShareResult() }
        )
    }

    // 新建文件夹：名称校验在弹窗内完成，创建请求交给 ViewModel（窗口内覆盖层）
    if (showCreateFolder) {
        Pan115CreateFolderDialog(
            onDismiss = { showCreateFolder = false },
            onConfirm = { name ->
                showCreateFolder = false
                viewModel.createFolder(name)
            }
        )
    }

    // 删除二次确认（窗口内覆盖层）
    FadeAlertDialog(
        visible = showDeleteConfirm,
        onDismissRequest = { showDeleteConfirm = false },
        title = { Text("删除文件") },
        text = {
            val deleting = if (viewModel.multiSelectMode) {
                "选中的 ${viewModel.selected.size} 项"
            } else {
                "「${viewModel.actionFile?.fname ?: ""}」"
            }
            Text("确定要删除$deleting 吗？删除后进入回收站。")
        },
        confirmButton = {
            TextButton(
                onClick = {
                    showDeleteConfirm = false
                    if (viewModel.multiSelectMode) viewModel.deleteSelected() else viewModel.deleteFile()
                }
            ) {
                Text("删除", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") }
        }
    )

    // 操作执行中加载弹窗（下载文件夹/批量下载显示进度；窗口内覆盖层）
    FadeAlertDialog(
        visible = viewModel.isOperating,
        onDismissRequest = { },
        title = { Text("处理中") },
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = viewModel.folderProgress ?: "正在处理，请稍候…",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = { viewModel.cancelDownload() }) {
                Text("中断", color = MaterialTheme.colorScheme.error)
            }
        }
    )
}

/** 新建文件夹弹窗（窗口内覆盖层，名称本地校验，创建后立即关闭，结果走 cloudMessage 提示） */
@Composable
private fun Pan115CreateFolderDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var touched by remember { mutableStateOf(false) }
    val error = pan115NameError(name)
    val showError = touched && error != null
    FadeAlertDialog(
        visible = true,
        onDismissRequest = onDismiss,
        title = { Text("创建文件夹") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        touched = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("文件夹名称") },
                    singleLine = true,
                    isError = showError,
                    supportingText = {
                        Text(
                            text = if (showError) error!! else "创建在当前目录下",
                            color = if (showError) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim()) },
                enabled = error == null
            ) { Text("创建") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/** 115 文件操作菜单：下载/分享/移动/重命名/删除 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Pan115ActionSheet(
    file: ShareFile,
    onDownload: () -> Unit,
    onDownloadTo: () -> Unit,
    onDownloadFolder: (() -> Unit)? = null,
    onDownloadFolderTo: (() -> Unit)? = null,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onMultiSelect: (() -> Unit)? = null,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 32.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(40.dp),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (file.isdir) Icons.Outlined.DriveFileMove else Icons.Outlined.Download,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(file.fname, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium, maxLines = 1)
                    Text(
                        text = if (file.isdir) "文件夹" else "文件",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(8.dp))
            if (!file.isdir) {
                Pan115ActionItem(Icons.Outlined.Download, "下载", "使用内置下载功能保存到本机", MaterialTheme.colorScheme.primary, onDownload)
                Pan115ActionItem(Icons.Outlined.FolderOpen, "下载到…", "选择本次保存的文件夹（不改全局设置）", MaterialTheme.colorScheme.primary, onDownloadTo)
            } else {
                if (onDownloadFolder != null) {
                    Pan115ActionItem(Icons.Outlined.Download, "下载文件夹", "递归下载整个文件夹，保持目录结构", MaterialTheme.colorScheme.primary, onDownloadFolder)
                }
                if (onDownloadFolderTo != null) {
                    Pan115ActionItem(Icons.Outlined.FolderOpen, "下载文件夹到…", "选择本次保存的文件夹（不改全局设置）", MaterialTheme.colorScheme.primary, onDownloadFolderTo)
                }
            }
            Pan115ActionItem(Icons.Outlined.Share, "分享", "生成分享链接（有效期可设，访问码由服务端生成）", MaterialTheme.colorScheme.primary, onShare)
            Pan115ActionItem(Icons.Outlined.DriveFileMove, "移动到", "移动到网盘的其他目录", MaterialTheme.colorScheme.primary, onMove)
            Pan115ActionItem(Icons.Outlined.Edit, "重命名", "修改文件名", MaterialTheme.colorScheme.primary, onRename)
            if (onMultiSelect != null) {
                Pan115ActionItem(Icons.Outlined.CheckCircle, "多选", "勾选多个项目批量下载 / 分享 / 移动 / 删除", MaterialTheme.colorScheme.primary, onMultiSelect)
            }
            Pan115ActionItem(Icons.Outlined.Delete, "删除", "删除到回收站", MaterialTheme.colorScheme.error, onDelete)
        }
    }
}

@Composable
private fun Pan115ActionItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    desc: String,
    tint: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.size(38.dp),
            shape = MaterialTheme.shapes.large,
            color = tint.copy(alpha = 0.12f)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
            }
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 重命名弹窗（窗口内覆盖层） */
@Composable
private fun Pan115RenameDialog(
    file: ShareFile,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(file.fname) }
    FadeAlertDialog(
        visible = true,
        onDismissRequest = onDismiss,
        title = { Text("重命名") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("新文件名") },
                singleLine = true,
                shape = MaterialTheme.shapes.large
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (name.isNotBlank() && name != file.fname) onConfirm(name.trim()) else onDismiss()
                },
                enabled = name.isNotBlank()
            ) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/** 移动目录选择弹窗（独立浏览，不影响主列表） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Pan115MoveSheet(
    viewModel: Pan115CloudViewModel,
    onDismiss: () -> Unit
) {
    val moveState by viewModel.moveUiState.collectAsState()
    LaunchedEffect(Unit) { viewModel.openMoveRoot() }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 32.dp)
        ) {
            Text("移动到", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
            Spacer(modifier = Modifier.height(8.dp))
            CrumbBar(
                rootTitle = "根目录",
                pathNames = (moveState as? Pan115CloudUiState.Loaded)?.pathNames ?: emptyList(),
                onNavigate = { viewModel.moveNavigateToLevel(it) }
            )
            Spacer(modifier = Modifier.height(8.dp))
            if ((moveState as? Pan115CloudUiState.Loaded)?.pathNames?.isNotEmpty() == true) {
                BackToParentItem(onClick = { viewModel.moveBack() })
                Spacer(modifier = Modifier.height(4.dp))
            }
            AnimatedContent(
                targetState = moveState,
                transitionSpec = { Motion.fade(180, 140) },
                label = "pan115MoveState"
            ) { s ->
                when (s) {
                    is Pan115CloudUiState.Loading -> Box(
                        modifier = Modifier.fillMaxWidth().height(180.dp),
                        contentAlignment = Alignment.Center
                    ) { CircularProgressIndicator() }

                    is Pan115CloudUiState.Error -> Box(
                        modifier = Modifier.fillMaxWidth().height(140.dp),
                        contentAlignment = Alignment.Center
                    ) { Text(s.message, color = MaterialTheme.colorScheme.onSurfaceVariant) }

                    is Pan115CloudUiState.Loaded -> {
                        val dirs = s.files.filter { it.isdir }
                        if (dirs.isEmpty()) {
                            Box(
                                modifier = Modifier.fillMaxWidth().height(90.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "当前目录没有子文件夹，可直接移动到此处",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                items(dirs, key = { it.fid }) { dir ->
                                    ShareFileRow(file = dir, onClick = { viewModel.openMoveFolder(dir) })
                                }
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            val dirName = (moveState as? Pan115CloudUiState.Loaded)?.pathNames?.lastOrNull() ?: "根目录"
            Button(
                onClick = {
                    val to = (moveState as? Pan115CloudUiState.Loaded)?.dirId ?: "0"
                    if (viewModel.multiSelectMode) viewModel.moveSelected(to) else viewModel.moveFile(to)
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth().height(50.dp)
            ) {
                Icon(Icons.Outlined.DriveFileMove, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("移动到此处（$dirName）")
            }
        }
    }
}

/** 分享设置弹窗（115 专属有效期档位；访问码由服务端生成） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Pan115ShareSheet(
    viewModel: Pan115CloudViewModel,
    onDismiss: () -> Unit
) {
    // 默认选中第一档（115 的档位码是 101..105，不能写死 ShareExpire.FOREVER）
    var expiredType by remember { mutableStateOf(ShareExpire.PAN115_OPTIONS.first().second) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 32.dp)
        ) {
            Text("分享文件", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                if (viewModel.multiSelectMode) "已选 ${viewModel.selected.size} 项" else "分享到 115 网盘链接",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "115 的访问码由服务端生成且不可自定义/取消，创建后展示在结果中。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))
            Text("有效期", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(6.dp))
            // 115 有 5 档（永久/1/3/7/15 天），一行放不下会压扁，换行展示
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ShareExpire.PAN115_OPTIONS.take(3).forEach { (label, value) ->
                    FilterChip(
                        selected = expiredType == value,
                        onClick = { expiredType = value },
                        label = { Text(label) },
                        colors = FilterChipDefaults.filterChipColors()
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ShareExpire.PAN115_OPTIONS.drop(3).forEach { (label, value) ->
                    FilterChip(
                        selected = expiredType == value,
                        onClick = { expiredType = value },
                        label = { Text(label) },
                        colors = FilterChipDefaults.filterChipColors()
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
            Button(
                onClick = {
                    if (viewModel.multiSelectMode) {
                        viewModel.shareSelected(expiredType)
                    } else {
                        viewModel.shareFile(expiredType)
                    }
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth().height(50.dp)
            ) {
                Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("创建分享")
            }
        }
    }
}
