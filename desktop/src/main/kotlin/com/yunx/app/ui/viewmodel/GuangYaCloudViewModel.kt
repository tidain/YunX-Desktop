package com.yunx.app.ui.viewmodel

import com.yunx.app.data.upload.UploadCandidate
import com.yunx.app.data.upload.UploadDirectoryResolver
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
import com.yunx.app.data.download.DownloadManager
import com.yunx.app.data.download.DownloadPlatform
import com.yunx.app.data.network.GuangYaApi
import com.yunx.app.data.network.GuangYaConstants
import com.yunx.app.data.network.model.DownloadLink
import com.yunx.app.data.network.model.ShareExpire
import com.yunx.app.data.network.model.ShareFile
import com.yunx.app.data.network.model.ShareInfo
import com.yunx.app.data.repository.GuangYaAccountRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlin.reflect.KClass

/** 光鸭云盘浏览 UI 状态（根目录 dirId = ""）。 */
sealed interface GuangYaCloudUiState {
    data object Loading : GuangYaCloudUiState
    data class Loaded(
        val files: List<ShareFile>,
        val pathNames: List<String>,
        val dirId: String
    ) : GuangYaCloudUiState
    data class Error(val message: String) : GuangYaCloudUiState
}

/**
 * 光鸭云盘浏览 ViewModel（对齐 123/115 云盘页）：
 * 目录浏览 + 下拉刷新 + 下载/重命名/移动/创建分享/删除 + 长按多选批量。
 * 认证走 access token（[GuangYaAccountRepository.ensureAccessToken]），目录用 fileId（根 = ""）。
 */
