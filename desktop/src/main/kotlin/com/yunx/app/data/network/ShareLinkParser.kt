package com.yunx.app.data.network

/** 网盘平台 */
enum class SharePlatform { QUARK, UC, XUNLEI, BAIDU, C139, PAN123, PAN115, GUANGYA, ILANZOU, LANZOU, GITHUB }

/**
 * 解析结果：share_id + 提取码 + 平台。
 */
data class ParsedShare(
    val shareId: String,
    val pwd: String?,
    val platform: SharePlatform
)

/** 文案中的一条分享链接（保留原始 URL，供批量解析 / 历史记录使用）。 */
data class ShareLinkMatch(
    val url: String,
    val share: ParsedShare
)

/**
 * 从分享链接或整段分享文案中提取 share_id 与提取码。
 * 支持：pan.quark.cn/s/xxx（夸克）、drive.uc.cn/s/xxx（UC）、pan.xunlei.com/s/xxx（迅雷）
 */
object ShareLinkParser {

    private val urlRegex = Regex("""https?://[^\s]+""")
    private val quarkShareIdRegex = Regex("""pan\.quark\.cn/s/([A-Za-z0-9]+)""", RegexOption.IGNORE_CASE)
    private val ucShareIdRegex = Regex("""drive\.uc\.cn/s/([A-Za-z0-9]+)""", RegexOption.IGNORE_CASE)
    private val xunleiShareIdRegex = Regex("""pan\.xunlei\.com/s/([A-Za-z0-9_-]+)""", RegexOption.IGNORE_CASE)
    private val baiduShareIdRegex = Regex("""pan\.baidu\.com/s/(1[A-Za-z0-9_-]+)""", RegexOption.IGNORE_CASE)
    private val c139ShareIdRegex = Regex("""yun\.139\.com/shareweb/.*?/w/i/([A-Za-z0-9_-]+)""", RegexOption.IGNORE_CASE)
    // 123 云盘分享链接（抓包 + alist 实践综合，文档 §4.1）：
    // - https://www.123pan.com/s/<ShareKey> / https://www.123865.com/s/<ShareKey>
    // - https://<UID>.share.123pan.cn/123pan/<ShareKey>
    // - https://www.123pan.cn/api/srr?sk=<ShareKey>&st=s
    // ShareKey 形态：含一个中划线、两端为字母数字，如 2785Vv-T4Ded
    private val pan123ShareIdRegex = Regex("""123(?:865|pan)\.(?:com|cn)/s/([A-Za-z0-9]+-[A-Za-z0-9]+)""", RegexOption.IGNORE_CASE)
    private val pan123ShareSubRegex = Regex("""share\.123pan\.cn/123pan/([A-Za-z0-9-]+)""", RegexOption.IGNORE_CASE)
    private val pan123SrrRegex = Regex("""api/srr\?sk=([A-Za-z0-9-]+)""", RegexOption.IGNORE_CASE)
    // 115 网盘分享链接：
    // - https://115.com/s/<share_code> / https://115cdn.com/s/<share_code> / https://115rc.com/s/<share_code>
    // - share_code 为 11 位 sw 开头；提取码在 ?password=xxxx 或文案「访问码：xxxx」
    // - 口令形式 https://115.com/sws8lxs36jf-n307/（code-提取码）
    private val pan115ShareIdRegex = Regex("""115(?:cdn|rc)?\.com/s/(sw[A-Za-z0-9]+)""", RegexOption.IGNORE_CASE)
    private val pan115CommandRegex = Regex("""115(?:cdn|rc)?\.com/(sw[A-Za-z0-9]{8,})-([A-Za-z0-9]{4,8})""", RegexOption.IGNORE_CASE)
    private val pwdIn115UrlRegex = Regex("""[?&]password=([A-Za-z0-9]+)""")
    // 光鸭云盘分享链接：https://www.guangyapan.com/s/{shareId}；shareId 字符集 [A-Za-z0-9_-]+
    private val guangyaShareIdRegex = Regex("""guangyapan\.com/s/([A-Za-z0-9_-]+)""", RegexOption.IGNORE_CASE)
    // 蓝奏云优享版分享链接：{ilanzou host}/s/{id}。先于蓝奏云判断，避免与蓝奏云混淆
    // （该平台无分享解析接口，解析入口会给出明确提示）。
    private val ilanzouShareIdRegex =
        Regex("""(?:^|[/.])ilanzou\.(?:com|net|org|cn)/s/([A-Za-z0-9_-]+)""", RegexOption.IGNORE_CASE)
    // 蓝奏云分享链接：域名族 lanzou* / lan[zs]o[ux]，后缀 com/net/org/cn；shareId 为路径末段。
    // 前置边界 (?:^|[/.]) 不可省：否则 "www.ilanzou.com" 里的 "lanzou.com" 会被误判为蓝奏云。
    private val lanzouShareIdRegex =
        Regex("""(?:^|[/.])(?:lanzou[a-z0-9-]*|lan[zs]o[ux])\.(?:com|net|org|cn)/([A-Za-z0-9]+)""", RegexOption.IGNORE_CASE)
    // 蓝奏云提取码参数：?pwd= / ?pass= / ?passcode= / ?password=
    private val pwdInLanzouUrlRegex = Regex("""[?&](?:pwd|pass|passcode|password)=([A-Za-z0-9]+)""", RegexOption.IGNORE_CASE)
    private val pwdInUrlRegex = Regex("""[?&]pwd=([A-Za-z0-9]+)""")
    private val pwdInTextRegex = Regex("""(?:提取码|访问码|密码)[：:]\s*([A-Za-z0-9]{4,8})""")
    private val trailingChars = charArrayOf('。', '，', ',', '；', ';', ')', ']', '}', '"', '\'')

