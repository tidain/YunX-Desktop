package com.yunx.app.data.download

import com.yunx.app.util.Log
import org.mp4parser.Container
import org.mp4parser.muxer.Movie
import org.mp4parser.muxer.builder.DefaultMp4Builder
import org.mp4parser.muxer.container.mp4.MovieCreator
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * MP4 分轨合轨 / 重封装（桌面端纯 Java 实现）。
 *
 * 用途：哔哩哔哩的高清是 **DASH 音视频分离**的两路流（视频 `.m4s` + 音频 `.m4s`），
 * 各自下载完必须合轨才是一个能播放的文件；微博动图则是 QuickTime 封装（`.mov`），
 * 内嵌到动态照片前需要换成标准 mp4 容器。
 *
 * ★ 为什么不用 `MovieCreator` + `DefaultMp4Builder` 做 DASH 合轨（2026-10-23 实测）：
 *   mp4parser 读分片 MP4 时**只认 trun 里的逐样本时长**，而 B 站把时长放在 `tfhd` 的
 *   `default_sample_duration`（音频甚至只放在 `mvex/trex`）里 —— 于是重建出来的 `stts`
 *   全是 0，成品时长只有 0.05 秒、进度条完全失效（这就是「下载并合并后时间轴损坏」）。
 *   mp4parser 也读不到分片的 `tfdt`，无法还原 DTS 起点。
 *   ⇒ 分片输入改由本文件**自己解析** moof/trun/tfhd/tfdt/trex，把每个样本的
 *     （文件偏移、大小、时长、CTO、是否关键帧）如实取出来，再自己写 `moov` + `mdat`。
 *     这样产物与源流的时间轴逐样本一致（实测 5306 帧 / 212.24s，与 ffmpeg 直转一致）。
 *
 * ★ 非分片的普通 MP4/MOV（微博动图那种）仍走 mp4parser，那条路是可靠的。
 */
object DashMuxer {

    private const val TAG = "YunX-Dash"

    /** ISO BMFF 的轨道类型：视频 / 声音（hdlr box 的 handlerType） */
    private const val HANDLER_VIDEO = "vide"
    private const val HANDLER_AUDIO = "soun"

    /** 成品 mvhd/tkhd 用的时间刻度（源文件基本都是 1000，编辑列表也是按它记的） */
    private const val MOVIE_TIMESCALE = 1000

    /**
     * 把分离的视频轨与音频轨（哔哩哔哩 DASH，fragmented MP4）合轨成一个普通 MP4。
     * 只重封装、不重编码。成功返回 true。
     *
     * 同步 IO 操作，调用方应在 IO 线程执行。
     *
     * @param onProgress 0f..1f 的粗略进度（可为空实现）
     */
    fun mux(video: File, audio: File, out: File, onProgress: (Float) -> Unit = {}): Boolean =
        runCatching {
            if (!video.isFile || video.length() == 0L || !audio.isFile || audio.length() == 0L) {
                Log.w(TAG, "合轨输入缺失或为空 video=${video.absolutePath} audio=${audio.absolutePath}")
                return@runCatching false
            }
            onProgress(0f)
            val videoTrack = TrackData.parse(video, HANDLER_VIDEO)
            val audioTrack = TrackData.parse(audio, HANDLER_AUDIO)
            if (videoTrack == null || audioTrack == null) {
                Log.w(TAG, "合轨失败：两路必须都是可分片解析的 MP4（视频轨/音轨缺失）")
                videoTrack?.close()
                audioTrack?.close()
                return@runCatching false
            }
            val videoSamples = videoTrack.durations.size.toLong()
            val audioSamples = audioTrack.durations.size.toLong()
            if (videoSamples <= 0 || audioSamples <= 0) {
                Log.w(TAG, "合轨失败：样本为空 video=$videoSamples audio=$audioSamples")
                videoTrack.close()
                audioTrack.close()
                return@runCatching false
            }
            onProgress(0.3f)
            try {
                Mp4Writer.write(listOf(videoTrack, audioTrack), out)
            } finally {
                videoTrack.close()
                audioTrack.close()
            }
            onProgress(1f)
            Log.d(
                TAG,
                "合轨完成 video=${video.length()} audio=${audio.length()} out=${out.length()} " +
                    "samples=$videoSamples+$audioSamples"
            )
            true
        }.onFailure {
            Log.w(TAG, "合轨异常 video=${video.name} audio=${audio.name}: ${it.message}")
        }.getOrDefault(false)

