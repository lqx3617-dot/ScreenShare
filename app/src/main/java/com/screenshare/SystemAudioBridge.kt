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
    // 阈值 500 ≈ -36dBFS：真正的无声（无播放/暂停/切后台）才触发；音乐弱音一般高于此，避免误伤音质。
    // 观看方 AudioTrack 无新数据时自然输出静默，因此观看方无需任何改动，向后兼容。
    private const val SILENCE_PEAK = 500
    // ADPCM 压缩（v1.124）：系统音频 48kHz 单声道 16bit = 768kbps，走 DataChannel 在弱网下严重挤占视频带宽。
    // IMA ADPCM 4bit 压缩后降至 192kbps（4:1），音质对屏幕共享场景足够。双端同步实现，带帧头标记。
    // 帧格式：首字节 0x01 表示后续为 IMA ADPCM 数据（4bit 打包，960 采样→480 字节）；0x00/其他为原始 PCM16（旧版兼容）
    private const val FRAME_FLAG_ADPCM = 0x01.toByte()
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

    /** 将一段 PCM16 编码为 IMA ADPCM 4bit（含帧头 1 字节），返回 null 表示输入为空/长度非法 */
    fun encodeAdpcm(pcm: ByteArray): ByteArray? {
        val n = pcm.size / 2
        if (n <= 0) return null
        // 每 2 个采样打包 1 字节（低 4 位=第 1 采样，高 4 位=第 2 采样）
        val outBytes = (n + 1) / 2
        val out = ByteArray(outBytes + 1)
        out[0] = FRAME_FLAG_ADPCM
        var predicted = encPredicted
        var index = encIndex
        var p = 0
        var o = 1
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
        if (frame[0] != FRAME_FLAG_ADPCM) {
            // 旧版共享方/原始 PCM 帧：直接返回（不含帧头？含？——发送端若旧版则为纯 PCM，此处原样交给播放器）
            return frame
        }
        val adpcmLen = frame.size - 1
        // 每 1 字节 ADPCM 解出 2 采样，每采样 2 字节 PCM → 输出字节数 = adpcmLen*4
        // （v1.124 误写 adpcmLen*2 导致数组越界崩溃，播放持续声音时必现）
        val outLen = adpcmLen * 4
        val out = ByteArray(outLen)
        var predicted = decPredicted
        var index = decIndex
        var p = 1
        var o = 0
        while (p < frame.size) {
            val byte = frame[p].toInt() and 0xFF
            val c1 = byte and 0x0F
            val c2 = (byte shr 4) and 0x0F
            val s1 = adpcmDecodeSample(c1, predicted, index)
            predicted = s1.pred; index = s1.idx
            writeSample(out, o++, s1.value)
            val s2 = adpcmDecodeSample(c2, predicted, index)
            predicted = s2.pred; index = s2.idx
            writeSample(out, o++, s2.value)
        }
        decPredicted = predicted
        decIndex = index
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
                        // 静音检测：PCM16 小端，峰值绝对值低于阈值视为静音帧，丢弃不发省带宽。
                        // 无声时 DataChannel 不再每秒发送 96KB 数据，弱网下视频带宽更充足
                        if (isSilent(buf, n)) continue
                        // v1.124: 编码 IMA ADPCM 4bit（1920B → 480B + 1 帧头），弱网下节省 75% 音频带宽
                        encodeAdpcm(buf.copyOf(n))?.let { onPcm(it) }
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
            audioTrack?.write(data, 0, data.size)
        } catch (t: Throwable) {
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