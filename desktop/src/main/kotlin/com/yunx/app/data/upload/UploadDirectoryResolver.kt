package com.yunx.app.data.upload

import com.yunx.app.data.network.model.ShareFile
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/** 每个上传批次独立保存目录映射；只串行解析/建目录，文件传输仍由现有队列并发执行。 */
class UploadDirectoryResolver(root: String) {
    private val mutex = Mutex()
    private val directories = mutableMapOf<List<String>, String>(emptyList<String>() to root)
    private val children = mutableMapOf<String, MutableList<ShareFile>>()
    private val failures = mutableMapOf<List<String>, Exception>()
    private val retryRequested = AtomicBoolean(false)

    /** 仅由用户显式重试失败项时调用；已确认成功的目录映射继续保留。 */
    fun retryFailed() {
        retryRequested.set(true)
    }

    suspend fun transfer(
        source: UploadSource,
        list: suspend (String) -> List<ShareFile>,
        create: suspend (String, String) -> String?,
        upload: suspend (String) -> Unit
    ) {
        val segments = source.parentDirectories + if (source.isDirectory) listOf(source.name) else emptyList()
        val target = mutex.withLock {
            if (retryRequested.getAndSet(false)) {
                failures.clear()
                children.clear()
            }
            var parent = directories.getValue(emptyList())
            for (length in 1..segments.size) {
                currentCoroutineContext().ensureActive()
                val path = segments.take(length)
                val name = path.last()
                require(name.isNotBlank() && name != "." && name != ".." && '/' !in name && '\\' !in name) { "无效的目录名称：$name" }
                val cached = directories[path]
                if (cached != null) { parent = cached; continue }
                failures[path]?.let { throw it }
                try {
                    val entries = children[parent] ?: list(parent).toMutableList().also { children[parent] = it }
                    val matches = entries.filter { it.fname == name }
                    check(matches.size <= 1 && matches.all { it.isdir }) { "目标存在同名文件或多个同名目录：${path.joinToString("/")}" }
                    val id = matches.singleOrNull()?.fid ?: create(parent, name)
                    check(!id.isNullOrBlank()) { "无法创建目录：${path.joinToString("/")}" }
                    if (matches.isEmpty()) {
                        entries.add(ShareFile(id, name, 0L, true, parent, ""))
                        // 刚创建的目录为空，无需为每一级再请求一次列表。
                        children[id] = mutableListOf()
                    }
                    directories[path] = id
                    parent = id
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // 同一路径失败后，不让后续文件自动重放不确定是否成功的建目录请求。
                    failures[path] = e
                    throw e
                }
            }
            parent
        }
        currentCoroutineContext().ensureActive()
        if (!source.isDirectory) upload(target)
    }
}

/** 目录查找必须翻完所有页，不能因为同名目录不在第一页就重复新建。 */
internal suspend fun uploadDirectoryEntries(
    fetch: suspend (String?) -> Pair<List<ShareFile>, String?>
): List<ShareFile> {
    val entries = linkedMapOf<String, ShareFile>()
    val cursors = mutableSetOf<String>()
    var cursor: String? = null
    do {
        currentCoroutineContext().ensureActive()
        val (page, next) = fetch(cursor)
        val previousSize = entries.size
        page.forEach { entries[it.fid] = it }
        cursor = next?.takeIf { it.isNotBlank() && it != "-1" }
        if (cursor != null) check(page.isNotEmpty() && entries.size > previousSize) { "目录分页未返回新项目，已停止创建目录" }
        if (cursor != null) check(cursors.size < 10000 && cursors.add(cursor)) { "目录分页重复或过多，已停止创建目录" }
    } while (cursor != null)
    return entries.values.toList()
}
