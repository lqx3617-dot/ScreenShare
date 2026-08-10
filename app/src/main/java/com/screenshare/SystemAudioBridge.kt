package com.screenshare

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.projection.MediaProjection
import android.util.Log

/**
 * 系统音频内录/播放桥。
 *
 * 背景：WebRTC Android 标准库的 AudioSource 只能采集麦克风，无法把系统内录音频
 * （视频/音乐播放的声音）注入标准音视频流。因此走 DataChannel 附加通道：
 *   - 共享方：AudioRecord + AudioPlaybackCaptureConfiguration（Android 10+ 内录 API，
 *     依赖 MediaProjection 授权）采集系统 PCM → 分块交给 WebRTCPeer 经 DataChannel 发送
 *   - 观看方：从 DataChannel 接收 PCM → AudioTrack 播放
 * 音画为近似同步（同连接、低延迟、接收端流式播放）。
 */
object SystemAudioBridge {
    private const val TAG = "SystemAudioBridge"
    private const val SAMPLE_RATE = 48000
    // 20ms/块 = 48000/1000*20 = 960 采样 * 2 字节 = 1920 字节，与 WebRTC 音频包惯例一致
    private const val CHUNK_SAMPLES = 960
    private const val CHUNK_BYTES = CHUNK_SAMPLES * 2
    // 静音检测（v1.121）：PCM16 采样峰值绝对值低于该阈值视为静音帧，直接丢弃不发送。
    // 阈值 100 ≈ -52dBFS（v1.126.1 从 500 下调）：真正的无声（无播放/暂停/切后台）才触发，
    // 避免某些设备内录增益偏低时把"有声音"误判为静音导致视频无声。
    private const val SILENCE_PEAK = 100
    // ADPCM 压缩（v1.124）：系统音频 48kHz 单声道 16bit = 768kbps，走 DataChannel 在弱网下严重挤占视频带宽。
    // IMA ADPCM 4bit 压缩后降至 192kbps（4:1），音质对屏幕共享场景足够。双端同步实现，带帧头标记。
    // 帧格式：首字节 0x01 表示后续为 IMA ADPCM 数据（4bit 打包，960 采样→480 字节）；0x00/其他为原始 PCM16（旧版兼容）
    // v1.128: 0x02 = 带 4 字节预测器状态头的 ADPCM 帧 [flag, pred_lo, pred_hi, index, adpcm...]
    //         接收端用首字节区分格式（替代脆弱的 frame.size>=5 判断），双端版本不一致也不会错位解码
    private const val FRAME_FLAG_ADPCM = 0x01.toByte()
    private const val FRAME_FLAG_ADPCM_STATE = 0x02.toByte()
    // IMA ADPCM 步长表（标准 89 项）
    private val stepSizeTable = intArrayOf(
        7, 8, 9, 10, 11, 12, 13, 14, 16, 17,
        19, 21, 23, 25, 28, 31, 34, 37, 41, 45,
        50, 55, 60, 66, 73, 80, 88, 97, 107, 118,
        130, 143, 157, 173, 190, 209, 230, 253, 279, 307,
        337, 371, 408, 449, 494, 544, 598, 658, 724, 796,
        876, 963, 1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066,
        2272, 2499, 2749, 3024, 3327, 3660, 4026, 4428, 4871, 5358,
        5894, 6484, 7132, 7845, 8630, 9493, 10442, 11487, 12635, 13899,
        15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794, 32767
    )
    private val indexTable = intArrayOf(
        -1, -1, -1, -1, 2, 4, 6, 8,
        -1, -1, -1, -1, 2, 4, 6, 8
    )
    // 编码预测器状态（采集线程单线程访问，无需加锁）
    private var encPredicted = 0
    private var encIndex = 0
    // 解码预测器状态（DataChannel 回调线程单线程访问）
    private var decPredicted = 0
    private var decIndex = 0

