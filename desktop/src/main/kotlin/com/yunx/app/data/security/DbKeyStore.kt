package com.yunx.app.data.security

import com.yunx.app.util.Log
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.Crypt32Util
import com.sun.jna.platform.win32.WinReg
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 数据库加密等级（设置 → 储存目录 → 数据库加密等级）。
 *
 * | 档位 | 库文件 | 凭证列 | 密钥文件 |
 * | --- | --- | --- | --- |
 * | [LOOSE] 宽松 | 明文 SQLite | AES-256-GCM | **明文**（现状，换机器/便携用这档） |
 * | [NORMAL] 普通（默认） | 明文 SQLite | AES-256-GCM | 用**本机派生密钥**包裹（绑定本机） |
 * | [STRICT] 较为严格 | 明文 SQLite | AES-256-GCM | 用 **Windows DPAPI** 包裹（绑定当前 Windows 用户） |
 * | [STRICTEST] 严格 | 整体加密（SQLCipher） | 同上 | DPAPI —— **尚未支持**，见 [isSupported] |
 *
 * ★ 关键取舍：前三档保护的都是**密钥**，库里的凭证列本来就是 AES 密文，所以**切档只需重新包裹 32 字节密钥**，
 *   不碰库里任何数据 —— 秒级完成、零数据风险。库里其它元数据（任务文件名 / URL / 保存路径）这三档都是明文。
 */
enum class DbEncryptionLevel(val id: String, val label: String) {
    LOOSE("loose", "宽松"),
    NORMAL("normal", "普通"),
    STRICT("strict", "较为严格"),
    STRICTEST("strictest", "严格");

    /** 该档位在当前系统上是否可用（严格档需要 SQLCipher 原生库，暂未支持；较为严格档依赖 Windows DPAPI） */
    val isSupported: Boolean
        get() = this != STRICTEST && (this != STRICT || System.getProperty("os.name").orEmpty().lowercase().contains("win"))

    /** 密钥是否绑定本机（换机器后凭证将无法解密，需重新登录） */
    val isMachineBound: Boolean get() = this == NORMAL || this == STRICT

    companion object {
        fun fromId(id: String?): DbEncryptionLevel =
            entries.firstOrNull { it.id == id } ?: NORMAL
    }
}

/**
 * 密钥保护：把 32 字节的凭证主密钥按当前档位包裹后落盘，并按档位解包。
 *
 * 封装格式（全部以 `yunx:key:` 开头，便于与老版本的「裸 32 字节」区分）：
 * - 宽松：[KEY_FILE] 里直接是 32 字节原始密钥（与历史版本一致，天然兼容）；
 * - 普通：`yunx:key:mk:v1:<salt b64>:<iv b64>:<ct b64>`，包裹密钥 = PBKDF2(机器指纹, salt)；
 * - 较为严格：`yunx:key:dpapi:v1:<blob b64>`，包裹密钥交给 Windows DPAPI。
 */
object DbKeyStore {

    const val KEY_FILE = "credential.key"

    private const val PREFIX = "yunx:key:"
    private const val TAG = "YunX-KeyStore"

    /** PBKDF2 迭代次数：与认证备份同一量级（21 万次），肉眼无感、暴力破解足够贵 */
    private const val PBKDF2_ITERATIONS = 210_000
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val GCM_TAG_BITS = 128

    /** 密钥文件位置：数据库目录优先，其次数据根目录（只读回退，兼容老版本） */
    fun keyFile(dbDir: File, legacyDataDir: File = dbDir): File {
        val primary = File(dbDir, KEY_FILE)
        if (primary.exists()) return primary
        val legacy = File(legacyDataDir, KEY_FILE)
        return if (legacy.exists() && legacy.canonicalPath != primary.canonicalPath) legacy else primary
    }

    /** 该文件里的密钥是否是「包裹过的」（老版本是裸字节） */
    fun isWrapped(file: File): Boolean = runCatching {
        val bytes = file.readBytes()
        bytes.size < 4096 && String(bytes, Charsets.UTF_8).startsWith(PREFIX)
    }.getOrDefault(false)

