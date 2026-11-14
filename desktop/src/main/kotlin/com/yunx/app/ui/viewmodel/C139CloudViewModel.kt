package com.yunx.app.ui.viewmodel

import com.yunx.app.data.upload.UploadCandidate
import com.yunx.app.data.upload.UploadDirectoryResolver
import com.yunx.app.data.upload.uploadDirectoryEntries
import com.yunx.app.data.upload.UploadProgress
import com.yunx.app.data.upload.UploadSessionException
import com.yunx.app.data.upload.UploadRunner
import com.yunx.app.data.network.HttpClients

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import kotlin.reflect.KClass
import com.yunx.app.data.download.DownloadManager
import com.yunx.app.data.download.DownloadPlatform
import com.yunx.app.data.network.C139Api
import com.yunx.app.data.network.C139Constants
import com.yunx.app.data.network.model.ShareFile
import com.yunx.app.data.network.model.DownloadLink
import com.yunx.app.data.network.model.ShareInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 139 网盘云盘浏览 UI 状态 */
sealed interface C139CloudUiState {
    data object Loading : C139CloudUiState
    data class Loaded(
        val files: List<ShareFile>,
        val pathNames: List<String>,
        /** 当前目录 fileId（根="/"） */
        val dirId: String
    ) : C139CloudUiState
    data class Error(val message: String) : C139CloudUiState
}

/**
 * 139 网盘（和彩云）云盘浏览 ViewModel（参考百度/夸克云盘）：
 * - 目录浏览（根/子目录/面包屑回退）+ 下拉刷新
 * - 文件操作：下载 / 重命名 / 移动 / 创建分享 / 删除 + 长按多选批量
 * 认证走 Cookie（内部提取 authorization），目录用 fileId（根"/"），文件标识 fileId。
 */
