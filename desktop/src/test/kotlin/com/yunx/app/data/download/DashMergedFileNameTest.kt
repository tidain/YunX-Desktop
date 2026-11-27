package com.yunx.app.data.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DASH 合轨输出文件名回归测试。
 *
 * `FfmpegMuxer` 的 `-c copy` 重封装没有显式 `-f`，靠输出扩展名推断封装格式；
 * 文件名缺 `.mp4` 会被 ffmpeg 以 `-22 Invalid argument` 拒绝、合轨必然失败（issue #125）。
 */
class DashMergedFileNameTest {

    @Test
    fun mergedFileNameEndsWithMp4() {
        val name = dashMergedFileName(42L)
        assertTrue(
            "合轨输出文件名必须以 .mp4 结尾，否则 ffmpeg 无法推断 MP4 封装格式",
            name.endsWith(".mp4")
        )
        assertEquals("dash_merged_42.mp4", name)
    }

    @Test
    fun mergedFileNamePreservesTaskId() {
        // 补扩展名不能吞掉任务 id：不同任务的临时产物必须互相隔离
        assertEquals("dash_merged_7.mp4", dashMergedFileName(7L))
        assertEquals("dash_merged_123456.mp4", dashMergedFileName(123456L))
    }
}
