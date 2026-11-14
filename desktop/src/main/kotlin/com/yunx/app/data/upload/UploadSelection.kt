package com.yunx.app.data.upload

import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.io.File
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.io.IOException
import java.util.concurrent.CancellationException

data class UploadCandidate(
    val file: File,
    val size: Long,
    val parentDirectories: List<String> = emptyList(),
    val isDirectory: Boolean = false
) {
    val relativePath: String = (parentDirectories + file.name).joinToString("/")
}

/** 选择器和系统拖放共用的预检；只读取元数据，不读取文件内容。 */
data class UploadSelection(val files: List<UploadCandidate>, val notice: String?) {
    companion object {
        fun readDrop(transferable: Transferable): List<File> {
            require(transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) { "请从文件管理器拖入本地文件" }
            val value = transferable.getTransferData(DataFlavor.javaFileListFlavor)
            require(value is List<*> && value.all { it is File }) { "无法读取拖入的文件列表" }
            return value.filterIsInstance<File>()
        }

        fun merge(existing: List<UploadCandidate>, incoming: List<File>, checkpoint: () -> Unit = {}): UploadSelection {
            val selected = existing.associateByTo(linkedMapOf()) { it.file.toPath() }
            var invalid = 0
            var duplicates = 0
            var links = 0
            fun add(path: Path, attrs: BasicFileAttributes, parents: List<String>) {
                if (selected.containsKey(path)) duplicates++
                else selected[path] = UploadCandidate(path.toFile(), if (attrs.isDirectory) 0L else attrs.size(), parents, attrs.isDirectory)
            }
            // 同时选择父子目录时先处理父目录，保留完整层级并按本地路径去重。
            for (file in incoming.distinctBy { it.toPath().toAbsolutePath().normalize() }.sortedBy { it.toPath().toAbsolutePath().nameCount }) {
                checkpoint()
                try {
                    val root = file.toPath().toRealPath(NOFOLLOW_LINKS)
                    if (root.fileName == null) { invalid++; continue }
                    val attributes = Files.readAttributes(root, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                    val rootParents = selected[root.parent]?.takeIf { it.isDirectory }
                        ?.let { it.parentDirectories + it.file.name } ?: emptyList()
                    if (attributes.isSymbolicLink || attributes.isOther) { links++; continue }
                    if (!Files.isReadable(root)) { invalid++; continue }
                    if (attributes.isRegularFile) {
                        add(root, attributes, rootParents)
                    } else if (attributes.isDirectory) {
                        val rootPath = (selected[root]?.parentDirectories ?: rootParents) + root.fileName.toString()
                        // 已单独选中的子文件在加入父目录后归入该目录，避免被扁平放到目标根部。
                        selected.entries.forEach { entry ->
                            checkpoint()
                            if (entry.key != root && entry.key.startsWith(root)) {
                                entry.setValue(entry.value.copy(parentDirectories = rootPath + root.relativize(entry.key.parent).map { it.toString() }.filter { it.isNotEmpty() }))
                            }
                        }
                        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
                            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                                checkpoint()
                                if (attrs.isSymbolicLink || attrs.isOther) { links++; return FileVisitResult.SKIP_SUBTREE }
                                if (!Files.isReadable(dir)) { invalid++; return FileVisitResult.SKIP_SUBTREE }
                                val parents = if (dir == root) rootParents else selected[dir.parent]!!.let { it.parentDirectories + it.file.name }
                                add(dir, attrs, parents)
                                return FileVisitResult.CONTINUE
                            }
                            override fun visitFile(path: Path, attrs: BasicFileAttributes): FileVisitResult {
                                checkpoint()
                                when {
                                    attrs.isSymbolicLink || attrs.isOther -> links++
                                    !attrs.isRegularFile || !Files.isReadable(path) -> invalid++
                                    else -> add(path, attrs, selected[path.parent]!!.let { it.parentDirectories + it.file.name })
                                }
                                return FileVisitResult.CONTINUE
                            }
                            override fun visitFileFailed(path: Path, exc: IOException): FileVisitResult {
                                checkpoint()
                                invalid++
                                return FileVisitResult.CONTINUE
                            }
                            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                                checkpoint()
                                if (exc != null) invalid++
                                return FileVisitResult.CONTINUE
                            }
                        })
                    } else {
                        invalid++
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    invalid++
                }
            }
            val notices = buildList {
                if (invalid > 0) add("已跳过 $invalid 个不存在、不可读取或不支持的项目（不直接上传磁盘根目录）")
                if (links > 0) add("已跳过 $links 个符号链接或特殊文件，不跟随链接扫描")
                if (duplicates > 0) add("已合并 $duplicates 个重复项目，文件夹内的文件保留所选目录层级")
            }
            return UploadSelection(selected.values.toList(), notices.takeIf { it.isNotEmpty() }?.joinToString("；"))
        }
    }
}
