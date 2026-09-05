package com.yunx.app.util

import com.yunx.app.AppContext
import java.io.File
import java.time.LocalDateTime

/**
 * 替代 android.util.Log 的日志门面：输出 stdout + 追加数据目录日志文件。
 * 与 android.util.Log 同签名（i/d/w/e），仅需替换 import。
 */
object Log {

    private val logFile: File = File(AppContext.filesDir, "yunx-pc.log")

    fun i(tag: String, msg: String) = write("I", tag, msg)
    fun d(tag: String, msg: String) = write("D", tag, msg)
    fun w(tag: String, msg: String) = write("W", tag, msg)
    fun e(tag: String, msg: String) = write("E", tag, msg)
    fun e(tag: String, msg: String, tr: Throwable) = write("E", tag, "$msg\n${tr.stackTraceToString()}")

    @Synchronized
    private fun write(level: String, tag: String, msg: String) {
        val line = "${LocalDateTime.now()} $level/$tag: $msg"
        println(line)
        runCatching {
            logFile.parentFile?.mkdirs()
            logFile.appendText(line + "\n")
        }
    }
}
