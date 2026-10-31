package com.yunx.app.tools

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.apache.commons.compress.compressors.gzip.GzipParameters
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

/**
 * 打包期工具（不参与运行）：把 jcef 原生包 jar 里内嵌的 `tar.gz` 重打成发布用的 `tar.gz`。
 *
 * 为什么这么做：CEF 原生库是发布包里最大的一块（解包后 261MB，官方 jar 内 gzip 后约 120MB）。
 * 这里**沿用 gzip**（不用 LZMA2/xz），把 Chromium 的语言包裁到只剩 `zh-CN`（应用固定
 * `settings.locale = "zh-CN"`，其余语言包永远不会被加载），其余文件一个不少。
 * 运行期由 [com.yunx.app.ui.jcef.JcefNativesInstaller] 解开，再让 jcefmaven 跳过它自己的安装步骤。
 *
 * 说明：早前这里用 xz(LZMA2+BCJ) 换来更高压缩率，但实测它对内嵌浏览器并不友好，故换回 gzip ——
 * 压缩率略低，但解压路径更稳（不再依赖额外的 xz 解压实现）。
 *
 * 用法：`JcefNativesPacker <jcef-natives-*.jar> <out.tar.gz>`
 */
object JcefNativesPacker {

    /** 保留的语言包（与 JcefHolder 的 settings.locale 一致） */
    private val KEEP_LOCALES = setOf("zh-CN")

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size >= 2) { "用法: JcefNativesPacker <jcef-natives-*.jar> <out.tar.gz>" }
        val jar = File(args[0])
        val out = File(args[1])
        require(jar.isFile) { "原生包不存在: $jar" }
        out.parentFile?.mkdirs()

        var files = 0
        ZipFile(jar).use { zip ->
            val entry = zip.entries().asSequence().firstOrNull { it.name.endsWith(".tar.gz") }
                ?: error("jar 内没有 .tar.gz: $jar")
            zip.getInputStream(entry).use { rawIn ->
                GzipCompressorInputStream(rawIn, true).use { gzipIn ->
                    TarArchiveInputStream(gzipIn).use { tarIn ->
                        val gzOut = GzipCompressorOutputStream(
                            BufferedOutputStream(FileOutputStream(out), BUFFER_SIZE),
                            GzipParameters().apply { compressionLevel = COMPRESSION_LEVEL }
                        )
                        gzOut.use { gz ->
                            TarArchiveOutputStream(gz).use { tarOut ->
                                tarOut.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                                while (true) {
                                    val entryIn = tarIn.nextEntry ?: break
                                    // commons-compress 解析时会去掉 tar 里的 "./" 前缀，这里统一规范化
                                    val name = entryIn.name.removePrefix("./")
                                    if (!keep(name)) continue
                                    val entryOut = TarArchiveEntry(name).apply {
                                        mode = entryIn.mode
                                        modTime = entryIn.modTime
                                        if (!entryIn.isDirectory) size = entryIn.size
                                    }
                                    tarOut.putArchiveEntry(entryOut)
                                    if (!entryIn.isDirectory) {
                                        tarIn.copyTo(tarOut, BUFFER_SIZE)
                                        files++
                                    }
                                    tarOut.closeArchiveEntry()
                                }
                            }
                        }
                    }
                }
            }
        }
        println(
            "jcef natives repacked: ${out.name} ${out.length() / 1024 / 1024} MB " +
                "(from ${jar.name} ${jar.length() / 1024 / 1024} MB, $files files, gzip level $COMPRESSION_LEVEL)"
        )
    }

    /** 只裁语言包；其余（libcef.dll / icudtl.dat / *.pak / *.dll / *.exe / *.bin）一律保留 */
    private fun keep(name: String): Boolean {
        if (!name.startsWith("locales/")) return true
        val leaf = name.removePrefix("locales/").trimEnd('/')
        return leaf.isEmpty() || leaf.removeSuffix(".pak") in KEEP_LOCALES
    }

    private const val BUFFER_SIZE = 1 shl 16

    /** gzip 压缩级别：9 = 最高压缩率（打包期一次性执行，多花的时间无所谓） */
    private const val COMPRESSION_LEVEL = 9
}
