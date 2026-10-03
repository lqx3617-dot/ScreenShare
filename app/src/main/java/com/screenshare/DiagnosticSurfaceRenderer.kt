package com.screenshare

import android.content.Context
import android.view.SurfaceHolder
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

/**
 * 带诊断日志的 SurfaceViewRenderer：记录 onMeasure/onLayout（View 尺寸）与
 * surfaceChanged（Surface 尺寸）的真实变化，用于定位"画面自动缩放"发生在哪一层。
 * 仅尺寸真正变化时打日志，避免高频噪音。
 */
class DiagnosticSurfaceRenderer(context: Context) : SurfaceViewRenderer(context) {

    private var lastMeasureW = -1
    private var lastMeasureH = -1
    private var lastSurfaceW = -1
    private var lastSurfaceH = -1
    private var lastScalingType: RendererCommon.ScalingType? = null

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        if (measuredWidth != lastMeasureW || measuredHeight != lastMeasureH) {
            AppLogger.app("renderer.onMeasure ${lastMeasureW}x${lastMeasureH} -> ${measuredWidth}x${measuredHeight}")
            lastMeasureW = measuredWidth
            lastMeasureH = measuredHeight
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (changed) {
            AppLogger.app("renderer.onLayout -> ${width}x$height")
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        super.surfaceCreated(holder)
        // v1.414: GONE/VISIBLE 切换会重建 surface，记录生命周期用于排查可见性时序
        AppLogger.app("renderer.surfaceCreated")
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        super.surfaceDestroyed(holder)
        AppLogger.app("renderer.surfaceDestroyed")
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
        super.surfaceChanged(holder, format, w, h)
        if (w != lastSurfaceW || h != lastSurfaceH) {
            // Surface 尺寸（= 解码输出尺寸）变化：硬件合成器将以此为依据缩放 View 层
            AppLogger.app("renderer.surfaceChanged ${lastSurfaceW}x${lastSurfaceH} -> ${w}x$h view=${this.width}x${this.height}")
            lastSurfaceW = w
            lastSurfaceH = h
        }
    }

    override fun setScalingType(scalingType: RendererCommon.ScalingType) {
        super.setScalingType(scalingType)
        // v1.414: 去重——模式切换频繁时每次都打日志会产生噪音，与其它回调的
        // "仅变化时打"策略保持一致
        if (lastScalingType != scalingType) {
            AppLogger.app("renderer.setScalingType -> $scalingType")
            lastScalingType = scalingType
        }
    }
}
