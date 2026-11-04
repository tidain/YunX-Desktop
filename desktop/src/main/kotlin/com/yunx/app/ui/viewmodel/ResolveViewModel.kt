package com.yunx.app.ui.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.yunx.app.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import kotlin.reflect.KClass
import com.yunx.app.data.db.BookmarkDao
import com.yunx.app.data.db.BookmarkEntity
import com.yunx.app.data.db.LinkHistoryDao
import com.yunx.app.data.db.LinkHistoryEntity
import com.yunx.app.data.download.DownloadManager
import com.yunx.app.data.download.DownloadPlatform
import com.yunx.app.data.download.DownloadSource
import com.yunx.app.data.network.BaiduConstants
import com.yunx.app.data.network.C139Constants
import com.yunx.app.data.network.GitHubApi
import com.yunx.app.data.network.GitHubCommitDateCache
import com.yunx.app.data.network.GitHubLinkParser
import com.yunx.app.data.network.GitHubLinkType
import com.yunx.app.data.network.GitHubRelease
import com.yunx.app.data.network.GitHubRepo
import com.yunx.app.data.network.GitHubResponseCache
import com.yunx.app.data.network.GuangYaConstants
import com.yunx.app.data.network.ILanzouConstants
import com.yunx.app.data.network.LanzouConstants
import com.yunx.app.data.network.Pan115Constants
import com.yunx.app.data.network.Pan123Constants
import com.yunx.app.data.network.QuarkConstants
import com.yunx.app.data.network.QuarkCdn
import com.yunx.app.data.network.GitHubMirror
import com.yunx.app.data.network.ShareLinkParser
import com.yunx.app.data.network.SharePlatform
import com.yunx.app.data.network.StarSeaApi
import com.yunx.app.data.network.UCConstants
import com.yunx.app.data.network.XunleiConstants
import com.yunx.app.data.network.XunleiKouling
import com.yunx.app.data.update.UpdateChecker
import com.yunx.app.data.network.model.DownloadLink
import com.yunx.app.data.network.model.ShareFile
import com.yunx.app.data.network.model.ShareSession
import com.yunx.app.data.prefs.SettingsRepository
import com.yunx.app.data.repository.BaiduAccountRepository
import com.yunx.app.data.repository.BaiduResolveRepository
import com.yunx.app.data.repository.C139AccountRepository
import com.yunx.app.data.repository.C139ResolveRepository
import com.yunx.app.data.repository.GuangYaAccountRepository
import com.yunx.app.data.repository.GuangYaResolveRepository
import com.yunx.app.data.repository.ILanzouAccountRepository
import com.yunx.app.data.repository.ILanzouResolveRepository
import com.yunx.app.data.repository.LanzouAccountRepository
import com.yunx.app.data.repository.LanzouResolveRepository
import com.yunx.app.data.repository.Pan115AccountRepository
import com.yunx.app.data.repository.Pan115ResolveRepository
import com.yunx.app.data.repository.Pan123AccountRepository
import com.yunx.app.data.repository.Pan123ResolveRepository
import com.yunx.app.data.repository.QuarkAccountRepository
import com.yunx.app.data.repository.QuarkResolveRepository
import com.yunx.app.data.repository.ShareResolveRepository
import com.yunx.app.data.repository.UCAccountRepository
import com.yunx.app.data.repository.UCResolveRepository
import com.yunx.app.data.repository.XunleiAccountRepository
import com.yunx.app.data.repository.XunleiResolveRepository
import com.yunx.app.ui.SnackbarController
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

sealed interface ResolveUiState {
    data object Idle : ResolveUiState
    data object Loading : ResolveUiState
    data class Detail(val session: ShareSession, val files: List<ShareFile>) : ResolveUiState
    data class Error(val message: String) : ResolveUiState
}

/**
 * 解析页 ViewModel：分享解析状态机 + 目录导航 + 下载直链。
 * 支持夸克 / UC / 迅雷 / 百度 / 139 / 123 / 115，按链接自动路由到对应平台仓库与凭证。
 */
