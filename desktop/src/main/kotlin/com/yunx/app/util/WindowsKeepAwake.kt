package com.yunx.app.util

import com.sun.jna.Native
import com.sun.jna.win32.StdCallLibrary
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 下载期间阻止系统睡眠（Windows）：
 * kernel32.SetThreadExecutionState(ES_CONTINUOUS | ES_SYSTEM_REQUIRED)。
 * 该状态是线程本地属性，所有调用固定投递到同一线程执行，引用计数归零时解除。
 */
object WindowsKeepAwake {
    private interface Kernel32 : StdCallLibrary {
        fun SetThreadExecutionState(esFlags: Int): Int
    }

    private val kernel32: Kernel32? by lazy {
        if (!WindowsTitleBar.isWindows) null
        else runCatching { Native.load("kernel32", Kernel32::class.java) }.getOrNull()
    }

    private val isMac: Boolean by lazy {
        val os = System.getProperty("os.name", "").lowercase()
        os.contains("mac") || os.contains("darwin")
    }

    @Volatile
    private var caffeinateProc: Process? = null

    private const val ES_CONTINUOUS = 0x80000000.toInt()
    private const val ES_SYSTEM_REQUIRED = 0x00000001

    /** 所有 SetThreadExecutionState 调用固定在此线程（状态线程本地，跨线程会失调） */
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "yunx-keep-awake").apply { isDaemon = true }
    }
    private val refs = AtomicInteger(0)

    fun acquire() {
        if (refs.getAndIncrement() == 0) {
            executor.execute {
                if (WindowsTitleBar.isWindows) {
                    runCatching { kernel32?.SetThreadExecutionState(ES_CONTINUOUS or ES_SYSTEM_REQUIRED) }
                } else if (isMac) {
                    runCatching {
                        if (caffeinateProc?.isAlive != true) {
                            caffeinateProc = ProcessBuilder("/usr/bin/caffeinate", "-i", "-w", ProcessHandle.current().pid().toString()).start()
                        }
                    }
                }
            }
        }
    }

    fun release() {
        if (refs.decrementAndGet() <= 0) {
            refs.set(0)
            executor.execute {
                if (WindowsTitleBar.isWindows) {
                    runCatching { kernel32?.SetThreadExecutionState(ES_CONTINUOUS) }
                } else if (isMac) {
                    runCatching {
                        caffeinateProc?.destroy()
                        caffeinateProc = null
                    }
                }
            }
        }
    }
}
