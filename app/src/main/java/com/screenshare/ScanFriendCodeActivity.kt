package com.screenshare

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.screenshare.databinding.ActivityScanFriendCodeBinding
import java.util.concurrent.Executors

/**
 * 扫码加好友：CameraX 取景 + ZXing 解码，扫到 6 位好友码后通过 RESULT_OK 返回。
 */
class ScanFriendCodeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityScanFriendCodeBinding
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var decoded = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera()
        else {
            Toast.makeText(this, "需要相机权限才能扫码", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScanFriendCodeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.btnClose.setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            if (decoded || isFinishing) return@addListener
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(cameraExecutor, QrAnalyzer { code -> onDecoded(code) }) }
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (e: Exception) {
                AppLogger.app("[SCAN] 相机绑定失败：${e.message}")
                Toast.makeText(this, "无法打开相机", Toast.LENGTH_SHORT).show()
                finish()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun onDecoded(code: String) {
        if (decoded) return
        decoded = true
        AppLogger.app("[SCAN] 扫到好友码 $code")
        setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_FRIEND_CODE, code))
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    /** ZXing 分析器：YUV 帧转灰度解码，识别后回调主线程 */
    private class QrAnalyzer(private val onCode: (String) -> Unit) : ImageAnalysis.Analyzer {
        private val reader = MultiFormatReader()

        override fun analyze(image: ImageProxy) {
            try {
                val plane = image.planes[0]
                val buf = plane.buffer
                val data = ByteArray(buf.remaining())
                buf.get(data)
                // QR 码旋转不变（解码器内部尝试各方向），直接用原始帧的宽高即可
                val src = PlanarYUVLuminanceSource(
                    data, image.width, image.height,
                    0, 0, image.width, image.height, false
                )
                val bitmap = BinaryBitmap(HybridBinarizer(src))
                val result = runCatching { reader.decode(bitmap) }.getOrNull()
                if (result != null) {
                    val text = result.text?.trim()?.uppercase().orEmpty()
                    if (text.isNotEmpty()) {
                        // 回主线程再 finish，避免在分析线程动 UI
                        android.os.Handler(android.os.Looper.getMainLooper()).post { onCode(text) }
                    }
                }
            } finally {
                image.close()
            }
        }
    }

    companion object {
        const val EXTRA_FRIEND_CODE = "friendCode"
    }
}
