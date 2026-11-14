package com.yunx.app.data.network

/**
 * 蓝奏云常量（对照上游 Android 版逐项对齐）。
 *
 * 蓝奏个人盘登录态在 `pc.woozooo.com` 域，接口与下载节点都用同一套浏览器 UA；
 * 原生账号密码登录走 `accounts.woozooo.com`（含 acw_sc__v2 人机校验，见 [LanzouCrypto]）。
 */
object LanzouConstants {

    /** 浏览器 UA（个人盘接口与下载节点都要求与网页一致，非浏览器 UA 易被风控） */
    const val WEB_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    // ---------- 个人盘 ----------

    /** 个人盘登录入口 */
    const val MYDISK_URL = "https://pc.woozooo.com/mydisk.php"

    /** 个人盘文件页（提取 uid / vei 的页面） */
    const val MYDISK_FILES_URL = "https://pc.woozooo.com/mydisk.php?item=files&action=index"

    /** 个人盘操作接口（task 系列，需 uid/vei 查询参数与登录 Cookie） */
    const val DOUPLOAD_URL = "https://pc.woozooo.com/doupload.php"

    /** 个人盘 Origin */
    const val ORIGIN = "https://pc.woozooo.com"

    /**
     * 原生账号密码登录（官网登录页 accounts.woozooo.com）：
     * POST /accounts.php {task=uselogin, username, password, ref=pc.woozooo.com} → {zt, msgs}；
     * 无挑战 Cookie 时返回 JS 人机校验页（arg1 + acw_sc__v2），需本地计算 Cookie 后重试。
     */
    const val ACCOUNTS_URL = "https://accounts.woozooo.com/accounts.php"
    const val ACCOUNTS_ORIGIN = "https://accounts.woozooo.com"
    const val LOGIN_REF = "pc.woozooo.com"

    /** 个人盘根目录 folder_id（-1 表根） */
    const val ROOT_FOLDER_ID = "-1"

    /** 分页上限（防止接口异常时死循环） */
    const val MAX_FILE_PAGES = 1000
    const val MAX_FOLDER_PAGES = 500

    /** 下载节点注入的 Cookie（缺它下载节点拒绝直链） */
    const val DOWN_IP_COOKIE = "down_ip=1"

    /** 列表 fId 前缀：文件夹 `d:` / 文件 `f:`（个人盘用 folder_id 与 file_id，两者可能撞值，加前缀区分） */
    const val FOLDER_PREFIX = "d:"
    const val FILE_PREFIX = "f:"

    /** 域名族：lanzou*、lan[zs]o[ux]，后缀 com/net/org/cn */
    private val HOST_REGEX =
        Regex("""^(?:[a-z0-9-]+\.)*(?:lanzou[a-z]?|lan[zs]o[ux])\.(?:com|net|org|cn)$""", RegexOption.IGNORE_CASE)

    /** 是否为蓝奏云域名 */
    fun isLanzouHost(host: String): Boolean = HOST_REGEX.matches(host)

    /** 将分享链接规范为 https、去掉 fragment、去掉结尾多余斜杠 */
    fun normalizeShareUrl(url: String): String {
        var u = url.trim()
        u = u.replaceFirst(Regex("^http://", RegexOption.IGNORE_CASE), "https://")
        u = u.substringBefore('#')
        return u.trimEnd('/')
    }
    // 原生上传协议
    const val HTML5UP_URL = "https://pc.woozooo.com/html5up.php"
}