    /**
     * 读取主密钥（必要时按 [level] 解包）。
     * 文件不存在时生成一把新密钥并按 [level] 包裹落盘。
     */
    fun loadOrCreate(file: File, level: DbEncryptionLevel): ByteArray {
        if (file.exists()) {
            val raw = file.readBytes()
            if (!isWrapped(file)) {
                require(raw.size == 32) { "invalid credential key file" }
                return raw
            }
            return unwrap(String(raw, Charsets.UTF_8), level)
        }
        val fresh = ByteArray(32).also { SecureRandom().nextBytes(it) }
        file.parentFile?.mkdirs()
        writeWrapAtomically(file, fresh, level)
        return fresh
    }

    /**
     * 切换加密等级：解包出主密钥 → 按新档位重新包裹 → 原子替换。
     * ★ 先解包成功再写，任何失败都保持原文件不动（宁可没切成，也不能把密钥写坏）。
     */
    fun switchLevel(file: File, from: DbEncryptionLevel, to: DbEncryptionLevel) {
        require(to.isSupported) { "该加密等级暂未支持：${to.label}" }
        val key = loadOrCreate(file, from)
        writeWrapAtomically(file, key, to)
    }

    /** 按档位解包；失败时抛出（调用方据此提示并保持现档位） */
    fun unwrap(wrapped: String, level: DbEncryptionLevel): ByteArray {
        val body = wrapped.removePrefix(PREFIX)
        val actual = when {
            body.startsWith("mk:v1:") -> DbEncryptionLevel.NORMAL
            body.startsWith("dpapi:v1:") -> DbEncryptionLevel.STRICT
            else -> throw IllegalStateException("未知的密钥封装格式")
        }
        // 封装格式自带标识，按格式解包即可；与配置档位不一致时只记一条（可能是手动改过偏好）
        if (actual != level) {
            Log.w(TAG, "密钥封装格式（${actual.label}）与配置档位（${level.label}）不一致，按文件实际格式解包")
        }
        return when (actual) {
            DbEncryptionLevel.NORMAL -> unwrapMachineKey(body.removePrefix("mk:v1:"))
            else -> unwrapDpapi(body.removePrefix("dpapi:v1:"))
        }.also {
            require(it.size == 32) { "invalid credential key length" }
        }
    }

    // ---------- 具体封装实现 ----------

    /** 按档位包裹并落盘：先写临时文件 → 立刻读回验证 → 原子替换 */
    private fun writeWrapAtomically(file: File, key: ByteArray, level: DbEncryptionLevel) {
        val content = when (level) {
            DbEncryptionLevel.LOOSE -> key
            DbEncryptionLevel.NORMAL -> wrapMachineKey(key).toByteArray(Charsets.UTF_8)
            DbEncryptionLevel.STRICT -> wrapDpapi(key).toByteArray(Charsets.UTF_8)
            DbEncryptionLevel.STRICTEST -> throw IllegalStateException("严格档需要 SQLCipher 支持，暂未实现")
        }
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeBytes(content)
        // 读回验证：确保这把密钥确实能解开（写坏了就立刻发现，而不是下次启动才发现）
        if (level != DbEncryptionLevel.LOOSE) {
            val check = unwrap(String(tmp.readBytes(), Charsets.UTF_8), level)
            require(check.contentEquals(key)) { "密钥回读校验失败" }
        }
        runCatching {
            Files.move(
                tmp.toPath(), file.toPath(),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE
            )
        }.getOrElse {
            // 少数文件系统不支持原子移动：退化为普通替换
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        Log.i(TAG, "凭证密钥已按「${level.label}」档写入：${file.name}")
    }

    /** 普通档：机器派生密钥 + AES-GCM */
    private fun wrapMachineKey(key: ByteArray): String {
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(IV_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, machineKey(salt), GCMParameterSpec(GCM_TAG_BITS, iv))
        val ct = cipher.doFinal(key)
        val b64 = Base64.getEncoder()
        return "$PREFIX" + "mk:v1:${b64.encodeToString(salt)}:${b64.encodeToString(iv)}:${b64.encodeToString(ct)}"
    }

    private fun unwrapMachineKey(body: String): ByteArray {
        val parts = body.split(":")
        require(parts.size == 3) { "malformed machine-key blob" }
        val b64 = Base64.getDecoder()
        val salt = b64.decode(parts[0])
        val iv = b64.decode(parts[1])
        val ct = b64.decode(parts[2])
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, machineKey(salt), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ct)
    }