    /** 把任意 MP4/MOV 重新封装成 MP4（微博动图 .mov 用），成功返回 true */
    fun remuxToMp4(input: File, out: File): Boolean =
        runCatching {
            if (!input.isFile || input.length() == 0L) {
                Log.w(TAG, "重封装输入缺失或为空: ${input.absolutePath}")
                return@runCatching false
            }
            val source = MovieCreator.build(input.absolutePath)
            if (source.tracks.isEmpty()) {
                Log.w(TAG, "重封装失败：${input.name} 没有可用轨道")
                return@runCatching false
            }
            val movie = Movie()
            movie.matrix = source.matrix
            source.tracks.forEach { movie.addTrack(it) }
            val ok = writeContainer(DefaultMp4Builder().build(movie), out)
            if (ok) Log.d(TAG, "重封装完成 in=${input.length()} out=${out.length()}")
            ok
        }.onFailure {
            Log.w(TAG, "重封装异常 in=${input.name}: ${it.message}")
        }.getOrDefault(false)

    /**
     * 把 [container] 写成 [out]（mp4parser 路径：重封装用）。
     *
     * DefaultMp4Builder 的写出过程内部没有进度回调，所以进度只能按阶段上报；
     * 真正的样本拷贝发生在 [Container.writeContainer] 里（一帧一帧读、一帧一帧写）。
     */
    private fun writeContainer(container: Container, out: File): Boolean {
        val ok = runCatching {
            FileOutputStream(out).use { stream -> container.writeContainer(stream.channel) }
            out.isFile && out.length() > 0L
        }.onFailure {
            Log.w(TAG, "写出 ${out.name} 失败: ${it.message}")
        }.getOrDefault(false)
        // 失败产物不留半成品（异常时可能只写了 ftyp/moov 的一部分）
        if (!ok) runCatching { out.delete() }
        return ok
    }

    // ---------------- 分片 MP4 解析 ----------------

    /**
     * 一个轨道从 `.m4s` 里读出来的全部信息。
     *
     * @param offsets 每个样本在源文件里的绝对偏移
     * @param sizes 每个样本的字节数
     * @param durations 每个样本的时长（轨道时间刻度）
     * @param ctsRuns 合成时间偏移的游程「(样本数, 偏移)」，写 ctts 用
     * @param sync 关键帧序号（从 1 开始）
     * @param edits 源 `elst` 条目「(段时长@源 mvhd 刻度, 媒体时间, 播放速率 16.16)」
     */
    private class TrackData {
        var handler: String = ""
        var timescale: Long = 0
        var sourceMovieTimescale: Long = MOVIE_TIMESCALE.toLong()
        var stsd: ByteArray = ByteArray(0)
        var matrix: ByteArray? = null
        var width: Double = 0.0
        var height: Double = 0.0
        var language: String = "und"
        val durations = ArrayList<Long>()
        val offsets = ArrayList<Long>()
        val sizes = ArrayList<Long>()
        val ctsRuns = ArrayList<LongArray>()
        val sync = ArrayList<Long>()
        val edits = ArrayList<LongArray>()
        private var trexDuration: Long = 0
        private var trexSize: Long = 0
        private var trexFlags: Int = 0
        lateinit var channel: FileChannel

        fun totalDuration(): Long = durations.sum()

        fun close() {
            runCatching { channel.close() }
        }

        companion object {
            /** 解析一个分片 MP4；不是分片结构（没有 moof）时返回 null，交给 mp4parser 兜底 */
            fun parse(file: File, expectHandler: String): TrackData? {
                val raf = RandomAccessFile(file, "r")
                val t = TrackData()
                t.channel = raf.channel
                try {
                    val length = t.channel.size()
                    var moov: ByteArray? = null
                    var moofCount = 0
                    forEachTopBox(t.channel, length) { type, offset, size, _ ->
                        if (type == "moov") {
                            val bytes = ByteArray(size.toInt())
                            t.channel.read(ByteBuffer.wrap(bytes), offset)
                            moov = bytes
                        } else if (type == "moof") {
                            moofCount++
                        }
                    }
                    val moovBytes = moov
                    if (moovBytes == null || moofCount == 0) {
                        t.close()
                        return null
                    }
                    t.readMoov(moovBytes)
                    if (t.handler != expectHandler) {
                        Log.w(TAG, "轨道类型不符 in=${file.name} expect=$expectHandler got=${t.handler}")
                        t.close()
                        return null
                    }
                    forEachTopBox(t.channel, length) { type, offset, size, _ ->
                        if (type == "moof") {
                            val buf = ByteArray(size.toInt())
                            t.channel.read(ByteBuffer.wrap(buf), offset)
                            t.readMoof(buf, offset)
                        }
                    }
                    return t
                } catch (e: Exception) {
                    t.close()
                    throw e
                }
            }
        }

