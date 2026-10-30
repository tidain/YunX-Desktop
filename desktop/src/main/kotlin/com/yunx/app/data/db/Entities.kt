package com.yunx.app.data.db

/**
 * 桌面版实体：与原 Room Entity 字段/默认值完全一致（去掉 Room 注解）。
 */

/** 夸克网盘登录凭证（cookie 落库，后续所有 API 请求携带）。 */
data class QuarkAccountEntity(
    override val id: String = "quark",
    val cookie: String = "",
    override val nickname: String = "",
    override val updatedAt: Long = System.currentTimeMillis(),
    /** 该平台主账号（列目录 / 转存等单一身份操作用它） */
    override val isPrimary: Boolean = true,
    /** 用户备注；空串 = 界面回退显示昵称 */
    override val remark: String = ""
) : MultiAccountEntity

/** UC 网盘登录凭证。 */
data class UCAccountEntity(
    override val id: String = "uc",
    val cookie: String = "",
    override val nickname: String = "",
    override val updatedAt: Long = System.currentTimeMillis(),
    override val isPrimary: Boolean = true,
    override val remark: String = ""
) : MultiAccountEntity

/** 迅雷网盘登录凭证（access_token 落库，pan API 请求携带 Bearer）。 */
data class XunleiAccountEntity(
    override val id: String = "xunlei",
    val accessToken: String = "",
    val refreshToken: String = "",
    val deviceId: String = "",
    val captchaToken: String = "",
    override val nickname: String = "",
    /**
     * 登录方式：空串 = App 通道（账号密码 / 短信，用 App OAuth 客户端刷新）；
     * [com.yunx.app.data.network.XunleiWebCredential.AUTH_TYPE] = 网页登录，
     * 刷新必须换成网页 OAuth 客户端（否则刷新必失败，用户会看到「刚登录就过期」）。
     */
    val authType: String = "",
    override val updatedAt: Long = System.currentTimeMillis(),
    override val isPrimary: Boolean = true,
    override val remark: String = ""
) : MultiAccountEntity

/** 百度网盘登录凭证。关键字段：BDUSS / STOKEN。 */
data class BaiduAccountEntity(
    override val id: String = "baidu",
    val cookie: String = "",
    override val nickname: String = "",
    override val updatedAt: Long = System.currentTimeMillis(),
    override val isPrimary: Boolean = true,
    override val remark: String = ""
) : MultiAccountEntity

/** 139 网盘（和彩云）登录凭证。 */
data class C139AccountEntity(
    override val id: String = "c139",
    val cookie: String = "",
    override val nickname: String = "",
    val authorization: String = "",
    override val updatedAt: Long = System.currentTimeMillis(),
    override val isPrimary: Boolean = true,
    override val remark: String = ""
) : MultiAccountEntity

/** 123 云盘登录凭证（JWT token，请求带 Authorization: Bearer <token>）。 */
data class Pan123AccountEntity(
    override val id: String = "pan123",
    val accessToken: String = "",
    val account: String = "",
    override val nickname: String = "",
    override val updatedAt: Long = System.currentTimeMillis(),
    override val isPrimary: Boolean = true,
    override val remark: String = ""
) : MultiAccountEntity

/**
 * 115 网盘登录凭证（115.com cookie 落库，后续 API 请求携带）。
 *
 * 115 的登录态完全由 Cookie 表达（UID/CID/SEID/KID 四个键）：
 * - `UID` 同时也是提取分享时的 `user_id`（`share/snap` 的请求参数），缺它连分享都打不开；
 * - `CID`/`SEID`/`KID` 是个人盘与转存接口的鉴权串；
 * - 下载直链还要求请求头带上这份 Cookie（115 CDN 无 Cookie 会 403）。
 */
data class Pan115AccountEntity(
    override val id: String = "pan115",
    val cookie: String = "",
    override val nickname: String = "",
    override val updatedAt: Long = System.currentTimeMillis(),
    override val isPrimary: Boolean = true,
    override val remark: String = ""
) : MultiAccountEntity

