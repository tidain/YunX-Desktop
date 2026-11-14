package com.yunx.app.data.network

/**
 * 蓝奏云优享版常量（对照上游 Android 版逐项对齐）。
 *
 * 认证参数 appToken + uuid 走 URL query；appToken 需特殊编码（标准 query 编码后把 %3A 还原成冒号）。
 * 个人盘接口在 `apis.ilanzou.com`，分享页走后端 `apix.ilanzou.com`，两者不是同一主机。
 */
object ILanzouConstants {

    const val API_BASE = "https://apis.ilanzou.com"
    const val WEB_SITE = "https://www.ilanzou.com"

    /** 下载请求头（文档 §4.1） */
    const val DOWNLOAD_REFERER = "$WEB_SITE/"
    const val DOWNLOAD_ORIGIN = WEB_SITE

    const val WEB_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    const val DEV_TYPE = "6"
    const val DEV_MODEL = "chrome"
    const val DEV_VERSION = "125"

    /** 页大小固定 60（文档 §3.1） */
    const val PAGE_SIZE = 60

    // ---------- 端点 ----------
    const val GET_UUID = "$API_BASE/unproved/getUuid"
    const val LOGIN = "$API_BASE/unproved/login"
    const val ACCOUNT_MAP = "$API_BASE/proved/user/account/map"
    const val FILE_LIST = "$API_BASE/proved/record/file/list"
    const val FOLDER_SAVE = "$API_BASE/proved/file/folder/save"
    const val FOLDER_EDIT = "$API_BASE/proved/file/folder/edit"
    const val FILE_EDIT = "$API_BASE/proved/file/edit"
    const val FOLDER_MOVE = "$API_BASE/proved/file/folder/move"
    const val FILE_DELETE = "$API_BASE/proved/file/delete"
    const val FILE_REDIRECT = "$API_BASE/unproved/file/redirect"

    // ---------- 分享（分享页走后端 apix，与个人盘 apis 不同主机） ----------
    /** 分享业务主机（分享页 JS config.apiBaseURL = https://apix.ilanzou.com/） */
    const val SHARE_API_BASE = "https://apix.ilanzou.com"
    const val SHARE_LIST = "$SHARE_API_BASE/unproved/share/list"
    const val SHARE_REDIRECT = "$SHARE_API_BASE/unproved/file/redirect"
    /** 分享页 Referer（分享页地址为 {WEB_SITE}/s/<shareId>） */
    const val SHARE_REFERER_PREFIX = "$WEB_SITE/s/"
    /** 分享页 devModel 与网页一致（首字母大写） */
    const val SHARE_DEV_MODEL = "Chrome"
    /** 分享转存（需登录）：/proved/file/transfer + 轮询 /proved/file/transfer/num */
    const val FILE_TRANSFER = "$API_BASE/proved/file/transfer"
    const val FILE_TRANSFER_NUM = "$API_BASE/proved/file/transfer/num"

    /** 根目录 id */
    const val ROOT_FOLDER_ID = "0"

    /** AES 密钥（文档 §6.1） */
    const val AES_KEY = "lanZouY-disk-app"

    /** 文件夹 id 前缀 d: / 文件 id 前缀 f:（文档 §3.1 ID 规范） */
    const val FOLDER_PREFIX = "d:"
    const val FILE_PREFIX = "f:"
    // 原生上传协议
    const val UPLOAD_TOKEN = "$API_BASE/proved/7n/getUpToken"
    const val QINIU_UPLOAD = "https://upload.qiniup.com"
    const val QINIU_BUCKET = "wpanstore-lanzou"
    const val UPLOAD_RESULT = "$API_BASE/unproved/7n/results"
}