class GuangYaCloudViewModel(
    private val api: GuangYaApi,
    private val accountRepository: GuangYaAccountRepository,
    private val downloadManager: DownloadManager,
    private val loginState: Flow<Boolean>
) : ViewModel() {

    private val _uiState = MutableStateFlow<GuangYaCloudUiState>(GuangYaCloudUiState.Loading)
    val uiState: StateFlow<GuangYaCloudUiState> = _uiState.asStateFlow()

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

    var downloadLink by mutableStateOf<DownloadLink?>(null)
        private set
    private var pendingDownload: PendingDownload? = null

    /** 当前待确认下载的保存目录（空串 = 跟随全局设置）；下载弹窗展示用 */
    var pendingSaveDir by mutableStateOf("")
        private set

    private val dirStack = ArrayDeque<String>()
    private val nameStack = ArrayDeque<String>()

    private val _moveUiState = MutableStateFlow<GuangYaCloudUiState>(GuangYaCloudUiState.Loading)
    val moveUiState: StateFlow<GuangYaCloudUiState> = _moveUiState.asStateFlow()
    private val moveDirStack = ArrayDeque<String>()
    private val moveNameStack = ArrayDeque<String>()

    init {
        loadRoot()
        // 启动期未登录时 loadRoot 会残留「请先登录…」错误态；登录态从无到有后自动重载根目录。
        viewModelScope.launch {
            loginState
                .drop(1)
                .distinctUntilChanged()
                .collect { loggedIn -> if (loggedIn) loadRoot() }
        }
    }

    private suspend fun token(): String =
        accountRepository.ensureAccessToken() ?: throw UploadSessionException("请先登录光鸭云盘")

    /** 当前目录 fileId（未加载时按根目录处理） */
    private fun currentDirId(): String =
        (_uiState.value as? GuangYaCloudUiState.Loaded)?.dirId ?: GuangYaConstants.ROOT_PARENT_ID

    // ---------- 目录浏览 ----------

    fun loadRoot() {
        dirStack.clear()
        nameStack.clear()
        load(GuangYaConstants.ROOT_PARENT_ID, emptyList())
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
        load(dirStack.lastOrNull() ?: GuangYaConstants.ROOT_PARENT_ID, nameStack.toList())
    }

    fun navigateToLevel(level: Int) {
        while (nameStack.size > level) {
            dirStack.removeLast()
            nameStack.removeLast()
        }
        load(dirStack.lastOrNull() ?: GuangYaConstants.ROOT_PARENT_ID, nameStack.toList())
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
        moveLoad(GuangYaConstants.ROOT_PARENT_ID, emptyList())
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
        moveLoad(moveDirStack.lastOrNull() ?: GuangYaConstants.ROOT_PARENT_ID, moveNameStack.toList())
    }

    fun moveNavigateToLevel(level: Int) {
        while (moveNameStack.size > level) {
            moveDirStack.removeLast()
            moveNameStack.removeLast()
        }
        moveLoad(moveDirStack.lastOrNull() ?: GuangYaConstants.ROOT_PARENT_ID, moveNameStack.toList())
    }

    private fun moveLoad(dirId: String, pathNames: List<String>) {
        _moveUiState.value = GuangYaCloudUiState.Loading
        viewModelScope.launch {
            try {
                val files = api.listCloudFiles(token(), dirId).filter { it.isdir }
                _moveUiState.value = GuangYaCloudUiState.Loaded(files, pathNames, dirId)
            } catch (e: Exception) {
                _moveUiState.value = GuangYaCloudUiState.Error(e.message ?: "加载失败")
            }
        }
    }

    // ---------- 单文件操作 ----------

    /** 光鸭下载请求头：直链 CDN 要求带网页 UA 与 Referer（业务 API 公共头同款）。 */
    private fun downloadHeaders(): Map<String, String> = mapOf(
        "User-Agent" to GuangYaConstants.WEB_UA,
        "Referer" to GuangYaConstants.DOWNLOAD_REFERER
    )

    /** 递归收集文件夹内所有文件（保持目录结构） */
    private suspend fun collectFolderFiles(
        dirId: String,
        prefix: String,
        tk: String,
        result: MutableList<Pair<ShareFile, String>>,
        depth: Int
    ) {
        if (depth > 12) return
        val list = runCatching { api.listCloudFiles(tk, dirId) }.getOrDefault(emptyList())
        list.filter { !it.isdir }.forEach { result.add(it to "$prefix/${it.fname}") }
        list.filter { it.isdir }.forEach {
            collectFolderFiles(it.fid, "$prefix/${it.fname}", tk, result, depth + 1)
        }
    }

    /** 下载整个文件夹（操作菜单）：递归收集所有文件，保持目录结构保存到指定目录（空=全局设置） */
    fun downloadFolder(saveDir: String = "") {
        val folder = actionFile ?: return
        if (!folder.isdir) return
        viewModelScope.launch {
            isOperating = true
            folderProgress = "正在收集文件…"
            downloadCancelRequested = false
            try {
                val tk = token()
                val tasks = mutableListOf<Pair<ShareFile, String>>()
                collectFolderFiles(folder.fid, folder.fname, tk, tasks, 0)
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
                        val link = api.getDownloadLink(tk, file) ?: return@runCatching
                        downloadManager.enqueue(
                            url = link.downloadUrl,
                            fileName = relPath,
                            size = link.size,
                            platform = DownloadPlatform.GUANGYA,
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

    /** 下载文件：取直链 → 弹下载确认弹窗（确认后入队） */
    fun downloadFile() = beginDownload("")

    /** 下载到指定目录：单次覆盖保存目录，不改全局设置 */
    fun downloadFileTo(saveDir: String) = beginDownload(saveDir)

    private fun beginDownload(saveDir: String) {
        val file = actionFile ?: return
        viewModelScope.launch {
            isOperating = true
            try {
                val link = api.getDownloadLink(token(), file)
                    ?: throw IllegalStateException("获取下载链接失败")
                pendingSaveDir = saveDir
                pendingDownload = PendingDownload(
                    url = link.downloadUrl,
                    fileName = file.fname.ifBlank { link.filename },
                    size = link.size,
                    saveDir = saveDir,
                    headers = downloadHeaders()
                )
                downloadLink = link
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
                    platform = DownloadPlatform.GUANGYA,
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
                api.renameFile(token(), file.fid, newName)
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

    /** 新建文件夹（当前目录下） */
    fun createFolder(name: String) {
        val newName = name.trim()
        if (newName.isEmpty()) return
        viewModelScope.launch {
            isOperating = true
            try {
                api.createDir(token(), currentDirId(), newName)
                cloudMessage = "已创建文件夹「$newName」"
                reloadCurrent()
            } catch (e: Exception) {
                cloudMessage = e.message ?: "新建文件夹失败"
            } finally {
                isOperating = false
            }
        }
    }

    /** 移动 */
    fun moveFile(toDirId: String) {
        val file = actionFile ?: return
        viewModelScope.launch {
            isOperating = true
            try {
                api.moveFiles(token(), listOf(file.fid), toDirId)
                cloudMessage = "已移动到目标目录"
                actionFile = null
                reloadCurrent()
            } catch (e: Exception) {
                cloudMessage = e.message ?: "移动失败"
            } finally {
                isOperating = false
            }
        }
    }

    /** 创建分享（expiredType 为 [ShareExpire] 中性码，内部转天数）。 */
    fun shareFile(expiredType: Int, sharePwd: String?) {
        val file = actionFile ?: return
        viewModelScope.launch {
            isOperating = true
            try {
                val info = api.createShare(
                    accessToken = token(),
                    fileIds = listOf(file.fid),
                    title = file.fname,
                    expireDays = ShareExpire.daysOrNull(expiredType),
                    passcode = sharePwd
                )
                shareResult = info.copy(expiredType = expiredType)
            } catch (e: Exception) {
                cloudMessage = e.message ?: "分享失败"
            } finally {
                isOperating = false
            }
        }
    }

    /** 删除 */
    fun deleteFile() {
        val file = actionFile ?: return
        viewModelScope.launch {
            isOperating = true
            try {
                api.deleteFiles(token(), listOf(file.fid))
                cloudMessage = "已删除「${file.fname}」"
                actionFile = null
                reloadCurrent()
            } catch (e: Exception) {
                cloudMessage = e.message ?: "删除失败"
            } finally {
                isOperating = false
            }
        }
    }

    // ---------- 批量操作 ----------

    /** 批量下载（选中文件夹时递归下载整个文件夹并保持目录结构） */
    fun downloadSelected(saveDir: String = "") {
        val files = _selected.toList()
        if (files.isEmpty()) return
        viewModelScope.launch {
            isOperating = true
            folderProgress = "正在收集文件…"
            downloadCancelRequested = false
            try {
                val tk = token()
                val tasks = mutableListOf<Pair<ShareFile, String>>()
                for (file in files) {
                    if (file.isdir) {
                        collectFolderFiles(file.fid, file.fname, tk, tasks, 0)
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
                tasks.forEachIndexed { index, (file, relPath) ->
                    if (downloadCancelRequested) return@forEachIndexed
                    folderProgress = "正在加入下载 ${index + 1}/${tasks.size}"
                    runCatching {
                        val link = api.getDownloadLink(tk, file) ?: return@runCatching
                        downloadManager.enqueue(
                            url = link.downloadUrl,
                            fileName = if (relPath.contains('/')) relPath else file.fname.ifBlank { link.filename },
                            size = link.size,
                            platform = DownloadPlatform.GUANGYA,
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
                cloudMessage = "已加入 $okCount 个下载任务"
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

    /** 批量分享 */
    fun shareSelected(expiredType: Int, sharePwd: String?) {
        val files = _selected.toList()
        if (files.isEmpty()) return
        viewModelScope.launch {
            isOperating = true
            try {
                val title = if (files.size == 1) files[0].fname else "分享 ${files.size} 个文件"
                val info = api.createShare(
                    accessToken = token(),
                    fileIds = files.map { it.fid },
                    title = title,
                    expireDays = ShareExpire.daysOrNull(expiredType),
                    passcode = sharePwd
                )
                shareResult = info.copy(expiredType = expiredType)
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
                api.moveFiles(token(), files.map { it.fid }, toDirId)
                cloudMessage = "已移动 ${files.size} 项"
                exitMultiSelect()
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
                api.deleteFiles(token(), files.map { it.fid })
                cloudMessage = "已删除 ${files.size} 项"
                exitMultiSelect()
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
        if (current !is GuangYaCloudUiState.Loaded) {
            loadRoot()
            return
        }
        refreshing = true
        viewModelScope.launch {
            try {
                val files = api.listCloudFiles(token(), current.dirId)
                _uiState.value = GuangYaCloudUiState.Loaded(files, current.pathNames, current.dirId)
            } catch (e: Exception) {
                cloudMessage = e.message ?: "刷新失败"
            } finally {
                refreshing = false
            }
        }
    }

    private fun reloadCurrent() {
        val current = uiState.value
        if (current is GuangYaCloudUiState.Loaded) {
            load(current.dirId, current.pathNames)
        } else {
            loadRoot()
        }
    }

    private fun load(dirId: String, pathNames: List<String>) {
        _uiState.value = GuangYaCloudUiState.Loading
        viewModelScope.launch {
            try {
                val files = api.listCloudFiles(token(), dirId)
                _uiState.value = GuangYaCloudUiState.Loaded(files, pathNames, dirId)
            } catch (e: Exception) {
                _uiState.value = GuangYaCloudUiState.Error(e.message ?: "加载失败")
            }
        }
    }

    // ---------- 上传（光鸭 OSS 直传 + 任务轮询） ----------

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
        val dir = (uiState.value as? GuangYaCloudUiState.Loaded)?.dirId ?: return
        val directories = UploadDirectoryResolver(dir)
        uploadRunner.startCandidates(files, DownloadPlatform.GUANGYA, onRetry = directories::retryFailed) { io, source ->
            val token = token()
            directories.transfer(source,
                list = { parent -> api.listCloudFiles(token, parent) },
                create = { parent, name -> api.createDir(token, parent, name) }
            ) { parent -> api.upload(io, source, parent, token) }
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
        private val api: GuangYaApi,
        private val accountRepository: GuangYaAccountRepository,
        private val downloadManager: DownloadManager,
        private val loginState: Flow<Boolean>
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: KClass<T>, extras: CreationExtras): T =
            GuangYaCloudViewModel(api, accountRepository, downloadManager, loginState) as T
    }
}
