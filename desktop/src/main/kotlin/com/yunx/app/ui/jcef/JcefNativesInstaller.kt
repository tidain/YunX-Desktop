package com.yunx.app.ui.jcef

import com.yunx.app.util.Log
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * JCEF 原生库的「首次使用自解包」。
 *
 * 发布包不再随带 `jcef-natives-*.jar`（其中的 tar.gz 有 120MB），而是带一份
 * `app\jcef-natives.tar.gz`（同一批文件，只是语言包裁到只剩 zh-CN）。本类负责：
 * 1. 把压缩包解到 jcefmaven 的 installDir（`<缓存>/jcef-bundle`）；
 * 2. 写 `install.lock` 与就绪标记（记录压缩包大小/时间，升级包换了自动重解）；
 * 3. 之后调用方用 `CefAppBuilder.setSkipInstallation(true)` 让 jcefmaven 跳过它自己的
 *    下载/解包步骤 —— 它不会再去找 classpath 里的原生包。
 *
 * 开发运行（`gradle run`）时原生 jar 仍在 classpath 上、也没有这个压缩包，
 * [ensureInstalled] 返回 false，行为与改动前完全一致（由 jcefmaven 自己装）。
 */
object JcefNativesInstaller {

    private const val TAG = "YunX-Jcef"

    /** 随包分发的压缩包文件名（与 portable-package.ps1 产出一致） */
    private const val ARCHIVE_NAME = "jcef-natives.tar.gz"

    /** 就绪标记：内容为解包时的「压缩包大小-修改时间」，压缩包变了就重解 */
    private const val READY_MARKER = ".yunx-natives-ready"

    /**
     * 文件清单：解包时记下每个文件的相对路径与字节数（`路径<TAB>大小`，每行一条）。
     * 启动时逐条比对，用于发现「上次解包被中断 / 目录被写坏」——这类残留会让 libcef.dll 等
     * 原生库不完整，进而触发 CEF 的 fail-fast（issue #34）。只记大小不记校验和：260MB 全量读取
     * 校验太慢，而中断 / 截断是主要损坏方式，大小足以发现。
     */
    private const val MANIFEST = ".yunx-natives-manifest"

    private const val BUFFER_SIZE = 1 shl 16

