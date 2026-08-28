package com.screenshare

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.projection.MediaProjection
import android.util.Log

/**
 * 系统音频内录/播放桥（v1.134 精简）。
 *
 * 背景：WebRTC Android 标准库的 AudioSource 只能采集麦克风，无法把系统内录音频
 * （视频/音乐播放的声音）注入标准音视频流。因此走 DataChannel 附加通道：
 *   - 共享方：AudioRecord + AudioPlaybackCaptureConfiguration（Android 10+ 内录 API，
 *     依赖 MediaProjection 授权）采集系统 PCM → 分块交给 WebRTCPeer 经 DataChannel 发送
 *   - 观看方：从 DataChannel 接收 PCM → AudioTrack 播放
 * 音画为近似同步（同连接、低延迟、接收端流式播放）。
 *
 * v1.133 重写：彻底删除 IMA ADPCM 编解码链路，改为原始 PCM16 直传（48kHz 单声道
 * 768kbps），观看方收到什么就播放什么，无编解码无状态，杜绝一切由压缩/状态恢复
 * 引入的失真（此前 5 个版本的电流声无法在编解码层定位，PCM 直传后根治）。
 * v1.134：移除 v1.130~v1.132 遗留的振幅/RMS/波形快照诊断（PCM 直传后已无诊断价值，
 * 纯每 20ms 计算开销），保留最小状态：仅采集/播放启停，无任何周期统计。
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
    // 会话 ID：每次 startCapture 递增，旧采集线程 read 返回后若 ID 不匹配则丢弃数据，
    // 防止 stopCapture 后阻塞在 record.read 的旧线程把上一场 PCM 灌进新会话造成串音。
    @Volatile private var captureSession = 0

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
        captureSession++
        val mySession = captureSession
        recording = true
        val thread = Thread {
            val buf = ByteArray(CHUNK_BYTES)
            try {
                record.startRecording()
                Log.d(TAG, "系统音频内录开始 (48kHz mono)")
                while (recording && mySession == captureSession) {
                    val n = record.read(buf, 0, CHUNK_BYTES)
                    if (n > 0 && recording && mySession == captureSession) {
                        // v1.133：原始 PCM 直传，不做编码也不做静音丢弃（保证播放连续，无帧间隙）
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
        try { recordThread?.join(1000) } catch (_: Throwable) {}
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

    /** 观看方写入一段 PCM16 到 AudioTrack（v1.133：DataChannel 收到原始 PCM 直接写入） */
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