    fun parse(text: String): ParsedShare? = parseAll(text).firstOrNull()?.share

    /**
     * 解析文本中的**全部**网盘分享链接（按出现顺序，重复 URL 只保留首条）。
     *
     * 提取码来源优先级：URL 自带参数 > 文案中离该链接最近的一条「提取码 / 访问码 / 密码」。
     * 单链接时沿用旧行为（取文案里第一条提取码），避免改变既有解析结果。
     */
    fun parseAll(text: String): List<ShareLinkMatch> {
        val urlMatches = urlRegex.findAll(text).toList()
        if (urlMatches.isEmpty()) return emptyList()

        val urls = LinkedHashMap<String, IntRange>()
        for (match in urlMatches) {
            val url = match.value.trimEnd(*trailingChars)
            if (url.isNotEmpty() && !urls.containsKey(url)) urls[url] = match.range
        }
        if (urls.isEmpty()) return emptyList()

        val pwdByUrl = assignPwdByNearestUrl(text, urls)
        return urls.entries.mapNotNull { (url, _) ->
            val share = parseUrl(url, pwdByUrl[url]) ?: return@mapNotNull null
            ShareLinkMatch(url = url, share = share)
        }
    }

    /**
     * 为每条链接选择提取码。多链接时按「字符距离最近」分配，避免一条文案里多个提取码时
     * 全部串到第一条链接上；单链接保持旧逻辑（第一条提取码）。
     */
    private fun assignPwdByNearestUrl(text: String, urls: Map<String, IntRange>): Map<String, String> {
        val pwdMatches = pwdInTextRegex.findAll(text).toList()
        if (pwdMatches.isEmpty()) return emptyMap()
        if (urls.size == 1) {
            return mapOf(urls.keys.first() to pwdMatches.first().groupValues[1])
        }

        data class Candidate(val url: String, val pwdMatch: MatchResult, val distance: Int)

        val candidates = urls.entries.flatMap { (url, range) ->
            pwdMatches.map { pwd ->
                Candidate(url, pwd, rangeDistance(range, pwd.range))
            }
        }.sortedBy { it.distance }

        val assigned = mutableMapOf<String, String>()
        val usedPwdRanges = mutableSetOf<IntRange>()
        for (candidate in candidates) {
            if (assigned.containsKey(candidate.url)) continue
            if (candidate.pwdMatch.range in usedPwdRanges) continue
            assigned[candidate.url] = candidate.pwdMatch.groupValues[1]
            usedPwdRanges += candidate.pwdMatch.range
        }
        return assigned
    }

    private fun rangeDistance(a: IntRange, b: IntRange): Int = when {
        a.last < b.first -> b.first - a.last
        b.last < a.first -> a.first - b.last
        else -> 0
    }

