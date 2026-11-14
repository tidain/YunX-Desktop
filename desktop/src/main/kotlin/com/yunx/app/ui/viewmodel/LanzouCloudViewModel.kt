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
import kotlin.reflect.KClass
import com.yunx.app.data.download.DownloadManager
import com.yunx.app.data.download.DownloadPlatform
import com.yunx.app.data.network.LanzouApi
import com.yunx.app.data.network.LanzouConstants
import com.yunx.app.data.network.model.DownloadLink
import com.yunx.app.data.network.model.ShareFile
import com.yunx.app.data.network.model.ShareInfo
import com.yunx.app.data.repository.LanzouAccountRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import java.net.URI

/** 蓝奏云浏览 UI 状态（根目录 dirId = "-1"）。 */
sealed interface LanzouCloudUiState {
    data object Loading : LanzouCloudUiState
    data class Loaded(
        val files: List<ShareFile>,
        val pathNames: List<String>,
        /** 当前目录 folder_id（根="-1"） */
        val dirId: String
    ) : LanzouCloudUiState
    data class Error(val message: String) : LanzouCloudUiState
}

/** 个人盘登录态参数缓存（Cookie + uid + vei） */
private data class LanzouCreds(val cookie: String, val uid: String, val vei: String)

/**
 * 蓝奏云个人盘浏览 ViewModel（镜像 123 云盘浏览）：
 * 目录浏览 + 下拉刷新 + 下载/重命名/移动/删除/创建分享 + 长按多选批量。
 * 认证走 Cookie（ylogin + phpdisk_info）；目录用 folder_id（根 = "-1"，文件夹 id 前缀 d:）。
 */