    /**
     * 机器派生密钥：PBKDF2(机器指纹, salt)。
     *
     * 指纹只用**开机即稳定、且不随账号改名而变**的三项：Windows MachineGuid（注册表，机器唯一）+ OS + 架构。
     * ★ 刻意不掺用户名/计算机名：用户改一次计算机名就会解不开自己的凭证，代价远大于收益。
     * 读不到 MachineGuid 时退回「主机名 + 用户名」（仍有绑定效果，但改名会失效，日志会记一条）。
     */
    private fun machineKey(salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(machineFingerprint().toCharArray(), salt, PBKDF2_ITERATIONS, 256)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return SecretKeySpec(bytes, "AES")
    }

    /**
     * 机器指纹暂时读不到：**属于「暂时不可用」，调用方绝不能据此更换主密钥**。
     *
     * ★ 旧实现读不到 MachineGuid 就静默退回「主机名 + 用户名」——那会派生出一把**不同的**密钥，
     *   偶尔一次读取失败就会让解包报 AEADBadTag，被误判成「密钥彻底损坏」→ 换密钥 → 凭证全丢。
     *   MachineGuid 在 Windows 上始终存在，读不到只可能是暂时性故障，因此宁可本次失败也不换指纹。
     */
    class MachineFingerprintUnavailable(message: String) : IllegalStateException(message)

    /** 机器指纹（可读性无关，只求稳定唯一） */
    private fun machineFingerprint(): String = buildString {
        append("yunx-desktop|")
        append(System.getProperty("os.name").orEmpty()).append('|')
        append(System.getProperty("os.arch").orEmpty()).append('|')
        append(
            machineGuid() ?: throw MachineFingerprintUnavailable(
                "暂时读不到 MachineGuid，本次不做密钥解包（下次会重试）"
            )
        )
    }

    /**
     * 机器唯一标识：Windows 读 MachineGuid（HKLM\SOFTWARE\Microsoft\Cryptography，普通用户可读）；
     * macOS 读 IOPlatformUUID（`ioreg`，硬件级、不随改名变化）。其它平台返回 null（行为同改动前）。
     */
    private fun machineGuid(): String? {
        if (isMac()) return macPlatformUuid()
        return runCatching {
            Advapi32Util.registryGetStringValue(
                WinReg.HKEY_LOCAL_MACHINE, "SOFTWARE\\Microsoft\\Cryptography", "MachineGuid"
            ).takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun isMac(): Boolean =
        System.getProperty("os.name").orEmpty().lowercase().let { it.contains("mac") || it.contains("darwin") }

    /** macOS IOPlatformUUID；读不到返回 null（调用方按「暂时不可用」处理，不会换密钥） */
    private fun macPlatformUuid(): String? = runCatching {
        val p = ProcessBuilder("/usr/sbin/ioreg", "-rd1", "-c", "IOPlatformExpertDevice")
            .redirectErrorStream(true)
            .start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        Regex("\"IOPlatformUUID\"\\s*=\\s*\"([^\"]+)\"").find(out)?.groupValues?.getOrNull(1)
    }.getOrNull()?.takeIf { it.isNotBlank() }

    /** 较为严格档：Windows DPAPI（CryptProtectData，绑定当前登录用户） */
    private fun wrapDpapi(key: ByteArray): String {
        require(isWindows()) { "DPAPI 仅在 Windows 上可用" }
        val blob = Crypt32Util.cryptProtectData(key)
        return "$PREFIX" + "dpapi:v1:" + Base64.getEncoder().encodeToString(blob)
    }

    private fun unwrapDpapi(body: String): ByteArray {
        require(isWindows()) { "DPAPI 仅在 Windows 上可用" }
        val blob = Base64.getDecoder().decode(body)
        return Crypt32Util.cryptUnprotectData(blob)
    }

    private fun isWindows(): Boolean =
        System.getProperty("os.name").orEmpty().lowercase().contains("win")
}
