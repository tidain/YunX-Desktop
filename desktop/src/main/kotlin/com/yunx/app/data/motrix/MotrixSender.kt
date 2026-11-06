package com.yunx.app.data.motrix

import com.yunx.app.data.prefs.SettingsRepository
import com.yunx.app.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 「发送到 Motrix」的统一入口。
 *
 * 为什么要这一层而不是各处直接调 [MotrixClient]：
 * - 配置从哪来要统一（[SettingsRepository.resolveMotrixConfig] 决定自动读还是手填）；
 * - 网络调用必须离开 UI 线程（RPC 是阻塞的 OkHttp 调用）；
 * - 「有没有可用的 Motrix」这个判断被 11 个下载弹窗与下载页右键菜单复用，
 *   统一在这里做，避免每处各写一遍。
 */
object MotrixSender {

    private const val TAG = "Motrix"

    /** 当前可用的 Motrix 参数；null = 没装 / 功能被关掉（界面据此隐藏入口） */
    fun configOrNull(settings: SettingsRepository = SettingsRepository()): MotrixConfig? =
        settings.resolveMotrixConfig()

    /** 是否显示「发送到 Motrix」入口 */
    fun available(settings: SettingsRepository = SettingsRepository()): Boolean =
        configOrNull(settings) != null

    /**
     * 把直链推给 Motrix。
     *
     * 失败不抛异常，一律折成 [MotrixClient.Result.Failure]，调用方直接展示 message。
     */
    suspend fun send(
        url: String,
        fileName: String,
        headers: Map<String, String> = emptyMap(),
        saveDir: String = "",
        keepSubDirs: Boolean = false,
        settings: SettingsRepository = SettingsRepository()
    ): MotrixClient.Result = withContext(Dispatchers.IO) {
        val config = configOrNull(settings)
            ?: return@withContext MotrixClient.Result.Failure(
                "没有可用的 Motrix 连接参数：去「设置 → Motrix 下载器」里手动填端口与密钥"
            )
        runCatching {
            MotrixClient.addUri(
                config = config,
                url = url,
                fileName = fileName,
                headers = headers,
                saveDir = saveDir,
                keepSubDirs = keepSubDirs
            )
        }.getOrElse { e ->
            Log.e(TAG, "发送到 Motrix 失败：${e.javaClass.simpleName}: ${e.message}", e)
            MotrixClient.Result.Failure(e.message ?: e.javaClass.simpleName)
        }
    }

    /** 结果 → 界面文案（含「失败」字样时提示条会自动用错误样式） */
    fun describe(result: MotrixClient.Result, fileName: String): String = when (result) {
        is MotrixClient.Result.Success -> "已发送到 Motrix：$fileName"
        is MotrixClient.Result.Failure -> "发送到 Motrix 失败：${result.message}"
    }
}