class LanzouCloudViewModel(
    private val api: LanzouApi,
    private val accountRepository: LanzouAccountRepository,
    private val downloadManager: DownloadManager,
    private val loginState: Flow<Boolean>
) : ViewModel() {

    private val _uiState = MutableStateFlow<LanzouCloudUiState>(LanzouCloudUiState.Loading)
    val uiState: StateFlow<LanzouCloudUiState> = _uiState.asStateFlow()

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

    private val _moveUiState = MutableStateFlow<LanzouCloudUiState>(LanzouCloudUiState.Loading)
    val moveUiState: StateFlow<LanzouCloudUiState> = _moveUiState.asStateFlow()
    private val moveDirStack = ArrayDeque<String>()
    private val moveNameStack = ArrayDeque<String>()

    private var cachedCreds: LanzouCreds? = null

    init {
        loadRoot()
        // 启动期未登录时 loadRoot 会残留「请先登录…」错误态；登录态从无到有后自动重载根目录。
        viewModelScope.launch {
            loginState.drop(1).distinctUntilChanged().collect { loggedIn ->
                cachedCreds = null
                if (loggedIn) loadRoot()
            }
        }
    }

    /** 取个人盘凭据（Cookie + uid + vei），按 Cookie 复用缓存 */
    private suspend fun creds(): LanzouCreds {
        val acc = accountRepository.getAccount() ?: throw UploadSessionException("请先登录蓝奏云")
        cachedCreds?.let { if (it.cookie == acc.cookie) return it }
        val params = api.fetchLoginParams(acc.cookie)
            ?: throw UploadSessionException("蓝奏登录态已失效，请重新登录")
        return LanzouCreds(acc.cookie, params.uid, params.vei).also { cachedCreds = it }
    }

    private fun folderNumeric(fid: String): String = fid.removePrefix(LanzouConstants.FOLDER_PREFIX)
    private fun fileNumeric(fid: String): String = fid.removePrefix(LanzouConstants.FILE_PREFIX)

    /** 蓝奏直链的请求头：带 Referer + down_ip（缺它下载节点拒绝） */
    private fun downloadHeaders(url: String): Map<String, String> {
        val origin = runCatching { URI(url) }.getOrNull()?.let { "${it.scheme}://${it.host}" }
            ?: "https://pan.lanzoui.com"
        return mapOf(
            "User-Agent" to LanzouConstants.WEB_UA,
            "Referer" to "$origin/",
            "Cookie" to LanzouConstants.DOWN_IP_COOKIE
        )
    }

    // ---------- 目录浏览 ----------

    fun loadRoot() {
        dirStack.clear()
        nameStack.clear()
        load(LanzouConstants.ROOT_FOLDER_ID, emptyList())
    }

    fun openFolder(file: ShareFile) {
        val dirId = folderNumeric(file.fid)
        dirStack.addLast(dirId)
        nameStack.addLast(file.fname)
        load(dirId, nameStack.toList())
    }

    fun back() {
        if (nameStack.isEmpty()) {
            loadRoot()
            return
        }
        dirStack.removeLast()
        nameStack.removeLast()
        load(dirStack.lastOrNull() ?: LanzouConstants.ROOT_FOLDER_ID, nameStack.toList())
    }

    fun navigateToLevel(level: Int) {
        while (nameStack.size > level) {
            dirStack.removeLast()
            nameStack.removeLast()
        }
        load(dirStack.lastOrNull() ?: LanzouConstants.ROOT_FOLDER_ID, nameStack.toList())
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

    fun cancelDownload() {
        downloadCancelRequested = true
    }

    // ---------- 移动目标浏览 ----------

    fun openMoveRoot() {
        moveDirStack.clear()
        moveNameStack.clear()
        moveLoad(LanzouConstants.ROOT_FOLDER_ID, emptyList())
    }

    fun openMoveFolder(file: ShareFile) {
        val dirId = folderNumeric(file.fid)
        moveDirStack.addLast(dirId)
        moveNameStack.addLast(file.fname)
        moveLoad(dirId, moveNameStack.toList())
    }

    fun moveBack() {
        if (moveNameStack.isEmpty()) return
        moveDirStack.removeLast()
        moveNameStack.removeLast()
        moveLoad(moveDirStack.lastOrNull() ?: LanzouConstants.ROOT_FOLDER_ID, moveNameStack.toList())
    }

    fun moveNavigateToLevel(level: Int) {
        while (moveNameStack.size > level) {
            moveDirStack.removeLast()
            moveNameStack.removeLast()
        }
        moveLoad(moveDirStack.lastOrNull() ?: LanzouConstants.ROOT_FOLDER_ID, moveNameStack.toList())
    }

    private fun moveLoad(dirId: String, pathNames: List<String>) {
        _moveUiState.value = LanzouCloudUiState.Loading
        viewModelScope.launch {
            try {
                val cred = creds()
                val files = api.listCloudFiles(cred.cookie, cred.uid, cred.vei, dirId).filter { it.isdir }
                _moveUiState.value = LanzouCloudUiState.Loaded(files, pathNames, dirId)
            } catch (e: Exception) {
                _moveUiState.value = LanzouCloudUiState.Error(e.message ?: "加载失败")
            }
        }
    }

    // ---------- 下载 ----------

    /** 递归收集文件夹内文件（先建分享链接再走匿名链路取直链） */
    private suspend fun downloadFolderFiles(
        cred: LanzouCreds,
        folder: ShareFile,
        prefix: String,
        out: MutableList<Triple<ShareFile, String, ShareInfo>>,
        depth: Int
    ) {
        if (depth > 6) return
        val info = runCatching {
            api.createFolderShare(cred.cookie, cred.uid, cred.vei, folderNumeric(folder.fid))
        }.getOrNull() ?: return
        val page = api.resolveShare(info.shareUrl, info.passcode)
        val files = api.listShareFolder(page, info.passcode.takeIf { it.isNotBlank() }, LanzouConstants.ROOT_FOLDER_ID)
        files.forEach { out.add(Triple(it, "$prefix/${it.fname}", info)) }
    }

    fun downloadFolder(saveDir: String = "") {
        val folder = actionFile ?: return
        if (!folder.isdir) return
        viewModelScope.launch {
            isOperating = true
            folderProgress = "正在收集文件…"
            downloadCancelRequested = false
            try {
                val cred = creds()
                val tasks = mutableListOf<Triple<ShareFile, String, ShareInfo>>()
                downloadFolderFiles(cred, folder, folder.fname, tasks, 0)
                if (tasks.isEmpty()) {
                    cloudMessage = "文件夹为空"
                    actionFile = null
                    return@launch
                }
                var okCount = 0
                tasks.forEachIndexed { index, (file, relPath, info) ->
                    if (downloadCancelRequested) return@forEachIndexed
                    folderProgress = "正在加入下载 ${index + 1}/${tasks.size}"
                    runCatching {
                        val page = api.resolveShare(info.shareUrl, info.passcode)
                        val link = api.getShareDownloadLink(page, file, info.passcode.takeIf { it.isNotBlank() })
                        downloadManager.enqueue(
                            url = link.downloadUrl,
                            fileName = relPath,
                            size = link.size,
                            platform = DownloadPlatform.LANZOU,
                            headers = downloadHeaders(link.downloadUrl),
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
                val cred = creds()
                val link = api.getPersonalDownloadLink(cred.cookie, cred.uid, cred.vei, fileNumeric(file.fid), file.fname)
                pendingSaveDir = saveDir
                pendingDownload = PendingDownload(
                    url = link.downloadUrl,
                    fileName = file.fname.ifBlank { link.filename },
                    size = link.size,
                    saveDir = saveDir,
                    headers = downloadHeaders(link.downloadUrl)
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
                    platform = DownloadPlatform.LANZOU,
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

    fun dismissDownloadDialog() {
        downloadLink = null
        pendingDownload = null
        pendingSaveDir = ""
    }

    // ---------- 单文件操作 ----------

    /** 重命名（文件/文件夹各用不同 task） */
    fun renameFile(newName: String) {
        val file = actionFile ?: return
        viewModelScope.launch {
            isOperating = true
            try {
                val cred = creds()
                if (file.isdir) {
                    api.renameFolder(cred.cookie, cred.uid, cred.vei, folderNumeric(file.fid), newName)
                } else {
                    api.renameFile(cred.cookie, cred.uid, cred.vei, fileNumeric(file.fid), newName)
                }
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
        val parentId = (uiState.value as? LanzouCloudUiState.Loaded)?.dirId ?: LanzouConstants.ROOT_FOLDER_ID
        viewModelScope.launch {
            isOperating = true
            try {
                val cred = creds()
                api.createFolder(cred.cookie, cred.uid, cred.vei, parentId, newName)
                cloudMessage = "已创建文件夹「$newName」"
                reloadCurrent()
            } catch (e: Exception) {
                cloudMessage = e.message ?: "新建文件夹失败"
            } finally {
                isOperating = false
            }
        }
    }

    /** 移动文件（蓝奏不支持移动文件夹） */
    fun moveFile(toDirId: String) {
        val file = actionFile ?: return
        if (file.isdir) {
            cloudMessage = "蓝奏云暂不支持移动文件夹"
            return
        }
        viewModelScope.launch {
            isOperating = true
            try {
                val cred = creds()
                api.moveFile(cred.cookie, cred.uid, cred.vei, toDirId, fileNumeric(file.fid))
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

    /** 创建分享（蓝奏由服务端生成提取码，忽略 UI 有效期/提取码选择）。 */
    fun shareFile() {
        val file = actionFile ?: return
        viewModelScope.launch {
            isOperating = true
            try {
                val cred = creds()
                val info = if (file.isdir) {
                    api.createFolderShare(cred.cookie, cred.uid, cred.vei, folderNumeric(file.fid))
                } else {
                    api.createFileShare(cred.cookie, cred.uid, cred.vei, fileNumeric(file.fid))
                }
                shareResult = info.copy(title = file.fname)
            } catch (e: Exception) {
                cloudMessage = e.message ?: "分享失败"
            } finally {
                isOperating = false
            }
        }
    }

    fun deleteFile() {
        val file = actionFile ?: return
        viewModelScope.launch {
            isOperating = true
            try {
                val cred = creds()
                if (file.isdir) {
                    api.deleteFolder(cred.cookie, cred.uid, cred.vei, folderNumeric(file.fid))
                } else {
                    api.deleteFile(cred.cookie, cred.uid, cred.vei, fileNumeric(file.fid))
                }
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

    fun downloadSelected(saveDir: String = "") {
        val files = _selected.toList()
        if (files.isEmpty()) return
        viewModelScope.launch {
            isOperating = true
            folderProgress = "正在收集文件…"
            downloadCancelRequested = false
            try {
                val cred = creds()
                val tasks = mutableListOf<Triple<ShareFile, String, ShareInfo?>>()
                for (file in files) {
                    if (file.isdir) {
                        val folderTasks = mutableListOf<Triple<ShareFile, String, ShareInfo>>()
                        downloadFolderFiles(cred, file, file.fname, folderTasks, 0)
                        folderTasks.forEach { tasks.add(Triple(it.first, it.second, it.third)) }
                    } else {
                        tasks.add(Triple(file, file.fname, null))
                    }
                }
                if (tasks.isEmpty()) {
                    cloudMessage = "所选文件夹为空"
                    exitMultiSelect()
                    return@launch
                }
                var okCount = 0
                tasks.forEachIndexed { index, (file, relPath, info) ->
                    if (downloadCancelRequested) return@forEachIndexed
                    folderProgress = "正在加入下载 ${index + 1}/${tasks.size}"
                    runCatching {
                        val link = if (info == null) {
                            api.getPersonalDownloadLink(cred.cookie, cred.uid, cred.vei, fileNumeric(file.fid), file.fname)
                        } else {
                            val page = api.resolveShare(info.shareUrl, info.passcode)
                            api.getShareDownloadLink(page, file, info.passcode.takeIf { it.isNotBlank() })
                        }
                        downloadManager.enqueue(
                            url = link.downloadUrl,
                            fileName = if (relPath.contains('/')) relPath else file.fname.ifBlank { link.filename },
                            size = link.size,
                            platform = DownloadPlatform.LANZOU,
                            headers = downloadHeaders(link.downloadUrl),
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

    fun shareSelected() {
        val files = _selected.toList()
        if (files.isEmpty()) return
        val file = files.first()
        viewModelScope.launch {
            isOperating = true
            try {
                val cred = creds()
                val info = if (file.isdir) {
                    api.createFolderShare(cred.cookie, cred.uid, cred.vei, folderNumeric(file.fid))
                } else {
                    api.createFileShare(cred.cookie, cred.uid, cred.vei, fileNumeric(file.fid))
                }
                shareResult = info.copy(title = file.fname)
                if (files.size > 1) cloudMessage = "蓝奏一次只能分享一个文件或文件夹，已分享第一项"
                exitMultiSelect()
            } catch (e: Exception) {
                cloudMessage = e.message ?: "分享失败"
            } finally {
                isOperating = false
            }
        }
    }

    fun moveSelected(toDirId: String) {
        val files = _selected.toList()
        if (files.isEmpty()) return
        viewModelScope.launch {
            isOperating = true
            try {
                val cred = creds()
                val movable = files.filter { !it.isdir }
                if (movable.isEmpty()) {
                    cloudMessage = "蓝奏云暂不支持移动文件夹"
                    return@launch
                }
                movable.forEach { api.moveFile(cred.cookie, cred.uid, cred.vei, toDirId, fileNumeric(it.fid)) }
                cloudMessage = "已移动 ${movable.size} 项"
                exitMultiSelect()
                reloadCurrent()
            } catch (e: Exception) {
                cloudMessage = e.message ?: "移动失败"
            } finally {
                isOperating = false
            }
        }
    }

    fun deleteSelected() {
        val files = _selected.toList()
        if (files.isEmpty()) return
        viewModelScope.launch {
            isOperating = true
            try {
                val cred = creds()
                files.forEach {
                    if (it.isdir) {
                        api.deleteFolder(cred.cookie, cred.uid, cred.vei, folderNumeric(it.fid))
                    } else {
                        api.deleteFile(cred.cookie, cred.uid, cred.vei, fileNumeric(it.fid))
                    }
                }
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
        if (current !is LanzouCloudUiState.Loaded) {
            loadRoot()
            return
        }
        refreshing = true
        viewModelScope.launch {
            try {
                val cred = creds()
                val files = api.listCloudFiles(cred.cookie, cred.uid, cred.vei, current.dirId)
                _uiState.value = LanzouCloudUiState.Loaded(files, current.pathNames, current.dirId)
            } catch (e: Exception) {
                cloudMessage = e.message ?: "刷新失败"
            } finally {
                refreshing = false
            }
        }
    }

    private fun reloadCurrent() {
        val current = uiState.value
        if (current is LanzouCloudUiState.Loaded) {
            load(current.dirId, current.pathNames)
        } else {
            loadRoot()
        }
    }

    private fun load(dirId: String, pathNames: List<String>) {
        _uiState.value = LanzouCloudUiState.Loading
        viewModelScope.launch {
            try {
                val cred = creds()
                val files = api.listCloudFiles(cred.cookie, cred.uid, cred.vei, dirId)
                _uiState.value = LanzouCloudUiState.Loaded(files, pathNames, dirId)
            } catch (e: Exception) {
                _uiState.value = LanzouCloudUiState.Error(e.message ?: "加载失败")
            }
        }
    }

    // ---------- 上传（蓝奏单次 multipart） ----------

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
        val dir = (uiState.value as? LanzouCloudUiState.Loaded)?.dirId ?: return
        val directories = UploadDirectoryResolver(dir)
        uploadRunner.startCandidates(files, DownloadPlatform.LANZOU, onRetry = directories::retryFailed) { io, source ->
            val cred = creds()
            directories.transfer(source,
                list = { parent -> api.listCloudFiles(cred.cookie, cred.uid, cred.vei, parent)
                    .map { if (it.isdir) it.copy(fid = folderNumeric(it.fid)) else it } },
                create = { parent, name -> api.createFolder(cred.cookie, cred.uid, cred.vei, parent, name) }
            ) { parent -> api.upload(io, source, parent, cred.cookie, cred.uid, cred.vei) }
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
        private val api: LanzouApi,
        private val accountRepository: LanzouAccountRepository,
        private val downloadManager: DownloadManager,
        private val loginState: Flow<Boolean>
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: KClass<T>, extras: CreationExtras): T =
            LanzouCloudViewModel(api, accountRepository, downloadManager, loginState) as T
    }
}
