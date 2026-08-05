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
                        onPcm(buf.copyOf(n))
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