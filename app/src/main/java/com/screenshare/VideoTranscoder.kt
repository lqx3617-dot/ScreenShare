package com.screenshare

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.util.Log
import android.view.Surface
import android.os.SystemClock
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 视频压缩转码器：把共享方本机视频统一转码为 H.264(AVC) + AAC 的 MP4，
 * 最长边缩到 MAX_DIM 以内、视频码率限到 VIDEO_BITRATE，显著缩小体积后再分块上传。
 *
 * 实现：MediaCodec 硬解码 → SurfaceTexture/EGL 渲染缩放 → MediaCodec 硬编码（surface 输入），
 * 音频走 PCM 转码；双轨经 MediaMuxer 合成。surface 方案免手写 YUV 颜色空间转换，兼容性最好。
 * 输出 MP4 带 KEY_ROTATION 提示，播放器自动旋转显示。
 */
object VideoTranscoder {
    private const val TAG = "VideoTranscoder"
    private const val MAX_DIM = 720
    private const val VIDEO_BITRATE = 2_000_000
    private const val AUDIO_BITRATE = 96_000
    private const val IFRAME_INTERVAL = 2
    private const val TIMEOUT_US = 15_000L

    /**
     * 转码 content:// 视频到 outPath。失败抛异常。
     * onProgress: 0..1（按已转码时长/总时长粗估，仅视频轨驱动）。
     */
    fun transcode(context: Context, uri: Uri, outPath: String, onProgress: (Float) -> Unit = {}) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
        } catch (e: Exception) {
            extractor.release()
            throw java.io.IOException("无法读取视频: ${e.message}")
        }

        var videoTrack = -1
        var audioTrack = -1
        var vFmt: MediaFormat? = null
        var aFmt: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
            when {
                mime.startsWith("video/") && videoTrack < 0 -> { videoTrack = i; vFmt = f }
                mime.startsWith("audio/") && audioTrack < 0 -> { audioTrack = i; aFmt = f }
            }
        }
        if (videoTrack < 0 || vFmt == null) {
            extractor.release()
            throw java.io.IOException("视频中没有视频轨道")
        }

        val srcW = vFmt.getInteger(MediaFormat.KEY_WIDTH)
        val srcH = vFmt.getInteger(MediaFormat.KEY_HEIGHT)
        val rotation = if (vFmt.containsKey(MediaFormat.KEY_ROTATION)) vFmt.getInteger(MediaFormat.KEY_ROTATION) else 0
        val frameRate = if (vFmt.containsKey(MediaFormat.KEY_FRAME_RATE)) vFmt.getInteger(MediaFormat.KEY_FRAME_RATE) else 30
        // 显示方向上的最长边决定缩放比例；输出尺寸按旋转后的方向计算
        // （旋转 90/270 时宽高互换），渲染层用 texMatrix 摆正后输出已是正立画面，
        // 因此 encoder/muxer 不再写任何 rotation，避免播放端重复旋转
        val dispW = if (rotation == 90 || rotation == 270) srcH else srcW
        val dispH = if (rotation == 90 || rotation == 270) srcW else srcH
        val scale = minOf(1f, MAX_DIM.toFloat() / maxOf(dispW, dispH))
        val outW = (dispW * scale).toInt().let { it - (it % 2) }.coerceAtLeast(2)
        val outH = (dispH * scale).toInt().let { it - (it % 2) }.coerceAtLeast(2)

        val durationUs = if (vFmt.containsKey(MediaFormat.KEY_DURATION)) vFmt.getLong(MediaFormat.KEY_DURATION) else 0L

        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val encFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, outW, outH)
        encFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        encFormat.setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_BITRATE)
        encFormat.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate.coerceAtLeast(1))
        encFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INTERVAL)
        // 不设 KEY_ROTATION：surface 输出时解码器通过 texMatrix 摆正，渲染已是正立帧，
        // encoder 再旋转会导致画面方向错误
        encoder.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val encoderSurface = encoder.createInputSurface()

        val renderer = SurfaceRender(outW, outH, encoderSurface)
        val decoder = MediaCodec.createDecoderByType(vFmt.getString(MediaFormat.KEY_MIME)!!)
        decoder.configure(vFmt, renderer.inputSurface, null, 0)

        // 音频链（buffer 模式）
        var audioDecoder: MediaCodec? = null
        var audioEncoder: MediaCodec? = null
        var audioExtractor: MediaExtractor? = null
        if (audioTrack >= 0 && aFmt != null) {
            val aMime = aFmt.getString(MediaFormat.KEY_MIME) ?: ""
            if (aMime.startsWith("audio/")) {
                val sampleRate = if (aFmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) aFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
                val channels = if (aFmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) aFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 1
                audioDecoder = MediaCodec.createDecoderByType(aMime)
                audioDecoder!!.configure(aFmt, null, null, 0)
                val aEncFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels)
                aEncFormat.setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE)
                aEncFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                audioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
                audioEncoder!!.configure(aEncFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                audioExtractor = MediaExtractor()
                audioExtractor!!.setDataSource(context, uri, null)
                audioExtractor!!.selectTrack(audioTrack)
            }
        }

        var muxer: MediaMuxer? = null
        var vOutFmt: MediaFormat? = null
        var aOutFmt: MediaFormat? = null
        var videoMuxTrack = -1
        var audioMuxTrack = -1
        var muxStarted = false
        var writeErr: Throwable? = null

        fun ensureMuxer() {
            if (muxStarted) return
            val vf = vOutFmt ?: return
            val af = aOutFmt
            if (audioEncoder != null && af == null) return
            try {
                val m = MediaMuxer(outPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                videoMuxTrack = m.addTrack(vf)
                if (audioEncoder != null && af != null) audioMuxTrack = m.addTrack(af)
                m.start()
                muxer = m
                muxStarted = true
                Log.i(TAG, "muxer 启动 track=v:$videoMuxTrack a:$audioMuxTrack")
            } catch (t: Throwable) {
                writeErr = t
            }
        }

        fun writeChunk(track: Int, buf: ByteBuffer, info: MediaCodec.BufferInfo) {
            val m = muxer ?: return
            if (track < 0) return
            try {
                buf.position(info.offset)
                buf.limit(info.offset + info.size)
                m.writeSampleData(track, buf, info)
            } catch (t: Throwable) {
                if (writeErr == null) writeErr = t
            }
        }

        try {
            extractor.selectTrack(videoTrack)
            audioExtractor?.let { ae -> /* 已 selectTrack */ }

            encoder.start()
            decoder.start()
            audioDecoder?.start()
            audioEncoder?.start()

            val info = MediaCodec.BufferInfo()
            val aInfo = MediaCodec.BufferInfo()
            var vInputDone = false
            var vDecoderEos = false
            var vSignalSent = false
            var vOutputEos = false
            var aInputDone = false
            var aOutputEos = false
            var aEosQueued = false
            val aPcm = AudioPcmQueue()

            // 安全计数器防止死循环
            var spins = 0
            // 真实时间超时保护：按视频时长给上限（时长×3 + 2 分钟），无时长信息时 10 分钟；
            // 防止编解码器异常（如驱动卡死）时无限空转，卡住相册同步后台线程
            val startWall = SystemClock.elapsedRealtime()
            val deadline = if (durationUs > 0) {
                startWall + durationUs / 1000 * 3 + 120_000L
            } else {
                startWall + 10 * 60_000L
            }
            while (!(vSignalSent && vOutputEos) || (audioEncoder != null && !aOutputEos)) {
                if (writeErr != null) throw java.io.IOException("转码写入失败: ${writeErr?.message}")
                if (SystemClock.elapsedRealtime() > deadline) {
                    Log.w(TAG, "转码超时保护退出（时长=${durationUs / 1_000_000}s，已转 ${(SystemClock.elapsedRealtime() - startWall) / 1000}s）")
                    break
                }

                // ===== 视频喂输入 =====
                if (!vInputDone) {
                    val inIdx = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inIdx >= 0) {
                        if (extractor.sampleTime >= 0) {
                            val buf = decoder.getInputBuffer(inIdx)!!
                            buf.clear()
                            val size = extractor.readSampleData(buf, 0)
                            val pts = extractor.sampleTime
                            val flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                            decoder.queueInputBuffer(inIdx, 0, size, pts, flags)
                            extractor.advance()
                        } else {
                            decoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            vInputDone = true
                        }
                    }
                }
                // ===== 视频取解码输出 → 渲染进 encoder =====
                val outIdx = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    outIdx >= 0 -> {
                        if (info.size > 0) renderer.render(info.presentationTimeUs)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) vDecoderEos = true
                        decoder.releaseOutputBuffer(outIdx, true)
                        if (durationUs > 0) onProgress((info.presentationTimeUs.toFloat() / durationUs).coerceIn(0f, 1f))
                    }
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {}
                    else -> {}
                }
                // decoder 排空完成 → 通知 encoder 输入结束
                if (vDecoderEos && !vSignalSent) {
                    try { encoder.signalEndOfInputStream() } catch (t: Throwable) {}
                    vSignalSent = true
                }
                // ===== 视频 drain encoder =====
                var vDrained = true
                while (vDrained) {
                    val eo = encoder.dequeueOutputBuffer(info, 0)
                    when {
                        eo >= 0 -> {
                            val buf = encoder.getOutputBuffer(eo)!!
                            if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                                info.size = 0
                            } else if (info.size > 0) {
                                if (vOutFmt == null) vOutFmt = encoder.outputFormat
                                ensureMuxer()
                                writeChunk(videoMuxTrack, buf, info)
                            }
                            if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) vOutputEos = true
                            encoder.releaseOutputBuffer(eo, false)
                        }
                        eo == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { vOutFmt = encoder.outputFormat; ensureMuxer() }
                        else -> vDrained = false
                    }
                }

                // ===== 音频喂输入 =====
                if (audioDecoder != null && audioExtractor != null && !aInputDone) {
                    val aiIdx = audioDecoder.dequeueInputBuffer(0)
                    if (aiIdx >= 0) {
                        if (audioExtractor.sampleTime >= 0) {
                            val buf = audioDecoder.getInputBuffer(aiIdx)!!
                            buf.clear()
                            val size = audioExtractor.readSampleData(buf, 0)
                            val pts = audioExtractor.sampleTime
                            val flags = if (audioExtractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                            audioDecoder.queueInputBuffer(aiIdx, 0, size, pts, flags)
                            audioExtractor.advance()
                        } else {
                            audioDecoder.queueInputBuffer(aiIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            aInputDone = true
                        }
                    }
                }
                // ===== 音频取解码 PCM → 队列 =====
                if (audioDecoder != null) {
                    var aGot = true
                    while (aGot) {
                        val aoIdx = audioDecoder.dequeueOutputBuffer(aInfo, 0)
                        when {
                            aoIdx >= 0 -> {
                                if (aInfo.size > 0) {
                                    val buf = audioDecoder.getOutputBuffer(aoIdx)!!
                                    val pcm = ByteArray(aInfo.size)
                                    buf.position(aInfo.offset)
                                    buf.get(pcm)
                                    aPcm.offer(pcm, aInfo.presentationTimeUs)
                                }
                                audioDecoder.releaseOutputBuffer(aoIdx, false)
                            }
                            aoIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {}
                            else -> aGot = false
                        }
                    }
                }
                // ===== 音频喂 encoder =====
                if (audioEncoder != null) {
                    var aFeeded = true
                    while (aFeeded) {
                        val ei = audioEncoder.dequeueInputBuffer(0)
                        if (ei >= 0) {
                            val pcm = aPcm.poll()
                            if (pcm != null) {
                                val buf = audioEncoder.getInputBuffer(ei)!!
                                buf.clear()
                                buf.put(pcm.data)
                                audioEncoder.queueInputBuffer(ei, 0, pcm.data.size, pcm.ptsUs, 0)
                            } else if (aInputDone) {
                                if (!aEosQueued) {
                                    audioEncoder.queueInputBuffer(ei, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                    aEosQueued = true
                                }
                                aFeeded = false
                            } else {
                                audioEncoder.queueInputBuffer(ei, 0, 0, 0, 0)
                                aFeeded = false
                            }
                        } else aFeeded = false
                    }
                }
                // ===== 音频 drain encoder =====
                if (audioEncoder != null) {
                    var aDrained = true
                    while (aDrained) {
                        val eo = audioEncoder.dequeueOutputBuffer(aInfo, 0)
                        when {
                            eo >= 0 -> {
                                val buf = audioEncoder.getOutputBuffer(eo)!!
                                if ((aInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                                    aInfo.size = 0
                                } else if (aInfo.size > 0) {
                                    if (aOutFmt == null) aOutFmt = audioEncoder.outputFormat
                                    ensureMuxer()
                                    writeChunk(audioMuxTrack, buf, aInfo)
                                }
                                if ((aInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) aOutputEos = true
                                audioEncoder.releaseOutputBuffer(eo, false)
                            }
                            eo == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { aOutFmt = audioEncoder.outputFormat; ensureMuxer() }
                            else -> aDrained = false
                        }
                    }
                }

                spins++
                if (spins > 100_000_000) {
                    Log.w(TAG, "转码超时保护退出")
                    break
                }
            }

            // 视频 encoder 结束：信号 + 排空到 EOS
            if (!vOutputEos) {
                try { encoder.signalEndOfInputStream() } catch (t: Throwable) {}
                drainVideo(encoder, { writeErr }, info) { buf, bi ->
                    if ((bi.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && bi.size > 0) {
                        if (vOutFmt == null) vOutFmt = encoder.outputFormat
                        ensureMuxer()
                        writeChunk(videoMuxTrack, buf, bi)
                    }
                    if ((bi.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) vOutputEos = true
                }
            }
            // 音频 encoder 排空到 EOS
            if (audioEncoder != null && !aOutputEos) {
                drainAudio(audioEncoder, aInfo) { buf, bi ->
                    if ((bi.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && bi.size > 0) {
                        if (aOutFmt == null) aOutFmt = audioEncoder.outputFormat
                        ensureMuxer()
                        writeChunk(audioMuxTrack, buf, bi)
                    }
                    if ((bi.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) aOutputEos = true
                }
            }

            if (writeErr != null) throw java.io.IOException("转码写入失败: ${writeErr?.message}")
            if (!muxStarted) {
                // 理论不会发生，兜底建 muxer 防 muxer.stop NPE
                ensureMuxer()
                if (!muxStarted) throw java.io.IOException("编码器未产出数据")
            }
            val m = muxer
            if (m == null) throw java.io.IOException("编码器未产出数据")
            m.stop()
            m.release()
            muxer = null
            onProgress(1f)
            Log.i(TAG, "转码完成 ${srcW}x$srcH rot=$rotation -> ${outW}x$outH -> $outPath")
        } finally {
            try { encoderSurface.release() } catch (t: Throwable) {}
            renderer.release()
            try { val mm = muxer; if (mm != null) mm.release() } catch (t: Throwable) {}
            try { encoder.stop() } catch (t: Throwable) {}
            try { encoder.release() } catch (t: Throwable) {}
            try { decoder.stop() } catch (t: Throwable) {}
            try { decoder.release() } catch (t: Throwable) {}
            try { audioDecoder?.stop() } catch (t: Throwable) {}
            try { audioDecoder?.release() } catch (t: Throwable) {}
            try { audioEncoder?.stop() } catch (t: Throwable) {}
            try { audioEncoder?.release() } catch (t: Throwable) {}
            try { audioExtractor?.release() } catch (t: Throwable) {}
            extractor.release()
        }
    }

    private fun drainVideo(
        encoder: MediaCodec,
        getErr: () -> Throwable?,
        info: MediaCodec.BufferInfo,
        onChunk: (ByteBuffer, MediaCodec.BufferInfo) -> Unit
    ) {
        var done = false
        while (!done) {
            if (getErr() != null) return
            val idx = encoder.dequeueOutputBuffer(info, TIMEOUT_US)
            when {
                idx >= 0 -> {
                    val buf = encoder.getOutputBuffer(idx)!!
                    onChunk(buf, info)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) done = true
                    encoder.releaseOutputBuffer(idx, false)
                }
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {}
                else -> Thread.sleep(5)
            }
        }
    }

    private fun drainAudio(encoder: MediaCodec, info: MediaCodec.BufferInfo, onChunk: (ByteBuffer, MediaCodec.BufferInfo) -> Unit) {
        var done = false
        while (!done) {
            val idx = encoder.dequeueOutputBuffer(info, TIMEOUT_US)
            when {
                idx >= 0 -> {
                    val buf = encoder.getOutputBuffer(idx)!!
                    onChunk(buf, info)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) done = true
                    encoder.releaseOutputBuffer(idx, false)
                }
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {}
                else -> Thread.sleep(5)
            }
        }
    }

    /** 音频 PCM 队列：限制内存上限，避免长视频/高码率爆内存 */
    private class AudioPcmQueue {
        private val items = ArrayDeque<PcmChunk>()
        private var bytes = 0
        @Synchronized fun offer(data: ByteArray, ptsUs: Long) {
            items.addLast(PcmChunk(data, ptsUs))
            bytes += data.size
            while (bytes > 8 * 1024 * 1024 && items.size > 4) {
                val removed = items.removeFirst()
                bytes -= removed.data.size
            }
        }
        @Synchronized fun poll(): PcmChunk? = items.removeFirstOrNull()?.also { bytes -= it.data.size }
        @Synchronized fun size(): Int = items.size
    }
    private data class PcmChunk(val data: ByteArray, val ptsUs: Long)

    /**
     * EGL + SurfaceTexture 渲染器：把解码器输出帧缩放到编码器目标尺寸并渲染进 encoder surface。
     * inputSurface 供 decoder.configure 使用；EGL window surface 绑定 encoder 输入 surface。
     */
    private class SurfaceRender(outW: Int, outH: Int, encoderInputSurface: Surface) {
        private var eglDisplay: EGLDisplay? = null
        private var eglContext: EGLContext? = null
        private var eglSurface: EGLSurface? = null
        private var program = 0
        private var texId = 0
        private var initOk = false
        private val surfaceTexture = SurfaceTexture(0)
        private val texMatrix = FloatArray(16)

        val inputSurface: Surface = Surface(surfaceTexture)

        init {
            try {
                surfaceTexture.setDefaultBufferSize(outW, outH)
                initEgl(encoderInputSurface)
                initGl()
                initOk = true
            } catch (t: Throwable) {
                Log.w(TAG, "SurfaceRender 初始化失败: ${t.message}")
                release()
            }
        }

        private fun initEgl(encoderSurface: Surface) {
            val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == EGL14.EGL_NO_DISPLAY) throw RuntimeException("eglGetDisplay failed")
            val version = IntArray(2)
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) throw RuntimeException("eglInitialize failed")

            val attribs = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGLExt.EGL_RECORDABLE_ANDROID, 1,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfig = IntArray(1)
            if (!EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, numConfig, 0) || numConfig[0] <= 0) {
                throw RuntimeException("eglChooseConfig failed")
            }
            val context = EGL14.eglCreateContext(
                display, configs[0], EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
            )
            if (context == EGL14.EGL_NO_CONTEXT) throw RuntimeException("eglCreateContext failed")

            val window = EGL14.eglCreateWindowSurface(display, configs[0], encoderSurface, intArrayOf(EGL14.EGL_NONE), 0)
            if (window == EGL14.EGL_NO_SURFACE) throw RuntimeException("eglCreateWindowSurface failed")

            if (!EGL14.eglMakeCurrent(display, window, window, context)) {
                throw RuntimeException("eglMakeCurrent failed")
            }
            eglDisplay = display
            eglContext = context
            eglSurface = window
        }

        private fun initGl() {
            val vsh = """
                attribute vec4 aPos;
                attribute vec2 aTex;
                varying vec2 vTex;
                void main() {
                  gl_Position = aPos;
                  vTex = aTex;
                }
            """.trimIndent()
            val fsh = """
                #extension GL_OES_EGL_image_external : require
                precision mediump float;
                uniform samplerExternalOES sTex;
                uniform mat4 uTexMatrix;
                varying vec2 vTex;
                void main() {
                  gl_FragColor = texture2D(sTex, (uTexMatrix * vec4(vTex, 0.0, 1.0)).xy);
                }
            """.trimIndent()
            program = createProgram(vsh, fsh)

            val tex = IntArray(1)
            GLES20.glGenTextures(1, tex, 0)
            texId = tex[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        }

        private fun createProgram(vsh: String, fsh: String): Int {
            val vs = compileShader(GLES20.GL_VERTEX_SHADER, vsh)
            val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fsh)
            val p = GLES20.glCreateProgram()
            GLES20.glAttachShader(p, vs)
            GLES20.glAttachShader(p, fs)
            GLES20.glLinkProgram(p)
            val status = IntArray(1)
            GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, status, 0)
            if (status[0] == 0) {
                val log = GLES20.glGetProgramInfoLog(p)
                throw RuntimeException("link failed: $log")
            }
            GLES20.glDeleteShader(vs)
            GLES20.glDeleteShader(fs)
            return p
        }

        private fun compileShader(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src)
            GLES20.glCompileShader(s)
            val status = IntArray(1)
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) {
                val log = GLES20.glGetShaderInfoLog(s)
                GLES20.glDeleteShader(s)
                throw RuntimeException("shader compile failed: $log")
            }
            return s
        }

        /** 渲染一帧到 encoder surface（每解码一帧调用一次） */
        fun render(ptsUs: Long) {
            if (!initOk) return
            try {
                surfaceTexture.updateTexImage()
                surfaceTexture.getTransformMatrix(texMatrix)

                GLES20.glUseProgram(program)
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
                GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uTexMatrix"), 1, false, texMatrix, 0)

                // 交错缓冲：每顶点 4 个 float（pos.x, pos.y, tex.u, tex.v）
                val verts = floatArrayOf(
                    -1f, -1f, 0f, 0f,
                    1f, -1f, 1f, 0f,
                    -1f, 1f, 0f, 1f,
                    1f, 1f, 1f, 1f
                )
                val buf = ByteBuffer.allocateDirect(verts.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
                buf.put(verts).position(0)

                val stride = 4 * 4
                val aPos = GLES20.glGetAttribLocation(program, "aPos")
                val aTex = GLES20.glGetAttribLocation(program, "aTex")
                GLES20.glEnableVertexAttribArray(aPos)
                GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, stride, buf)
                buf.position(2)
                GLES20.glEnableVertexAttribArray(aTex)
                GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, stride, buf)
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                GLES20.glDisableVertexAttribArray(aPos)
                GLES20.glDisableVertexAttribArray(aTex)

                EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, ptsUs * 1000)
                EGL14.eglSwapBuffers(eglDisplay, eglSurface)
            } catch (t: Throwable) {
                Log.w(TAG, "render 异常: ${t.message}")
            }
        }

        fun release() {
            try { EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT) } catch (t: Throwable) {}
            try { if (eglSurface != null) EGL14.eglDestroySurface(eglDisplay, eglSurface) } catch (t: Throwable) {}
            try { if (eglContext != null) EGL14.eglDestroyContext(eglDisplay, eglContext) } catch (t: Throwable) {}
            try { if (eglDisplay != null) EGL14.eglTerminate(eglDisplay) } catch (t: Throwable) {}
            try { inputSurface.release() } catch (t: Throwable) {}
            try { surfaceTexture.release() } catch (t: Throwable) {}
        }
    }
}