        private fun readMoov(moov: ByteArray) {
            val trak = findBox(moov, 8, moov.size, "trak")
                ?: throw IOException("moov 里没有 trak")
            val tkhd = findBox(moov, trak.body, trak.end, "tkhd")
                ?: throw IOException("trak 里没有 tkhd")
            val mdia = findBox(moov, trak.body, trak.end, "mdia")
                ?: throw IOException("trak 里没有 mdia")
            val mdhd = findBox(moov, mdia.body, mdia.end, "mdhd")
                ?: throw IOException("mdia 里没有 mdhd")
            val hdlr = findBox(moov, mdia.body, mdia.end, "hdlr")
                ?: throw IOException("mdia 里没有 hdlr")
            val minf = findBox(moov, mdia.body, mdia.end, "minf")
                ?: throw IOException("mdia 里没有 minf")
            val stbl = findBox(moov, minf.body, minf.end, "stbl")
                ?: throw IOException("minf 里没有 stbl")
            val stsdBox = findBox(moov, stbl.body, stbl.end, "stsd")
                ?: throw IOException("stbl 里没有 stsd")

            this.stsd = moov.copyOfRange(stsdBox.start, stsdBox.end)
            this.handler = String(moov, hdlr.body + 8, 4, Charsets.US_ASCII)

            val mdhdVersion = moov[mdhd.body].toInt() and 0xFF
            if (mdhdVersion == 1) {
                timescale = moov.u32(mdhd.body + 20)
                language = languageOf(moov, mdhd.body + 32)
            } else {
                timescale = moov.u32(mdhd.body + 12)
                language = languageOf(moov, mdhd.body + 20)
            }

            val tkhdVersion = moov[tkhd.body].toInt() and 0xFF
            // tkhd: version/flags(4) + 3×时间字段 + track_ID + reserved + duration + reserved(8)
            //       + layer/alt_group/volume/reserved(8) → 矩阵起始位置 v0=40 / v1=52
            val matrixOffset = tkhd.body + if (tkhdVersion == 1) 52 else 40
            matrix = moov.copyOfRange(matrixOffset, matrixOffset + 36)
            width = moov.u32(matrixOffset + 36) / 65536.0
            height = moov.u32(matrixOffset + 40) / 65536.0

            val edts = findBox(moov, trak.body, trak.end, "edts")
            val elst = edts?.let { findBox(moov, it.body, it.end, "elst") }
            if (elst != null) {
                val version = moov[elst.body].toInt() and 0xFF
                val count = moov.i32(elst.body + 4)
                var i = elst.body + 8
                repeat(count) {
                    val segment: Long
                    val mediaTime: Long
                    if (version == 1) {
                        segment = moov.i64(i); i += 8
                        mediaTime = moov.i64(i); i += 8
                    } else {
                        segment = moov.u32(i); i += 4
                        mediaTime = moov.i32(i).toLong(); i += 4
                    }
                    val rate = moov.i32(i); i += 4
                    edits.add(longArrayOf(segment, mediaTime, rate.toLong()))
                }
            }

            val mvhd = findBox(moov, 8, moov.size, "mvhd")
            if (mvhd != null) {
                val version = moov[mvhd.body].toInt() and 0xFF
                sourceMovieTimescale = if (version == 1) moov.u32(mvhd.body + 20) else moov.u32(mvhd.body + 12)
            }

            val mvex = findBox(moov, 8, moov.size, "mvex")
            val trex = mvex?.let { findBox(moov, it.body, it.end, "trex") }
            if (trex != null) {
                trexDuration = moov.u32(trex.body + 12)
                trexSize = moov.u32(trex.body + 16)
                trexFlags = moov.i32(trex.body + 20)
            }
        }