/** 光鸭云盘登录凭证（accessToken / refreshToken / 设备标识落库，接口鉴权与设备校验用）。 */
data class GuangYaAccountEntity(
    override val id: String = "guangya",
    val accessToken: String = "",
    val refreshToken: String = "",
    val deviceId: String = "",
    val deviceSign: String = "",
    val account: String = "",
    override val nickname: String = "",
    override val updatedAt: Long = System.currentTimeMillis(),
    override val isPrimary: Boolean = true,
    override val remark: String = ""
) : MultiAccountEntity

/** 蓝奏云优享版登录凭证（appToken 鉴权；账号密码用于重新登录）。 */
data class ILanzouAccountEntity(
    override val id: String = "ilanzou",
    val appToken: String = "",
    val uuid: String = "",
    val account: String = "",
    val password: String = "",
    val userId: String = "",
    override val nickname: String = "",
    override val updatedAt: Long = System.currentTimeMillis(),
    override val isPrimary: Boolean = true,
    override val remark: String = ""
) : MultiAccountEntity

/** 蓝奏云登录凭证（cookie 落库，后续接口请求携带）。 */
data class LanzouAccountEntity(
    override val id: String = "lanzou",
    val cookie: String = "",
    override val nickname: String = "",
    override val updatedAt: Long = System.currentTimeMillis(),
    override val isPrimary: Boolean = true,
    override val remark: String = ""
) : MultiAccountEntity

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
    /** 原始分享链接（如 https://pan.baidu.com/s/xxx），用于右键"复制分享链接" */
    val shareUrl: String = "",
    /** 下载完成时的平均速度（字节/秒）；完成态展示用，进行中为 0 */
    val avgSpeed: Long = 0,
    /**
     * 外部下载引擎（Gopeed）的任务 ID；空串 = 由内置分片下载器执行。
     * 非空时该任务的进度由引擎同步协程回写，暂停 / 继续 / 删除都要转发给引擎。
     */
    val engineTaskId: String = "",
    /**
     * 单文件多源分片（实验性「多账号下载提速 C2」）的备选下载源 JSON：多账号对同一文件
     * 各取一条直链，内置分片下载器把分片轮流分给不同源（落库前按 `download.altSources` 加密）。
     * 空串 = 单源，与未开启该功能时完全一致。
     */
    val altSourcesJson: String = "",
    /**
     * 本任务的保存目录覆盖（绝对路径）；空串 = 跟随「设置 → 储存目录」的下载保存目录。
     * 单次下载选择「下载到…」时写入，暂停/续传/重新下载都沿用它，不受之后改全局设置影响。
     */
    val saveDir: String = "",
    /** 实验性直写：目标目录中的 `.yunx-*.part` 绝对路径；空串 = 非直写（走缓存分片） */
    val directPart: String = "",
    /** 直写模式：0=非直写，1=单线程直写，2=多线程直写 */
    val directMode: Int = 0,
    /** DASH 分轨下载的音轨地址（哔哩哔哩高清）；空串 = 单文件下载 */
    val mergeAudioUrl: String = "",
    /** 音轨请求头 JSON（落库前按 `download.mergeAudioHeaders` 加密）；空串 = 无 */
    val mergeAudioHeadersJson: String = "",
    /** 动图资源地址（Motion Photo 合成用）；空串 = 普通静图 */
    val motionUrl: String = "",
    /** 本任务的下载线程数覆盖（浏览器插件按用户弹窗选择传入）；0 = 跟随平台的线程数设置 */
    val threads: Int = 0,
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

/** 网盘解析历史（解析过的分享链接，便于一键重新解析）。 */
data class LinkHistoryEntity(
    val id: Long = 0,
    /** 完整分享链接 */
    val url: String,
    /** 分享标题（解析后回填；为空时展示回退为 url） */
    val title: String = "",
    /** 平台枚举名（QUARK/UC/XUNLEI/BAIDU/C139/PAN123），未知为空串 */
    val platform: String = "",
    /** 提取码（可选） */
    val pwd: String = "",
    val createTime: Long = System.currentTimeMillis()
)