    /** 将一段 PCM16 编码为 IMA ADPCM 4bit，返回 null 表示输入为空/长度非法 */
    fun encodeAdpcm(pcm: ByteArray): ByteArray? {
        val n = pcm.size / 2
        if (n <= 0) return null
        // 每 2 个采样打包 1 字节（低 4 位=第 1 采样，高 4 位=第 2 采样）
        val outBytes = (n + 1) / 2
        // 帧头：1 字节标记 + 2 字节预测器 + 1 字节步长索引（v1.127 起携带预测器状态，抗丢包防电流声）
        val out = ByteArray(outBytes + 4)
        out[0] = FRAME_FLAG_ADPCM_STATE
        // 写入本帧起始预测器状态：解码端每帧从帧头恢复，单帧丢包不再导致永久失步
        val startPred = encPredicted
        val startIdx = encIndex
        out[1] = (startPred and 0xFF).toByte()
        out[2] = ((startPred shr 8) and 0xFF).toByte()
        out[3] = startIdx.toByte()
        var predicted = encPredicted
        var index = encIndex
        var p = 0
        var o = 4
        while (p < n) {
            val s1 = readSample(pcm, p++)
            var enc = adpcmEncodeSample(s1, predicted, index)
            predicted = enc.pred
            index = enc.idx
            var byte = (enc.value and 0x0F)
            if (p < n) {
                val s2 = readSample(pcm, p++)
                enc = adpcmEncodeSample(s2, predicted, index)
                predicted = enc.pred
                index = enc.idx
                byte = byte or ((enc.value and 0x0F) shl 4)
            }
            out[o++] = byte.toByte()
        }
        encPredicted = predicted
        encIndex = index
        return out
    }

    /** 解码带帧头的 ADPCM 数据，返回 PCM16；帧头为原始 PCM 时原样返回。解码失败返回 null */
    fun decodeFrame(frame: ByteArray): ByteArray? {
        if (frame.isEmpty()) return null
        if (frame[0] != FRAME_FLAG_ADPCM && frame[0] != FRAME_FLAG_ADPCM_STATE) {
            // 旧版共享方/原始 PCM 帧：直接返回（不含帧头？含？——发送端若旧版则为纯 PCM，此处原样交给播放器）
            return frame
        }
        // 帧头版本判断（v1.128）：首字节 0x02 = 新帧含 4 字节预测器状态，0x01 = 旧帧无状态。
        // 用标志字节而非帧长判断，双端版本不一致时也不会误判解码偏移。
        val hasStateHeader = frame[0] == FRAME_FLAG_ADPCM_STATE
        val headerLen = if (hasStateHeader) 4 else 1
        var predicted: Int
        var index: Int
        if (hasStateHeader) {
            // 从帧头恢复预测器状态：即使之前丢帧，本帧从正确状态开始解，杜绝持续电流声
            predicted = (frame[1].toInt() and 0xFF) or ((frame[2].toInt() and 0xFF) shl 8)
            if (predicted > 32767) predicted -= 65536
            index = frame[3].toInt() and 0xFF
            if (index > 88) index = 0
        } else {
            predicted = decPredicted
            index = decIndex
        }
        val adpcmLen = frame.size - headerLen
        // 每 1 字节 ADPCM 解出 2 采样，每采样 2 字节 PCM → 输出字节数 = adpcmLen*4
        // 防御：即使帧头/长度异常也不越界，越界的采样丢弃
        val sampleCount = adpcmLen * 2
        val out = ByteArray(sampleCount * 2)
        var p = headerLen
        var o = 0
        while (p < frame.size && o < sampleCount) {
            val byte = frame[p].toInt() and 0xFF
            val c1 = byte and 0x0F
            val c2 = (byte shr 4) and 0x0F
            val s1 = adpcmDecodeSample(c1, predicted, index)
            predicted = s1.pred; index = s1.idx
            writeSample(out, o++, s1.value)
            if (o >= sampleCount) break
            val s2 = adpcmDecodeSample(c2, predicted, index)
            predicted = s2.pred; index = s2.idx
            writeSample(out, o++, s2.value)
        }
        // 仅在旧帧（无状态头）时回写解码器状态，供后续旧帧延续；
        // 新帧已自带状态，无需回写（避免与新帧状态头互相污染）
        if (!hasStateHeader) {
            decPredicted = predicted
            decIndex = index
        }
        return out
    }

    private class AdpcmSample(val value: Int, val pred: Int, val idx: Int)