        private fun readMoof(moof: ByteArray, moofStart: Long) {
            forEachBox(moof, 8, moof.size) { traf ->
                if (traf.type != "traf") return@forEachBox
                var baseDataOffset = -1L
                var defaultDuration = trexDuration
                var defaultSize = trexSize
                var defaultFlags = trexFlags
                forEachBox(moof, traf.body, traf.end) { child ->
                    when (child.type) {
                        "tfhd" -> {
                            val b = child.body
                            val flags = (moof.u8(b + 1) shl 16) or (moof.u8(b + 2) shl 8) or moof.u8(b + 3)
                            var i = b + 8
                            if ((flags and 0x01) != 0) { baseDataOffset = moof.i64(i); i += 8 }
                            if ((flags and 0x02) != 0) i += 4
                            if ((flags and 0x08) != 0) { defaultDuration = moof.u32(i); i += 4 }
                            // ★ ISO 14496-12 §8.8.7 字段顺序：duration → size(0x10) → flags(0x20)。
                            //   原实现两位写反且顺序颠倒：只带 default-sample-flags(0x20) 的 moof 会把
                            //   flags 值（关键帧标志 0x02000000）当成每样本 size → 样本偏移累计飞出
                            //   文件尾（表现为「合轨异常：样本读取越界」）。
                            if ((flags and 0x10) != 0) { defaultSize = moof.u32(i); i += 4 }
                            if ((flags and 0x20) != 0) { defaultFlags = moof.i32(i); i += 4 }
                        }
                        "tfdt" -> Unit // 片段的 decode time 已在各样本时长里连续累计，无需另记
                    }
                }
                val base = if (baseDataOffset >= 0) baseDataOffset else moofStart
                forEachBox(moof, traf.body, traf.end) { child ->
                    if (child.type != "trun") return@forEachBox
                    readTrun(moof, child, base, defaultDuration, defaultSize, defaultFlags)
                }
            }
        }

        private fun readTrun(
            moof: ByteArray,
            box: BoxRef,
            base: Long,
            defaultDuration: Long,
            defaultSize: Long,
            defaultFlags: Int
        ) {
            val b = box.body
            val version = moof[b].toInt() and 0xFF
            val flags = (moof.u8(b + 1) shl 16) or (moof.u8(b + 2) shl 8) or moof.u8(b + 3)
            val count = moof.i32(b + 4)
            var i = b + 8
            var dataOffset = 0L
            if ((flags and 0x01) != 0) { dataOffset = moof.i32(i).toLong(); i += 4 }
            var firstFlags = Int.MIN_VALUE
            if ((flags and 0x04) != 0) { firstFlags = moof.i32(i); i += 4 }
            var sampleOffset = base + dataOffset
            for (s in 0 until count) {
                var duration = defaultDuration
                var size = defaultSize
                var sampleFlags = if (s == 0 && firstFlags != Int.MIN_VALUE) firstFlags else defaultFlags
                var cts = 0L
                if ((flags and 0x100) != 0) { duration = moof.u32(i); i += 4 }
                if ((flags and 0x200) != 0) { size = moof.u32(i); i += 4 }
                if ((flags and 0x400) != 0) { sampleFlags = moof.i32(i); i += 4 }
                if ((flags and 0x800) != 0) {
                    cts = if (version == 0) moof.u32(i) else moof.i32(i).toLong()
                    i += 4
                }
                offsets.add(sampleOffset)
                sizes.add(size)
                durations.add(duration)
                // sample_depends_on == 2 表示 I 帧；缺省(0)时把每个 trun 的首样本当关键帧
                val dependsOn = (sampleFlags ushr 24) and 0x3
                if (dependsOn == 2 || (dependsOn == 0 && firstFlags != Int.MIN_VALUE && s == 0)) {
                    sync.add(durations.size.toLong())
                }
                val last = ctsRuns.lastOrNull()
                if (last != null && last[1] == cts) last[0]++ else ctsRuns.add(longArrayOf(1, cts))
                sampleOffset += size
            }
        }
    }

    /** moov 里一个 box 的边界（start 含头，body 是头之后） */
    private class BoxRef(val type: String, val start: Int, val body: Int, val end: Int)

