package com.yunx.app.ui.viewmodel

import com.yunx.app.data.motrix.MotrixClient
import com.yunx.app.data.motrix.MotrixSender
import com.yunx.app.util.Log

private const val TAG = "Motrix"

/**
 * 一条待发给 Motrix 的下载源。
 *
 * 为什么要连 headers 一起带：夸克 / UC / 百度 / 115 的直链如果不带 Cookie、Referer、UA，
 * 会被 CDN 直接回 412 / 403；而这些头只有各网盘 ViewModel 知道怎么算，所以取链时一并取回。
 *
 * @param fallbackName 取链响应里的文件名，仅在相对路径为空时兜底
 */
internal class MotrixSource(
    val url: String,
    val headers: Map<String, String>,
    val fallbackName: String = ""
)

/** 批量发送的统计结果，用来拼一句给用户看的提示 */
internal class MotrixBatchOutcome(
    val total: Int,
    val succeeded: Int,
    val failed: Int,
    val lastFailure: String?
) {
    /**
     * @param folder 文件夹名；批量下发时传 null（提示里就不出现「：文件夹名」）
     */
    fun describe(folder: String?): String = when {
        total == 0 -> "没有可发送的文件"
        succeeded == 0 -> "发送到 Motrix 失败：${lastFailure ?: "未知原因"}"
        failed > 0 -> "已发送 $succeeded/$total 个文件到 Motrix，$failed 个失败：${lastFailure ?: "未知原因"}"
        folder.isNullOrBlank() -> "已发送 $succeeded 个文件到 Motrix"
        else -> "已发送 $succeeded 个文件到 Motrix：$folder"
    }
}

/**
 * 逐条把文件发送到 Motrix（外部下载器）。
 *
 * 串行而不是并发：批量并发会把 Motrix 队列一次塞满，用户反而不知道该先下哪个。
 * 单条失败不影响后续，失败原因只留最后一条用于提示。
 *
 * @param tasks 已展开的待发送项（文件夹已递归成文件，名称带相对路径）
 * @param nameOf 取相对路径（如 `文件夹A/子目录/文件.mp4`），Motrix 据此保持目录结构
 * @param resolve 取直链 + 该直链专属的请求头；返回 null 表示这一条跳过（算失败）
 */
internal suspend fun <T> sendFilesToMotrix(
    tasks: List<T>,
    saveDir: String,
    isCancelled: () -> Boolean,
    onProgress: (String) -> Unit,
    nameOf: (T) -> String,
    resolve: suspend (T) -> MotrixSource?
): MotrixBatchOutcome {
    var succeeded = 0
    var failed = 0
    var lastFailure: String? = null
    tasks.forEachIndexed { index, task ->
        if (isCancelled()) return@forEachIndexed
        onProgress("正在发送到 Motrix ${index + 1}/${tasks.size}")
        val source = runCatching { resolve(task) }.getOrElse { e ->
            Log.w(TAG, "取直链失败，跳过本条：${e.javaClass.simpleName}: ${e.message}")
            null
        }
        if (source == null) {
            failed++
            if (lastFailure == null) lastFailure = "取下载直链失败"
            return@forEachIndexed
        }
        val relativeName = nameOf(task).ifBlank { source.fallbackName }
        val result = runCatching {
            MotrixSender.send(
                url = source.url,
                fileName = relativeName,
                headers = source.headers,
                saveDir = saveDir,
                keepSubDirs = true
            )
        }.getOrElse { e -> MotrixClient.Result.Failure(e.message ?: e.javaClass.simpleName) }
        when (result) {
            is MotrixClient.Result.Success -> succeeded++
            is MotrixClient.Result.Failure -> {
                failed++
                lastFailure = result.message
            }
        }
    }
    return MotrixBatchOutcome(
        total = tasks.size,
        succeeded = succeeded,
        failed = failed,
        lastFailure = lastFailure
    )
}
