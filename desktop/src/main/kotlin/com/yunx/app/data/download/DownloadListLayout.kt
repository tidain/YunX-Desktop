package com.yunx.app.data.download

import com.yunx.app.data.db.DownloadTaskEntity

/**
 * 下载页列表的扁平化行。
 *
 * 文件夹组的子任务不再塞进同一个卡片 Column（展开时会一次性组合全部子项，
 * 上千文件时直接卡死），而是展开成独立的 LazyColumn item，由 LazyColumn
 * 按可视区域逐个组合/回收。
 */
internal sealed interface DownloadListRow {
    val key: String

    data class RootTask(val task: DownloadTaskEntity) : DownloadListRow {
        override val key: String get() = "root_${task.id}"
    }

    data class FolderHeader(
        val groupId: String,
        val folder: String,
        val saveRoot: String,
        val tasks: List<DownloadTaskEntity>,
        val expanded: Boolean
    ) : DownloadListRow {
        override val key: String get() = "folder_$groupId"
    }

    data class FolderChild(
        val groupId: String,
        val task: DownloadTaskEntity,
        /** 组内第一个子项：负责补卡片顶部的内边距 */
        val first: Boolean,
        /** 组内最后一个子项：负责圆角收尾和组间距 */
        val last: Boolean
    ) : DownloadListRow {
        override val key: String get() = "child_${groupId}_${task.id}"
    }
}

/**
 * 下载页列表数据准备：根任务 + 文件夹分组（表头，展开时再追加每个子任务）。
 *
 * 纯函数、不依赖 Compose，供 UI 在 `remember(tasks, ...)` 中缓存调用；
 * 进度统计（stats）高频刷新时不会重复分组排序。
 */
internal object DownloadListLayout {

    fun groupId(normalizedRoot: String, folder: String): String = "$normalizedRoot\u0000$folder"

    fun rows(
        tasks: List<DownloadTaskEntity>,
        globalSaveRoot: String?,
        collapsedGroupIds: Set<String>
    ): List<DownloadListRow> {
        val rows = ArrayList<DownloadListRow>(tasks.size + 8)

        // 根目录任务（无相对路径）单独显示；下载失败的排到最上方（其余保持原有顺序）
        tasks.filter { !it.fileName.contains('/') }
            .sortedBy { if (it.status == DownloadTaskEntity.STATUS_FAILED) 0 else 1 }
            .forEach { rows += DownloadListRow.RootTask(it) }

        // 文件夹下载任务：按「保存根目录 + 顶级目录」分组，含失败子任务的组整体置顶
        tasks.filter { it.fileName.contains('/') }
            .groupBy { DownloadGrouping.groupKey(it, globalSaveRoot) }
            .entries
            .sortedBy { entry ->
                if (entry.value.any { it.status == DownloadTaskEntity.STATUS_FAILED }) 0 else 1
            }
            .forEach { entry ->
                val (root, folder) = entry.key
                val gid = groupId(root, folder)
                val groupTasks = entry.value
                val expanded = gid !in collapsedGroupIds
                rows += DownloadListRow.FolderHeader(
                    groupId = gid,
                    folder = folder,
                    saveRoot = DownloadGrouping.folderSaveRoot(groupTasks.first(), globalSaveRoot),
                    tasks = groupTasks,
                    expanded = expanded
                )
                if (expanded) {
                    // 子任务同样失败置顶（其余保持原有顺序）
                    val ordered = groupTasks.sortedBy {
                        if (it.status == DownloadTaskEntity.STATUS_FAILED) 0 else 1
                    }
                    ordered.forEachIndexed { index, task ->
                        rows += DownloadListRow.FolderChild(
                            groupId = gid,
                            task = task,
                            first = index == 0,
                            last = index == ordered.lastIndex
                        )
                    }
                }
            }
        return rows
    }
}
