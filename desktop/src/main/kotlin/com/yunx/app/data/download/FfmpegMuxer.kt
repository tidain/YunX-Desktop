package com.yunx.app.data.download

import com.yunx.app.data.preview.FfplayManager
import com.yunx.app.util.Log
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * DASH 合轨（ffmpeg 版）：调用 ffmpeg `-c copy` 只重封装、不重编码，把分离的视频轨与音频轨
 * 合成一个普通 MP4。
 *
 * 与 [DashMuxer]（纯 Java 自研 ISO BMFF 解析器）的关系：
 * - **ffmpeg 为主**：本机有 ffmpeg（应用私有目录 / PATH / 常见安装目录）时优先用它，
 *   兼容性与异常输入处理更稳；
 * - **纯 Java 兜底**：没有引擎，或 ffmpeg 合轨失败时退回 [DashMuxer]，保证功能始终可用。
 *
 * 引擎从 [FfplayManager.locateFfmpeg] 取；ffmpeg 精简版不随包发布，由「引擎管理」页或
 * 首次提示下载。
 */
object FfmpegMuxer {

    private const val TAG = "YunX-FfmpegMux"

    /** 合轨超时（10 分钟；`-c copy` 只是重封装，正常远小于此） */
    private const val MUX_TIMEOUT_SECONDS = 600L

    /**
     * 用 ffmpeg 合轨。成功返回 true，失败返回 false（交由上层兜底）。
     * 同步 IO 操作，调用方应在 IO 线程执行。
     *
     * @param onProgress 0f..1f 的合轨进度：通过 ffmpeg `-progress pipe:1` 的 `out_time_us`
     *   与 ffprobe 测得的视频总时长换算（拿不到时长时只在起止各回调一次）
     */
    fun mux(video: File, audio: File, out: File, onProgress: (Float) -> Unit = {}): Boolean {
        val exe = FfplayManager.locateFfmpeg() ?: return false
        if (!video.isFile || video.length() == 0L || !audio.isFile || audio.length() == 0L) {
            Log.w(TAG, "合轨输入缺失或为空 video=${video.absolutePath} audio=${audio.absolutePath}")
            return false
        }
        val inputArgs = listOf(
            "-i", video.absolutePath,
            "-i", audio.absolutePath,
            "-map", "0:v:0",
            "-map", "1:a:0"
        )
        // 合轨阶段要能看到进度：4K 长片的 -c copy 也要几十秒，只报起止会让进度条卡在 90%
        return runFfmpeg(exe, inputArgs, out, probeDurationUs(exe, video), "合轨", onProgress)
    }

    /**
     * 单输入重封装：把 HLS 分片合并出的 TS 转封装成 MP4（`-c copy`，不重编码）。
     * 成功返回 true，失败返回 false（调用方保留原 TS 产物）。调用方应在 IO 线程执行。
     */
    fun remuxToMp4(input: File, out: File, onProgress: (Float) -> Unit = {}): Boolean {
        val exe = FfplayManager.locateFfmpeg() ?: return false
        if (!input.isFile || input.length() == 0L) {
            Log.w(TAG, "重封装输入缺失或为空: ${input.absolutePath}")
            return false
        }
        return runFfmpeg(
            exe, listOf("-i", input.absolutePath), out, probeDurationUs(exe, input), "重封装", onProgress
        )
    }

    /** 统一执行一次 `-c copy` 重封装：进度读取 / 超时 / 失败判定 / 日志都收敛在这里 */
    private fun runFfmpeg(
        exe: File,
        inputArgs: List<String>,
        out: File,
        totalUs: Long,
        label: String,
        onProgress: (Float) -> Unit
    ): Boolean {
        onProgress(0f)
        out.parentFile?.mkdirs()
        runCatching { out.delete() }
        val cmd = buildList {
            add(exe.absolutePath)
            addAll(listOf("-hide_banner", "-loglevel", "error", "-nostats", "-progress", "pipe:1", "-y"))
            addAll(inputArgs)
            addAll(listOf("-c", "copy", "-movflags", "+faststart", out.absolutePath))
        }
        val logFile = File(
            out.parentFile ?: File(System.getProperty("java.io.tmpdir")),
            "ffmpeg-mux-${System.currentTimeMillis()}.log"
        )
        return runCatching {
            val proc = ProcessBuilder(cmd)
                .redirectError(ProcessBuilder.Redirect.appendTo(logFile))
                .start()
            // 单独线程读 stdout：ffmpeg 的进度行是边跑边输出的，主线程要能继续等超时
            val reader = Thread {
                runCatching {
                    proc.inputStream.bufferedReader().forEachLine { line ->
                        if (!line.startsWith("out_time_us=") && !line.startsWith("out_time_ms=")) return@forEachLine
                        // ffmpeg 的 out_time_us / out_time_ms 都是微秒（历史命名问题）
                        val us = line.substringAfter('=').trim().toLongOrNull() ?: return@forEachLine
                        if (totalUs > 0 && us > 0) {
                            onProgress((us.toFloat() / totalUs).coerceIn(0f, 1f))
                        }
                    }
                }
            }.apply { isDaemon = true; start() }
            try {
                if (!proc.waitFor(MUX_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    proc.destroyForcibly()
                    Log.w(TAG, "ffmpeg $label 超时（${MUX_TIMEOUT_SECONDS}s）")
                    return@runCatching false
                }
                reader.join(1_000)
                val code = proc.exitValue()
                if (code != 0 || !out.isFile || out.length() == 0L) {
                    Log.w(TAG, "ffmpeg $label 失败 code=$code：${runCatching { logFile.readText() }.getOrDefault("").takeLast(600)}")
                    return@runCatching false
                }
                onProgress(1f)
                true
            } finally {
                runCatching { logFile.delete() }
            }
        }.getOrElse { t ->
            Log.w(TAG, "ffmpeg $label 异常：${t.message}")
            false
        }
    }

    /** 用同目录的 ffprobe 取视频总时长（微秒）；没有 ffprobe / 取不到返回 0 */
    private fun probeDurationUs(ffmpeg: File, video: File): Long {
        val probe = File(ffmpeg.parentFile, "ffprobe.exe").takeIf { it.isFile } ?: return 0L
        return runCatching {
            val proc = ProcessBuilder(
                probe.absolutePath,
                "-v", "error",
                "-show_entries", "format=duration",
                "-of", "default=nw=1:nk=1",
                video.absolutePath
            ).redirectErrorStream(true).start()
            val text = proc.inputStream.bufferedReader().readText().trim()
            proc.waitFor(10, TimeUnit.SECONDS)
            val seconds = text.lineSequence()
                .mapNotNull { it.trim().toDoubleOrNull() }
                .firstOrNull() ?: return 0L
            (seconds * 1_000_000.0).toLong()
        }.getOrDefault(0L)
    }
}
