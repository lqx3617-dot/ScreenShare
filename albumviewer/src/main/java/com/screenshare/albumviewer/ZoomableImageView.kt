package com.screenshare.albumviewer

import android.content.Context
import android.graphics.Matrix
import android.graphics.PointF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.ImageView

/**
 * 支持手势缩放的 ImageView：
 * - 双击放大 / 再次双击还原
 * - 双指捏合缩放（3x 上限）
 * - 放大后单指拖动平移
 * - 单击回调（可关大图）
 */
class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ImageView(context, attrs, defStyleAttr) {

    var onSingleTap: (() -> Unit)? = null

    private val matrix = Matrix()
    private val baseMatrix = Matrix()
    private var baseScale = 1f
    private var maxScale = 4f

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val target = currentScale() * detector.scaleFactor
            matrix.postScale(detector.scaleFactor, detector.scaleFactor, detector.focusX, detector.focusY)
            clampScale()
            fixTranslation()
            imageMatrix = matrix
            return true
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (currentScale() > baseScale * 1.2f) {
                resetZoom()
            } else {
                matrix.postScale(2f, 2f, e.x, e.y)
                clampScale()
                fixTranslation()
                imageMatrix = matrix
            }
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            onSingleTap?.invoke()
            return true
        }

        override fun onDown(e: MotionEvent): Boolean = true
    })

    override fun setImageMatrix(m: Matrix) {
        super.setImageMatrix(m)
    }

    private var lastX = 0f
    private var lastY = 0f
    private var moved = false

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                moved = false
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                if (scaleDetector.isInProgress) return true
                val dx = event.x - lastX
                val dy = event.y - lastY
                if (Math.abs(dx) + Math.abs(dy) > 8f) moved = true
                if (currentScale() > baseScale * 1.05f && moved) {
                    matrix.postTranslate(dx, dy)
                    fixTranslation()
                    imageMatrix = matrix
                }
                lastX = event.x
                lastY = event.y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    private fun currentScale(): Float {
        val v = FloatArray(9)
        matrix.getValues(v)
        return v[Matrix.MSCALE_X]
    }

    private fun clampScale() {
        val v = FloatArray(9)
        matrix.getValues(v)
        val s = v[Matrix.MSCALE_X].coerceIn(baseScale, baseScale * maxScale)
        val ratio = s / v[Matrix.MSCALE_X]
        if (ratio != 1f) {
            matrix.postScale(ratio, ratio, width / 2f, height / 2f)
        }
    }

    private fun fixTranslation() {
        val v = FloatArray(9)
        matrix.getValues(v)
        val s = v[Matrix.MSCALE_X]
        val tx = v[Matrix.MTRANS_X]
        val ty = v[Matrix.MTRANS_Y]

        val imgW = drawable?.intrinsicWidth ?: 0
        val imgH = drawable?.intrinsicHeight ?: 0
        if (imgW == 0 || imgH == 0) return

        val drawnW = imgW * s
        val drawnH = imgH * s
        val viewW = width.toFloat()
        val viewH = height.toFloat()

        val minX = viewW - drawnW
        val maxX = 0f
        val minY = viewH - drawnH
        val maxY = 0f

        var newTx = tx
        var newTy = ty
        if (drawnW <= viewW) newTx = (viewW - drawnW) / 2f
        else newTx = tx.coerceIn(minX, maxX)
        if (drawnH <= viewH) newTy = (viewH - drawnH) / 2f
        else newTy = ty.coerceIn(minY, maxY)

        if (newTx != tx || newTy != ty) {
            matrix.postTranslate(newTx - tx, newTy - ty)
        }
    }

    fun resetZoom() {
        matrix.set(baseMatrix)
        imageMatrix = matrix
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        initBaseMatrix()
    }

    private fun initBaseMatrix() {
        val d = drawable ?: return
        val vw = width.toFloat()
        val vh = height.toFloat()
        val iw = d.intrinsicWidth.toFloat()
        val ih = d.intrinsicHeight.toFloat()
        if (iw == 0f || ih == 0f) return

        baseMatrix.reset()
        val fit = Math.min(vw / iw, vh / ih).coerceAtMost(1f)
        baseScale = fit
        baseMatrix.postScale(fit, fit)
        baseMatrix.postTranslate((vw - iw * fit) / 2f, (vh - ih * fit) / 2f)
        matrix.set(baseMatrix)
        imageMatrix = matrix
    }

    override fun setImageBitmap(bm: android.graphics.Bitmap?) {
        super.setImageBitmap(bm)
        if (bm != null) {
            post { initBaseMatrix() }
        }
    }

    override fun setImageDrawable(d: android.graphics.drawable.Drawable?) {
        super.setImageDrawable(d)
        if (d != null) {
            post { initBaseMatrix() }
        }
    }
}