    /**
     * 确保 installDir 里有可用的 CEF 原生库。
     * @return true = 原生库已就绪（调用方应 setSkipInstallation(true)）；
     *         false = 发布包没带压缩包（开发运行）或解包失败，交给 jcefmaven 走原流程。
     */
    fun ensureInstalled(installDir: File): Boolean {
        val archive = findArchive() ?: return false
        val stamp = "${archive.length()}-${archive.lastModified()}"
        if (isReady(installDir, stamp)) return true
        return try {
            extract(archive, installDir, stamp)
            Log.i(TAG, "CEF natives extracted: ${installDir.absolutePath}")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "CEF natives extract failed (${archive.absolutePath})", t)
            false
        }
    }

    private fun isReady(installDir: File, stamp: String): Boolean {
        if (!File(installDir, "install.lock").isFile) return false
        if (!File(installDir, "build_meta.json").isFile) return false
        val marker = File(installDir, READY_MARKER)
        if (!marker.isFile || marker.readText().trim() != stamp) return false
        return manifestMatches(installDir)
    }

    /** 逐条比对清单：文件缺失或大小不符即判定目录已损坏，需要重解 */
    private fun manifestMatches(installDir: File): Boolean {
        val lines = runCatching { File(installDir, MANIFEST).readLines() }.getOrNull() ?: return false
        if (lines.isEmpty()) return false
        for (line in lines) {
            if (line.isBlank()) continue
            val tab = line.indexOf('\t')
            if (tab <= 0) return false
            val rel = line.substring(0, tab)
            val size = line.substring(tab + 1).toLongOrNull() ?: return false
            val f = File(installDir, rel)
            if (!f.isFile || f.length() != size) {
                Log.w(TAG, "CEF natives 清单不符，将重新解包: $rel")
                return false
            }
        }
        return true
    }

    /**
     * 找到随包分发的压缩包。发布包里它就在 `app\` 下：
     * - 启动器已把 `app\` 设为 java.library.path 首项；
     * - 兜底再用 `jpackage.app-path`（指向 `<根>\YunX-Desktop.exe`）/ 工作目录推算。
     */
    private fun findArchive(): File? {
        val candidates = LinkedHashSet<File>()
        System.getProperty("java.library.path").orEmpty()
            .split(File.pathSeparatorChar)
            .filter { it.isNotBlank() }
            .forEach { candidates += File(it, ARCHIVE_NAME) }
        System.getProperty("jpackage.app-path")?.let { p ->
            File(p).parentFile?.let { candidates += File(it, "app/$ARCHIVE_NAME") }
        }
        candidates += File(System.getProperty("user.dir") ?: ".", "app/$ARCHIVE_NAME")
        candidates += File(System.getProperty("user.dir") ?: ".", ARCHIVE_NAME)
        return candidates.firstOrNull { it.isFile }
    }

    /**
     * 解包（tar + gzip）。条目名以 `./` 开头，落盘前统一规范化并拒绝越界路径。
     *
     * ★ 原子化：先解到同级的临时目录 `<installDir>.extracting`，把 install.lock / 就绪标记 / 清单
     *   都写好后，再把临时目录整体重命名到 [installDir]。这样即使中途被中断（覆盖安装双开、进程被杀），
     *   正式目录里也不会留下「看起来就绪、其实残缺」的原生库；下一次启动按清单校验即可发现并重解
     *   （issue #34：双开时序下 jcef-bundle 被写坏且永不自愈）。
     */
    private fun extract(archive: File, installDir: File, stamp: String) {
        val parent = installDir.parentFile ?: error("无法定位 CEF 上级目录: ${installDir.absolutePath}")
        if (!parent.isDirectory && !parent.mkdirs()) error("无法创建 CEF 上级目录: ${parent.absolutePath}")
        val temp = File(parent, installDir.name + ".extracting")
        if (temp.exists() && !temp.deleteRecursively()) error("无法清理临时解包目录: ${temp.absolutePath}")
        if (!temp.mkdirs() && !temp.isDirectory) error("无法创建临时解包目录: ${temp.absolutePath}")
        val root = temp.canonicalFile
        val manifest = StringBuilder()
        GzipCompressorInputStream(BufferedInputStream(FileInputStream(archive), BUFFER_SIZE)).use { gz ->
            TarArchiveInputStream(gz).use { tar ->
                var written = 0L
                while (true) {
                    val entry = tar.nextEntry ?: break
                    val name = entry.name.replace('\\', '/').removePrefix("./")
                    if (name.isBlank() || name == ".") continue
                    val target = File(root, name).canonicalFile
                    if (!target.path.startsWith(root.path + File.separator) && target != root) {
                        Log.w(TAG, "跳过越界条目: $name")
                        continue
                    }
                    if (entry.isDirectory) {
                        target.mkdirs()
                        continue
                    }
                    target.parentFile?.mkdirs()
                    val copied = FileOutputStream(target).use { out -> tar.copyTo(out, BUFFER_SIZE) }
                    written += copied
                    manifest.append(name).append('\t').append(copied).append('\n')
                }
                Log.i(TAG, "CEF natives unpacked: ${written / 1024 / 1024} MB")
            }
        }
        // 就绪标记 / 清单都写进临时目录，随后随重命名一起就位，保证「可见即完整」
        File(temp, "install.lock").createNewFile()
        File(temp, READY_MARKER).writeText(stamp)
        File(temp, MANIFEST).writeText(manifest.toString())
        if (installDir.exists() && !installDir.deleteRecursively()) {
            error("无法清理旧的 CEF 目录: ${installDir.absolutePath}")
        }
        if (!temp.renameTo(installDir)) error("无法启用新解包的 CEF 目录: ${installDir.absolutePath}")
    }
}