class ResolveViewModel(
    private val accountRepository: QuarkAccountRepository,
    private val resolveRepository: QuarkResolveRepository,
    private val ucAccountRepository: UCAccountRepository,
    private val ucResolveRepository: UCResolveRepository,
    private val xunleiAccountRepository: XunleiAccountRepository,
    private val xunleiResolveRepository: XunleiResolveRepository,
    private val baiduAccountRepository: BaiduAccountRepository,
    private val baiduResolveRepository: BaiduResolveRepository,
    private val c139AccountRepository: C139AccountRepository,
    private val c139ResolveRepository: C139ResolveRepository,
    private val pan123AccountRepository: Pan123AccountRepository,
    private val pan123ResolveRepository: Pan123ResolveRepository,
    private val pan115AccountRepository: Pan115AccountRepository,
    private val pan115ResolveRepository: Pan115ResolveRepository,
    private val guangyaAccountRepository: GuangYaAccountRepository,
    private val guangyaResolveRepository: GuangYaResolveRepository,
    private val ilanzouAccountRepository: ILanzouAccountRepository,
    private val ilanzouResolveRepository: ILanzouResolveRepository,
    private val lanzouAccountRepository: LanzouAccountRepository,
    private val lanzouResolveRepository: LanzouResolveRepository,
    private val downloadManager: DownloadManager,
    private val bookmarkDao: BookmarkDao,
    private val linkHistoryDao: LinkHistoryDao,
    /** GitHub 解析器实例（null 表示未启用 GitHub 平台） */
    private val githubApi: GitHubApi? = null,
    /** GitHub 下载镜像前缀提供者（由上层从设置注入；默认走 GitHubMirror 自动选最快或用户固定） */
    private val mirrorPrefixProvider: () -> String = { GitHubMirror.activePrefix() },
    /**
     * 取链方式提供者（由上层从设置注入）：
     * true = 夸克免转存（直接换直链，不写入网盘，默认）；false = 先转存到临时目录再取链。
     * 用 lambda 而不是构造时取值，设置页改了立即生效。
     */
    private val noSaveDownloadProvider: () -> Boolean = { true },
    /**
     * 多账号下载（实验性 C1）开关：由上层从 SettingsRepository 注入，改设置即时生效。
     * 开启后批量下载会按文件轮换该网盘的不同账号取链，绕开单账号的并发数 / 限速
     * （每个文件仍只用一个账号下载），见 [credentialRotation]。
     */
    private val multiAccountDownloadProvider: () -> Boolean = { false },
    /**
     * 单文件多源分片（实验性 C2）开关：由上层从 SettingsRepository 注入
     * （工厂侧用 `multiSourceActive()` 判断，已保证 C2 生效时 C1 也开着）。
     * 开启后同一个文件用多个账号各取一条直链，分片轮流交给不同直链下载，见 [fetchAltLinks]。
     */
    private val multiSourceDownloadProvider: () -> Boolean = { false }
) : ViewModel() {

    init {
        // 多账号下载（实验性 C1/C2）：迅雷每次请求都必须用该账号自己的设备指纹与 captcha，
        // 仓库通过 access_token 反查账号行（桌面端在这里接线，避免改动 MainScreen 的装配）
        xunleiResolveRepository.accountByTokenProvider = { token ->
            xunleiAccountRepository.getAccounts().firstOrNull { it.accessToken == token }
        }
    }

    var uiState by mutableStateOf<ResolveUiState>(ResolveUiState.Idle)
        private set

    var downloadLink by mutableStateOf<DownloadLink?>(null)
        private set

    /**
     * 单文件多源分片（实验性 C2）预取的备选直链（直链 + 该账号凭证）。
     * 与 [downloadLink] 在同一时刻取回、由紧接着的那次 [startDownload] 消费后清空 ——
     * 直链签名有时效，不能等用户点「下载」时再去取。
     */
    private var pendingAltLinks: List<Pair<DownloadLink, String>> = emptyList()

    /** 本次单文件下载的保存目录（空串 = 跟随全局设置）；下载弹窗内可更改 */
    var pendingSaveDir by mutableStateOf("")
        private set

    /** 下载弹窗内选择/更改保存目录 */
    fun chooseDownloadDir(dir: String) {
        pendingSaveDir = dir
    }

    var downloadError by mutableStateOf<String?>(null)
    private set

    /** 获取下载直链中（UI 显示加载弹窗） */
    var isFetchingDownloadLink by mutableStateOf(false)
        private set

    /** 待转存文件（转存弹窗）；null 表示未在转存流程 */
    var saveTarget by mutableStateOf<ShareFile?>(null)
        private set

    /** 转存中（UI 显示加载） */
    var isSaving by mutableStateOf(false)
        private set

    /** 转存结果消息（Toast） */
    var saveMessage by mutableStateOf<String?>(null)
        private set

    /** 当前分享是否支持转存（夸克 / UC / 迅雷 / 百度 / 139 / 123 / 115 / 光鸭） */
    val canSave: Boolean
        get() = currentPlatform == SharePlatform.QUARK ||
            currentPlatform == SharePlatform.UC ||
            currentPlatform == SharePlatform.XUNLEI ||
            currentPlatform == SharePlatform.BAIDU ||
            currentPlatform == SharePlatform.C139 ||
            currentPlatform == SharePlatform.PAN123 ||
            currentPlatform == SharePlatform.PAN115 ||
            currentPlatform == SharePlatform.GUANGYA

    /** 当前分享是否为迅雷（UI 选择迅雷版转存目录选择器） */
    val isSaveXunlei: Boolean
        get() = currentPlatform == SharePlatform.XUNLEI

    /** 当前分享是否为百度（UI 选择百度版转存目录选择器） */
    val isSaveBaidu: Boolean
        get() = currentPlatform == SharePlatform.BAIDU

    /** 当前分享是否为百度（限速提示判断用） */
    val isBaidu: Boolean
        get() = currentPlatform == SharePlatform.BAIDU

    /** 当前分享是否为 139（UI 选择 139 版转存目录选择器） */
    val isSaveC139: Boolean
        get() = currentPlatform == SharePlatform.C139

    /** 当前分享是否为 UC（UI 选择 UC 版转存目录选择器） */
    val isSaveUC: Boolean
        get() = currentPlatform == SharePlatform.UC

    /** 当前分享是否为 123（UI 选择 123 版转存目录选择器） */
    val isSavePan123: Boolean
        get() = currentPlatform == SharePlatform.PAN123

    /** 当前分享是否为 115（UI 选择 115 版转存目录选择器） */
    val isSavePan115: Boolean
        get() = currentPlatform == SharePlatform.PAN115

    /** 当前分享是否为光鸭云盘（UI 选择光鸭版转存目录选择器） */
    val isSaveGuangYa: Boolean
        get() = currentPlatform == SharePlatform.GUANGYA

    /** 请求转存：记录目标文件并打开目录选择弹窗 */
    fun requestSave(file: ShareFile) {
        // 游客模式只开放列目录：转存需要登录，直接给提示，别让用户进到空的目录选择
        if (isGuest) {
            downloadError = "转存需要先登录${platformName()}（未登录仅能浏览文件列表）"
            return
        }
        saveTarget = file
        saveMessage = null
    }

    fun dismissSave() {
        saveTarget = null
        isSaving = false
    }

    fun consumeSaveMessage() {
        saveMessage = null
    }

    /** 转存到网盘指定目录（保存成功自动关闭弹窗；夸克 / 迅雷 / 百度分平台实现） */
    fun saveToCloud(toDirFid: String) {
        val file = saveTarget ?: return
        val s = session ?: return
        viewModelScope.launch {
            isSaving = true
            try {
                when (currentPlatform) {
                    SharePlatform.XUNLEI -> {
                        val credential = currentCredential()
                        if (credential.isNullOrBlank()) {
                            saveMessage = "请先登录迅雷网盘"
                            return@launch
                        }
                        xunleiResolveRepository.transferFile(s, file, toDirFid, credential)
                            .onSuccess {
                                saveMessage = "已保存到迅雷网盘"
                                saveTarget = null
                            }
                            .onFailure {
                                saveMessage = it.message ?: "转存失败"
                            }
                    }
                    SharePlatform.BAIDU -> {
                        val credential = currentCredential()
                        if (credential.isNullOrBlank()) {
                            saveMessage = "请先登录百度网盘"
                            return@launch
                        }
                        baiduResolveRepository.transferFile(s, file, toDirFid, credential)
                            .onSuccess {
                                saveMessage = "已保存到百度网盘"
                                saveTarget = null
                            }
                            .onFailure {
                                saveMessage = it.message ?: "转存失败"
                            }
                    }
                    SharePlatform.C139 -> {
                        val credential = currentCredential()
                        if (credential.isNullOrBlank()) {
                            saveMessage = "请先登录139网盘"
                            return@launch
                        }
                        c139ResolveRepository.transferFile(s, file, toDirFid, credential)
                            .onSuccess {
                                saveMessage = "已保存到139网盘"
                                saveTarget = null
                            }
                            .onFailure {
                                saveMessage = it.message ?: "转存失败"
                            }
                    }
                    SharePlatform.UC -> {
                        val credential = currentCredential()
                        if (credential.isNullOrBlank()) {
                            saveMessage = "请先登录UC网盘"
                            return@launch
                        }
                        ucResolveRepository.transferFile(s, file, toDirFid, credential)
                            .onSuccess {
                                saveMessage = "已保存到UC网盘"
                                saveTarget = null
                            }
                            .onFailure {
                                saveMessage = it.message ?: "转存失败"
                            }
                    }
                    SharePlatform.PAN123 -> {
                        // 123 保存到个人盘：copy/save（mshare 无需签名）+ 轮询 task
                        val credential = currentCredential()
                        if (credential.isNullOrBlank()) {
                            saveMessage = "请先登录123云盘"
                            return@launch
                        }
                        pan123ResolveRepository.transferFile(s, file, toDirFid, credential)
                            .onSuccess {
                                saveMessage = "已保存到123云盘"
                                saveTarget = null
                            }
                            .onFailure {
                                saveMessage = it.message ?: "转存失败"
                            }
                    }
                    SharePlatform.PAN115 -> {
                        // 115 保存到个人盘：share/receive（不回传新 fid，仓库内部转存后回查）
                        val credential = currentCredential()
                        if (credential.isNullOrBlank()) {
                            saveMessage = "请先登录115网盘"
                            return@launch
                        }
                        pan115ResolveRepository.transferFile(s, file, toDirFid, credential)
                            .onSuccess {
                                saveMessage = "已保存到115网盘"
                                saveTarget = null
                            }
                            .onFailure {
                                saveMessage = it.message ?: "转存失败"
                            }
                    }
                    SharePlatform.GUANGYA -> {
                        // 光鸭保存到个人盘：restore_share（accessToken + fileIds + parentId）
                        val credential = currentCredential()
                        if (credential.isNullOrBlank()) {
                            saveMessage = "请先登录光鸭云盘"
                            return@launch
                        }
                        guangyaResolveRepository.transferFile(s, file, toDirFid, credential)
                            .onSuccess {
                                saveMessage = "已保存到光鸭云盘"
                                saveTarget = null
                            }
                            .onFailure {
                                saveMessage = it.message ?: "转存失败"
                            }
                    }
                    else -> {
                        val credential = currentCredential()
                        if (credential.isNullOrBlank()) {
                            saveMessage = "请先登录夸克网盘"
                            return@launch
                        }
                        resolveRepository.saveToCloud(s, file, toDirFid, credential)
                            .onSuccess {
                                saveMessage = "已保存到夸克网盘"
                                saveTarget = null
                            }
                            .onFailure {
                                saveMessage = it.message ?: "转存失败"
                            }
                    }
                }
            } finally {
                isSaving = false
            }
        }
    }

    /**
     * 游客模式：本次解析没有携带任何登录凭据（列目录可用，下载/转存需登录）。
     * 6 个网盘的分享**列表**接口都允许匿名（123/139/百度/夸克/UC 直接匿名；迅雷走不带
     * Authorization 的分享接口），因此解析不再要求登录；下载/转存在各自入口按平台判断。
     */
    var isGuest by mutableStateOf(false)
        private set

    /** 下载已入队事件：触发后由 UI 切换到下载页 */
    var downloadStarted by mutableStateOf(false)
        private set

    // ---------- 长按多选（解析页文件列表） ----------

    /** 多选模式（长按进入） */
    var multiSelectMode by mutableStateOf(false)
        private set

    private val _selected = mutableStateListOf<ShareFile>()
    val selected: List<ShareFile> get() = _selected

    /** 批量处理中（UI 显示加载弹窗） */
    var isBatchWorking by mutableStateOf(false)
        private set

    /** 批量下载进度（如 "2/5"）；null 表示未显示进度 */
    var batchProgress by mutableStateOf<String?>(null)
        private set

    /** 批量处理中断请求（UI 点「中断」后置 true，批量循环中检查并跳出） */
    private var batchCancelRequested = false

    /** 中断当前批量处理（批量下载/批量转存） */
    fun cancelBatch() {
        batchCancelRequested = true
    }

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

    /** 批量转存到网盘根目录（夸克 / 迅雷 / 百度分平台；仅支持转存的平台） */
    fun batchSaveToCloud() {
        val files = _selected.toList()
        val s = session ?: return
        viewModelScope.launch {
            isBatchWorking = true
            batchCancelRequested = false
            try {
                val credential = currentCredential()
                if (credential.isNullOrBlank()) {
                    // 游客模式只开放列目录：转存需要登录
                    downloadError = "转存需要先登录${platformName()}（未登录仅能浏览文件列表）"
                    return@launch
                }
                var okCount = 0
                var interrupted = false
                // 整批空间预算校验：进入循环前把选中项大小一次性求和，只查一次配额；
                // 必须在循环外做，错误才不会被循环内的 runCatching 吞掉（否则表现为「少转存了一项」且无提示）
                val batchChecked = try {
                    currentRepo().ensureBatchSpace(files.map { it.fsize }, credential)
                } catch (e: Exception) {
                    downloadError = e.message ?: "剩余空间不足"
                    return@launch
                }
                for (file in files) {
                    // 用户点击「中断」：停止剩余项，已转存的不回滚
                    if (batchCancelRequested) {
                        interrupted = true
                        downloadError = "已中断批量转存"
                        break
                    }
                    runCatching {
                        when (currentPlatform) {
                            SharePlatform.XUNLEI -> {
                                // 迅雷批量转存到根目录（parent_id 为空）
                                xunleiResolveRepository.transferFile(s, file, "", credential, batchChecked)
                            }
                            SharePlatform.BAIDU -> {
                                // 百度批量转存到根目录（绝对路径 "/"）
                                baiduResolveRepository.transferFile(s, file, "/", credential, batchChecked)
                            }
                            SharePlatform.C139 -> {
                                // 139 批量转存到根目录（fileId "/"）
                                c139ResolveRepository.transferFile(s, file, "/", credential, batchChecked)
                            }
                            SharePlatform.UC -> {
                                // UC 批量转存到根目录（pdir_fid "0"）
                                ucResolveRepository.transferFile(s, file, UCConstants.DEFAULT_PDIR_FID, credential, batchChecked)
                            }
                            SharePlatform.PAN123 -> {
                                // 123 批量转存到根目录（fileId "0"）
                                pan123ResolveRepository.transferFile(s, file, "0", credential, batchChecked)
                            }
                            SharePlatform.PAN115 -> {
                                // 115 批量转存到根目录（cid "0"）
                                pan115ResolveRepository.transferFile(s, file, Pan115Constants.ROOT_CID, credential, batchChecked)
                            }
                            SharePlatform.GUANGYA -> {
                                // 光鸭批量转存到根目录（parentId ""）
                                guangyaResolveRepository.transferFile(s, file, "", credential, batchChecked)
                            }
                            SharePlatform.ILANZOU -> {
                                // 蓝奏优享批量转存到根目录（folderId "0"）
                                ilanzouResolveRepository.transferFile(s, file, "0", credential, batchChecked)
                            }
                            else -> {
                                resolveRepository.saveToCloud(
                                    s, file, QuarkConstants.DEFAULT_PDIR_FID, credential, batchChecked
                                )
                            }
                        }
                    }.onSuccess { okCount++ }
                }
                if (!interrupted) {
                    downloadError = if (okCount > 0) "已转存 $okCount 项到${platformName()}" else "转存失败"
                }
                exitMultiSelect()
            } finally {
                isBatchWorking = false
                batchCancelRequested = false
            }
        }
    }

    /** 批量下载：逐个取直链入队（选中文件夹时递归下载整个文件夹并保持目录结构，全部获取完再统一切到下载页） */
    fun batchDownload(saveDir: String = "") {
        // GitHub 分支：无需凭证/取链 API，逐文件构造直链入队（代码文件夹递归收集 blob）
        if (currentPlatform == SharePlatform.GITHUB) {
            batchGitHubDownload(saveDir)
            return
        }
        val files = _selected.toList()
        val s = session ?: return
        viewModelScope.launch {
            isBatchWorking = true
            batchProgress = "正在收集文件…"
            batchCancelRequested = false
            try {
                // 游客模式：夸克/UC 的小文件直链不要求登录（走游客取链），其余平台必须登录
                val credential = currentCredential().orEmpty()
                if (credential.isBlank() && !supportsGuestDownload()) {
                    downloadError = "下载需要先登录${platformName()}（未登录仅能浏览文件列表）"
                    return@launch
                }
                // 夸克/UC 共用 __puus：取链与下载必须用同一份已刷新 Cookie（直链签名绑定取链时刻的 __puus）
                val quarkCred = if (credential.isBlank()) {
                    ""
                } else {
                    when (currentPlatform) {
                        SharePlatform.QUARK -> accountRepository.getFreshCookie() ?: credential
                        SharePlatform.UC -> ucAccountRepository.getFreshCookie() ?: credential
                        else -> credential
                    }
                }
                // 展开选中项：文件直接加入，文件夹递归收集（相对路径 = 文件夹名/子/...）
                val tasks = mutableListOf<Pair<ShareFile, String>>()
                for (file in files) {
                    if (file.isdir) {
                        collectShareFolder(s, file.fid, file.fname, quarkCred, tasks, 0)
                    } else {
                        tasks.add(file to "")
                    }
                }
                if (tasks.isEmpty()) {
                    downloadError = "所选文件夹为空"
                    exitMultiSelect()
                    return@launch
                }
                // 展开后的整批文件大小（多账号空间判定用；未知大小按 0 处理）
                val sizes = tasks.map { it.first.fsize }
                // 仅「下载路径需先转存」的平台会占用目标账号空间；
                // UC / 139 / 123 等直接取分享直链、不占空间，无需校验。
                // ★ 夸克开着「免转存」时，批量下载走 getShareDownloadLinkWithoutSave —— 既不建临时目录、
                //   也不转存，**根本不占空间**。若仍按「要转存」做整批预算，网盘满了的用户会被
                //   「剩余空间不足」整批挡住，正好和免转存的初衷相反。
                //   个别文件免转存失败、回退到转存时，由逐项校验兜底（下面 batchChecked 保持 false）。
                val noSaveThisBatch = noSaveDownloadProvider() && currentPlatform == SharePlatform.QUARK
                val downloadTransfers = !noSaveThisBatch && (
                    currentPlatform == SharePlatform.QUARK ||
                        currentPlatform == SharePlatform.XUNLEI ||
                        currentPlatform == SharePlatform.BAIDU
                    )
                // 多账号下载（实验性 C1）：按文件轮换该网盘的不同账号取链（主账号在前）。
                // 开关关闭 / 只有主账号时这里就是 [quarkCred] 一项，行为与开启前完全一致。
                var credentialPool = if (credential.isBlank()) emptyList() else credentialRotation(quarkCred)
                var skippedForSpace = 0
                var batchChecked = false
                if (downloadTransfers && credential.isNotBlank() && credentialPool.isNotEmpty()) {
                    // 逐个账号做整批空间预算：放不下的账号直接跳过、不参与轮换。
                    // TransferSpaceGuard 只在「确实查到配额且不够」时抛错（查询失败 / 大小未知会放行），
                    // 所以 isFailure 就代表「这个账号空间不够」。
                    val usable = mutableListOf<String>()
                    var firstFailure: Throwable? = null
                    credentialPool.forEachIndexed { i, cred ->
                        val checked = runCatching { currentRepo().ensureBatchSpace(sizes, cred) }
                        if (checked.isSuccess) {
                            usable += cred
                            if (i == 0) batchChecked = checked.getOrDefault(false)
                        } else {
                            skippedForSpace++
                            if (firstFailure == null) firstFailure = checked.exceptionOrNull()
                        }
                    }
                    if (usable.isEmpty()) {
                        // 所有账号都放不下：沿用原来的口径，直接中止并提示
                        downloadError = firstFailure?.message ?: "剩余空间不足"
                        return@launch
                    }
                    credentialPool = usable
                }
                // 轮换池只剩 1 个可用账号时自然退回单账号（fileCred = credentialPool[0]）
                var okCount = 0
                var failCount = 0
                var firstFailReason: String? = null
                // 「有多少个账号成功」：真正成功取到过直链的账号集合
                val successfulCredentials = linkedSetOf<String>()
                var interrupted = false
                for ((index, task) in tasks.withIndex()) {
                    // 用户点击「中断」：停止剩余项，已入队的任务保留下载
                    if (batchCancelRequested) {
                        interrupted = true
                        downloadError = "已中断批量下载"
                        break
                    }
                    val (file, relPath) = task
                    batchProgress = "${index + 1}/${tasks.size}"
                    // 本次文件用哪个账号：按文件序号轮换（取链与下载始终用同一份凭证）
                    val fileCred = if (credentialPool.isEmpty()) {
                        quarkCred
                    } else {
                        credentialPool[index % credentialPool.size]
                    }
                    runCatching {
                        // 游客态（凭据为空）走游客取链：夸克/UC 分享直链不要求账号，带游客 __pugs 即可下载；
                        // 登录态优先免转存，夸克失败时自动回退到转存取链
                        val linkResult = if (credential.isBlank()) {
                            currentRepo().getGuestShareDownloadLink(s, file)
                        } else {
                            resolveShareLink(currentRepo(), s, file, fileCred, batchChecked)
                        }
                        // ★ 取链失败必须抛出（而不是 getOrNull() 后什么都不做）：批量下载里静默跳过会
                        //   表现为「整个文件夹只加入了几个文件，其余像没发生一样」，用户根本不知道是失败了
                        val link = linkResult.getOrNull()
                            ?: throw IllegalStateException(linkResult.exceptionOrNull()?.message ?: "未获取到下载直链")
                        // 文件夹内文件用相对路径（保持目录结构）；根目录文件用取链返回的文件名
                        enqueueDownload(
                            link,
                            fileCred,
                            if (relPath.isBlank()) link.filename else relPath,
                            saveDir = saveDir
                        )
                        successfulCredentials += fileCred
                        okCount++
                    }.onFailure { e ->
                        // 协程取消不是「失败」：原样抛出去，否则会被记成一条假的失败原因
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        failCount++
                        val reason = e.message ?: e.javaClass.simpleName
                        if (firstFailReason == null) firstFailReason = reason
                        Log.w("YunX-Resolve", "批量下载跳过 ${file.fname}：$reason")
                    }
                }
                if (!interrupted) {
                    // 账号情况：成功参与取链的账号数 + 空间不足被跳过的账号数；
                    // 只剩一个可用账号时明确提示已退回单账号，避免用户以为多账号没生效
                    val notes = mutableListOf<String>()
                    if (credentialPool.size > 1 || skippedForSpace > 0) {
                        notes += "${successfulCredentials.size} 个账号成功取链"
                        if (skippedForSpace > 0) notes += "$skippedForSpace 个账号空间不足已跳过"
                        if (credentialPool.size == 1) notes += "已退回单账号"
                    }
                    val note = if (notes.isEmpty()) "" else "（${notes.joinToString("，")}）"
                    downloadError = when {
                        failCount == 0 -> if (okCount > 0) {
                            "已加入 $okCount 个下载任务$note"
                        } else {
                            "获取下载链接失败"
                        }
                        else -> "已加入 $okCount 个，$failCount 个失败$note" +
                            (firstFailReason?.let { "；首个失败原因：$it" } ?: "")
                    }
                    // 全部获取完再一次性切到下载页
                    if (okCount > 0) downloadStarted = true
                }
                exitMultiSelect()
            } finally {
                isBatchWorking = false
                batchProgress = null
                batchCancelRequested = false
            }
        }
    }

    /**
     * 递归收集分享文件夹内所有文件（保持目录结构）。
     * @param dirFid 分享内目录 fid
     * @param prefix 相对路径前缀（如 "文件夹A/子目录"）
     * @param result 输出：文件 + 相对路径（"文件夹A/子目录/文件.mp4"）
     */
    private suspend fun collectShareFolder(
        s: ShareSession,
        dirFid: String,
        prefix: String,
        credential: String,
        result: MutableList<Pair<ShareFile, String>>,
        depth: Int
    ) {
        if (depth > 12) return
        val files = runCatching {
            currentRepo().listFiles(s, dirFid, credential).getOrNull() ?: emptyList()
        }.getOrDefault(emptyList())
        files.filter { !it.isdir }.forEach { result.add(it to "$prefix/${it.fname}") }
        files.filter { it.isdir }.forEach {
            collectShareFolder(s, it.fid, "$prefix/${it.fname}", credential, result, depth + 1)
        }
    }

    fun consumeDownloadStarted() {
        downloadStarted = false
    }

    fun consumeDownloadError() {
        downloadError = null
    }

    private var session: ShareSession? = null
    private var currentDirFid = QuarkConstants.DEFAULT_PDIR_FID
    private val dirStack = ArrayDeque<String>()

    /** 当前解析的原始分享链接与提取码（收藏当前分享用） */
    private var currentLink: String? = null
    private var currentPwd: String? = null

    /** 当前目录路径名栈（用于面包屑显示），如 [辅助工具, 专用模组] */
    var pathNames by mutableStateOf<List<String>>(emptyList())
        private set

    /** 当前解析平台（QUARK / UC / XUNLEI），由链接自动检测 */
    private var currentPlatform: SharePlatform = SharePlatform.QUARK

    /** 当前平台凭证（夸克/UC/百度/139/115 用 cookie，迅雷/123 用 access_token；GitHub 无需凭证） */
    private suspend fun currentCredential(): String? = when (currentPlatform) {
        SharePlatform.UC -> ucAccountRepository.getAccount()?.cookie
        SharePlatform.XUNLEI -> xunleiAccountRepository.getAccount()?.accessToken
        SharePlatform.BAIDU -> baiduAccountRepository.getAccount()?.cookie
        SharePlatform.C139 -> c139AccountRepository.getAccount()?.cookie
        SharePlatform.PAN123 -> pan123AccountRepository.getAccount()?.accessToken
        SharePlatform.PAN115 -> pan115AccountRepository.getAccount()?.cookie
        SharePlatform.GUANGYA -> guangyaAccountRepository.ensureAccessToken()
        SharePlatform.ILANZOU -> ilanzouAccountRepository.getAccount()?.appToken
        SharePlatform.LANZOU -> lanzouAccountRepository.getAccount()?.cookie
        SharePlatform.GITHUB -> null
        else -> accountRepository.getAccount()?.cookie
    }

    private fun currentRepo(): ShareResolveRepository = when (currentPlatform) {
        SharePlatform.UC -> ucResolveRepository
        SharePlatform.XUNLEI -> xunleiResolveRepository
        SharePlatform.BAIDU -> baiduResolveRepository
        SharePlatform.C139 -> c139ResolveRepository
        SharePlatform.PAN123 -> pan123ResolveRepository
        SharePlatform.PAN115 -> pan115ResolveRepository
        SharePlatform.GUANGYA -> guangyaResolveRepository
        SharePlatform.ILANZOU -> ilanzouResolveRepository
        SharePlatform.LANZOU -> lanzouResolveRepository
        else -> resolveRepository
    }

    /**
     * 多账号下载（实验性 C1）：本次批量下载可以轮换使用的凭证列表（主账号在前）。
     *
     * 10 个平台都已接入：各平台「取链仓库」本来就把凭证作为方法参数（token 型平台内部用
     * `cookie.ifBlank { tokenProvider() }` 兜底），所以这里只要把同平台所有账号的凭证按主账号优先排好即可。
     * 关闭开关、平台只有一个账号、或游客链路时返回原来那一条。
     */
    private suspend fun credentialRotation(fallback: String): List<String> {
        if (!multiAccountDownloadProvider()) return listOf(fallback)
        val credentials = when (currentPlatform) {
            SharePlatform.QUARK -> accountRepository.allCredentials()
            SharePlatform.UC -> ucAccountRepository.allCredentials()
            SharePlatform.XUNLEI -> xunleiAccountRepository.allCredentials()
            SharePlatform.BAIDU -> baiduAccountRepository.allCredentials()
            SharePlatform.C139 -> c139AccountRepository.allCredentials()
            SharePlatform.PAN123 -> pan123AccountRepository.allCredentials()
            SharePlatform.PAN115 -> pan115AccountRepository.allCredentials()
            SharePlatform.GUANGYA -> guangyaAccountRepository.allCredentials()
            SharePlatform.ILANZOU -> ilanzouAccountRepository.allCredentials()
            SharePlatform.LANZOU -> lanzouAccountRepository.allCredentials()
            else -> emptyList()
        }.filter { it.isNotBlank() }
        return credentials.ifEmpty { listOf(fallback) }
    }

    /** 单文件多源分片（实验性 C2）支持的平台：其余平台即使开着 C2 也退回单源 */
    private val multiSourcePlatforms = setOf(
        SharePlatform.QUARK,
        SharePlatform.UC,
        SharePlatform.XUNLEI,
        SharePlatform.BAIDU
    )

    /**
     * 单文件多源分片（实验性 C2）：对同一个文件再用**其他账号**各取一条直链。
     *
     * 只在「C2 生效 + 平台受支持 + 确实还有第二个账号」时才返回非空；任一账号取链失败就跳过它
     * （多源只是提速手段，少一条照样能把文件下完）。
     * 返回「直链 + 该账号凭证」成对的数据：下载请求头必须按这个凭证配，不能配主账号的。
     */
    private suspend fun fetchAltLinks(
        file: ShareFile,
        primaryCredential: String
    ): List<Pair<DownloadLink, String>> {
        if (!multiSourceDownloadProvider() || currentPlatform !in multiSourcePlatforms) return emptyList()
        val s = session ?: return emptyList()
        return credentialRotation(primaryCredential)
            .filter { it != primaryCredential }
            .mapNotNull { credential ->
                runCatching { resolveShareLink(currentRepo(), s, file, credential).getOrNull() }
                    .getOrNull()
                    ?.let { link -> link to credential }
            }
    }

    private fun currentDefaultDirFid(): String = when (currentPlatform) {
        SharePlatform.UC -> UCConstants.DEFAULT_PDIR_FID
        SharePlatform.XUNLEI -> "0"
        SharePlatform.BAIDU -> ""
        SharePlatform.C139 -> "0"
        SharePlatform.PAN123 -> "0"
        SharePlatform.PAN115 -> Pan115Constants.ROOT_CID
        SharePlatform.GUANGYA -> ""
        SharePlatform.ILANZOU -> ""
        SharePlatform.LANZOU -> ""
        SharePlatform.GITHUB -> "github:root"
        else -> QuarkConstants.DEFAULT_PDIR_FID
    }

    private fun platformName(): String = when (currentPlatform) {
        SharePlatform.UC -> "UC 网盘"
        SharePlatform.XUNLEI -> "迅雷网盘"
        SharePlatform.BAIDU -> "百度网盘"
        SharePlatform.C139 -> "139 网盘"
        SharePlatform.PAN123 -> "123云盘"
        SharePlatform.PAN115 -> "115网盘"
        SharePlatform.GUANGYA -> "光鸭云盘"
        SharePlatform.ILANZOU -> "蓝奏云优享版"
        SharePlatform.LANZOU -> "蓝奏云"
        SharePlatform.GITHUB -> "GitHub"
        else -> "夸克网盘"
    }

    /**
     * 平台是否支持「未登录下载」：
     * - 夸克/UC：分享直链不要求登录态（夸克仅约 50MB 以内，且直链要带游客态 __pugs）；
     * - 光鸭：分享列目录与取链本身匿名可用（受限分享返回 207 时会提示登录转存）；
     * - 蓝奏云 / 蓝奏云优享版：分享解析与取链完全匿名。
     * 其余平台仍要求登录。
     */
    private fun supportsGuestDownload(): Boolean =
        currentPlatform == SharePlatform.QUARK ||
            currentPlatform == SharePlatform.UC ||
            currentPlatform == SharePlatform.GUANGYA ||
            currentPlatform == SharePlatform.ILANZOU ||
            currentPlatform == SharePlatform.LANZOU

    /**
     * 「把原文回填到解析输入框」的请求：解析失败时写入，解析页消费后置空。
     *
     * 场景：剪贴板右下角弹窗点「打开」时，链接若**没带提取码**就会解析失败 ——
     * 回填后用户只需再补一个提取码（不必把链接重新粘贴一次）。
     */
    var prefillRequest by mutableStateOf<String?>(null)
        private set

    fun consumePrefill(): String? = prefillRequest.also { prefillRequest = null }

    /** 最近一次 startResolve 的原文（口令转换之前），失败时原样回填 */
    private var lastResolveInput: String = ""

    /** 开始解析：链接 → token →（密码）→ 根目录列表 */
    fun startResolve(link: String, pwd: String?) {
        lastResolveInput = link
        currentLink = link
        currentPwd = pwd
        // 写入当前分享链接，供下载入队时兜底填充 task.shareUrl（右键菜单「复制分享链接」用）
        downloadManager.currentShareUrl = link
        // GitHub 链接统一识别：仓库 / 账号 / 文件直链 → GitHub 解析流程。
        // 放在网盘解析之前，使「收藏打开 / 剪贴板 / 解析页 / 解析历史」等所有走 startResolve
        // 的入口都能解析 GitHub 链接，无需在 UI 层各处重复判断。
        val github = GitHubLinkParser.parse(link)
        if (github != null) {
            startGitHubResolve(github)
            return
        }
        viewModelScope.launch {
            uiState = ResolveUiState.Loading
            // 中文口令兜底（既不是分享链接、也不是 GitHub 链接）：**先迅雷，迅雷没给出可用链接再问星海**。
            // ① 迅雷口令（如「张三丰资源」）：口令是「分享链接 + 提取码」的打包形式，打 shoulei 跳转接口换链接；
            // ② 星海（夸克/UC 中文口令）：换回 pwd_id，再合成分享链接，后续提取码/登录/列表/取链全走既有链路。
            // 两个服务都用同一段原文，成功即止；都失败时把原因带出去（而不是只丢一句「无法识别」）。
            var effectiveLink = link
            var commandError: String? = null
            if (ShareLinkParser.parse(link) == null) {
                if (XunleiKouling.looksLikeKouling(link)) {
                    xunleiResolveRepository.resolveKouling(XunleiKouling.normalize(link))
                        .onSuccess { effectiveLink = it }
                        .onFailure { commandError = it.message }
                }
                if (ShareLinkParser.parse(effectiveLink) == null && StarSeaApi.looksLikeCommand(link)) {
                    StarSeaApi.resolveToShareLink(link)
                        .onSuccess { effectiveLink = it }
                        .onFailure { commandError = it.message }
                }
            }
            val parsed = ShareLinkParser.parse(effectiveLink)
            if (parsed == null) {
                // 两个口令服务都给不出结果时，用更靠后那次（星海）的原因——它对夸克/UC 口令最准
                prefillRequest = lastResolveInput
                uiState = ResolveUiState.Error(commandError ?: "无法识别分享链接")
                return@launch
            }
            // 口令已换成真实分享链接：收藏 / 复制 / 重新解析都用它，而不是原始口令
            currentLink = effectiveLink
            currentPlatform = parsed.platform
            // 游客模式：列目录不要求登录（6 个网盘的分享列表接口都允许匿名）。
            // 凭据为空时传空串，是否放行由服务端决定；下载/转存仍在各自入口要求登录。
            val credential = currentCredential().orEmpty()
            isGuest = credential.isBlank()
            val repo = currentRepo()
            repo.createSession(effectiveLink, pwd, credential)
                .onSuccess { s ->
                    session = s
                    currentDirFid = currentDefaultDirFid()
                    dirStack.clear()
                    pathNames = emptyList()
                    // 记录到链接历史（1 小时内同 URL 去重，失败不影响解析）
                    runCatching { recordLinkHistory(s.title) }
                    loadFiles(s, currentDirFid, credential, repo)
                }
                .onFailure { e ->
                    val msg = e.message ?: "解析失败"
                    // 失败（最常见是「链接不带提取码」）→ 把原文回填输入框，用户补个提取码即可重试
                    prefillRequest = lastResolveInput
                    // 游客模式失败时补一句可执行的引导，而不是只抛服务端原文
                    uiState = ResolveUiState.Error(
                        if (isGuest) "$msg（当前未登录，可到「网盘」页登录${platformName()}后重试）" else msg
                    )
                }
        }
    }

    /**
     * 记录解析历史到 link_history 表：同 URL 在 1 小时内已存在则跳过（避免重复解析污染历史）。
     * 失败静默忽略（不阻断主流程）。
     */
    private suspend fun recordLinkHistory(title: String) {
        val link = currentLink?.takeIf { it.isNotBlank() } ?: return
        val existing = linkHistoryDao.search(link).first()
        val latest = existing.maxByOrNull { it.createTime }
        if (latest != null && System.currentTimeMillis() - latest.createTime < 60 * 60 * 1000) return
        linkHistoryDao.insert(
            LinkHistoryEntity(
                url = link,
                title = title.ifBlank { link },
                platform = currentPlatform.name,
                pwd = currentPwd.orEmpty()
            )
        )
    }

    // ---------- GitHub 平台状态 ----------

    /** 当前浏览的 GitHub 仓库（仓库根目录/代码/Releases 时非空） */
    private var currentGitHubRepo: GitHubRepo? = null

    /** 当前浏览的 GitHub 账号/组织（账号仓库列表时非空） */
    private var currentGitHubOwner: String? = null

    /** 当前 owner 的类型（"User"/"Organization"），首次加载后缓存，避免分页每页都探测 */
    private var currentGitHubOwnerType: String? = null

    /** 已累加的 Releases 列表（分页累加） */
    private val currentGitHubReleases = mutableListOf<GitHubRelease>()

    /** 已累加的账号仓库列表（分页累加） */
    private val currentGitHubRepos = mutableListOf<GitHubRepo>()

    /** README 原文（仓库根目录加载；无则 null） */
    var githubReadme: String? by mutableStateOf(null)
        private set

    /** forked from 上游全名（fork 仓库根目录显示；非 fork 为 null） */
    var githubParentFullName: String? by mutableStateOf(null)
        private set

    /** 文件徽章映射：fid -> 徽章文本（Releases 的 最新/预发布/草稿，账号 repo 的 Fork/语言） */
    var githubBadges: Map<String, String> by mutableStateOf(emptyMap())
        private set

    /** 目录令牌：每次进入 GitHub 代码目录自增，用于丢弃异步时间请求的过期结果（防串目录） */
    private var githubDirToken = 0

    /** 当前是否为 GitHub 平台（UI 据此渲染 README/forked from/徽章等额外内容） */
    val isGitHubPlatform: Boolean get() = currentPlatform == SharePlatform.GITHUB

    /** 当前平台（只读暴露给 UI）：游客提示条等按平台给不同文案 */
    val sharePlatform: SharePlatform get() = currentPlatform

    /**
     * 当前是否停在某个仓库的首页（仓库根目录）：fid 为 `github:root`（链接直达 / 跳上游仓库）
     * 或 `github:repo:owner/name`（从账号仓库列表点进来）。
     * README 与 forked from 只在仓库首页渲染：进「代码」「Releases」等子目录不显示，
     * 返回账号仓库列表（github:account_root）也不残留。
     */
    val githubAtRepoRoot: Boolean
        get() = isGitHubPlatform &&
            (currentDirFid == "github:root" || currentDirFid.startsWith("github:repo:"))

    /** README 渲染用：当前仓库 owner（账号/组织浏览或无仓库时为 null） */
    val githubRepoOwner: String? get() = currentGitHubRepo?.owner

    /** README 渲染用：当前仓库名 */
    val githubRepoName: String? get() = currentGitHubRepo?.name

    /** README 渲染用：默认分支（用于相对链接补全） */
    val githubDefaultBranch: String? get() = currentGitHubRepo?.defaultBranch

    /** GitHub 下拉刷新指示器状态 */
    var githubRefreshing by mutableStateOf(false)
        private set

    /**
     * 下拉刷新当前 GitHub 节点：先按前缀清空统一缓存（用户主动刷新要看最新），
     * 再走 loadGitHubDir 重新请求；失败保持现有数据不崩。非 GitHub 平台忽略。
     */
    fun refreshGitHubCurrentNode() {
        if (!isGitHubPlatform || githubRefreshing) return
        invalidateCurrentGitHubCache()
        viewModelScope.launch {
            githubRefreshing = true
            try {
                loadGitHubDir(currentDirFid)
            } finally {
                githubRefreshing = false
            }
        }
    }

    /** 按当前 GitHub 节点清空统一缓存前缀（下拉刷新绕缓存用） */
    private fun invalidateCurrentGitHubCache() {
        val repo = currentGitHubRepo
        when {
            // 仓库根 / 代码目录 / Releases：清该仓库 tree、releases、readme、repo 信息
            currentDirFid.startsWith("github:root") ||
                currentDirFid.startsWith("github:code") ||
                currentDirFid.startsWith("github:release") ||
                currentDirFid == "github:releases" -> {
                if (repo != null) {
                    // 注意：必须用 repo.name（GitHubRepo 是 data class，直接插值会得到 toString，前缀匹配不上缓存 key）
                    GitHubResponseCache.invalidatePrefix("tree:${repo.owner}/${repo.name}/")
                    GitHubResponseCache.invalidatePrefix("releases:${repo.owner}/${repo.name}:")
                    GitHubResponseCache.invalidatePrefix("readme:${repo.owner}/${repo.name}/")
                    GitHubResponseCache.invalidatePrefix("repo:${repo.owner}/${repo.name}")
                    // 代码目录时间缓存一并失效：用户主动刷新期望看到最新提交时间
                    GitHubCommitDateCache.invalidatePrefix("${repo.owner}/${repo.name}/")
                }
            }
            // 账号仓库列表：清该 owner 的 users/orgs 列表与类型
            currentDirFid == "github:account_root" -> {
                currentGitHubOwner?.let { owner ->
                    GitHubResponseCache.invalidatePrefix("userrepos:$owner:")
                    GitHubResponseCache.invalidatePrefix("orgrepos:$owner:")
                    GitHubResponseCache.invalidatePrefix("usertype:$owner")
                }
            }
        }
    }

    // ---------- GitHub 平台入口与导航 ----------

    /**
     * GitHub 链接解析入口：仓库 → 代码/Releases 根；账号 → 仓库列表；直链 → 直接弹下载确认。
     * 复用 ShareDetailScreen 框架（搜索/多选/批量/确认弹窗/收藏/面包屑），canSave=false（无转存）。
     */
    fun startGitHubResolve(linkType: GitHubLinkType) {
        currentPlatform = SharePlatform.GITHUB
        // GitHub 不需要登录，也不属于「游客模式」提示的适用范围
        isGuest = false
        currentPwd = null
        // 重置 GitHub 状态
        currentGitHubRepo = null
        currentGitHubOwner = null
        currentGitHubOwnerType = null
        currentGitHubReleases.clear()
        currentGitHubRepos.clear()
        githubReadme = null
        githubParentFullName = null
        githubBadges = emptyMap()
        dirStack.clear()
        pathNames = emptyList()
        viewModelScope.launch {
            uiState = ResolveUiState.Loading
            when (linkType) {
                is GitHubLinkType.Repository -> {
                    currentLink = "https://github.com/${linkType.owner}/${linkType.repo}"
                    downloadManager.currentShareUrl = currentLink ?: ""
                    val repo = githubApi?.getRepo(linkType.owner, linkType.repo)
                    if (repo == null) {
                        uiState = ResolveUiState.Error("无法获取仓库信息：${linkType.owner}/${linkType.repo}")
                        return@launch
                    }
                    session = ShareSession(shareId = "github:${repo.fullName}", stoken = "", title = repo.fullName)
                    currentGitHubRepo = repo
                    currentDirFid = "github:root"
                    runCatching { recordLinkHistory(repo.fullName) }
                    loadGitHubRoot()
                }
                is GitHubLinkType.Account -> {
                    currentLink = "https://github.com/${linkType.owner}"
                    downloadManager.currentShareUrl = currentLink ?: ""
                    session = ShareSession(shareId = "github:user:${linkType.owner}", stoken = "", title = linkType.owner)
                    currentGitHubOwner = linkType.owner
                    currentDirFid = "github:account_root"
                    runCatching { recordLinkHistory(linkType.owner) }
                    loadGitHubAccountRepos(firstPage = true)
                }
                is GitHubLinkType.DirectFile -> {
                    currentLink = linkType.url
                    downloadManager.currentShareUrl = linkType.url
                    session = ShareSession(shareId = "github:direct", stoken = "", title = linkType.fileName)
                    // 直链：直接构造 DownloadLink 弹确认弹窗（不进入文件列表）
                    downloadLink = DownloadLink(
                        fid = "github:direct",
                        filename = linkType.fileName,
                        downloadUrl = linkType.url,
                        size = -1
                    )
                    uiState = ResolveUiState.Idle
                }
            }
        }
    }

    /** 点击「forked from」：跳转到上游仓库根目录 */
    fun openGitHubParentRepo() {
        val parent = githubParentFullName ?: return
        val owner = parent.substringBefore('/')
        val repoName = parent.substringAfter('/')
        viewModelScope.launch {
            uiState = ResolveUiState.Loading
            // 从父仓库进入：重置当前仓库上下文，回到顶层仓库根
            dirStack.clear()
            pathNames = emptyList()
            val repo = githubApi?.getRepo(owner, repoName)
            if (repo == null) {
                uiState = ResolveUiState.Error("无法打开上游仓库：$parent")
                return@launch
            }
            session = ShareSession(shareId = "github:${repo.fullName}", stoken = "", title = repo.fullName)
            currentGitHubRepo = repo
            currentDirFid = "github:root"
            loadGitHubRoot()
        }
    }

    /** 根目录：代码 + Releases 两个文件夹；并行加载 README 与 forked-from 标记 */
    private suspend fun loadGitHubRoot() {
        val repo = currentGitHubRepo ?: return
        githubParentFullName = if (repo.fork) repo.parentFullName else null
        githubBadges = emptyMap()
        // README 并行加载，不阻塞根目录列表
        viewModelScope.launch {
            githubReadme = githubApi?.getReadme(repo.owner, repo.name, repo.defaultBranch)
        }
        val files = listOf(
            ShareFile(
                fid = "github:code:${repo.defaultBranch}",
                fname = "代码",
                fsize = -1,
                isdir = true,
                pdirFid = "",
                fidToken = "",
                modifyTime = repo.pushedAt.orEmpty()
            ),
            ShareFile(
                fid = "github:releases",
                fname = "Releases",
                fsize = -1,
                isdir = true,
                pdirFid = "",
                fidToken = ""
            )
        )
        uiState = ResolveUiState.Detail(session!!, files)
    }

    /** 代码目录：tree（不 recursive）映射为文件夹/文件；根代码目录首项加源码 ZIP */
    private suspend fun loadGitHubCodeDir(sha: String) {
        val repo = currentGitHubRepo ?: return
        val entries = githubApi?.getTree(repo.owner, repo.name, sha)
        if (entries == null) {
            uiState = ResolveUiState.Error("加载目录失败")
            return
        }
        // 目录令牌：每次加载自增；异步时间请求回来时比对，防止切换目录后旧结果串层
        val dirToken = ++githubDirToken
        val files = mutableListOf<ShareFile>()
        // 根代码目录（sha == 默认分支）首项：下载完整源码 ZIP（时间用 pushedAt，无需额外请求）
        if (sha == repo.defaultBranch) {
            files.add(
                ShareFile(
                    fid = "github:zip",
                    fname = "下载完整源码 ZIP",
                    fsize = -1,
                    isdir = false,
                    pdirFid = "",
                    fidToken = "",
                    modifyTime = repo.pushedAt.orEmpty()
                )
            )
        }
        for (e in entries) {
            val displayName = e.path.substringAfterLast('/')
            if (e.type == "tree") {
                files.add(
                    ShareFile(
                        fid = "github:code:${e.sha}",
                        fname = displayName,
                        fsize = -1,
                        isdir = true,
                        pdirFid = "",
                        fidToken = ""
                    )
                )
            } else {
                files.add(
                    ShareFile(
                        fid = "github:file:${e.path}",
                        fname = displayName,
                        fsize = e.size ?: -1,
                        isdir = false,
                        pdirFid = "",
                        fidToken = ""
                    )
                )
            }
        }
        // 先渲染列表（含大小），再并行补时间
        uiState = ResolveUiState.Detail(session!!, files)
        // 并行请求每个条目最后提交时间（Semaphore 限流保护匿名 60/h）；失败静默留空
        viewModelScope.launch {
            val semaphore = Semaphore(8)
            val timeMap = java.util.concurrent.ConcurrentHashMap<String, String>()
            val api = githubApi
            coroutineScope {
                entries.forEach { e ->
                    launch {
                        semaphore.withPermit {
                            // 走内存缓存：命中不发请求，失败/限流结果缓存 1 分钟，in-flight 去重
                            if (api != null) {
                                GitHubCommitDateCache.get(repo.owner, repo.name, e.path, api)
                                    ?.let { iso -> timeMap[e.path] = iso }
                            }
                        }
                    }
                }
            }
            // 目录已切换则丢弃旧结果
            if (dirToken != githubDirToken) return@launch
            if (timeMap.isEmpty()) return@launch
            val updated = files.map { f ->
                val path = when {
                    f.fid.startsWith("github:file:") -> f.fid.removePrefix("github:file:")
                    // tree 条目：fid 是 github:code:{sha}，反查 path 用 entries 中 sha->path
                    f.fid.startsWith("github:code:") -> entries.firstOrNull { it.sha == f.fid.removePrefix("github:code:") }?.path
                    else -> null
                }
                if (path != null && timeMap[path] != null) f.copy(modifyTime = timeMap[path]!!) else f
            }
            uiState = ResolveUiState.Detail(session!!, updated)
        }
    }

    /** Releases 列表：分页累加；首个非预发布非草稿标记为「最新」；满页加「加载更多」 */
    private suspend fun loadGitHubReleases(firstPage: Boolean, page: Int = 1) {
        val repo = currentGitHubRepo ?: return
        if (firstPage) {
            currentGitHubReleases.clear()
        }
        val rels = githubApi?.getReleases(repo.owner, repo.name, page = page)
        if (rels == null && page == 1) {
            uiState = ResolveUiState.Error("加载 Releases 失败")
            return
        }
        if (rels != null) {
            // 并发/重放的加载会重复追加同一页，fid 重复 → LazyColumn key 冲突崩溃；按 tagName 去重后追加
            val seen = currentGitHubReleases.mapTo(HashSet()) { it.tagName }
            currentGitHubReleases.addAll(rels.filter { seen.add(it.tagName) })
        }
        // 计算徽章：第一个非预发布非草稿 = 最新；其余按 prerelease/draft
        val badgeMap = mutableMapOf<String, String>()
        var latestMarked = false
        for (rel in currentGitHubReleases) {
            val fid = "github:release:${rel.tagName}"
            when {
                rel.draft -> badgeMap[fid] = "草稿"
                rel.prerelease -> badgeMap[fid] = "预发布"
                !latestMarked -> {
                    badgeMap[fid] = "最新"
                    latestMarked = true
                }
            }
        }
        githubBadges = badgeMap
        val files = currentGitHubReleases.map { rel ->
            ShareFile(
                fid = "github:release:${rel.tagName}",
                fname = rel.tagName,
                fsize = -1,
                isdir = true,
                pdirFid = "",
                fidToken = "",
                modifyTime = rel.publishedAt ?: ""
            )
        }.toMutableList()
        // 满页（100 条）追加「加载更多」虚拟文件夹
        if (rels != null && rels.size == 100) {
            files.add(
                ShareFile(
                    fid = "github:more_rel:${page + 1}",
                    fname = "加载更多",
                    fsize = -1,
                    isdir = true,
                    pdirFid = "",
                    fidToken = ""
                )
            )
        }
        uiState = ResolveUiState.Detail(session!!, files)
    }

    /** 单个 Release 的资产列表 */
    private suspend fun loadGitHubReleaseAssets(tag: String) {
        val rel = currentGitHubReleases.firstOrNull { it.tagName == tag }
        if (rel == null) {
            uiState = ResolveUiState.Error("未找到 Release：$tag")
            return
        }
        githubBadges = emptyMap()
        val files = rel.assets.map { a ->
            ShareFile(
                fid = "github:asset:${a.downloadUrl}",
                fname = a.name,
                fsize = a.size,
                isdir = false,
                pdirFid = "",
                fidToken = "",
                modifyTime = a.updatedAt.orEmpty()
            )
        }
        uiState = ResolveUiState.Detail(session!!, files)
    }

    /** 账号/组织仓库列表：先用 /users/{owner} 的 type 一次选对端点；分页累加；满页加「加载更多」 */
    private suspend fun loadGitHubAccountRepos(firstPage: Boolean, page: Int = 1) {
        val owner = currentGitHubOwner ?: return
        if (firstPage) {
            currentGitHubRepos.clear()
            currentGitHubOwnerType = null
        }
        // 首次加载先探测 owner 类型并缓存；type=Organization 走 orgs 端点，其余走 users
        if (currentGitHubOwnerType == null) {
            currentGitHubOwnerType = githubApi?.getUserType(owner)
        }
        var repos = if (currentGitHubOwnerType == "Organization") {
            githubApi?.getOrgRepos(owner, page = page)
        } else {
            // User 或类型探测失败：先走 users；仍失败兜底 orgs（保持对异常情况的兼容）
            githubApi?.getUserRepos(owner, page = page)
        }
        if (repos == null && currentGitHubOwnerType != "Organization") {
            repos = githubApi?.getOrgRepos(owner, page = page)
        }
        if (repos == null && page == 1) {
            uiState = ResolveUiState.Error("无法获取 $owner 的仓库列表")
            return
        }
        if (repos != null) {
            // 同上：按 fullName 去重，避免重复 fid 触发 LazyColumn key 冲突
            val seen = currentGitHubRepos.mapTo(HashSet()) { it.fullName }
            currentGitHubRepos.addAll(repos.filter { seen.add(it.fullName) })
        }
        // 徽章：fork 标记 + 主语言
        val badgeMap = mutableMapOf<String, String>()
        for (r in currentGitHubRepos) {
            val fid = "github:repo:${r.fullName}"
            val parts = buildList {
                if (r.fork) add("Fork")
                r.language?.let { add(it) }
            }
            if (parts.isNotEmpty()) badgeMap[fid] = parts.joinToString(" · ")
        }
        githubBadges = badgeMap
        val files = currentGitHubRepos.map { r ->
            ShareFile(
                fid = "github:repo:${r.fullName}",
                fname = r.name,
                fsize = -1,
                isdir = true,
                pdirFid = "",
                fidToken = "",
                modifyTime = r.updatedAt.orEmpty()
            )
        }.toMutableList()
        if (repos != null && repos.size == 100) {
            files.add(
                ShareFile(
                    fid = "github:more_acct:${page + 1}",
                    fname = "加载更多",
                    fsize = -1,
                    isdir = true,
                    pdirFid = "",
                    fidToken = ""
                )
            )
        }
        uiState = ResolveUiState.Detail(session!!, files)
    }

    /** 按 fid 分发加载对应目录层 */
    private suspend fun loadGitHubDir(fid: String) {
        when {
            fid == "github:root" -> loadGitHubRoot()
            fid.startsWith("github:code:") -> loadGitHubCodeDir(fid.removePrefix("github:code:"))
            fid == "github:releases" -> loadGitHubReleases(firstPage = true)
            fid.startsWith("github:release:") -> loadGitHubReleaseAssets(fid.removePrefix("github:release:"))
            fid == "github:account_root" -> loadGitHubAccountRepos(firstPage = true)
            fid.startsWith("github:more_rel:") -> {
                val p = fid.removePrefix("github:more_rel:").toIntOrNull() ?: return
                loadGitHubReleases(firstPage = false, page = p)
            }
            fid.startsWith("github:more_acct:") -> {
                val p = fid.removePrefix("github:more_acct:").toIntOrNull() ?: return
                loadGitHubAccountRepos(firstPage = false, page = p)
            }
            fid.startsWith("github:repo:") -> {
                val fullName = fid.removePrefix("github:repo:")
                val owner = fullName.substringBefore('/')
                val repoName = fullName.substringAfter('/')
                val repo = githubApi?.getRepo(owner, repoName)
                if (repo == null) {
                    uiState = ResolveUiState.Error("无法打开仓库：$fullName")
                    return
                }
                currentGitHubRepo = repo
                githubReadme = null
                loadGitHubRoot()
            }
            else -> uiState = ResolveUiState.Error("未知目录：$fid")
        }
    }

    /** 根据 GitHub fid 计算下载直链（raw / codeload / asset url / 直链）；无法识别返回 null */
    private fun githubUrlForFid(fid: String): String? {
        val repo = currentGitHubRepo ?: return null
        val branch = repo.defaultBranch
        return when {
            fid == "github:zip" ->
                "https://codeload.github.com/${repo.owner}/${repo.name}/zip/refs/heads/$branch"
            fid.startsWith("github:file:") -> {
                val path = fid.removePrefix("github:file:")
                "https://raw.githubusercontent.com/${repo.owner}/${repo.name}/$branch/${encodePath(path)}"
            }
            fid.startsWith("github:asset:") -> fid.removePrefix("github:asset:")
            // github:direct（直链）在 startGitHubResolve 已直接设置 downloadLink，不走此方法
            else -> null
        }
    }

    /**
     * 对 raw 直链路径逐段 URL 编码（保留 `/` 分隔符）。
     * 文件名含 `#`/`?`/空格/中文等字符时，未编码会导致 URL 在 `#`/`?` 处截断。
     */
    private fun encodePath(path: String): String =
        path.split('/').joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8") }

    /**
     * GitHub 批量下载：选中的叶子文件直接入队；代码文件夹（github:code:*）递归 getTree 收集 blob；
     * Releases 文件夹展开为资产；其余（repo 根/账号列表）跳过。全部直链先经镜像前缀转换。
     */
    private fun batchGitHubDownload(saveDir: String = "") {
        val selected = _selected.toList()
        viewModelScope.launch {
            isBatchWorking = true
            batchProgress = "正在收集文件…"
            batchCancelRequested = false
            try {
                val prefix = mirrorPrefixProvider()
                val tasks = mutableListOf<Triple<String, String, Long>>() // url, fileName, size
                // 收集文件
                for (file in selected) {
                    if (batchCancelRequested) break
                    when {
                        // 叶子文件：直接构造 URL
                        file.fid == "github:zip" || file.fid.startsWith("github:file:") ||
                            file.fid.startsWith("github:asset:") || file.fid == "github:direct" -> {
                            githubUrlForFid(file.fid)?.let { url ->
                                tasks.add(Triple(url, file.fname, file.fsize))
                            }
                        }
                        // 代码文件夹：递归收集 blob（不 recursive，逐层 getTree）
                        file.fid.startsWith("github:code:") -> {
                            collectGitHubBlobs(file.fid.removePrefix("github:code:"), "", tasks, 0)
                        }
                        // Release 文件夹：展开为资产
                        file.fid.startsWith("github:release:") -> {
                            val tag = file.fid.removePrefix("github:release:")
                            currentGitHubReleases.firstOrNull { it.tagName == tag }?.assets?.forEach { a ->
                                tasks.add(Triple(a.downloadUrl, a.name, a.size))
                            }
                        }
                        // 其余文件夹（repo 根/账号列表/加载更多）：跳过
                        else -> {}
                    }
                }
                if (tasks.isEmpty()) {
                    downloadError = "所选内容无可下载文件"
                    exitMultiSelect()
                    return@launch
                }
                var okCount = 0
                for ((index, t) in tasks.withIndex()) {
                    if (batchCancelRequested) {
                        downloadError = "已中断批量下载"
                        break
                    }
                    batchProgress = "${index + 1}/${tasks.size}"
                    runCatching {
                        downloadManager.enqueue(
                            url = UpdateChecker.mirrorUrl(t.first, prefix),
                            fileName = t.second,
                            size = t.third,
                            platform = DownloadPlatform.GITHUB,
                            // 镜像挂掉时回退原始直连（t.first 为未镜像的 GitHub 直链）
                            fallbackUrl = t.first,
                            saveDir = saveDir
                        )
                        okCount++
                    }
                }
                if (!batchCancelRequested) {
                    downloadError = if (okCount > 0) "已加入 $okCount 个下载任务" else "下载失败"
                    if (okCount > 0) downloadStarted = true
                }
                exitMultiSelect()
            } finally {
                isBatchWorking = false
                batchProgress = null
                batchCancelRequested = false
            }
        }
    }

    /**
     * 递归收集 GitHub 代码目录下所有 blob 的直链（逐层 getTree，不加 recursive=1）。
     * 用户在批量下载弹窗点取消后，正在进行的递归收集立即停止，已收集的 blob 不入队。
     */
    private suspend fun collectGitHubBlobs(
        sha: String,
        prefix: String,
        result: MutableList<Triple<String, String, Long>>,
        depth: Int
    ) {
        if (depth > 12) return
        if (batchCancelRequested) return
        val repo = currentGitHubRepo ?: return
        val entries = githubApi?.getTree(repo.owner, repo.name, sha) ?: return
        val branch = repo.defaultBranch
        for (e in entries) {
            if (batchCancelRequested) return
            val displayName = e.path.substringAfterLast('/')
            if (e.type == "tree") {
                collectGitHubBlobs(e.sha, if (prefix.isBlank()) displayName else "$prefix/$displayName", result, depth + 1)
            } else {
                val url = "https://raw.githubusercontent.com/${repo.owner}/${repo.name}/$branch/${encodePath(e.path)}"
                result.add(Triple(url, if (prefix.isBlank()) displayName else "$prefix/$displayName", e.size ?: -1))
            }
        }
    }

    /** 进入文件夹 */
    fun openFolder(file: ShareFile) {
        // GitHub 分支：fid 前缀分发，无需网盘凭证
        if (currentPlatform == SharePlatform.GITHUB) {
            // 「加载更多」虚拟项：原地追加分页，不入目录栈
            if (file.fid.startsWith("github:more_")) {
                viewModelScope.launch {
                    uiState = ResolveUiState.Loading
                    loadGitHubDir(file.fid)
                }
                return
            }
            dirStack.addLast(currentDirFid)
            pathNames = pathNames + file.fname
            currentDirFid = file.fid
            viewModelScope.launch {
                uiState = ResolveUiState.Loading
                loadGitHubDir(file.fid)
            }
            return
        }
        val s = session ?: return
        dirStack.addLast(currentDirFid)
        pathNames = pathNames + file.fname
        currentDirFid = file.fid
        viewModelScope.launch {
            uiState = ResolveUiState.Loading
            // 游客模式：子目录同样允许匿名列出
            val credential = currentCredential().orEmpty()
            loadFiles(s, file.fid, credential, currentRepo())
        }
    }

    /** 返回上级目录 */
    fun goBack() {
        // GitHub 分支：无需凭证，按 fid 重新加载对应层
        if (currentPlatform == SharePlatform.GITHUB) {
            if (dirStack.isEmpty()) return
            currentDirFid = dirStack.removeLast()
            pathNames = pathNames.dropLast(1)
            viewModelScope.launch {
                uiState = ResolveUiState.Loading
                loadGitHubDir(currentDirFid)
            }
            return
        }
        val s = session ?: return
        if (dirStack.isEmpty()) return
        currentDirFid = dirStack.removeLast()
        pathNames = pathNames.dropLast(1)
        viewModelScope.launch {
            uiState = ResolveUiState.Loading
            // 游客模式：空凭据照常加载
            loadFiles(s, currentDirFid, currentCredential().orEmpty(), currentRepo())
        }
    }

    /** 返回：在子目录则返回上一级，在根目录则返回输入页 */
    fun navigateBack() {
        if (dirStack.isEmpty()) {
            backToInput()
        } else {
            goBack()
        }
    }

    /** 返回输入页 */
    fun backToInput() {
        session = null
        downloadLink = null
        currentLink = null
        currentPwd = null
        pathNames = emptyList()
        // 重置 GitHub 平台状态
        currentGitHubRepo = null
        currentGitHubOwner = null
        currentGitHubOwnerType = null
        currentGitHubReleases.clear()
        currentGitHubRepos.clear()
        githubReadme = null
        githubParentFullName = null
        githubBadges = emptyMap()
        isGuest = false
        uiState = ResolveUiState.Idle
    }

    /** 将当前分享链接收藏到指定分类（标题可自定义，为空时回退分享标题） */
    fun addCurrentToBookmark(title: String, category: String) {
        val link = currentLink?.takeIf { it.isNotBlank() }
        if (link == null) {
            SnackbarController.show("缺少分享链接")
            return
        }
        val cat = category.ifBlank { BookmarkEntity.DEFAULT_CATEGORY }
        val resolvedTitle = title.ifBlank { session?.title.orEmpty() }
        viewModelScope.launch {
            bookmarkDao.insert(
                BookmarkEntity(
                    link = link,
                    title = resolvedTitle,
                    platform = currentPlatform.name,
                    pwd = currentPwd.orEmpty(),
                    category = cat
                )
            )
            SnackbarController.show("已收藏到「$cat」")
        }
    }

    // ---------- 链接历史 ----------

    /** 解析历史记录 Flow（供 LinkHistoryDialog 订阅展示） */
    fun linkHistoryFlow() = linkHistoryDao.observeAll()

    /** 删除单条历史记录 */
    fun deleteLinkHistory(id: Long) {
        viewModelScope.launch { linkHistoryDao.delete(id) }
    }

    /** 清空全部历史记录 */
    fun clearLinkHistory() {
        viewModelScope.launch { linkHistoryDao.clear() }
    }

    /**
     * 面包屑导航：点击第 level 级（0=分享根目录）回退到该目录并刷新列表。
     * 当前所在层（level == pathNames.size）无需操作。
     */
    fun navigateToLevel(level: Int) {
        if (level < 0 || level > pathNames.size) return
        if (level == pathNames.size) return
        // 弹出目录栈直到对应层级；level=0 时回到分享根目录
        while (dirStack.size > level) dirStack.removeLast()
        currentDirFid = if (dirStack.isEmpty()) currentDefaultDirFid() else dirStack.last()
        pathNames = pathNames.take(level)
        // GitHub 分支：无需凭证，按 fid 重新加载
        if (currentPlatform == SharePlatform.GITHUB) {
            viewModelScope.launch {
                uiState = ResolveUiState.Loading
                loadGitHubDir(currentDirFid)
            }
            return
        }
        val s = session ?: return
        viewModelScope.launch {
            // 游客模式：面包屑回退同样允许匿名列出目录
            val credential = currentCredential().orEmpty()
            loadFiles(s, currentDirFid, credential, currentRepo())
        }
    }

    /**
     * 登录态取分享直链：默认优先「免转存」——把分享凭证直接交给 file/download 换直链，
     * 不建临时目录、不转存（统一入口见 [ShareResolveRepository.getShareDownloadLinkWithoutSave]）。
     *
     * 两条退路：
     * - 用户在设置里关掉「免转存下载」（[noSaveDownloadProvider]）⇒ 直接走原来的转存流程；
     * - 开着免转存但夸克个别分享类型服务端仍要求「先转存再取链」⇒ 失败后自动回退转存。
     * 其余平台的默认实现就是原有取链，不存在第二条链路，不重复请求。
     */
    private suspend fun resolveShareLink(
        repo: ShareResolveRepository,
        session: ShareSession,
        file: ShareFile,
        credential: String,
        skipSpaceCheck: Boolean = false
    ): Result<DownloadLink> {
        if (!noSaveDownloadProvider()) return repo.getShareDownloadLink(session, file, credential, skipSpaceCheck)
        val noSave = repo.getShareDownloadLinkWithoutSave(session, file, credential)
        if (noSave.isSuccess || currentPlatform != SharePlatform.QUARK) return noSave
        return repo.getShareDownloadLink(session, file, credential, skipSpaceCheck)
    }

    /** 获取文件下载直链（各平台实现不同：夸克免转存优先（失败回退转存）/ UC 直接取 / 迅雷转存后取详情直链；GitHub 直接构造 URL） */
    fun fetchDownloadLink(file: ShareFile) {
        // GitHub 分支：无需 API 取链，按 fid 直接构造下载 URL 弹确认弹窗
        if (currentPlatform == SharePlatform.GITHUB) {
            viewModelScope.launch {
                downloadLink = null
                downloadError = null
                isFetchingDownloadLink = true
                try {
                    // 直链文件（DirectFile）在 startGitHubResolve 已设置 downloadLink；这里仅处理列表内文件
                    val url = githubUrlForFid(file.fid)
                    if (url == null) {
                        downloadError = "无法获取下载链接"
                        return@launch
                    }
                    downloadLink = DownloadLink(
                        fid = file.fid,
                        filename = file.fname,
                        downloadUrl = url,
                        size = file.fsize
                    )
                } finally {
                    isFetchingDownloadLink = false
                }
            }
            return
        }
        viewModelScope.launch {
            downloadLink = null
            downloadError = null
            // 新一轮取链：上一次预取的多源直链作废（直链签名有时效，不能跨次复用）
            pendingAltLinks = emptyList()
            isFetchingDownloadLink = true
            try {
                val s = session
                if (s == null) {
                    downloadError = "请先解析分享"
                    return@launch
                }
                val credential = currentCredential()
                if (credential.isNullOrBlank()) {
                    // 游客模式：夸克/UC 的分享直链本身不要求登录（夸克约 50MB 以内，超出报 23018；
                    // UC 实测大文件也放行），走各自的游客取链；其余平台必须登录
                    if (!supportsGuestDownload()) {
                        downloadError = "下载需要先登录${platformName()}（未登录仅能浏览文件列表）"
                        return@launch
                    }
                    currentRepo().getGuestShareDownloadLink(s, file)
                        .onSuccess { downloadLink = it }
                        .onFailure { downloadError = it.message ?: "获取下载链接失败" }
                    return@launch
                }
                // 夸克/UC 共用 __puus：取链前确保新鲜（直链签名绑定取链时刻的 Cookie）
                val quarkCred = when (currentPlatform) {
                    SharePlatform.QUARK -> accountRepository.getFreshCookie() ?: credential
                    SharePlatform.UC -> ucAccountRepository.getFreshCookie() ?: credential
                    else -> credential
                }
                // 优先免转存（不往用户网盘里转存）：夸克失败时自动回退到转存取链
                resolveShareLink(currentRepo(), s, file, quarkCred)
                    .onSuccess { link ->
                        downloadLink = link
                        // 单文件多源分片（实验性 C2）：同一时刻再用其他账号各取一条直链，
                        // 下载时由内置分片下载器按分片轮流使用（见 DownloadManager 的「多源分片」）
                        pendingAltLinks = fetchAltLinks(file, quarkCred)
                    }
                    .onFailure { downloadError = it.message ?: "获取下载链接失败" }
            } finally {
                isFetchingDownloadLink = false
            }
        }
    }

    fun dismissDownloadDialog() {
        val link = downloadLink
        downloadLink = null
        // 弹窗没开始下载就关掉：预取的多源直链一并作废（下次取链会重新预取，避免用到过期直链）
        pendingAltLinks = emptyList()
        pendingSaveDir = ""
        // 弹窗被关闭（用户点关闭，未开始下载）：清理当前平台的云端临时转存，避免残留
        if (link?.cleanupDirFid != null) {
            viewModelScope.launch {
                val credential = currentCredential() ?: return@launch
                link.cleanupDirFid?.let { dirFid ->
                    currentRepo().cleanupTempDir(dirFid, credential)
                }
            }
        }
    }

    /**
     * 将直链加入下载队列（携带对应平台凭证与 UA；夸克直链做 CDN 节点优选）。
     * 不触发切页 —— 与 startDownload 的区别：批量下载全部入队后才统一切到下载页。
     */
    private suspend fun enqueueDownload(
        link: DownloadLink,
        credential: String,
        fileName: String = link.filename,
        /**
         * 单文件多源分片（实验性 C2）的备选直链（其他账号的直链 + 该账号凭证）；空 = 单源。
         * 由 [fetchAltLinks] 预取，来自取链弹窗那一次交互，见 [pendingAltLinks]。
         */
        altLinks: List<Pair<DownloadLink, String>> = emptyList(),
        /** 任务级保存目录（空串 = 跟随全局设置） */
        saveDir: String = ""
    ) {
        val isUC = currentPlatform == SharePlatform.UC
        val isXunlei = currentPlatform == SharePlatform.XUNLEI
        val isBaidu = currentPlatform == SharePlatform.BAIDU
        val isC139 = currentPlatform == SharePlatform.C139
        val isPan123 = currentPlatform == SharePlatform.PAN123
        val isPan115 = currentPlatform == SharePlatform.PAN115
        val isGuangYa = currentPlatform == SharePlatform.GUANGYA
        val isILanzou = currentPlatform == SharePlatform.ILANZOU
        val isLanzou = currentPlatform == SharePlatform.LANZOU
        val isQuark = currentPlatform == SharePlatform.QUARK
        // 下载来源平台：按平台应用下载线程数设置
        val platform = when {
            isXunlei -> DownloadPlatform.XUNLEI
            isUC -> DownloadPlatform.UC
            isBaidu -> DownloadPlatform.BAIDU
            isC139 -> DownloadPlatform.C139
            isPan123 -> DownloadPlatform.PAN123
            isPan115 -> DownloadPlatform.PAN115
            isGuangYa -> DownloadPlatform.GUANGYA
            isILanzou -> DownloadPlatform.ILANZOU
            isLanzou -> DownloadPlatform.LANZOU
            else -> DownloadPlatform.QUARK
        }
        // 【关键修复】夸克/UC 共用 __puus：取链与下载必须用同一份已刷新 Cookie（AlistGo/alist#830 类缺陷）
        // refreshIfPrimary 只在「传进来的就是主账号那一份」时才刷新，所以主账号路径与原来的
        // getFreshCookie 完全一致；多账号下载轮换到非主账号时保留它自己的 Cookie ——
        // 换成主账号的会让直链与身份配成两个账号，CDN 直接 412/403。
        // 游客直链（夸克/UC 小文件）：只有服务端随取链响应下发的游客态 __pugs，没有登录 Cookie。
        // 夸克缺它 CDN 直接 412；UC 缺它 CDN 403（RequestDeniedByCallback: require login）
        val guestCookie = link.guestCookie
        val isGuestLink = guestCookie.isNotBlank()
        val effectiveCredential = if (isGuestLink) {
            guestCookie
        } else {
            when (currentPlatform) {
                SharePlatform.QUARK -> accountRepository.refreshIfPrimary(credential)
                SharePlatform.UC -> ucAccountRepository.refreshIfPrimary(credential)
                else -> credential
            }
        }
        // 迅雷直链 URL 自带签名，无需 Cookie；夸克/UC/百度需 Cookie + UA；139 直链为 CDN 签名地址；123 直链需 Referer
        val headers = when {
            isXunlei -> mapOf("User-Agent" to XunleiConstants.APP_UA) // 迅雷直链必须用官方 app UA，浏览器 UA 会触发 CDN 降级（200整文件）
            isBaidu -> mapOf(
                "Cookie" to credential,
                "User-Agent" to BaiduConstants.UA_NETDISK
            )
            isC139 -> mapOf("User-Agent" to C139Constants.PC_UA)
            // 115：CDN 同时校验登录 Cookie 与取链响应下发的 900 秒 CDN Cookie，
            // 缺任一直接 403 no cookie value，因此两份 Cookie 拼接后一起发；
            // UA 必须是客户端串（与取链一致），浏览器 UA 会被判「网页端」拒发大文件直链
            isPan115 -> mapOf(
                "Cookie" to Pan115Constants.mergeCookies(effectiveCredential, guestCookie),
                "User-Agent" to Pan115Constants.CLIENT_UA,
                "Referer" to Pan115Constants.DOWNLOAD_REFERER
            )
            // 123 分享/个人盘直链为 CDN 签名地址，下载必须带 Referer（文档 §5.3.1）
            isPan123 -> mapOf(
                "User-Agent" to Pan123Constants.WEB_UA,
                "Referer" to Pan123Constants.DOWNLOAD_REFERER
            )
            // 光鸭直链为签名 CDN 地址，下载带 UA + Referer，且不携带 Authorization/Cookie
            isGuangYa -> mapOf(
                "User-Agent" to GuangYaConstants.WEB_UA,
                "Referer" to GuangYaConstants.DOWNLOAD_REFERER
            )
            // 蓝奏优享直链为 CDN 地址，下载带 UA + Referer + Origin，不带 Cookie
            isILanzou -> mapOf(
                "User-Agent" to ILanzouConstants.WEB_UA,
                "Referer" to ILanzouConstants.DOWNLOAD_REFERER,
                "Origin" to ILanzouConstants.DOWNLOAD_ORIGIN
            )
            // 蓝奏云下载节点要求 Referer 为节点 origin，并注入 down_ip=1 Cookie
            isLanzou -> {
                val lanzouOrigin = runCatching { java.net.URI(link.downloadUrl) }.getOrNull()
                    ?.let { "${it.scheme}://${it.host}" } ?: "https://pan.lanzoui.com"
                mapOf(
                    "User-Agent" to LanzouConstants.WEB_UA,
                    "Referer" to "$lanzouOrigin/",
                    "Cookie" to LanzouConstants.DOWN_IP_COOKIE
                )
            }
            // UC：OSS 直链按 Referer 档位限速（缺 Referer 被 Callback 限到 ~100 KB/s），
            // 补官方 Web 客户端同款 Referer/Origin 即满速
            isUC -> if (isGuestLink) mapOf(
                // 游客链路换 uc-cloud-drive 客户端 UA + Sec-Ch-Ua（游客请求同款头），Referer 仍不可少
                "Cookie" to guestCookie,
                "User-Agent" to UCConstants.GUEST_UA,
                "Sec-Ch-Ua" to UCConstants.GUEST_SEC_CH_UA,
                "Referer" to UCConstants.DOWNLOAD_REFERER,
                "Origin" to UCConstants.WEB_ORIGIN
            ) else mapOf(
                "Cookie" to credential,
                "User-Agent" to UCConstants.USER_AGENT,
                "Referer" to UCConstants.DOWNLOAD_REFERER,
                "Origin" to UCConstants.WEB_ORIGIN
            )
            // 夸克：防盗链需固定 Referer（对齐 AList quark_uc）
            else -> if (isGuestLink) mapOf(
                "Cookie" to guestCookie,
                "User-Agent" to QuarkConstants.API_USER_AGENT,
                "Referer" to QuarkConstants.DOWNLOAD_REFERER
            ) else mapOf(
                "Cookie" to effectiveCredential,
                "User-Agent" to QuarkConstants.API_USER_AGENT,
                "Referer" to QuarkConstants.DOWNLOAD_REFERER
            )
        }
        // 夸克直链：原样使用（关闭节点改写/探测，避免消耗直链额度与节点签名 412）
        val effectiveUrl = if (isQuark) {
            QuarkCdn.fastest(link.downloadUrl, if (isGuestLink) guestCookie else effectiveCredential)
        } else {
            link.downloadUrl
        }
        // 入队前先记住「哪个平台的仓库 + 哪份凭证」：下载完成时用户可能已经切到别的分享页
        val cleanupRepo = currentRepo()
        val cleanupCredential = credential
        // 单文件多源分片（实验性 C2）：备选源 = 其他账号的直链 + 「主源那一套请求头、只换 Cookie」。
        // ★ 当前支持的平台（夸克 / UC / 迅雷 / 百度）下载请求头差异都只有 Cookie 一项
        //   （迅雷直链头里根本没有 Cookie，直链自带签名），所以这里沿用主源头再换 Cookie 即可；
        //   将来若给 115（双 Cookie）之类开多源，必须重新评估这一处。
        val altSources = altLinks.map { (altLink, altCred) ->
            val url = if (isQuark) {
                // 夸克每条备选直链同样做一次 CDN 节点优选（原样直链容易 412）
                QuarkCdn.fastest(altLink.downloadUrl, altCred)
            } else {
                altLink.downloadUrl
            }
            val headersForAlt = if (headers.containsKey("Cookie")) {
                headers + ("Cookie" to altCred)
            } else {
                headers
            }
            DownloadSource(url, headersForAlt)
        }
        downloadManager.enqueue(
            url = effectiveUrl,
            fileName = fileName,
            headers = headers,
            size = link.size,
            platform = platform,
            altSources = altSources,
            saveDir = saveDir
        ) {
            // 下载完成（通过 onComplete 回调）：清理网盘临时转存目录；失败/取消不触发
            val dirFid = link.cleanupDirFid
            if (dirFid != null && cleanupCredential.isNotBlank()) {
                cleanupRepo.cleanupTempDir(dirFid, cleanupCredential)
            }
        }
    }

    /** 将直链加入下载队列（单文件下载：入队后立即切换到下载页） */
    fun startDownload(link: DownloadLink) {
        viewModelScope.launch {
            // 开始下载：先关闭弹窗（临时转存由下载完成 onComplete 清理，不在此时删）
            val saveDir = pendingSaveDir
            downloadLink = null
            pendingSaveDir = ""
            // GitHub 分支：无需网盘凭证，直链先经镜像前缀转换，带 size 和 platform=github 入队
            if (currentPlatform == SharePlatform.GITHUB) {
                try {
                    val prefix = mirrorPrefixProvider()
                    downloadManager.enqueue(
                        url = UpdateChecker.mirrorUrl(link.downloadUrl, prefix),
                        fileName = link.filename,
                        size = link.size,
                        platform = DownloadPlatform.GITHUB,
                        // 镜像挂掉时回退原始直连（link.downloadUrl 为未镜像的 GitHub 直链）
                        fallbackUrl = link.downloadUrl,
                        saveDir = saveDir
                    )
                    downloadStarted = true
                } catch (e: Exception) {
                    Log.e("ResolveVM", "startDownload(github) failed: ${e.javaClass.simpleName}: ${e.message}", e)
                    downloadError = "下载启动失败：${e.message ?: e.javaClass.simpleName}"
                }
                return@launch
            }
            // 游客模式：夸克/UC 的小文件直链不要求登录（link 里已带游客 __pugs）
            val credential = currentCredential().orEmpty()
            if (credential.isBlank() && !supportsGuestDownload()) {
                downloadError = "下载需要先登录${platformName()}（未登录仅能浏览文件列表）"
                return@launch
            }
            try {
                enqueueDownload(link, credential, saveDir = saveDir)
                downloadStarted = true
            } catch (e: Exception) {
                Log.e("ResolveVM", "startDownload failed: ${e.javaClass.simpleName}: ${e.message}", e)
                downloadError = "下载启动失败：${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    private suspend fun loadFiles(
        s: ShareSession,
        dirFid: String,
        credential: String,
        repo: ShareResolveRepository
    ) {
        repo.listFiles(s, dirFid, credential)
            .onSuccess { files ->
                uiState = ResolveUiState.Detail(s, files)
            }
            .onFailure { e ->
                // 同 createSession 失败：回填原文，便于用户补提取码后重试
                prefillRequest = lastResolveInput
                uiState = ResolveUiState.Error(e.message ?: "获取文件列表失败")
            }
    }

    class Factory(
        private val accountRepository: QuarkAccountRepository,
        private val resolveRepository: QuarkResolveRepository,
        private val ucAccountRepository: UCAccountRepository,
        private val ucResolveRepository: UCResolveRepository,
        private val xunleiAccountRepository: XunleiAccountRepository,
        private val xunleiResolveRepository: XunleiResolveRepository,
        private val baiduAccountRepository: BaiduAccountRepository,
        private val baiduResolveRepository: BaiduResolveRepository,
        private val c139AccountRepository: C139AccountRepository,
        private val c139ResolveRepository: C139ResolveRepository,
        private val pan123AccountRepository: Pan123AccountRepository,
        private val pan123ResolveRepository: Pan123ResolveRepository,
        private val pan115AccountRepository: Pan115AccountRepository,
        private val pan115ResolveRepository: Pan115ResolveRepository,
        private val guangyaAccountRepository: GuangYaAccountRepository,
        private val guangyaResolveRepository: GuangYaResolveRepository,
        private val ilanzouAccountRepository: ILanzouAccountRepository,
        private val ilanzouResolveRepository: ILanzouResolveRepository,
        private val lanzouAccountRepository: LanzouAccountRepository,
        private val lanzouResolveRepository: LanzouResolveRepository,
        private val downloadManager: DownloadManager,
        private val bookmarkDao: BookmarkDao,
        private val linkHistoryDao: LinkHistoryDao,
        private val githubApi: GitHubApi? = null,
        private val mirrorPrefixProvider: () -> String = { GitHubMirror.activePrefix() },
        private val noSaveDownloadProvider: () -> Boolean = { true },
        /** 设置仓库：默认直接读偏好，MainScreen 无需改动；多账号 / 多源开关改完即时生效 */
        private val settings: SettingsRepository = SettingsRepository(),
        private val multiAccountDownloadProvider: () -> Boolean = { settings.multiAccountDownload },
        private val multiSourceDownloadProvider: () -> Boolean = { settings.multiSourceActive() }
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: KClass<T>, extras: CreationExtras): T {
            require(modelClass == ResolveViewModel::class)
            return ResolveViewModel(
                accountRepository, resolveRepository,
                ucAccountRepository, ucResolveRepository,
                xunleiAccountRepository, xunleiResolveRepository,
                baiduAccountRepository, baiduResolveRepository,
                c139AccountRepository, c139ResolveRepository,
                pan123AccountRepository, pan123ResolveRepository,
                pan115AccountRepository, pan115ResolveRepository,
                guangyaAccountRepository, guangyaResolveRepository,
                ilanzouAccountRepository, ilanzouResolveRepository,
                lanzouAccountRepository, lanzouResolveRepository,
                downloadManager,
                bookmarkDao,
                linkHistoryDao,
                githubApi,
                mirrorPrefixProvider,
                noSaveDownloadProvider,
                multiAccountDownloadProvider,
                multiSourceDownloadProvider
            ) as T
        }
    }
}
