/*
 * YunX - Copyright (C) 2026 CYQawa
 * 桌面文件适配基于上游 UploadSource；遵循 GNU AGPL-3.0-or-later。
 */
package com.yunx.app.data.upload

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes

/** 保存文件元数据，哈希与传输均按需读取，避免把整个文件缓存到内存。 */
class UploadSource private constructor(
    val path: Path,
    val name: String,
    val size: Long,
    val mimeType: String,
    val parentDirectories: List<String>,
    val isDirectory: Boolean,
    private val attributes: BasicFileAttributes
) {
    fun verifyUnchanged() {
        val now = Files.readAttributes(path, BasicFileAttributes::class.java, java.nio.file.LinkOption.NOFOLLOW_LINKS)
        if (isDirectory) {
            if (!now.isDirectory || now.isOther || now.fileKey() != attributes.fileKey()) throw IOException("本地目录已变化：$name")
            return
        }
        if (!now.isRegularFile || now.size() != size ||
            now.lastModifiedTime() != attributes.lastModifiedTime() || now.fileKey() != attributes.fileKey()
        ) throw IOException("文件在上传期间发生变化，请重新选择：$name")
    }

    /** 打开 [start, end) 区间；关闭流同时释放文件句柄。 */
    fun open(start: Long = 0L, end: Long = size): InputStream {
        require(!isDirectory) { "文件夹不能作为文件读取" }
        require(start >= 0 && end >= start && end <= size) { "无效的文件读取区间" }
        verifyUnchanged()
        val channel = FileChannel.open(path, StandardOpenOption.READ)
        try {
            channel.position(start)
            return RangeInputStream(channel, end - start)
        } catch (e: Exception) {
            channel.close()
            throw e
        }
    }

    companion object {
        private val mimePattern = Regex("^[A-Za-z0-9.+-]+/[A-Za-z0-9.+-]+$")

        fun resolve(file: File): UploadSource = resolve(UploadCandidate(file, 0L))

        fun resolve(candidate: UploadCandidate): UploadSource {
            val file = candidate.file
            val path = file.toPath().toAbsolutePath().normalize()
            val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, java.nio.file.LinkOption.NOFOLLOW_LINKS)
            require(!attributes.isSymbolicLink && !attributes.isOther && Files.isReadable(path) &&
                (if (candidate.isDirectory) attributes.isDirectory else attributes.isRegularFile)) { "文件或目录不存在或不可读取：${file.name}" }
            val mime = if (candidate.isDirectory) "inode/directory" else runCatching { Files.probeContentType(path) }.getOrNull()
                ?.takeIf { mimePattern.matches(it) } ?: "application/octet-stream"
            return UploadSource(path, path.fileName.toString(), if (candidate.isDirectory) 0L else attributes.size(), mime,
                candidate.parentDirectories, candidate.isDirectory, attributes)
        }
    }
}

private class RangeInputStream(private val channel: FileChannel, private var remaining: Long) : InputStream() {
    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
    }

    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
        java.util.Objects.checkFromIndexSize(offset, length, bytes.size)
        if (length == 0) return 0
        if (remaining == 0L) return -1
        val count = channel.read(ByteBuffer.wrap(bytes, offset, minOf(length.toLong(), remaining).toInt()))
        if (count < 0) throw IOException("文件读取不完整，上传已停止")
        remaining -= count
        return count
    }

    override fun skip(count: Long): Long {
        val skipped = count.coerceAtLeast(0).coerceAtMost(remaining)
        channel.position(channel.position() + skipped)
        remaining -= skipped
        return skipped
    }

    override fun available(): Int = minOf(remaining, Int.MAX_VALUE.toLong()).toInt()
    override fun close() = channel.close()
}
