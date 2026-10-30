package com.yunx.app.data.network.video

/**
 * 单个视频平台的解析器。
 *
 * 实现约定：
 * - 网络请求走 `HttpClients.apiClient()`（统一超时 + 代理），不要自己 new OkHttpClient；
 * - 失败一律抛 [VideoParseException]（带 [VideoParseErrorKind]），不要抛裸异常；
 * - [cookie] 为该平台账号的完整 Cookie 头（未登录时为空串）。需要登录的平台在空 Cookie 时应抛
 *   `UNSUPPORTED` 并给出「请先到账号页登录」这类可操作提示；
 * - 产出的 [VideoMedia.headers] 要带上该直链真正需要的 Referer/UA/Cookie，下载器会原样使用。
 */
interface VideoParser {

    val platform: VideoPlatform

    /**
     * 解析一条已识别的链接。
     *
     * @param link 由 [VideoLinkParser] 识别出的平台内资源标识（短链已在内部展开）
     * @param cookie 该平台的 Cookie；未登录为空串
     */
    suspend fun parse(link: DetectedVideoLink, cookie: String): VideoParseResult

    /**
     * 保存登录态前的校验：返回**昵称**（拿不到昵称时返回空串），Cookie 无效返回 null。
     *
     * ★ 为什么要有这一步：网页登录抓到的 Cookie 可能是「页面已加载但没真正登录」的中间态，
     *   不做校验就会存下一份永远解析失败的账号，用户看不懂哪里错了（网盘那边同样会先校验再保存）。
     * 默认实现只校验非空，能真正问平台「我是谁」的解析器应当覆盖它。
     */
    suspend fun validateCookie(cookie: String): String? =
        cookie.trim().takeIf { it.isNotBlank() }?.let { "" }
}

/**
 * 支持「按清晰度重解析」的解析器（目前只有哔哩哔哩实现）。
 *
 * ★ 为什么是「重解析」而不是「一次拿全部直链」：直链带时效签名且逐档不同，
 *   一次请求只会下发用户所选那一档的地址；所以用户换档位时重新解析一遍最省内存也最简单。
 *   [qn] 为 null 表示「不指定，取平台默认（通常是最高的可用档）」。
 */
interface QualitySelectableParser {

    suspend fun parseWithQuality(link: DetectedVideoLink, cookie: String, qn: Int?): VideoParseResult
}
