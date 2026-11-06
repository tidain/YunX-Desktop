package com.yunx.app.data.motrix

import com.yunx.app.util.Log
import org.json.JSONObject
import java.io.File

/**
 * Motrix 的 aria2 RPC 连接参数。
 *
 * Motrix 内置一个 aria2c 子进程并对外暴露 JSON-RPC（默认 `127.0.0.1:16800`）。
 * 端口与 `rpc-secret` 存放位置随 Motrix 版本变过：
 * - Motrix 2.x：`%APPDATA%\Motrix\settings.json` 的 `engine.rpcPort` / `engine.rpcSecret`
 *   （下载目录在 `app.defaultSaveDir`）；
 * - Motrix 1.x：`%APPDATA%\Motrix\system.json` 顶层的 `rpc-listen-port` / `rpc-secret`
 *   （下载目录在 `dir`）。
 *
 * 升级过 Motrix 的机器上两个文件会同时存在，旧的那个不会再被更新 —— 所以不能只看一个。
 * 设置页仍可手动覆盖（便携版、改过 APPDATA、或两个文件都没写对时用）。
 */
data class MotrixConfig(
    /** RPC 端口（aria2 rpc-listen-port） */
    val port: Int,
    /** RPC 密钥（aria2 rpc-secret）；空串 = Motrix 没设密钥，请求不带 token */
    val secret: String,
    /** Motrix 自己的下载目录（aria2 dir），YunX 未指定任务目录时跟随它 */
    val defaultDir: String,
    /** 参数来源说明（设置页显示：读到的是哪个文件 / 手动覆盖） */
    val source: String
) {
    /** JSON-RPC 端点 */
    val endpoint: String get() = "http://127.0.0.1:$port/jsonrpc"

    /** 人类可读摘要（设置页用） */
    fun describe(): String = "127.0.0.1:$port" + if (secret.isBlank()) "（未设密钥）" else "（已带密钥）"
}

/**
 * 从 Motrix 配置文件里定位 RPC 参数。
 *
 * 会按可信度顺序读两种格式（新版 `settings.json` 优先，旧版 `system.json` 兜底），
 * 并把配置文件 mtime 当作缓存键 —— 用户在 Motrix 里改了端口/密钥后，这里下一次
 * 调用就会重新读盘，不必重启 YunX。
 */
object MotrixLocator {

    /** Motrix 在 %APPDATA% 下的目录名（官方安装版） */
    private const val MOTRIX_DIR = "Motrix"

    /** Motrix 2.x 的配置文件（engine.rpcPort / engine.rpcSecret） */
    private const val SETTINGS_NAME = "settings.json"

    /** Motrix 1.x 的配置文件（顶层 rpc-listen-port / rpc-secret） */
    private const val CONFIG_NAME = "system.json"

    /** Motrix 发行版默认端口（配置文件里没写端口时的兜底） */
    const val MOTRIX_DEFAULT_PORT = 16800

    /** 进程内缓存：文件很小，但设置页/发送入口会反复问，避免每次都解析 JSON */
    @Volatile
    private var cachedCandidates: List<MotrixConfig>? = null

    /** 缓存对应的文件时间戳（null = 还没读过） */
    @Volatile
    private var cachedStamp: String? = null

    /** Motrix 配置目录（不存在 = 本机没装 Motrix，或装在非标准位置） */
    fun configDir(): File? {
        val appData = System.getenv("APPDATA")?.trim().orEmpty()
        if (appData.isNotEmpty()) {
            val dir = File(appData, MOTRIX_DIR)
            if (dir.isDirectory) return dir
        }
        // 兜底：个别用户把 APPDATA 指到别处，这里按用户目录再试一次
        val home = System.getProperty("user.home")?.trim().orEmpty()
        if (home.isNotEmpty()) {
            val dir = File(home, "AppData/Roaming/$MOTRIX_DIR")
            if (dir.isDirectory) return dir
        }
        return null
    }

    /** 配置文件路径（设置页展示用；不保证存在） */
    fun configFiles(): List<File> {
        val dir = configDir() ?: return emptyList()
        return listOf(File(dir, SETTINGS_NAME), File(dir, CONFIG_NAME)).filter { it.isFile }
    }

