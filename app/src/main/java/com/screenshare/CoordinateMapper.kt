package com.screenshare

/**
 * 观看方画面坐标 → 归一化坐标 → 共享方真实屏幕坐标的纯函数映射。
 * 无 Android 依赖，便于单元测试。
 */
object CoordinateMapper {

    /**
     * 视频内容在渲染区域内的显示矩形。
     * @param rendererW/H 观看方渲染区域尺寸
     * @param videoW/H 视频帧尺寸（已含旋转）
     * @param crop true=SCALE_ASPECT_CROP 铺满无黑边；false=SCALE_ASPECT_FIT 等比有黑边
     * @return [left, top, right, bottom] 内容矩形（渲染区域坐标系）
     */
    fun contentRect(
        rendererW: Float, rendererH: Float,
        videoW: Int, videoH: Int,
        crop: Boolean
    ): FloatArray {
        if (rendererW <= 0 || rendererH <= 0 || videoW <= 0 || videoH <= 0) {
            return floatArrayOf(0f, 0f, rendererW, rendererH)
        }
        if (crop) {
            return floatArrayOf(0f, 0f, rendererW, rendererH)
        }
        val vw = videoW.toFloat()
        val vh = videoH.toFloat()
        val scale = minOf(rendererW / vw, rendererH / vh)
        val cw = vw * scale
        val ch = vh * scale
        val left = (rendererW - cw) / 2f
        val top = (rendererH - ch) / 2f
        return floatArrayOf(left, top, left + cw, top + ch)
    }

    /**
     * 渲染区域内触点坐标 → 归一化坐标 [0..1]。
     * 触点在黑边区域（fit 模式）或越界时返回 null，上层不产生控制指令。
     */
    fun normalizeTouch(
        x: Float, y: Float,
        rendererW: Float, rendererH: Float,
        videoW: Int, videoH: Int,
        crop: Boolean
    ): FloatArray? {
        val r = contentRect(rendererW, rendererH, videoW, videoH, crop)
        val left = r[0]; val top = r[1]; val right = r[2]; val bottom = r[3]
        if (x < left || x > right || y < top || y > bottom) return null
        val w = right - left
        val h = bottom - top
        if (w <= 0 || h <= 0) return null
        val nx = (x - left) / w
        val ny = (y - top) / h
        return floatArrayOf(nx.coerceIn(0f, 1f), ny.coerceIn(0f, 1f))
    }

    /** 归一化坐标 → 共享方真实屏幕像素坐标（按共享方屏幕分辨率还原） */
    fun toScreenPx(nx: Float, ny: Float, screenW: Int, screenH: Int): Pair<Int, Int> {
        val sx = (nx * screenW).toInt().coerceIn(0, screenW - 1)
        val sy = (ny * screenH).toInt().coerceIn(0, screenH - 1)
        return sx to sy
    }
}