    /** 顶层 box 扫描：[callback] 收到「类型 / 偏移 / 大小 / 头长」 */
    private inline fun forEachTopBox(
        channel: FileChannel,
        length: Long,
        callback: (type: String, offset: Long, size: Long, headerLen: Int) -> Unit
    ) {
        val head = ByteBuffer.allocate(16)
        var pos = 0L
        while (pos + 8 <= length) {
            head.clear()
            channel.read(head, pos)
            var size = head.getInt(0).toLong() and 0xFFFFFFFFL
            val type = String(head.array(), 4, 4, Charsets.US_ASCII)
            var headerLen = 8
            if (size == 1L) {
                size = head.getLong(8)
                headerLen = 16
            } else if (size == 0L) {
                size = length - pos
            }
            if (size <= 0) break
            callback(type, pos, size, headerLen)
            pos += size
        }
    }

    private inline fun forEachBox(bytes: ByteArray, start: Int, end: Int, action: (BoxRef) -> Unit) {
        var pos = start
        while (pos + 8 <= end) {
            var size = bytes.i32(pos)
            val type = String(bytes, pos + 4, 4, Charsets.US_ASCII)
            var headerLen = 8
            if (size == 1) {
                size = bytes.i64(pos + 8).toInt()
                headerLen = 16
            } else if (size == 0) {
                size = end - pos
            }
            if (size <= 0) return
            action(BoxRef(type, pos, pos + headerLen, pos + size))
            pos += size
        }
    }

    private fun findBox(bytes: ByteArray, start: Int, end: Int, type: String): BoxRef? {
        var found: BoxRef? = null
        forEachBox(bytes, start, end) { if (it.type == type) found = it }
        return found
    }

    private fun languageOf(bytes: ByteArray, offset: Int): String {
        val packed = (bytes.u8(offset) shl 8) or bytes.u8(offset + 1)
        val c1 = ((packed shr 10) and 0x1F) + 0x60
        val c2 = ((packed shr 5) and 0x1F) + 0x60
        val c3 = (packed and 0x1F) + 0x60
        return String(charArrayOf(c1.toChar(), c2.toChar(), c3.toChar()))
    }

    // ---------------- 写标准 MP4 ----------------

    private object Mp4Writer {

        /** 分块写入：每块约 1 秒，两轨按时间交错，兼顾顺序读与拖动 */
        private class Chunk(
            val track: Int,
            val startSample: Int,
            val count: Int,
            val bytes: Long,
            val seconds: Double
        ) {
            var offset: Long = 0
        }

        fun write(tracks: List<TrackData>, out: File) {
            val chunks = ArrayList<Chunk>()
            tracks.forEachIndexed { ti, t ->
                val total = t.durations.size
                val avg = (t.totalDuration() / total.coerceAtLeast(1)).coerceAtLeast(1L)
                val per = (t.timescale / avg).coerceAtLeast(1L).toInt()
                var start = 0
                var dts = 0L
                while (start < total) {
                    val count = minOf(per, total - start)
                    var bytes = 0L
                    for (k in start until start + count) bytes += t.sizes[k]
                    chunks.add(Chunk(ti, start, count, bytes, dts.toDouble() / t.timescale))
                    for (k in start until start + count) dts += t.durations[k]
                    start += count
                }
            }
            chunks.sortWith(compareBy<Chunk> { it.seconds }.thenBy { it.track })

            val ftyp = ftyp()
            var moov = buildMoov(tracks, chunks)
            var mdatStart = ftyp.size + moov.size + 8L
            assignOffsets(chunks, mdatStart)
            moov = buildMoov(tracks, chunks)
            val expected = ftyp.size + moov.size + 8L
            if (expected != mdatStart) {
                mdatStart = expected
                assignOffsets(chunks, mdatStart)
                moov = buildMoov(tracks, chunks)
            }

            val payload = chunks.sumOf { it.bytes }
            BufferedOutputStream(FileOutputStream(out), 1 shl 20).use { os ->
                os.write(ftyp)
                os.write(moov)
                if (8 + payload > 0xFFFFFFFFL) {
                    os.write(u32(1))
                    os.write("mdat".toByteArray(Charsets.US_ASCII))
                    os.write(u64(16 + payload))
                } else {
                    os.write(u32((8 + payload).toInt()))
                    os.write("mdat".toByteArray(Charsets.US_ASCII))
                }
                val buffer = ByteBuffer.allocate(1 shl 20)
                for (chunk in chunks) {
                    val t = tracks[chunk.track]
                    for (i in chunk.startSample until chunk.startSample + chunk.count) {
                        copySample(t.channel, t.offsets[i], t.sizes[i], os, buffer)
                    }
                }
            }
        }

