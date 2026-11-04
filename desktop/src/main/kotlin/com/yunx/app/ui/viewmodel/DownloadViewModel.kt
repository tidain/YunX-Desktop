package com.yunx.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import kotlin.reflect.KClass
import com.yunx.app.data.db.DownloadTaskEntity
import com.yunx.app.data.download.DownloadDebugLog
import com.yunx.app.data.download.DownloadManager
import com.yunx.app.data.download.DownloadStats
import com.yunx.app.data.prefs.SettingsRepository
import com.yunx.app.ui.SnackbarController
import com.yunx.app.util.DesktopActions
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 下载页 ViewModel：任务列表（Room Flow → StateFlow）+ 实时统计 + 操作转发。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DownloadViewModel(private val manager: DownloadManager) : ViewModel() {

    private val settings = SettingsRepository()

    val tasks: StateFlow<List<DownloadTaskEntity>> = manager.tasks
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    /** 实时下载统计：任务 id → 速度/剩余时间/线程数 */
    val stats: StateFlow<Map<Long, DownloadStats>> = manager.stats

    /** 添加下载任务（headers 可携带 Referer/Cookie 等；platform 用于按平台应用下载线程数） */
    fun enqueue(
        url: String,
        fileName: String,
        headers: Map<String, String> = emptyMap(),
        platform: String = "",
        /** 本次任务的保存目录（空串 = 跟随全局设置） */
        saveDir: String = ""
    ) {
        viewModelScope.launch {
            manager.enqueue(url, fileName, headers, platform = platform, saveDir = saveDir)
        }
    }

    fun pause(id: Long) = manager.pause(id)

    fun resume(id: Long) = manager.start(id)

    fun remove(id: Long, deleteLocal: Boolean = false) = manager.remove(id, deleteLocal)

    /** 重新下载：校验直链有效性后新建任务（直链过期时提示） */
    fun redownload(task: DownloadTaskEntity) {
        viewModelScope.launch {
            val ok = manager.redownload(task.id)
            SnackbarController.show(if (ok) "已重新加入下载" else "直链已过期，请重新获取下载链接")
        }
    }

    /** 全部暂停：暂停所有正在下载（含等待中）的任务 */
    fun pauseAll() {
        tasks.value.filter {
            it.status == DownloadTaskEntity.STATUS_DOWNLOADING ||
                it.status == DownloadTaskEntity.STATUS_PENDING
        }.forEach { manager.pause(it.id) }
    }

    /** 全部开始：恢复所有已暂停/失败的任务（断点续传） */
    fun resumeAll() {
        tasks.value.filter {
            it.status == DownloadTaskEntity.STATUS_PAUSED ||
                it.status == DownloadTaskEntity.STATUS_FAILED
        }.forEach { manager.start(it.id) }
    }

    /** 删除全部任务（可同时删除已保存到本地的文件） */
    fun removeAll(deleteLocal: Boolean = false) {
        tasks.value.toList().forEach { manager.remove(it.id, deleteLocal) }
    }

    // ---------- 下载完成后关机 ----------
    // 放在 ViewModel 而不是下载页：切到别的 Tab 时下载页会被销毁，但 ViewModel 常驻，
    // 观察协程仍能等到「任务全部结束」再触发关机（用户正是会切走或最小化到托盘等下载）。

    /** 「下载完成后关机」开关（与设置持久化同步，下载页批量操作栏的开关观察它） */
    private val _shutdownWhenDone = MutableStateFlow(settings.shutdownWhenDownloadsComplete)
    val shutdownWhenDone: StateFlow<Boolean> = _shutdownWhenDone.asStateFlow()

    /** 关机倒计时剩余秒数；null = 未在倒计时（UI 据此显示/隐藏关机确认弹窗） */
    private val _shutdownCountdown = MutableStateFlow<Int?>(null)
    val shutdownCountdown: StateFlow<Int?> = _shutdownCountdown.asStateFlow()

    /** 关机倒计时总秒数（留出取消时间，避免误关） */
    private val shutdownCountdownSeconds = 60

    /** 是否已观察到「有任务在下载」：只有真正跑过任务、再全部结束才触发，避免开着开关时误判 */
    private var shutdownArmed = false

    /** 当前倒计时协程（取消/新任务到来时中止） */
    private var shutdownJob: Job? = null

    init {
        // 开关开启期间才订阅任务流（关闭后不再占用 Room 观察）。
        // 注意：这是**全应用常驻**的观察，与下载页是否显示无关。
        viewModelScope.launch {
            _shutdownWhenDone.flatMapLatest { enabled ->
                if (!enabled) {
                    shutdownArmed = false
                    stopShutdownCountdown()
                    flowOf(emptyList<DownloadTaskEntity>())
                } else {
                    manager.tasks
                }
            }.collect { list ->
                if (!_shutdownWhenDone.value) return@collect
                val hasActive = list.any {
                    it.status == DownloadTaskEntity.STATUS_DOWNLOADING ||
                        it.status == DownloadTaskEntity.STATUS_PENDING
                }
                if (hasActive) {
                    // 有任务在跑：撤销可能已在进行的倒计时（说明用户又加了任务），并武装等待其结束
                    stopShutdownCountdown()
                    shutdownArmed = true
                } else if (shutdownArmed && list.isNotEmpty() &&
                    list.none { it.status == DownloadTaskEntity.STATUS_PAUSED }
                ) {
                    // 队列已排空（只剩完成/失败，且没有用户主动暂停的任务）→ 开始关机倒计时
                    startShutdownCountdown()
                }
            }
        }
    }

    /** 开关切换：写入设置并同步 UI 状态；关闭时立即取消倒计时 */
    fun setShutdownWhenDone(enabled: Boolean) {
        if (_shutdownWhenDone.value == enabled) return
        settings.shutdownWhenDownloadsComplete = enabled
        _shutdownWhenDone.value = enabled
        if (!enabled) cancelShutdownCountdown()
    }

    /** 用户点「取消关机」：中止倒计时，并解除武装（避免任务状态下一次变化时又立刻触发） */
    fun cancelShutdownCountdown() {
        stopShutdownCountdown()
        shutdownArmed = false
    }

    /** 中止倒计时协程并清空倒计时显示 */
    private fun stopShutdownCountdown() {
        shutdownJob?.cancel()
        shutdownJob = null
        _shutdownCountdown.value = null
    }

    /** 用户点「立即关机」：跳过倒计时直接关机 */
    fun shutdownNow() {
        stopShutdownCountdown()
        DesktopActions.shutdownComputer(0)
    }

    private fun startShutdownCountdown() {
        if (shutdownJob?.isActive == true) return
        shutdownJob = viewModelScope.launch {
            for (s in shutdownCountdownSeconds downTo 1) {
                _shutdownCountdown.value = s
                delay(1000)
            }
            val ok = DesktopActions.shutdownComputer(0)
            // 就地复位（不要回调 stopShutdownCountdown()：它 cancel 的正是本协程自身）
            shutdownJob = null
            _shutdownCountdown.value = null
            shutdownArmed = false
            if (!ok) SnackbarController.show("自动关机失败：当前系统不支持")
        }
    }

    // ---------- 下载调试（设置 → 关于云析 → 长按 → 开发调试 → 下载调试）----------

    /** 某任务当前每个在飞分片（= 每个线程正在干什么），调试页每秒拉一次 */
    fun chunkDebugSnapshot(taskId: Long) = manager.chunkDebugSnapshot(taskId)

    /** 全局资源快照（在飞配额 / 活跃任务 / 线程池 / 堆内存） */
    fun resourceDebugSnapshot() = manager.resourceDebugSnapshot()

    /** 某任务的事件流（时间正序） */
    fun debugLogEntries(taskId: Long) = DownloadDebugLog.entries(taskId)

    /** 调试页「强制重连」：把某一路在飞分片标记为慢连接、换连接续传 */
    fun forcePreemptChunk(taskId: Long, key: String) = manager.forcePreemptChunk(taskId, key)

    class Factory(private val manager: DownloadManager) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: KClass<T>, extras: CreationExtras): T {
            require(modelClass == DownloadViewModel::class)
            return DownloadViewModel(manager) as T
        }
    }
}