    private fun adpcmEncodeSample(sample: Int, predicted: Int, index: Int): AdpcmSample {
        val step = stepSizeTable[index.coerceIn(0, stepSizeTable.size - 1)]
        var diff = sample - predicted
        var sign = 0
        if (diff < 0) { sign = 8; diff = -diff }
        var code = (4 * diff) / step
        if (code > 7) code = 7
        // 重建预测值：与解码器完全一致的公式（step/8 基底 + code 位加权）
        var rdiff = step / 8
        if (code and 1 != 0) rdiff += step / 4
        if (code and 2 != 0) rdiff += step / 2
        if (code and 4 != 0) rdiff += step
        if (sign == 8) rdiff = -rdiff
        var newPred = predicted + rdiff
        if (newPred > 32767) newPred = 32767
        if (newPred < -32768) newPred = -32768
        val newIndex = (index + indexTable[code]).coerceIn(0, 88)
        return AdpcmSample((sign or code) and 0x0F, newPred, newIndex)
    }

    private fun adpcmDecodeSample(code: Int, predicted: Int, index: Int): AdpcmSample {
        val step = stepSizeTable[index.coerceIn(0, stepSizeTable.size - 1)]
        var diff = step / 8
        if (code and 1 != 0) diff += step / 4
        if (code and 2 != 0) diff += step / 2
        if (code and 4 != 0) diff += step
        if (code and 8 != 0) diff = -diff
        var newPred = predicted + diff
        if (newPred > 32767) newPred = 32767
        if (newPred < -32768) newPred = -32768
        val newIndex = (index + indexTable[code]).coerceIn(0, 88)
        return AdpcmSample(newPred, newPred, newIndex)
    }

    private fun readSample(data: ByteArray, sampleIdx: Int): Int {
        val i = sampleIdx * 2
        return ((data[i].toInt() and 0xFF) or (data[i + 1].toInt() shl 8)).toShort().toInt()
    }

    private fun writeSample(data: ByteArray, sampleIdx: Int, value: Int) {
        val i = sampleIdx * 2
        data[i] = (value and 0xFF).toByte()
        data[i + 1] = ((value shr 8) and 0xFF).toByte()
    }

    // ==================== 共享方：采集系统 PCM ====================
    @Volatile private var recordThread: Thread? = null
    @Volatile private var recording = false
    // 采集诊断统计（v1.126.1）：定位"视频无声"问题
    @Volatile private var captureFrameCount = 0L
    @Volatile private var captureSilentCount = 0L
    @Volatile private var captureEncodedCount = 0L
    @Volatile private var captureByteCount = 0L
    @Volatile private var captureReadBytes = 0L
    // 最近采集 PCM 振幅摘要（诊断"无声"：若采集峰值很小则确认是静音误杀或采集失败）
    @Volatile private var lastCapPeak = 0
    @Volatile private var lastCapRms = 0
    // 最近一帧采集 PCM 前 16 采样快照（诊断电流声：与 viewer 端解码快照对比，判断失真环节）
    @Volatile private var lastCapSnap = ""

    /** 采集端诊断摘要（周期性由 MainActivity 上报 /diag） */
    fun captureStats(): String {
        val total = captureFrameCount
        return "capFrames=$total silent=$captureSilentCount encoded=$captureEncodedCount " +
            "readBytes=$captureReadBytes sentBytes=$captureByteCount peak=$lastCapPeak rms=$lastCapRms " +
            "snap=[$lastCapSnap]"
    }

    fun resetCaptureStats() {
        captureFrameCount = 0; captureSilentCount = 0; captureEncodedCount = 0
        captureByteCount = 0; captureReadBytes = 0
        lastCapPeak = 0; lastCapRms = 0
    }

