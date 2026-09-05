package com.yunx.app.data.download

import com.yunx.app.util.DesktopActions
import com.yunx.app.util.Log
import java.io.File

/**
 * 桌面版完成文件保存：直接写普通文件系统。
 * - 默认目录：<用户目录>/Downloads；
 * - 自定义目录：设置页选择的绝对路径（原 SAF tree Uri 概念在桌面映射为普通目录路径）；
 * - 同名文件自动加时间戳防重，绝不覆盖用户已有文件。
 */
object DownloadSaver {

    private const val TAG = "YunX-DL"

    /** 大文件拷贝缓冲：1MB */
    private const val COPY_BUFFER_SIZE = 1 * 1024 * 1024

    /**
     * 保存文件到下载目录。
     * @param fileName 可为**相对路径**（如 "文件夹A/子/文件.mp4"，用于下载整个文件夹保持目录结构）；
     *                 纯文件名时保存到根目录。
     * @param targetDirUri 自定义保存目录（桌面版为普通目录绝对路径）；null 时用系统默认 Downloads
     * @return 保存成功后的文件绝对路径；失败返回 null
     */
    fun save(fileName: String, source: File, targetDirUri: String? = null): String? {
        val safePath = DownloadPathPolicy.sanitize(
            fileName,
            fallbackName = "download_${System.currentTimeMillis()}"
        ) ?: run {
            Log.e(TAG, "拒绝不安全的下载相对路径")
            return null
        }
        val safeName = safePath.fileName
        val safeDir = safePath.relativeDirectory

        return runCatching {
            val baseDir: File = if (!targetDirUri.isNullOrBlank()) {
                File(targetDirUri)
            } else {
                DesktopActions.defaultDownloadDir
            }
            val canonicalBase = baseDir.canonicalFile
            val destDir = (if (safeDir.isBlank()) canonicalBase else File(canonicalBase, safeDir)).canonicalFile
            if (destDir != canonicalBase && !DownloadPathPolicy.isContained(canonicalBase, destDir)) {
                throw SecurityException("下载目录越界")
            }
            if (!destDir.exists() && !destDir.mkdirs()) {
                throw java.io.IOException("无法创建目录：$destDir")
            }
            val candidates = buildList {
                add(safeName)
                repeat(3) { i -> add(timestampedName(safeName, i)) }
            }
            val dest = candidates.asSequence()
                .map { File(destDir, it).canonicalFile }
                .firstOrNull { candidate ->
                    DownloadPathPolicy.isContained(canonicalBase, candidate) && !candidate.exists()
                } ?: return@runCatching null
            source.inputStream().use { input ->
                dest.outputStream().use { output ->
                    val buffer = ByteArray(COPY_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                    }
                }
            }
            dest.absolutePath
        }.onFailure {
            Log.e(TAG, "保存失败: ${it.message}")
        }.getOrNull()
    }

    /** 在文件名扩展名前加时间戳防重：base.apk → base_20260812165000.apk */
    private fun timestampedName(fileName: String, attempt: Int): String {
        val dot = fileName.lastIndexOf('.')
        val base = if (dot > 0) fileName.substring(0, dot) else fileName
        val ext = if (dot > 0) fileName.substring(dot) else ""
        val ts = System.currentTimeMillis()
        return if (attempt == 0) "${base}_$ts$ext" else "${base}_${ts}_${attempt + 1}$ext"
    }

    /** 自定义目录展示名：桌面版直接返回目录路径 */
    fun safDirDisplay(uriString: String): String = uriString.ifBlank { "自定义目录" }

    /**
     * 删除已保存的本地文件（配合任务删除）。
     * @return 是否删除成功（false 表示未找到或删除失败）
     */
    fun delete(savePath: String): Boolean {
        if (savePath.isBlank()) return false
        return runCatching {
            File(savePath).delete()
        }.onFailure {
            Log.e(TAG, "删除本地文件失败: ${it.message}")
        }.getOrDefault(false)
    }
}
