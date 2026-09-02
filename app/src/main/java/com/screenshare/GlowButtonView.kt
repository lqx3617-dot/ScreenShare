package com.screenshare

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * 流动光斑按钮：黄橙渐变底 + 内部多个半透明模糊光斑循环移动。
 * 对应 Web 端「加入会议」CTA 的 .uiverse 流动光斑效果。
 * 光斑用带透明度的径向渐变模拟模糊（无需 RenderScript/blur，性能安全）。
 */
class GlowButtonView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 16 * resources.displayMetrics.scaledDensity
        typeface = Typeface.DEFAULT_BOLD
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xE6FFDF34.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val bodyRect = RectF()
    private val dp = resources.displayMetrics.density
    // 圆角半径：随按钮高度比例（52dp 按钮约 20dp 圆角，越高越大），适配不同按钮尺寸
    private val radius = (18 * dp).coerceAtLeast(12 * dp)
    // 光斑圆心相对按钮的归一化坐标（0..1）+ 移动振幅
    private class Blob(
        val nx: Float, val ny: Float, val r: Float,
        val ampX: Float, val ampY: Float, val phase: Float, val speed: Float,
        val color: Int,
    )
    // 12 个光斑：颜色取玫瑰/蜜桃/柠檬（与温柔浪漫主题协调，橙黄底衬托）
    private val blobs = listOf(
        Blob(0.10f, 0.30f, 20f, 0.18f, 0.12f, 0.00f, 1.0f, 0x80FFE35C.toInt()),
        Blob(0.30f, 0.10f, 16f, 0.22f, 0.16f, 1.04f, 0.9f, 0x551A23FF.toInt()),
        Blob(0.55f, 0.80f, 22f, 0.14f, 0.20f, 2.10f, 0.8f, 0x66E21BDA.toInt()),
        Blob(0.75f, 0.25f, 18f, 0.20f, 0.14f, 3.20f, 1.1f, 0x80FFE35C.toInt()),
        Blob(0.90f, 0.70f, 20f, 0.16f, 0.18f, 4.30f, 1.0f, 0x66E21BDA.toInt()),
        Blob(0.20f, 0.85f, 17f, 0.22f, 0.16f, 5.40f, 0.85f, 0x551A23FF.toInt()),
        Blob(0.42f, 0.45f, 15f, 0.18f, 0.14f, 0.52f, 0.75f, 0x80FFE35C.toInt()),
        Blob(0.65f, 0.55f, 19f, 0.15f, 0.18f, 1.57f, 1.05f, 0x66E21BDA.toInt()),
        Blob(0.82f, 0.12f, 16f, 0.17f, 0.15f, 2.62f, 0.9f, 0x80FFE35C.toInt()),
        Blob(0.12f, 0.60f, 18f, 0.20f, 0.17f, 3.67f, 1.15f, 0x551A23FF.toInt()),
        Blob(0.35f, 0.68f, 15f, 0.16f, 0.20f, 4.72f, 0.8f, 0x80FFE35C.toInt()),
        Blob(0.72f, 0.88f, 17f, 0.18f, 0.14f, 5.76f, 1.0f, 0x66E21BDA.toInt()),
    )

    private var progress = 0f // 0..1 动画相位，所有光斑共用
    private var label: String = ""
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val blobPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 7000
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { progress = it.animatedValue as Float; invalidate() }
    }

    init {
        isClickable = true
        setBackgroundColor(Color.TRANSPARENT)
        isFocusable = true
        // 保留 Material ripple 视觉：点击时有轻微提亮反馈
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (windowVisibility == VISIBLE) animator.start()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator.cancel()
    }

    /** 页面不可见（息屏/被遮挡/切后台）时暂停光斑动画，回前台恢复，避免白耗电 */
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) {
            if (isAttachedToWindow && !animator.isRunning) animator.start()
        } else {
            animator.cancel()
        }
    }

    fun setLabel(text: String) {
        label = text
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // 文案字号随按钮高度自适应（高约 52dp 时约 16sp）
        textPaint.textSize = (h / 3.25f).coerceIn(13f * dp, 18f * dp)

        // 1. 黄橙渐变底（浅至深的流动光斑氛围底色）
        bodyPaint.shader = LinearGradient(
            0f, 0f, w, h,
            intArrayOf(0xFFFFD215.toInt(), 0xFFFFA31A.toInt(), 0xFFFF7A3D.toInt()),
            floatArrayOf(0f, 0.6f, 1f),
            Shader.TileMode.CLAMP,
        )
        bodyRect.set(0f, 0f, w, h)
        canvas.drawRoundRect(bodyRect, radius, radius, bodyPaint)
        bodyPaint.shader = null

        // 2. 12 个模糊光斑：径向渐变（中心实色 → 透明），随 progress 循环平移
        // 光斑尺寸随按钮高度缩放（高度约 52dp 时约 18dp）
        val blobScale = (h / (52 * dp)).coerceIn(0.7f, 1.4f)
        canvas.save()
        canvas.clipPath(android.graphics.Path().apply { addRoundRect(bodyRect, radius, radius, Path.Direction.CW) })
        for (b in blobs) {
            val t = (progress * b.speed + b.phase) % 1f
            // 每条轴的往返移动，制造流动感
            val px = b.nx * w + b.ampX * w * kotlin.math.sin(t * 2 * Math.PI).toFloat()
            val py = b.ny * h + b.ampY * h * kotlin.math.cos(t * 2 * Math.PI).toFloat()
            val r = b.r * blobScale
            blobPaint.shader = RadialGradient(
                px, py, r,
                intArrayOf(b.color, Color.TRANSPARENT),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawCircle(px, py, r, blobPaint)
            blobPaint.shader = null
        }
        canvas.restore()

        // 3. 内发光描边（顶部亮、底部暗，类似 Web 端 inset box-shadow）
        strokePaint.color = 0xE6FFDF34.toInt()
        strokePaint.strokeWidth = (2 * dp).coerceAtLeast(1.5f)
        canvas.drawRoundRect(bodyRect, radius, radius, strokePaint)

        // 4. 文案（带投影，白色加粗）
        val baseline = (h + textPaint.textSize / 2.5f) / 2f
        textPaint.setShadowLayer(3f, 0f, 1.5f, 0x40000000.toInt())
        canvas.drawText(label, w / 2f, baseline, textPaint)
        textPaint.clearShadowLayer()
    }
}