        private fun assignOffsets(chunks: List<Chunk>, mdatStart: Long) {
            var pos = mdatStart
            for (chunk in chunks) {
                chunk.offset = pos
                pos += chunk.bytes
            }
        }

        private fun copySample(
            channel: FileChannel,
            offset: Long,
            size: Long,
            os: OutputStream,
            buffer: ByteBuffer
        ) {
            var pos = offset
            var remain = size
            while (remain > 0) {
                val want = minOf(buffer.capacity().toLong(), remain).toInt()
                buffer.clear()
                buffer.limit(want)
                val read = channel.read(buffer, pos)
                if (read < 0) throw IOException("样本读取越界 offset=$pos")
                os.write(buffer.array(), 0, read)
                pos += read
                remain -= read
            }
        }

        private fun buildMoov(tracks: List<TrackData>, chunks: List<Chunk>): ByteArray {
            val moov = ByteArrayOutputStream()
            val durationsMs = tracks.map { it.totalDuration() * MOVIE_TIMESCALE / it.timescale.coerceAtLeast(1L) }
            val movieDuration = durationsMs.maxOrNull() ?: 0L

            val mvhd = ByteArrayOutputStream()
            fullBoxHeader(mvhd, 0, 0)
            mvhd.writeBytes(u32(0))
            mvhd.writeBytes(u32(0))
            mvhd.writeBytes(u32(MOVIE_TIMESCALE))
            mvhd.writeBytes(u32(movieDuration.toInt()))
            mvhd.writeBytes(u32(0x00010000))       // rate 1.0
            mvhd.writeBytes(u16(0x0100))           // volume 1.0
            mvhd.writeBytes(u16(0))
            mvhd.writeBytes(u32(0))
            mvhd.writeBytes(u32(0))
            mvhd.writeBytes(IDENTITY_MATRIX)
            mvhd.writeBytes(ByteArray(24))
            mvhd.writeBytes(u32(tracks.size + 1))  // next_track_ID
            moov.writeBytes(box("mvhd", mvhd.toByteArray()))

            tracks.forEachIndexed { ti, t ->
                val trackDurationMs = durationsMs[ti]
                val trak = ByteArrayOutputStream()

                val tkhd = ByteArrayOutputStream()
                fullBoxHeader(tkhd, 0, 3)          // track enabled | in movie
                tkhd.writeBytes(u32(0))
                tkhd.writeBytes(u32(0))
                tkhd.writeBytes(u32(ti + 1))
                tkhd.writeBytes(u32(0))
                tkhd.writeBytes(u32(trackDurationMs.toInt()))
                tkhd.writeBytes(ByteArray(8))
                tkhd.writeBytes(u16(0))            // layer
                tkhd.writeBytes(u16(0))            // alternate_group
                tkhd.writeBytes(u16(if (t.handler == HANDLER_AUDIO) 0x0100 else 0))
                tkhd.writeBytes(u16(0))
                tkhd.writeBytes(t.matrix ?: IDENTITY_MATRIX)
                tkhd.writeBytes(u32(Math.round(t.width * 65536).toInt()))
                tkhd.writeBytes(u32(Math.round(t.height * 65536).toInt()))
                trak.writeBytes(box("tkhd", tkhd.toByteArray()))

                if (t.edits.isNotEmpty()) {
                    val elst = ByteArrayOutputStream()
                    fullBoxHeader(elst, 0, 0)
                    elst.writeBytes(u32(t.edits.size))
                    t.edits.forEach { edit ->
                        var segment = edit[0]
                        if (t.sourceMovieTimescale != MOVIE_TIMESCALE.toLong() && t.sourceMovieTimescale > 0) {
                            segment = segment * MOVIE_TIMESCALE / t.sourceMovieTimescale
                        }
                        // 源里「最后一段」的段时长常写 0（意为到媒体末尾），这里补成实际剩余时长，
                        // 否则部分播放器会把进度条算成 0
                        if (segment == 0L) {
                            val remaining = t.totalDuration() - edit[1]
                            segment = (remaining * MOVIE_TIMESCALE / t.timescale.coerceAtLeast(1L)).coerceAtLeast(0L)
                        }
                        elst.writeBytes(u32(segment.toInt()))
                        elst.writeBytes(u32(edit[1].toInt()))
                        elst.writeBytes(u32(edit[2].toInt()))
                    }
                    trak.writeBytes(box("edts", box("elst", elst.toByteArray())))
                }

                val mdia = ByteArrayOutputStream()
                val mdhd = ByteArrayOutputStream()
                fullBoxHeader(mdhd, 0, 0)
                mdhd.writeBytes(u32(0))
                mdhd.writeBytes(u32(0))
                mdhd.writeBytes(u32(t.timescale.toInt()))
                mdhd.writeBytes(u32(t.totalDuration().toInt()))
                mdhd.writeBytes(u16(packLanguage(t.language)))
                mdhd.writeBytes(u16(0))
                mdia.writeBytes(box("mdhd", mdhd.toByteArray()))

                val hdlr = ByteArrayOutputStream()
                fullBoxHeader(hdlr, 0, 0)
                hdlr.writeBytes(u32(0))
                hdlr.writeBytes(t.handler.toByteArray(Charsets.US_ASCII))
                hdlr.writeBytes(ByteArray(12))
                hdlr.writeBytes(
                    (if (t.handler == HANDLER_AUDIO) "SoundHandler" else "VideoHandler")
                        .toByteArray(Charsets.US_ASCII)
                )
                hdlr.write(0)
                mdia.writeBytes(box("hdlr", hdlr.toByteArray()))

                val minf = ByteArrayOutputStream()
                val mediaHeader = ByteArrayOutputStream()
                if (t.handler == HANDLER_AUDIO) {
                    fullBoxHeader(mediaHeader, 0, 0)
                    mediaHeader.writeBytes(u16(0))
                    mediaHeader.writeBytes(u16(0))
                } else {
                    fullBoxHeader(mediaHeader, 0, 1)
                    mediaHeader.writeBytes(ByteArray(8))
                }
                minf.writeBytes(
                    box(if (t.handler == HANDLER_AUDIO) "smhd" else "vmhd", mediaHeader.toByteArray())
                )
                val dref = ByteArrayOutputStream()
                fullBoxHeader(dref, 0, 0)
                dref.writeBytes(u32(1))
                val url = ByteArrayOutputStream()
                fullBoxHeader(url, 0, 1)           // 媒体数据就在本文件里
                dref.writeBytes(box("url ", url.toByteArray()))
                minf.writeBytes(box("dinf", box("dref", dref.toByteArray())))

                // ---- stbl ----
                val stbl = ByteArrayOutputStream()
                stbl.writeBytes(t.stsd)            // 原始 stsd：含 avcC / hvcC / esds，原样搬运

                val sttsRuns = ArrayList<LongArray>()
                for (d in t.durations) {
                    val last = sttsRuns.lastOrNull()
                    if (last != null && last[1] == d) last[0]++ else sttsRuns.add(longArrayOf(1, d))
                }
                val stts = ByteArrayOutputStream()
                fullBoxHeader(stts, 0, 0)
                stts.writeBytes(u32(sttsRuns.size))
                for (run in sttsRuns) {
                    stts.writeBytes(u32(run[0].toInt()))
                    stts.writeBytes(u32(run[1].toInt()))
                }
                stbl.writeBytes(box("stts", stts.toByteArray()))

                if (t.ctsRuns.any { it[1] != 0L }) {
                    val negative = t.ctsRuns.any { it[1] < 0 }
                    val ctts = ByteArrayOutputStream()
                    fullBoxHeader(ctts, 0, if (negative) 1 else 0)
                    ctts.writeBytes(u32(t.ctsRuns.size))
                    for (run in t.ctsRuns) {
                        ctts.writeBytes(u32(run[0].toInt()))
                        ctts.writeBytes(u32(run[1].toInt()))
                    }
                    stbl.writeBytes(box("ctts", ctts.toByteArray()))
                }

                if (t.handler == HANDLER_VIDEO && t.sync.isNotEmpty() && t.sync.size != t.durations.size) {
                    val stss = ByteArrayOutputStream()
                    fullBoxHeader(stss, 0, 0)
                    stss.writeBytes(u32(t.sync.size))
                    for (s in t.sync) stss.writeBytes(u32(s.toInt()))
                    stbl.writeBytes(box("stss", stss.toByteArray()))
                }

                val stsz = ByteArrayOutputStream()
                fullBoxHeader(stsz, 0, 0)
                stsz.writeBytes(u32(0))
                stsz.writeBytes(u32(t.sizes.size))
                for (s in t.sizes) stsz.writeBytes(u32(s.toInt()))
                stbl.writeBytes(box("stsz", stsz.toByteArray()))

                val mine = chunks.filter { it.track == ti }
                val stscRuns = ArrayList<LongArray>()
                mine.forEachIndexed { index, chunk ->
                    val last = stscRuns.lastOrNull()
                    if (last == null || last[1] != chunk.count.toLong()) {
                        stscRuns.add(longArrayOf(index + 1L, chunk.count.toLong(), 1))
                    }
                }
                val stsc = ByteArrayOutputStream()
                fullBoxHeader(stsc, 0, 0)
                stsc.writeBytes(u32(stscRuns.size))
                for (run in stscRuns) {
                    stsc.writeBytes(u32(run[0].toInt()))
                    stsc.writeBytes(u32(run[1].toInt()))
                    stsc.writeBytes(u32(run[2].toInt()))
                }
                stbl.writeBytes(box("stsc", stsc.toByteArray()))

                val co64 = mine.any { it.offset > 0xFFFFFFFFL }
                val stco = ByteArrayOutputStream()
                fullBoxHeader(stco, 0, 0)
                stco.writeBytes(u32(mine.size))
                for (chunk in mine) {
                    if (co64) stco.writeBytes(u64(chunk.offset)) else stco.writeBytes(u32(chunk.offset.toInt()))
                }
                stbl.writeBytes(box(if (co64) "co64" else "stco", stco.toByteArray()))

                minf.writeBytes(box("stbl", stbl.toByteArray()))
                mdia.writeBytes(box("minf", minf.toByteArray()))
                trak.writeBytes(box("mdia", mdia.toByteArray()))
                moov.writeBytes(box("trak", trak.toByteArray()))
            }
            return box("moov", moov.toByteArray())
        }

