package com.yunx.app.data.repository

import com.yunx.app.data.network.BaiduApi
import com.yunx.app.data.network.BaiduConstants
import com.yunx.app.data.network.ShareLinkParser
import com.yunx.app.data.network.model.DownloadLink
import com.yunx.app.data.network.model.ShareFile
import com.yunx.app.data.network.model.ShareSession

/**
 * 百度分享解析仓库：verify 拿 sekey → xpan/share 列文件 → 转存临时目录（自动创建，失败回退根目录）→ locatedownload 拿 appall 高速链 → 立即删除临时转存。
 * 全部基于抓包链路（share/verify → xpan/share list → share/transfer → locatedownload）。
 * appall 直链 URL 自带签名、删除转存后仍有效，故取链成功后立即删除临时转存（失败不阻断）。
 */
class BaiduResolveRepository(private val api: BaiduApi) : ShareResolveRepository {

    /** surl -> sekey（verify 返回的 randsk） */
    private val sekeys = mutableMapOf<String, String>()

    /** surl -> (share_id, uk)，由列表接口返回（转存必需） */
    private val shareInfos = mutableMapOf<String, Pair<String, String>>()

    override suspend fun createSession(link: String, pwd: String?, cookie: String): Result<ShareSession> =
        runCatching {
            val parsed = ShareLinkParser.parse(link)
                ?: throw IllegalArgumentException("无法识别百度分享链接")
            val surl = parsed.shareId
            // 修复：公共分享（pwd 为空）不强制提取码——跳过 verify，sekey 置空，
            // listShare 将不带 sekey 直接列出（抓包实证：公共分享无需 sekey/Cookie 即 errno=0）
            val effectivePwd = pwd?.takeIf { it.isNotBlank() } ?: parsed.pwd
            val sekey = if (effectivePwd.isNullOrBlank()) {
                ""
            } else {
                api.verifyShare(surl, effectivePwd, cookie)
            }
            sekeys[surl] = sekey
            ShareSession(surl, sekey, "")
        }.fold(
            onSuccess = { Result.success(it) },
            onFailure = { Result.failure(it) }
        )

    override suspend fun listFiles(session: ShareSession, dirFid: String, cookie: String): Result<List<ShareFile>> =
        runCatching {
            val sekey = session.stoken.ifBlank { sekeys[session.shareId] ?: "" }
            // 顶层 dirFid 为空/"/"；子目录 dirFid 为目录 path（如 /folder）
            val all = mutableListOf<ShareFile>()
            var page = 1
            var result: com.yunx.app.data.network.BaiduShareList
            do {
                result = api.listShare(session.shareId, sekey, dirFid, cookie, page)
                all += result.files
                page++
            } while (result.files.size == 100 && page <= 100)
            // 缓存 share_id/uk（转存需要）
            shareInfos[session.shareId] = result.shareId to result.uk
            all
        }.fold(
            onSuccess = { Result.success(it) },
            onFailure = { Result.failure(it) }
        )

    override suspend fun ensureTempDir(cookie: String): Result<String> = runCatching {
        val dir = "/${BaiduConstants.TEMP_DIR_NAME}"
        // 已存在则直接复用；不存在则创建（web UA 已修正）；创建失败回退根目录（鲁棒性）
        val exists = runCatching { api.listDir("/", cookie).any { it == dir } }.getOrDefault(false)
        val ok = exists || api.createDir(dir, cookie)
        if (ok) dir else "/"
    }.fold(
        onSuccess = { Result.success(it) },
        onFailure = { Result.failure(it) }
    )