    /**
     * 所有能读到的候选配置，按可信度排序：新版 `settings.json` 在前，旧版 `system.json` 在后。
     *
     * 去重按（端口 + 密钥）—— 升级过 Motrix 且没改过端口的机器上两份配置可能完全一样，
     * 这时列表里只会剩一条，设置页不会出现两条一模一样的记录。
     */
    fun candidates(): List<MotrixConfig> {
        val dir = configDir() ?: return emptyList()
        val stamp = stampOf(dir)
        if (cachedStamp == stamp) return cachedCandidates ?: emptyList()
        synchronized(this) {
            if (cachedStamp == stamp) return cachedCandidates ?: emptyList()
            val list = runCatching { readCandidates(dir) }
                .onFailure { Log.w(TAG, "读取 Motrix 配置失败：${it.message}") }
                .getOrDefault(emptyList())
            cachedCandidates = list
            cachedStamp = stamp
            return list
        }
    }

    /** 默认使用的配置（= 候选列表第一条）；读不到（没装 Motrix / 文件损坏）返回 null */
    fun detect(): MotrixConfig? = candidates().firstOrNull()

    /** 丢弃缓存（设置页「重新检测」用；正常情况下 mtime 变化会自动失效） */
    fun invalidate() {
        synchronized(this) {
            cachedCandidates = null
            cachedStamp = null
        }
    }

    /** 把两个配置文件的时间戳拼成缓存键：任一文件被改写，缓存自动失效 */
    private fun stampOf(dir: File): String =
        File(dir, SETTINGS_NAME).lastModified().toString() + "/" +
            File(dir, CONFIG_NAME).lastModified().toString()

    private fun readCandidates(dir: File): List<MotrixConfig> {
        val out = mutableListOf<MotrixConfig>()
        readSettingsJson(File(dir, SETTINGS_NAME))?.let { out += it }
        readSystemJson(File(dir, CONFIG_NAME))?.let { out += it }
        return out.distinctBy { it.port to it.secret }
    }

    /**
     * Motrix 2.x：`{"engine":{"rpcPort":16800,"rpcSecret":"..."},"app":{"defaultSaveDir":"D:\\Download"}}`
     *
     * 没写密钥是允许的（Motrix 里关掉了 RPC 密钥），所以只有「端口和密钥都读不到」才算无效 ——
     * 这时让位给旧格式，避免刚装的新版把老版配置挡掉。
     */
    private fun readSettingsJson(file: File): MotrixConfig? = runCatching {
        if (!file.isFile) return null
        val text = file.readText(Charsets.UTF_8)
        if (text.isBlank()) return null
        val root = JSONObject(text)
        val engine = root.optJSONObject("engine") ?: return null
        val port = intOf(engine, "rpcPort")
        val secret = stringOf(engine, "rpcSecret")
        if (port == null && secret.isBlank()) return null
        val saveDir = root.optJSONObject("app")?.let { stringOf(it, "defaultSaveDir") }.orEmpty()
        MotrixConfig(
            port = if (port != null && port in 1..65535) port else MOTRIX_DEFAULT_PORT,
            secret = secret,
            defaultDir = saveDir,
            source = "自动读取 ${file.absolutePath}"
        )
    }.onFailure { Log.w(TAG, "解析 ${file.name} 失败：${it.message}") }.getOrNull()

    /** Motrix 1.x：`{"rpc-listen-port":16800,"rpc-secret":"...","dir":"D:\\Download"}` */
    private fun readSystemJson(file: File): MotrixConfig? = runCatching {
        if (!file.isFile) return null
        val text = file.readText(Charsets.UTF_8)
        if (text.isBlank()) return null
        val root = JSONObject(text)
        val port = intOf(root, "rpc-listen-port") ?: MOTRIX_DEFAULT_PORT
        val secret = stringOf(root, "rpc-secret")
        val saveDir = stringOf(root, "dir")
        MotrixConfig(
            port = if (port in 1..65535) port else MOTRIX_DEFAULT_PORT,
            secret = secret,
            defaultDir = saveDir,
            source = "自动读取 ${file.absolutePath}"
        )
    }.onFailure { Log.w(TAG, "解析 ${file.name} 失败：${it.message}") }.getOrNull()

    /** 取整数字段：JSON 可能写 `16800` 也可能写 `"16800"` */
    private fun intOf(obj: JSONObject, key: String): Int? {
        if (!obj.has(key)) return null
        return when (val raw = obj.opt(key)) {
            is Number -> raw.toInt()
            is String -> raw.trim().toIntOrNull()
            else -> null
        }
    }

    /** 取字符串字段；数字/布尔值转成字符串（Motrix 偶尔把端口写成数字） */
    private fun stringOf(obj: JSONObject, key: String): String {
        if (!obj.has(key)) return ""
        return when (val raw = obj.opt(key)) {
            null, JSONObject.NULL -> ""
            is String -> raw.trim()
            else -> raw.toString().trim()
        }
    }

    private const val TAG = "Motrix"
}