        private fun ftyp(): ByteArray {
            val payload = ByteArrayOutputStream()
            payload.writeBytes("isom".toByteArray(Charsets.US_ASCII))
            payload.writeBytes(u32(512))
            payload.writeBytes("isomiso2avc1mp41".toByteArray(Charsets.US_ASCII))
            return box("ftyp", payload.toByteArray())
        }
    }

    // ---------------- 字节工具 ----------------

    private val IDENTITY_MATRIX: ByteArray = run {
        val b = ByteArrayOutputStream()
        b.writeBytes(u32(0x00010000)); b.writeBytes(u32(0)); b.writeBytes(u32(0))
        b.writeBytes(u32(0)); b.writeBytes(u32(0x00010000)); b.writeBytes(u32(0))
        b.writeBytes(u32(0)); b.writeBytes(u32(0)); b.writeBytes(u32(0x40000000))
        b.toByteArray()
    }

    private fun box(type: String, payload: ByteArray): ByteArray =
        ByteArrayOutputStream().apply {
            writeBytes(u32(8 + payload.size))
            writeBytes(type.toByteArray(Charsets.US_ASCII))
            writeBytes(payload)
        }.toByteArray()

    /** FullBox 的 version + flags（4 字节）；外层由 [box] 补 size/type */
    private fun fullBoxHeader(out: ByteArrayOutputStream, version: Int, flags: Int) {
        out.write(version)
        out.write((flags ushr 16) and 0xFF)
        out.write((flags ushr 8) and 0xFF)
        out.write(flags and 0xFF)
    }

    private fun u16(value: Int): ByteArray = byteArrayOf((value ushr 8).toByte(), value.toByte())

    private fun u32(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte()
    )

    private fun u64(value: Long): ByteArray = ByteArray(8) { i -> (value ushr (56 - 8 * i)).toByte() }

    private fun packLanguage(language: String): Int {
        if (language.length < 3) return 0x55C4 // und
        val c1 = language[0].code - 0x60
        val c2 = language[1].code - 0x60
        val c3 = language[2].code - 0x60
        return ((c1 and 0x1F) shl 10) or ((c2 and 0x1F) shl 5) or (c3 and 0x1F)
    }

    private fun ByteArray.u8(offset: Int): Int = this[offset].toInt() and 0xFF

    private fun ByteArray.u32(offset: Int): Long =
        (u8(offset).toLong() shl 24) or (u8(offset + 1).toLong() shl 16) or
            (u8(offset + 2).toLong() shl 8) or u8(offset + 3).toLong()

    private fun ByteArray.i32(offset: Int): Int = u32(offset).toInt()

    private fun ByteArray.i64(offset: Int): Long =
        (u32(offset) shl 32) or u32(offset + 4)
}
