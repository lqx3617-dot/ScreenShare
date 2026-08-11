package com.screenshare

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.projection.MediaProjection
import android.util.Log

/**
 * 系统音频内录/播放桥（v1.133 重写）。
 *
 * 背景：WebRTC Android 标准库的 AudioSource 只能采集麦克风，无法把系统内录音频
 * （视频/音乐播放的声音）注入标准音视频流。因此走 DataChannel 附加通道：
 *   - 共享方：AudioRecord + AudioPlaybackCaptureConfiguration（Android 10+ 内录 API，
 *     依赖 MediaProjection 授权）采集系统 PCM → 分块交给 WebRTCPeer 经 DataChannel 发送
 *   - 观看方：从 DataChannel 接收 PCM → AudioTrack 播放
 * 音画为近似同步（同连接、低延迟、接收端流式播放）。
 *
 * v1.133 重写说明：彻底删除 IMA ADPCM 编解码链路（编码/解码、预测器状态头、
 * 帧序号、校验和）。此前 ADPCM 链路在真机上出现预测器状态发散、帧头与数据
 * 不匹配等异常，且无法完全定位。本版改为原始 PCM16 直传（48kHz 单声道，
 * 768kbps），观看方收到什么就播放什么，无编解码无状态，杜绝一切由压缩/
 * 状态恢复引入的失真。若弱网下带宽压力大，后续再做无状态压缩优化。
 */
object SystemAudioBridge {
    private const val TAG = "SystemAudioBridge"
    private const val SAMPLE_RATE = 48000
    // 20ms/块 = 48000/1000*20 = 960 采样 * 2 字节 = 1920 字节，与 WebRTC 音频包惯例一致
    private const val CHUNK_SAMPLES = 960
    private const val CHUNK_BYTES = CHUNK_SAMPLES * 2

    // ==================== 共享方：采集系统 PCM ====================
    @Volatile private var recordThread: Thread? = null
    @Volatile private var recording = false
    // 采集诊断统计
    @Volatile private var captureFrameCount = 0L
    @Volatile private var captureSentCount = 0L
    @Volatile private var captureReadBytes = 0L
    @Volatile private var captureByteCount = 0L
    // 最近采集 PCM 振幅摘要与快照（诊断采集端是否正常）
    @Volatile private var lastCapPeak = 0
    @Volatile private var lastCapRms = 0
    @Volatile private var lastCapSnap = ""

    /** 采集端诊断摘要（周期性由 MainActivity 上报 /diag） */
    fun captureStats(): String {
        val total = captureFrameCount
        return "capFrames=$total sent=$captureSentCount " +
            "readBytes=$captureReadBytes sentBytes=$captureByteCount peak=$lastCapPeak rms=$lastCapRms " +
            "snap=[$lastCapSnap]"
    }

    fun resetCaptureStats() {
        captureFrameCount = 0; captureSentCount = 0
        captureReadBytes = 0; captureByteCount = 0
        lastCapPeak = 0; lastCapRms = 0; lastCapSnap = ""
    }

    /**
     * 开始内录系统音频（视频/音乐等 Media 声音）。必须在 MediaProjection 授权后调用。
     * @param mp MediaProjection 实例（复用屏幕采集器内部的同一实例）
     * @param onPcm 每 20ms 回调一次 PCM 数据（48kHz 单声道 16bit），回调可直接发送
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
                        // 抽样计算采集振幅（每 32 采样取 1）
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
                        // 采样快照：首 16 个采集 PCM 采样
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
                        // v1.133：原始 PCM 直传，不做编码也不做静音丢弃（保证播放连续，无帧间隙）
                        onPcm(buf.copyOf(n))
                        captureSentCount++
                        captureByteCount += n
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
        try { recordThread?.join(1000) } catch (_: Throwable) {}
        recordThread = null
    }

    // ==================== 观看方：播放系统 PCM ====================
    @Volatile private var audioTrack: AudioTrack? = null
    // 播放诊断统计
    @Volatile private var playFrameCount = 0L
    @Volatile private var playDecodedCount = 0L
    @Volatile private var playDroppedCount = 0L
    @Volatile private var playBytesCount = 0L
    // 最近一次播放 PCM 的振幅摘要与快照（诊断播放端是否正常）
    @Volatile private var lastPcmPeak = 0
    @Volatile private var lastPcmRms = 0
    @Volatile private var lastPcmSnap = ""

    /** 播放端诊断摘要（周期性由 MainActivity 上报 /diag） */
    fun playbackStats(): String {
        return "playFrames=$playFrameCount decoded=$playDecodedCount dropped=$playDroppedCount " +
            "pcmBytes=$playBytesCount peak=$lastPcmPeak rms=$lastPcmRms " +
            "snap=[$lastPcmSnap] track=${if (audioTrack != null) "on" else "off"}"
    }

    fun resetPlaybackStats() {
        playFrameCount = 0; playDecodedCount = 0; playDroppedCount = 0; playBytesCount = 0
        lastPcmPeak = 0; lastPcmRms = 0; lastPcmSnap = ""
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

    /** 观看方写入一段 PCM16 到 AudioTrack（v1.133：DataChannel 收到原始 PCM 直接写入） */
    fun writePcm(data: ByteArray) {
        try {
            playFrameCount++
            // 抽样计算振幅（每 32 采样取 1）
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
            // 采样快照：首 16 个 PCM 采样
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