    private fun parseUrl(url: String, pwdFromText: String?): ParsedShare? {
        // 夸克链接
        quarkShareIdRegex.find(url)?.groupValues?.getOrNull(1)?.let { sid ->
            val pwd = pwdInUrlRegex.find(url)?.groupValues?.getOrNull(1) ?: pwdFromText
            return ParsedShare(shareId = sid, pwd = pwd, platform = SharePlatform.QUARK)
        }
        // UC 链接
        ucShareIdRegex.find(url)?.groupValues?.getOrNull(1)?.let { sid ->
            val pwd = pwdInUrlRegex.find(url)?.groupValues?.getOrNull(1) ?: pwdFromText
            return ParsedShare(shareId = sid, pwd = pwd, platform = SharePlatform.UC)
        }
        // 迅雷链接
        xunleiShareIdRegex.find(url)?.groupValues?.getOrNull(1)?.let { sid ->
            val pwd = pwdInUrlRegex.find(url)?.groupValues?.getOrNull(1) ?: pwdFromText
            return ParsedShare(shareId = sid, pwd = pwd, platform = SharePlatform.XUNLEI)
        }
        // 百度链接：https://pan.baidu.com/s/1xxxxx?pwd=xxxx
        baiduShareIdRegex.find(url)?.groupValues?.getOrNull(1)?.let { sid ->
            // 百度 surl 不包含开头的 "1"（verify/list 接口用 1 后面的部分）
            val surl = sid.removePrefix("1")
            val pwd = pwdInUrlRegex.find(url)?.groupValues?.getOrNull(1) ?: pwdFromText
            return ParsedShare(shareId = surl, pwd = pwd, platform = SharePlatform.BAIDU)
        }
        // 139（和彩云）链接：https://yun.139.com/shareweb/#/w/i/{linkID} 提取码 xxxx
        c139ShareIdRegex.find(url)?.groupValues?.getOrNull(1)?.let { sid ->
            val pwd = pwdInUrlRegex.find(url)?.groupValues?.getOrNull(1) ?: pwdFromText
            return ParsedShare(shareId = sid, pwd = pwd, platform = SharePlatform.C139)
        }
        // 123 云盘链接（3 种形态，按优先级匹配）
        pan123ShareIdRegex.find(url)?.groupValues?.getOrNull(1)?.let { sid ->
            val pwd = pwdInUrlRegex.find(url)?.groupValues?.getOrNull(1) ?: pwdFromText
            return ParsedShare(shareId = sid, pwd = pwd, platform = SharePlatform.PAN123)
        }
        pan123ShareSubRegex.find(url)?.groupValues?.getOrNull(1)?.let { sid ->
            val pwd = pwdInUrlRegex.find(url)?.groupValues?.getOrNull(1) ?: pwdFromText
            return ParsedShare(shareId = sid, pwd = pwd, platform = SharePlatform.PAN123)
        }
        pan123SrrRegex.find(url)?.groupValues?.getOrNull(1)?.let { sid ->
            val pwd = pwdInUrlRegex.find(url)?.groupValues?.getOrNull(1) ?: pwdFromText
            return ParsedShare(shareId = sid, pwd = pwd, platform = SharePlatform.PAN123)
        }
        // 115 网盘链接：提取码可能只在文案的「访问码：xxxx」里（链接不带 password 不代表不需要码）
        pan115ShareIdRegex.find(url)?.groupValues?.getOrNull(1)?.let { sid ->
            val pwd = pwdIn115UrlRegex.find(url)?.groupValues?.getOrNull(1) ?: pwdFromText
            return ParsedShare(shareId = sid, pwd = pwd, platform = SharePlatform.PAN115)
        }
        // 115 口令形式：https://115.com/sws8lxs36jf-n307/
        pan115CommandRegex.find(url)?.let { match ->
            return ParsedShare(
                shareId = match.groupValues[1],
                pwd = match.groupValues[2],
                platform = SharePlatform.PAN115
            )
        }
        // 光鸭云盘链接：https://www.guangyapan.com/s/{shareId}（提取码可由 ?pwd= 或文案指定）
        guangyaShareIdRegex.find(url)?.groupValues?.getOrNull(1)?.let { sid ->
            val pwd = pwdInUrlRegex.find(url)?.groupValues?.getOrNull(1) ?: pwdFromText
            return ParsedShare(shareId = sid, pwd = pwd, platform = SharePlatform.GUANGYA)
        }
        // 蓝奏云优享版链接（先于蓝奏云判断，避免混淆）
        ilanzouShareIdRegex.find(url)?.groupValues?.getOrNull(1)?.let { sid ->
            val pwd = pwdInLanzouUrlRegex.find(url)?.groupValues?.getOrNull(1) ?: pwdFromText
            return ParsedShare(shareId = sid, pwd = pwd, platform = SharePlatform.ILANZOU)
        }
        // 蓝奏云链接（提取码参数 pwd/pass/passcode/password，或文案）
        lanzouShareIdRegex.find(url)?.groupValues?.getOrNull(1)?.let { sid ->
            val pwd = pwdInLanzouUrlRegex.find(url)?.groupValues?.getOrNull(1) ?: pwdFromText
            return ParsedShare(shareId = sid, pwd = pwd, platform = SharePlatform.LANZOU)
        }
        return null
    }
}