class C139CloudViewModel(
    private val api: C139Api,
    private val cookieProvider: suspend () -> String?,
    private val downloadManager: DownloadManager
) : ViewModel() {

    private val _uiState = MutableStateFlow<C139CloudUiState>(C139CloudUiState.Loading)
    val uiState: StateFlow<C139CloudUiState> = _uiState.asStateFlow()

    var actionFile by mutableStateOf<ShareFile?>(null)
        private set
    var cloudMessage by mutableStateOf<String?>(null)
        private set
    var isOperating by mutableStateOf(false)
        private set
    var folderProgress by mutableStateOf<String?>(null)
        private set
    private var downloadCancelRequested = false
    var refreshing by mutableStateOf(false)
        private set
    var downloadTriggered by mutableStateOf(0)
        private set
    var shareResult by mutableStateOf<ShareInfo?>(null)
        private set
    var multiSelectMode by mutableStateOf(false)
        private set
    private val _selected = mutableStateListOf<ShareFile>()
    val selected: List<ShareFile> get() = _selected

    private val dirStack = ArrayDeque<String>()
    private val nameStack = ArrayDeque<String>()

    private val _moveUiState = MutableStateFlow<C139CloudUiState>(C139CloudUiState.Loading)
    val moveUiState: StateFlow<C139CloudUiState> = _moveUiState.asStateFlow()
    private val moveDirStack = ArrayDeque<String>()
    private val moveNameStack = ArrayDeque<String>()

    init {
        loadRoot()
    }

    private suspend fun cookie(): String =
        cookieProvider() ?: throw IllegalStateException("请先登录139网盘")

    // ---------- 目录浏览 ----------

    fun loadRoot() {
        dirStack.clear()
        nameStack.clear()
        load("/", emptyList())
    }

    fun openFolder(file: ShareFile) {
        dirStack.addLast(file.fid)
        nameStack.addLast(file.fname)
        load(file.fid, nameStack.toList())
    }

    fun back() {
        if (nameStack.isEmpty()) {
            loadRoot()
            return
        }
        dirStack.removeLast()
        nameStack.removeLast()
        load(dirStack.lastOrNull() ?: "/", nameStack.toList())
    }

    fun navigateToLevel(level: Int) {
        while (nameStack.size > level) {
            dirStack.removeLast()
            nameStack.removeLast()
        }
        load(dirStack.lastOrNull() ?: "/", nameStack.toList())
    }

    // ---------- 多选 ----------

    fun enterMultiSelect(file: ShareFile) {
        multiSelectMode = true
        _selected.clear()
        _selected.add(file)
    }

    fun toggleSelect(file: ShareFile) {
        if (_selected.contains(file)) _selected.remove(file) else _selected.add(file)
    }

    fun toggleSelectAll(files: List<ShareFile>) {
        if (_selected.size == files.size) _selected.clear()
        else {
            _selected.clear()
            _selected.addAll(files)
        }
    }

    fun exitMultiSelect() {
        multiSelectMode = false
        _selected.clear()
    }

    fun openActions(file: ShareFile) {
        actionFile = file
    }

    fun dismissActions() {
        actionFile = null
    }

    fun consumeMessage() {
        cloudMessage = null
    }

    fun dismissShareResult() {
        shareResult = null
    }

    fun consumeDownloadTriggered() {
        downloadTriggered = 0
    }

    /** 中断当前下载（批量下载/文件夹下载） */
    fun cancelDownload() {
        downloadCancelRequested = true
    }

    // ---------- 移动目标浏览 ----------

    fun openMoveRoot() {
        moveDirStack.clear()
        moveNameStack.clear()
        moveLoad("/", emptyList())
    }

    fun openMoveFolder(file: ShareFile) {
        moveDirStack.addLast(file.fid)
        moveNameStack.addLast(file.fname)
        moveLoad(file.fid, moveNameStack.toList())
    }

    fun moveBack() {
        if (moveNameStack.isEmpty()) return
        moveDirStack.removeLast()
        moveNameStack.removeLast()
        moveLoad(moveDirStack.lastOrNull() ?: "/", moveNameStack.toList())
    }

    fun moveNavigateToLevel(level: Int) {
        while (moveNameStack.size > level) {
            moveDirStack.removeLast()
            moveNameStack.removeLast()
        }
        moveLoad(moveDirStack.lastOrNull() ?: "/", moveNameStack.toList())
    }

    private fun moveLoad(dirId: String, pathNames: List<String>) {
        _moveUiState.value = C139CloudUiState.Loading
        viewModelScope.launch {
            try {
                val files = api.listFolders(dirId, cookie())
                _moveUiState.value = C139CloudUiState.Loaded(files, pathNames, dirId)
            } catch (e: Exception) {
                _moveUiState.value = C139CloudUiState.Error(e.message ?: "加载失败")
            }
        }
    }

    // ---------- 单文件操作 ----------

    /** 139 下载直链的请求头（OBS 直链，UA + Referer） */
    private fun downloadHeaders(): Map<String, String> = mapOf(
        "User-Agent" to C139Constants.PC_UA,
        "Referer" to "https://yun.139.com/"
    )

    /**
     * 递归收集文件夹内所有文件（保持目录结构）。
     */
    private suspend fun collectFolderFiles(
        dirId: String,
        prefix: String,
        cookie: String,
        result: MutableList<Pair<ShareFile, String>>,
        depth: Int
    ) {
        if (depth > 12) return
        val list = runCatching { api.listCloudFiles(dirId, cookie).first }.getOrDefault(emptyList())
        list.filter { !it.isdir }.forEach { result.add(it to "$prefix/${it.fname}") }
        list.filter { it.isdir }.forEach {
            collectFolderFiles(it.fid, "$prefix/${it.fname}", cookie, result, depth + 1)
        }
    }

    /** 下载整个文件夹（操作菜单）：递归收集所有文件，保持目录结构保存到 Download */
    fun downloadFolder(saveDir: String = "") {
        val folder = actionFile ?: return
        if (!folder.isdir) return
        viewModelScope.launch {
            isOperating = true
            folderProgress = "正在收集文件…"
            downloadCancelRequested = false
            try {
                val cookie = cookie()
                val tasks = mutableListOf<Pair<ShareFile, String>>()
                collectFolderFiles(folder.fid, folder.fname, cookie, tasks, 0)
                if (tasks.isEmpty()) {
                    cloudMessage = "文件夹为空"
                    actionFile = null
                    return@launch
                }
                var okCount = 0
                tasks.forEachIndexed { index, (file, relPath) ->
                    // 用户点击「中断」：跳过剩余项（已入队任务保留下载）
                    if (downloadCancelRequested) return@forEachIndexed
                    folderProgress = "正在加入下载 ${index + 1}/${tasks.size}"
                    runCatching {
                        val link = api.getDownloadUrl(file.fid, cookie) ?: return@runCatching
                        downloadManager.enqueue(
                            url = link.downloadUrl,
                            fileName = relPath, // 相对路径：Download/文件夹A/子目录/文件.mp4
                            size = link.size,
                            platform = DownloadPlatform.C139,
                            headers = downloadHeaders(),
                            saveDir = saveDir
                        )
                        okCount++
                    }
                }
                if (downloadCancelRequested) {
                    cloudMessage = "已中断下载"
                    actionFile = null
                    return@launch
                }
                cloudMessage = "已加入 $okCount 个下载任务"
                actionFile = null
            } catch (e: Exception) {
                cloudMessage = e.message ?: "下载文件夹失败"
            } finally {
                isOperating = false
                folderProgress = null
                downloadCancelRequested = false
            }
        }
    }

    /** 下载：getDownloadUrl 取 OBS 直链（900s 有效，UA + Referer 即可）→ 内置下载队列 */
    /** 待确认的下载直链（单文件下载弹窗展示用，长按链接可复制） */
    var downloadLink by mutableStateOf<DownloadLink?>(null)
        private set

    /** 与 downloadLink 配套的入队参数（弹窗确认后直接入队） */
    private var pendingDownload: PendingDownload? = null

    /** 当前待确认下载的保存目录（空串 = 跟随全局设置）；下载弹窗展示用 */
    var pendingSaveDir by mutableStateOf("")
        private set

    /** 下载文件：取直链 → 弹出下载确认弹窗（对齐解析页行为，确认后入队） */
    fun downloadFile() = beginDownload("")

    /** 下载到指定目录：单次覆盖保存目录，不改全局设置 */
    fun downloadFileTo(saveDir: String) = beginDownload(saveDir)

    private fun beginDownload(saveDir: String) {
        val file = actionFile ?: return
        viewModelScope.launch {
            isOperating = true
            try {
                val link = api.getDownloadUrl(file.fid, cookie())
                    ?: throw IllegalStateException("获取下载链接失败")
                pendingSaveDir = saveDir
                pendingDownload = PendingDownload(
                    url = link.downloadUrl,
                    // 139 getDownloadUrl 响应不含 name → 用列表里的文件名（与分享链接下载一致，避免 fileId 乱码）
                    fileName = file.fname.ifBlank { link.filename },
                    size = link.size,
                    saveDir = saveDir,
                    headers = mapOf(
                        "User-Agent" to C139Constants.PC_UA,
                        "Referer" to "https://yun.139.com/"
                    )
                )
                downloadLink = link // 弹下载确认弹窗（长按直链可复制）
            } catch (e: Exception) {
                cloudMessage = e.message ?: "下载失败"
            } finally {
                isOperating = false
            }
        }
    }

    /** 下载弹窗确认：用已生成的直链入队 */
    fun startDownload() {
        val pd = pendingDownload ?: return
        downloadLink = null
        pendingDownload = null
        pendingSaveDir = ""
        viewModelScope.launch {
            isOperating = true
            try {
                downloadManager.enqueue(
                    url = pd.url,
                    fileName = pd.fileName,
                    size = pd.size,
                    platform = DownloadPlatform.C139,
                    headers = pd.headers,
                    saveDir = pd.saveDir
                )
                cloudMessage = "已加入下载：${pd.fileName}"
                actionFile = null
                downloadTriggered++
            } catch (e: Exception) {
                cloudMessage = e.message ?: "下载失败"
            } finally {
                isOperating = false
            }
        }
    }

    /** 关闭下载弹窗（放弃下载） */
    fun dismissDownloadDialog() {
        downloadLink = null
        pendingDownload = null
        pendingSaveDir = ""
    }

    /** 重命名 */
    fun renameFile(newName: String) {
        val file = actionFile ?: return
        viewModelScope.launch {
            isOperating = true
            try {
                api.renameFile(file.fid, newName, cookie())
                cloudMessage = "已重命名"
                actionFile = null
                reloadCurrent()
            } catch (e: Exception) {
                cloudMessage = e.message ?: "重命名失败"
            } finally {
                isOperating = false
            }
        }
    }

    /** 新建文件夹（当前目录下）；139 用 fileId，根目录沿用列目录/移动的 "/" 约定 */
    fun createFolder(name: String) {
        val newName = name.trim()
        if (newName.isEmpty()) return
        val parentId = (uiState.value as? C139CloudUiState.Loaded)?.dirId ?: "/"
        viewModelScope.launch {
            isOperating = true
            try {
                api.createDir(parentId, newName, cookie())
                cloudMessage = "已创建文件夹「$newName」"
                reloadCurrent()
            } catch (e: Exception) {
                cloudMessage = e.message ?: "新建文件夹失败"
            } finally {
                isOperating = false
            }
        }
    }

    /** 移动（异步任务 → 轮询） */
    fun moveFile(toDirId: String) {
        val file = actionFile ?: return
        viewModelScope.launch {
            isOperating = true
            try {
                val taskId = api.moveFiles(listOf(file.fid), toDirId, cookie())
                    ?: throw IllegalStateException("移动失败")
                pollTask(taskId)
                cloudMessage = "已移动到目标目录"
                actionFile = null
                delay(1500)
                reloadCurrent()
            } catch (e: Exception) {
                cloudMessage = e.message ?: "移动失败"
            } finally {
                isOperating = false
            }
        }
    }

    /** 创建分享（139 提取码系统自动生成，仅选有效期；桌面版自带弹窗，period 为**天数**：null=永久 1/7/30=天数） */
    fun shareFile(period: Int?) {
        val file = actionFile ?: return
        viewModelScope.launch {
            isOperating = true
            try {
                val coLst = if (file.isdir) emptyList() else listOf(file.fid)
                val caLst = if (file.isdir) listOf(file.fid) else emptyList()
                val info = api.createShare(coLst, caLst, period, file.fname, cookie())
                shareResult = info
            } catch (e: Exception) {
                cloudMessage = e.message ?: "分享失败"
            } finally {
                isOperating = false
            }
        }
    }

    /** 删除（异步任务 → 轮询） */
    fun deleteFile() {
        val file = actionFile ?: return
        viewModelScope.launch {
            isOperating = true
            try {
                val taskId = api.deleteFiles(listOf(file.fid), cookie())
                    ?: throw IllegalStateException("删除失败")
                pollTask(taskId)
                cloudMessage = "已删除「${file.fname}」"
                actionFile = null
                delay(1200)
                reloadCurrent()
            } catch (e: Exception) {
                cloudMessage = e.message ?: "删除失败"
            } finally {
                isOperating = false
            }
        }
    }

    // ---------- 批量操作 ----------

    /** 批量下载（不切页；选中文件夹时递归下载整个文件夹并保持目录结构） */
    fun downloadSelected(saveDir: String = "") {
        val files = _selected.toList()
        if (files.isEmpty()) return
        viewModelScope.launch {
            isOperating = true
            folderProgress = "正在收集文件…"
            downloadCancelRequested = false
            try {
                val cookie = cookie()
                // 展开选中项：文件直接加入，文件夹递归收集
                val tasks = mutableListOf<Pair<ShareFile, String>>()
                for (file in files) {
                    if (file.isdir) {
                        collectFolderFiles(file.fid, file.fname, cookie, tasks, 0)
                    } else {
                        tasks.add(file to file.fname)
                    }
                }
                if (tasks.isEmpty()) {
                    cloudMessage = "所选文件夹为空"
                    exitMultiSelect()
                    return@launch
                }
                var okCount = 0
                var failCount = 0
                tasks.forEachIndexed { index, (file, relPath) ->
                    // 用户点击「中断」：跳过剩余项（已入队任务保留下载）
                    if (downloadCancelRequested) return@forEachIndexed
                    folderProgress = "正在加入下载 ${index + 1}/${tasks.size}"
                    runCatching {
                        val link = api.getDownloadUrl(file.fid, cookie) ?: return@runCatching
                        downloadManager.enqueue(
                            url = link.downloadUrl,
                            // 文件夹内文件用相对路径；根目录文件用列表文件名（139 取链响应不含 name）
                            fileName = if (relPath.contains('/')) relPath else file.fname.ifBlank { link.filename },
                            size = link.size,
                            platform = DownloadPlatform.C139,
                            headers = downloadHeaders(),
                            saveDir = saveDir
                        )
                        okCount++
                    }
                }
                if (downloadCancelRequested) {
                    cloudMessage = "已中断批量下载"
                    exitMultiSelect()
                    return@launch
                }
                cloudMessage = if (failCount > 0) {
                    "已加入 $okCount 个下载任务（$failCount 个失败）"
                } else {
                    "已加入 $okCount 个下载任务"
                }
                exitMultiSelect()
            } catch (e: Exception) {
                cloudMessage = e.message ?: "批量下载失败"
            } finally {
                isOperating = false
                folderProgress = null
                downloadCancelRequested = false
            }
        }
    }

    /** 批量分享（period 为天数，语义见 [shareFile]） */
    fun shareSelected(period: Int?) {
        val files = _selected.toList()
        if (files.isEmpty()) return
        viewModelScope.launch {
            isOperating = true
            try {
                val coLst = files.filter { !it.isdir }.map { it.fid }
                val caLst = files.filter { it.isdir }.map { it.fid }
                val title = if (files.size == 1) files[0].fname else "分享 ${files.size} 个文件"
                val info = api.createShare(coLst, caLst, period, title, cookie())
                shareResult = info
                exitMultiSelect()
            } catch (e: Exception) {
                cloudMessage = e.message ?: "分享失败"
            } finally {
                isOperating = false
            }
        }
    }

    /** 批量移动 */
    fun moveSelected(toDirId: String) {
        val files = _selected.toList()
        if (files.isEmpty()) return
        viewModelScope.launch {
            isOperating = true
            try {
                val taskId = api.moveFiles(files.map { it.fid }, toDirId, cookie())
                    ?: throw IllegalStateException("移动失败")
                pollTask(taskId)
                cloudMessage = "已移动 ${files.size} 项"
                exitMultiSelect()
                delay(1500)
                reloadCurrent()
            } catch (e: Exception) {
                cloudMessage = e.message ?: "移动失败"
            } finally {
                isOperating = false
            }
        }
    }

    /** 批量删除 */
    fun deleteSelected() {
        val files = _selected.toList()
        if (files.isEmpty()) return
        viewModelScope.launch {
            isOperating = true
            try {
                val taskId = api.deleteFiles(files.map { it.fid }, cookie())
                    ?: throw IllegalStateException("删除失败")
                pollTask(taskId)
                cloudMessage = "已删除 ${files.size} 项"
                exitMultiSelect()
                delay(1200)
                reloadCurrent()
            } catch (e: Exception) {
                cloudMessage = e.message ?: "删除失败"
            } finally {
                isOperating = false
            }
        }
    }

    // ---------- 内部 ----------

    /** 下拉刷新 */
    fun refresh() {
        val current = uiState.value
        if (current !is C139CloudUiState.Loaded) {
            loadRoot()
            return
        }
        refreshing = true
        viewModelScope.launch {
            try {
                val files = api.listCloudFiles(current.dirId, cookie()).first
                _uiState.value = C139CloudUiState.Loaded(files, current.pathNames, current.dirId)
            } catch (e: Exception) {
                cloudMessage = e.message ?: "刷新失败"
            } finally {
                refreshing = false
            }
        }
    }

    private fun reloadCurrent() {
        val current = uiState.value
        if (current is C139CloudUiState.Loaded) {
            load(current.dirId, current.pathNames)
        } else {
            loadRoot()
        }
    }

    private fun load(dirId: String, pathNames: List<String>) {
        _uiState.value = C139CloudUiState.Loading
        viewModelScope.launch {
            try {
                val files = api.listCloudFiles(dirId, cookie()).first
                _uiState.value = C139CloudUiState.Loaded(files, pathNames, dirId)
            } catch (e: Exception) {
                _uiState.value = C139CloudUiState.Error(e.message ?: "加载失败")
            }
        }
    }

    /** 轮询异步任务直到 Succeed（最长 ~30s） */
    private suspend fun pollTask(taskId: String) {
        delay(500)
        repeat(30) {
            val status = api.getTask(taskId, cookie())
            if (status.status == "Succeed" || status.progress >= 100) return
            if (status.results.any { it.second.isNotBlank() && it.second != "0000" }) {
                throw IllegalStateException("任务失败（${status.results.first().second}）")
            }
            delay(800)
        }
        throw IllegalStateException("任务超时")
    }

    // ---------- 上传（139 分片直传） ----------

    /** 上传进度（null 表示没有任务）；上传弹窗直接读这里 */
    val uploadProgress: UploadProgress? get() = uploadRunner.progress
    val uploadTaskProgress: UploadProgress? get() = uploadRunner.progress ?: uploadRunner.lastResult

    private val uploadRunner = UploadRunner(viewModelScope, { HttpClients.uploadClient() }) { outcome ->
        // 失败/取消时也刷新：服务端可能已接收文件，但确认响应在途中中断。
        reloadCurrent()
        cloudMessage = outcome.message()
    }

    /**
     * 上传选中的本地文件到**当前目录**。
     *
     * 目标目录在这里就先固定下来（上传期间用户可能切目录、返回上级），
     * 凭据留到 IO 线程现取，避免拿着已过期的 Cookie 一路失败。
     */
    fun uploadFiles(files: List<UploadCandidate>) {
        val dir = (uiState.value as? C139CloudUiState.Loaded)?.dirId ?: return
        val directories = UploadDirectoryResolver(dir)
        uploadRunner.startCandidates(files, DownloadPlatform.C139, onRetry = directories::retryFailed) { io, source ->
            val cookie = cookieProvider() ?: throw UploadSessionException("请先登录移动云盘")
            directories.transfer(source,
                list = { parent -> uploadDirectoryEntries { cursor -> api.listCloudFiles(parent, cookie, cursor) } },
                create = { parent, name -> api.createDir(parent, name, cookie) }
            ) { parent -> api.upload(io, source, parent, cookie) }
        }
    }

    /** 取消当前上传（正在发的那一段会立刻被打断） */
    fun cancelUpload() = uploadRunner.cancel()

    fun appendUploadFiles(files: List<UploadCandidate>, batchId: Long): Int = uploadRunner.appendCandidates(files, batchId)

    fun retryFailedUploads(batchId: Long): Int = uploadRunner.retryFailed(batchId)
    fun retryUploadFile(batchId: Long, candidate: UploadCandidate): Int = uploadRunner.retrySingle(batchId, candidate)

    fun dismissUploadResult(batchId: Long) = uploadRunner.dismissResult(batchId)

    override fun onCleared() {
        uploadRunner.cancel()
        super.onCleared()
    }

    class Factory(
        private val api: C139Api,
        private val cookieProvider: suspend () -> String?,
        private val downloadManager: DownloadManager
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: KClass<T>, extras: CreationExtras): T =
            C139CloudViewModel(api, cookieProvider, downloadManager) as T
    }
}