    override suspend fun transferFile(
        session: ShareSession,
        file: ShareFile,
        toDirFid: String,
        cookie: String,
        skipSpaceCheck: Boolean
    ): Result<String> = runCatching {
        // 转存前置空间校验：空间不足直接抛出，不再走后面的转存（避免被误报「转存超时」）；
        // 批量入口已做过整批预算校验时跳过（避免同一批多次查配额）
        if (!skipSpaceCheck) checkSpace(file, cookie)
        val (shareId, uk) = requireShareInfo(session, cookie)
        val result = api.transfer(shareId, uk, session.stoken, file.fid, toDirFid, cookie)
        result.fsId
    }.fold(
        onSuccess = { Result.success(it) },
        onFailure = { Result.failure(it) }
    )

    /** 批量转存 / 批量下载前的整批空间预算校验：只查一次配额，不足时抛出（百度转存占用目标账号空间） */
    override suspend fun ensureBatchSpace(sizes: List<Long>, credential: String): Boolean =
        TransferSpaceGuard.ensureEnoughSpaceForBatch(sizes, "百度") { api.getQuota(credential) }

    /** 转存前置空间校验：空间不足直接抛出，不再走后面的转存与轮询 */
    private suspend fun checkSpace(file: ShareFile, cookie: String) =
        TransferSpaceGuard.ensureEnoughSpace(file.fsize.takeIf { it > 0 }, "百度") { api.getQuota(cookie) }

    /** 个人网盘文件直链（filemetas） */
    override suspend fun getDownloadLink(fid: String, cookie: String): Result<DownloadLink> = runCatching {
        val dlink = api.fileMetasDlink(fid, cookie)
        DownloadLink(fid = fid, filename = "", downloadUrl = dlink, size = 0L)
    }.fold(
        onSuccess = { Result.success(it) },
        onFailure = { Result.failure(it) }
    )

    /** 百度取直链：转存临时目录（自动创建，失败回退根目录）→ locatedownload 拿 appall 高速链 → 立即删除临时转存（失败不阻断） */
    override suspend fun getShareDownloadLink(
        session: ShareSession,
        file: ShareFile,
        cookie: String,
        skipSpaceCheck: Boolean
    ): Result<DownloadLink> = runCatching {
        // 下载路径同样要先转存（占用空间）：单文件下载在此校验；批量时由调用方整批校验后跳过
        if (!skipSpaceCheck) checkSpace(file, cookie)
        val (shareId, uk) = requireShareInfo(session, cookie)
        val dirPath = ensureTempDir(cookie).getOrThrow()
        val transferred = api.transfer(shareId, uk, session.stoken, file.fid, dirPath, cookie)
        // 在删除临时文件前取完整下载快照，P2P 模式还需要 fsId、文件大小与加密密钥。
        val dlink = api.locateDownload(
            path = transferred.path,
            fsId = transferred.fsId,
            cookie = cookie,
            fileSize = file.fsize
        )
        // 已取得的 CDN 链与 P2P 快照不依赖转存文件存活。
        deleteTransferred(transferred.path, cookie)
        DownloadLink(
            fid = transferred.fsId,
            filename = file.fname,
            downloadUrl = dlink,
            size = file.fsize
        )
    }.fold(
        onSuccess = { Result.success(it) },
        onFailure = { Result.failure(it) }
    )

    /** 删除转存文件（失败不阻断）；转存在临时目录时，删完文件后尝试删空目录 */
    private suspend fun deleteTransferred(path: String, cookie: String) {
        runCatching { api.deleteFile(path, cookie) }
        val tempDir = "/${BaiduConstants.TEMP_DIR_NAME}"
        if (path.startsWith("$tempDir/")) {
            runCatching { api.deleteFile(tempDir, cookie) }
        }
    }

    /** 取 share_id/uk：优先用列表接口缓存的，否则先列一次根目录 */
    private suspend fun requireShareInfo(session: ShareSession, cookie: String): Pair<String, String> {
        shareInfos[session.shareId]?.let { return it }
        val sekey = session.stoken.ifBlank { sekeys[session.shareId] ?: "" }
        val result = api.listShare(session.shareId, sekey, "/", cookie)
        val info = result.shareId to result.uk
        shareInfos[session.shareId] = info
        return info
    }
}
