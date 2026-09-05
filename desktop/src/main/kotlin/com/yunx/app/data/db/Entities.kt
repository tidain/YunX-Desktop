package com.yunx.app.data.db

/**
 * 桌面版实体：与原 Room Entity 字段/默认值完全一致（去掉 Room 注解）。
 */

/** 夸克网盘登录凭证（cookie 落库，后续所有 API 请求携带）。 */
data class QuarkAccountEntity(
    val id: String = "quark",
    val cookie: String = "",
    val nickname: String = "",
    val updatedAt: Long = System.currentTimeMillis()
)

/** UC 网盘登录凭证。 */
data class UCAccountEntity(
    val id: String = "uc",
    val cookie: String = "",
    val nickname: String = "",
    val updatedAt: Long = System.currentTimeMillis()
)

/** 迅雷网盘登录凭证（access_token 落库，pan API 请求携带 Bearer）。 */
data class XunleiAccountEntity(
    val id: String = "xunlei",
    val accessToken: String = "",
    val refreshToken: String = "",
    val deviceId: String = "",
    val captchaToken: String = "",
    val nickname: String = "",
    val updatedAt: Long = System.currentTimeMillis()
)

/** 百度网盘登录凭证。关键字段：BDUSS / STOKEN。 */
data class BaiduAccountEntity(
    val id: String = "baidu",
    val cookie: String = "",
    val nickname: String = "",
    val updatedAt: Long = System.currentTimeMillis()
)

/** 139 网盘（和彩云）登录凭证。 */
data class C139AccountEntity(
    val id: String = "c139",
    val cookie: String = "",
    val nickname: String = "",
    val authorization: String = "",
    val updatedAt: Long = System.currentTimeMillis()
)

/** 123 云盘登录凭证（JWT token，请求带 Authorization: Bearer <token>）。 */
data class Pan123AccountEntity(
    val id: String = "pan123",
    val accessToken: String = "",
    val account: String = "",
    val nickname: String = "",
    val updatedAt: Long = System.currentTimeMillis()
)

/** 下载任务（持久化，断点续传依赖 part 文件 + 已下载大小）。 */
data class DownloadTaskEntity(
    val id: Long = 0,
    val url: String,
    val fileName: String,
    val totalSize: Long = 0L,
    val downloadedSize: Long = 0L,
    val status: Int = STATUS_PENDING,
    /** 失败原因（服务端/网络/分片等具体错误信息），成功或进行中为空 */
    val errorMsg: String = "",
    /** 完成后的保存位置：文件绝对路径 */
    val savePath: String = "",
    /** 恢复任务所需的请求头 JSON（Cookie/Referer/UA 等） */
    val requestHeadersJson: String = "{}",
    /** 首次探测大小后固定的分片数，恢复时不随设置变化 */
    val chunkCount: Int = 0,
    /** 与 chunkCount 对应的服务器总大小 */
    val plannedTotalSize: Long = 0L,
    /** 下载完成/删除任务后应清理的云端临时目录 ID（当前为夸克） */
    val cleanupId: String = "",
    /** 下载来源平台标识（用于按平台应用下载线程数设置）；通用/手动添加为空串 */
    val platform: String = "",
    /** 下载完成时的平均速度（字节/秒）；完成态展示用，进行中为 0 */
    val avgSpeed: Long = 0,
    val createTime: Long = System.currentTimeMillis()
) {
    companion object {
        const val STATUS_PENDING = 0
        const val STATUS_DOWNLOADING = 1
        const val STATUS_PAUSED = 2
        const val STATUS_COMPLETED = 3
        const val STATUS_FAILED = 4

        fun statusText(status: Int): String = when (status) {
            STATUS_PENDING -> "等待中"
            STATUS_DOWNLOADING -> "下载中"
            STATUS_PAUSED -> "已暂停"
            STATUS_COMPLETED -> "已完成"
            STATUS_FAILED -> "失败"
            else -> "未知"
        }
    }
}

/** 网盘链接收藏（支持多种分类）。 */
data class BookmarkEntity(
    val id: Long = 0,
    /** 完整分享链接 / 分享文案（再次解析用，原样保存） */
    val link: String,
    /** 分享标题（解析后回填；手动添加可为空，展示时回退为链接） */
    val title: String = "",
    /** 平台枚举名（QUARK/UC/XUNLEI/BAIDU/C139/PAN123），未知为空串 */
    val platform: String = "",
    /** 提取码（可选） */
    val pwd: String = "",
    /** 分类 */
    val category: String = DEFAULT_CATEGORY,
    val createTime: Long = System.currentTimeMillis()
) {
    companion object {
        const val DEFAULT_CATEGORY = "未分类"

        /** 预设分类：新增收藏 / 修改分类 / 分类筛选共用 */
        val PRESET_CATEGORIES = listOf(
            DEFAULT_CATEGORY, "视频", "文档", "软件", "音乐", "图片", "压缩包", "其他"
        )
    }
}