    /**
     * 开始内录系统音频（视频/音乐等 Media 声音）。必须在 MediaProjection 授权后调用。
     * @param mp MediaProjection 实例（复用屏幕采集器内部的同一实例）
     * @param onPcm 每 20ms 回调一次 PCM 数据（48kHz 单声道 16bit）
     * @return true 表示录音器创建并启动成功
     */
    fun startCapture(mp: MediaProjection?, onPcm: (ByteArray) -> Unit): Boolean {
        if (mp == null) {
            Log.e(TAG, "没有 MediaProjection，无法内录系统音频")
            return false
        }
        val config = try {
            AudioPlaybackCaptureConfiguration.Builder(mp)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
        } catch (t: Throwable) {
            Log.e(TAG, "AudioPlaybackCaptureConfiguration 创建失败: ${t.message}")
            return false
        }
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val record = try {
            AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(config)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build()
                )
                .setBufferSizeInBytes(minBuf.coerceAtLeast(CHUNK_BYTES * 2))
                .build()
        } catch (t: Throwable) {
            Log.e(TAG, "AudioRecord 创建失败: ${t.message}")
            return false
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord 初始化失败")
            record.release()
            return false
        }

        stopCapture()
        recording = true
        val thread = Thread {
            val buf = ByteArray(CHUNK_BYTES)
            try {
                record.startRecording()
                Log.d(TAG, "系统音频内录开始 (48kHz mono)")
                while (recording) {
                    val n = record.read(buf, 0, CHUNK_BYTES)
                    if (n > 0 && recording) {
                        captureFrameCount++
                        captureReadBytes += n
                        // 抽样计算采集振幅
                        var peak = 0; var sumSq = 0L; var cnt = 0; var i = 0
                        while (i + 1 < n) {
                            val lo = buf[i].toInt() and 0xFF
                            val hi = buf[i + 1].toInt()
                            val s = (lo or (hi shl 8)).toShort().toInt()
                            val a = kotlin.math.abs(s)
                            if (a > peak) peak = a
                            sumSq += s.toLong() * s.toLong()
                            cnt++
                            i += 64
                        }
                        lastCapPeak = peak
                        if (cnt > 0) lastCapRms = kotlin.math.sqrt(sumSq.toDouble() / cnt).toInt()
                        // 采样快照：首 16 个采集 PCM 采样（诊断电流声时与 viewer 端解码快照对比）
                        if (n >= 32) {
                            val sb = StringBuilder(96)
                            for (k in 0 until 16) {
                                if (k > 0) sb.append(',')
                                val slo = buf[k*2].toInt() and 0xFF
                                val shi = buf[k*2+1].toInt()
                                val sv = (slo or (shi shl 8)).toShort().toInt()
                                sb.append(sv)
                            }
                            lastCapSnap = sb.toString()
                        }
                        // 静音检测：PCM16 小端，峰值绝对值低于阈值视为静音帧，丢弃不发省带宽。
                        // 无声时 DataChannel 不再每秒发送 96KB 数据，弱网下视频带宽更充足
                        if (isSilent(buf, n)) {
                            captureSilentCount++
                            continue
                        }
                        // v1.124: 编码 IMA ADPCM 4bit（1920B → 480B + 1 帧头），弱网下节省 75% 音频带宽
                        encodeAdpcm(buf.copyOf(n))?.let {
                            captureEncodedCount++
                            captureByteCount += it.size
                            onPcm(it)
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "系统音频内录异常: ${t.message}")
            } finally {
                try { record.stop() } catch (_: Throwable) {}
                record.release()
            }
        }
        thread.isDaemon = true
        thread.start()
        recordThread = thread
        return true
    }

    fun stopCapture() {
        recording = false
        recordThread = null
    }

    /** 静音检测：遍历 PCM16 采样，所有采样绝对值 < SILENCE_PEAK 视为静音帧 */
    private fun isSilent(data: ByteArray, len: Int): Boolean {
        var i = 0
        val end = len - 1
        while (i < end) {
            val lo = data[i].toInt() and 0xFF
            val hi = data[i + 1].toInt()
            val sample = (lo or (hi shl 8)).toShort()
            if (kotlin.math.abs(sample.toInt()) >= SILENCE_PEAK) return false
            i += 2
        }
        return true
    }

    // ==================== 观看方：播放系统 PCM ====================
    @Volatile private var audioTrack: AudioTrack? = null
    // 播放诊断统计（v1.126.1）
    @Volatile private var playFrameCount = 0L
    @Volatile private var playDecodedCount = 0L
    @Volatile private var playDroppedCount = 0L
    @Volatile private var playBytesCount = 0L
    // 最近一次解码 PCM 的振幅摘要（诊断"无声"：若 decoded>0 但 rms=0 则解码输出静音）
    @Volatile private var lastPcmPeak = 0
    @Volatile private var lastPcmRms = 0
    // 最近一帧解码 PCM 的前 16 个采样值快照（诊断"电流声"：对比 host 采集波形，判断解码是否失真）
    @Volatile private var lastPcmSnap = ""
    // 最近一次解码的帧长度与帧头（诊断帧格式/版本错配）
    @Volatile private var lastFrameInfo = ""

    /** 记录最近一帧的原始信息（长度/首字节），诊断帧格式版本错配 */
    fun noteRawFrame(frame: ByteArray) {
        if (frame.isEmpty()) { lastFrameInfo = "empty"; return }
        val flag = frame[0].toInt() and 0xFF
        lastFrameInfo = "len=${frame.size} flag=0x${flag.toString(16)}"
    }

    /** 播放端诊断摘要（周期性由 MainActivity 上报 /diag） */
    fun playbackStats(): String {
        return "playFrames=$playFrameCount decoded=$playDecodedCount dropped=$playDroppedCount " +
            "pcmBytes=$playBytesCount peak=$lastPcmPeak rms=$lastPcmRms " +
            "snap=[$lastPcmSnap] fi=[$lastFrameInfo] " +
            "track=${if (audioTrack != null) "on" else "off"}"
    }

    fun resetPlaybackStats() {
        playFrameCount = 0; playDecodedCount = 0; playDroppedCount = 0; playBytesCount = 0
        lastPcmPeak = 0; lastPcmRms = 0
    }

    /** 开始播放（创建流式 AudioTrack 并 play） */
    fun startPlayback(): Boolean {
        stopPlayback()
        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(minBuf.coerceAtLeast(CHUNK_BYTES * 2))
                .build()
        } catch (t: Throwable) {
            Log.e(TAG, "AudioTrack 创建失败: ${t.message}")
            return false
        }
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            Log.e(TAG, "AudioTrack 初始化失败")
            track.release()
            return false
        }
        audioTrack = track
        try {
            track.play()
            Log.d(TAG, "系统音频播放开始")
        } catch (t: Throwable) {
            Log.e(TAG, "AudioTrack play 失败: ${t.message}")
            track.release()
            audioTrack = null
            return false
        }
        return true
    }

    fun writePcm(data: ByteArray) {
        try {
            playFrameCount++
            // 抽样计算振幅（每 32 采样取 1，避免整帧遍历开销）
            var peak = 0; var sumSq = 0L; var cnt = 0
            var i = 0
            while (i + 1 < data.size) {
                val lo = data[i].toInt() and 0xFF
                val hi = data[i + 1].toInt()
                val s = (lo or (hi shl 8)).toShort().toInt()
                val a = kotlin.math.abs(s)
                if (a > peak) peak = a
                sumSq += s.toLong() * s.toLong()
                cnt++
                i += 64
            }
            lastPcmPeak = peak
            if (cnt > 0) lastPcmRms = kotlin.math.sqrt(sumSq.toDouble() / cnt).toInt()
            // 采样快照：首 16 个 PCM 采样，诊断电流声时对比 host 采集波形
            if (data.size >= 32) {
                val sb = StringBuilder(96)
                for (k in 0 until 16) {
                    if (k > 0) sb.append(',')
                    val lo = data[k*2].toInt() and 0xFF
                    val hi = data[k*2+1].toInt() and 0xFF
                    val sv = (lo or (hi shl 8)).toShort().toInt()
                    sb.append(sv)
                }
                lastPcmSnap = sb.toString()
            }
            val written = audioTrack?.write(data, 0, data.size)
            if (written != null && written > 0) {
                playDecodedCount++
                playBytesCount += written
            } else {
                playDroppedCount++
            }
        } catch (t: Throwable) {
            playDroppedCount++
            Log.w(TAG, "写入 AudioTrack 失败: ${t.message}")
        }
    }

    fun stopPlayback() {
        try {
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.release()
        } catch (_: Throwable) {}
        audioTrack = null
    }
}