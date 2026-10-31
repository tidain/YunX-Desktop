package com.yunx.app.data.bridge

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong

/**
 * 浏览器插件 ↔ 云析 的「下载劫持」桥接模型。
 *
 * 浏览器扩展（MV3）嗅探到下载后，通过本地回环 HTTP（见 [BrowserBridgeServer]）把请求 POST 到 /hook，
 * 这里把请求挂起进 [pending] 队列，由 UI 层弹出询问窗（保存目录 + 线程数）；
 * 用户在弹窗里做出决定后，[HookRequest.deferred] 被完成，服务端把决定返回给扩展。
 *
 * 三种决定（与扩展约定）：
 * - [HookDecision.Accept]：交给云析下载（扩展取消浏览器下载，并 erase 掉那条记录）；
 * - [HookDecision.Cancel]：用户放弃这次下载（扩展同样取消 + erase，不重新下载）；
 * - [HookDecision.ReturnToBrowser]：归还浏览器下载（扩展用 chrome.downloads.download 重新发起，
 *   并对该根域名写入 5 分钟冷却，冷却期内不再劫持该域名及以下子域名的下载）。
 */
object BrowserBridge {

    /** 扩展上报的一次下载请求（已由扩展补齐请求头 / Cookie / 大小等信息） */
    data class HookRequest(
        /** 桥接内部自增编号（仅用于 UI key 与日志） */
        val id: Long,
        val url: String,
        val fileName: String,
        /** 文件大小（字节）；-1 = 未知 */
        val fileSize: Long,
        /** 发起下载的页面地址（Referer 兜底） */
        val pageUrl: String,
        val referer: String,
        val userAgent: String,
        /** 扩展用 chrome.cookies.getAll 组装的 Cookie 头；空串 = 无 */
        val cookie: String,
        /** MIME 类型（展示用） */
        val mime: String,
        /** 根域名（eTLD+1，冷却键） */
        val rootDomain: String,
        /** 供 dl 使用的请求头（Cookie/Referer/User-Agent 等，已按需过滤空值） */
        val headers: Map<String, String>,
        /** 等待 UI 决定；由 [BrowserBridge] 完成 */
        val deferred: CompletableDeferred<HookDecision>
    )

    /** 用户在弹窗里做出的决定 */
    sealed interface HookDecision {
        /** 交给云析下载：[saveDir] 为空串 = 跟随全局保存目录；[threads] 为本任务线程数 */
        data class Accept(val saveDir: String, val threads: Int) : HookDecision

        /** 放弃本次下载（扩展取消并 erase，不重新下载） */
        data object Cancel : HookDecision

        /** 归还浏览器下载（扩展重新发起下载 + 该根域名 5 分钟冷却） */
        data object ReturnToBrowser : HookDecision
    }

    /**
     * 扩展「B 站专属页」提交的一次解析下载请求（不是劫持，是扩展主动交给云析）。
     *
     * 与劫持不同：这里**不需要用户确认**（清晰度与下载方式已在插件页选好），
     * 云析收到后静默解析 BVID 并按 [mode] 直接入队。
     */
    data class BiliRequest(
        /** 视频 BVID（如 `BV1xx411c7mD`） */
        val bvid: String,
        /** 发起页面地址（日志用） */
        val pageUrl: String,
        /** 目标清晰度编号（B 站 qn）；0 = 不指定，用平台默认 */
        val qn: Int,
        /** 下载方式：`merge` 合轨 / `video` 仅视频 / `audio` 仅音频 / `separate` 两轨分别保存 */
        val mode: String,
        /** 视频标题（日志用） */
        val title: String
    )

    /** B 站请求的消费者（由 UI 层注册）；返回是否已受理 */
    @Volatile
    var onBiliRequest: ((BiliRequest) -> Boolean)? = null

    /** 交给 UI 层处理一条 B 站请求；未注册消费者时按未受理返回 */
    fun dispatchBili(req: BiliRequest): Boolean = onBiliRequest?.invoke(req) ?: false

    /**
     * 扩展「其他视频平台」提交的一次解析下载请求（抖音 / 微博 / AcFun …）。
     *
     * 与 [BiliRequest] 同属「主动静默提交」：插件读不到这些平台的清晰度档位，
     * 只有把页面链接交给云析，由云析侧的 [com.yunx.app.data.parser.VideoLinkParser] 识别并展开短链，
     * 再按平台可用的最高清晰度解析入队。
     */
    data class VideoRequest(
        /** 平台标识：douyin / weibo / acfun …（日志与提示用） */
        val platform: String,
        /** 视频页面链接（含短链，云析侧会展开） */
        val url: String,
        /** 视频标题（日志用） */
        val title: String,
        /** 发起页面地址（日志用） */
        val pageUrl: String
    )

    /** 其他平台请求的消费者（由 UI 层注册）；返回是否已受理 */
    @Volatile
    var onVideoRequest: ((VideoRequest) -> Boolean)? = null

    /** 交给 UI 层处理一条其他平台请求；未注册消费者时按未受理返回 */
    fun dispatchVideo(req: VideoRequest): Boolean = onVideoRequest?.invoke(req) ?: false

    private val seq = AtomicLong(0)

    private val _pending = MutableStateFlow<List<HookRequest>>(emptyList())

    /** 待处理的劫持请求队列（队首即当前弹窗展示的那条） */
    val pending: StateFlow<List<HookRequest>> = _pending.asStateFlow()

    /** 注册一条来自扩展的劫持请求，返回其等待决定的 deferred（调用方挂起等待） */
    fun submit(
        url: String,
        fileName: String,
        fileSize: Long,
        pageUrl: String,
        referer: String,
        userAgent: String,
        cookie: String,
        mime: String,
        rootDomain: String
    ): HookRequest {
        val headers = buildMap {
            if (cookie.isNotBlank()) put("Cookie", cookie)
            if (referer.isNotBlank()) put("Referer", referer)
            if (userAgent.isNotBlank()) put("User-Agent", userAgent)
        }
        val req = HookRequest(
            id = seq.incrementAndGet(),
            url = url,
            fileName = fileName,
            fileSize = fileSize,
            pageUrl = pageUrl,
            referer = referer,
            userAgent = userAgent,
            cookie = cookie,
            mime = mime,
            rootDomain = rootDomain,
            headers = headers,
            deferred = CompletableDeferred()
        )
        _pending.update { it + req }
        return req
    }

    /** 用户在弹窗里选择「下载」 */
    fun accept(req: HookRequest, saveDir: String, threads: Int) {
        req.deferred.complete(HookDecision.Accept(saveDir.trim(), threads))
        remove(req)
    }

    /** 用户在弹窗里选择「取消」 */
    fun cancel(req: HookRequest) {
        req.deferred.complete(HookDecision.Cancel)
        remove(req)
    }

    /** 用户在弹窗里选择「归还浏览器下载」 */
    fun returnToBrowser(req: HookRequest) {
        req.deferred.complete(HookDecision.ReturnToBrowser)
        remove(req)
    }

    /** 服务端超时 / 扩展断开：按「归还浏览器下载」处理，避免用户丢了这次下载 */
    fun timeout(req: HookRequest) {
        req.deferred.complete(HookDecision.ReturnToBrowser)
        remove(req)
    }

    private fun remove(req: HookRequest) {
        _pending.update { list -> list.filterNot { it.id == req.id } }
    }
}
