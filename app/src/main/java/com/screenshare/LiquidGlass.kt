package com.screenshare

import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.util.Log
import android.view.View

/**
 * 液态玻璃工具（v1.375）：
 * 对半透明玻璃视图应用系统级背景模糊（backdrop blur）。
 *
 * Android 13+ 的 createBackdropBlurEffect 是非公开 API，用反射调用；
 * Android 12 或反射失败时静默降级为 drawable 自带的半透明叠层观感，
 * 不影响布局与功能。
 *
 * 实现参考 iOS 26 Liquid Glass 与 AndroidLiquidGlassView 库的同一系统底层能力，
 * 不引入外部依赖，避免 APK 体积增长与 minSdk 兼容负担。
 */
object LiquidGlass {

    private const val TAG = "LiquidGlass"

    /** 缓存反射结果，避免每个视图都查一次方法 */
    @Volatile private var blurMethod: java.lang.reflect.Method? = null
    @Volatile private var methodChecked = false

    /**
     * 对 [views] 应用背景模糊。
     * @param radiusX 横向模糊半径（dp 无关，直接传像素）
     * @param radiusY 纵向模糊半径
     */
    fun apply(vararg views: View, radiusX: Float = 24f, radiusY: Float = 24f) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val effect = createBlurEffect(radiusX, radiusY) ?: return
        for (v in views) {
            try {
                v.setRenderEffect(effect)
            } catch (t: Throwable) {
                Log.w(TAG, "液态玻璃模糊失败: ${t.message}")
            }
        }
    }

    private fun createBlurEffect(radiusX: Float, radiusY: Float): RenderEffect? {
        if (!methodChecked) {
            methodChecked = true
            try {
                blurMethod = RenderEffect::class.java.getMethod(
                    "createBackdropBlurEffect",
                    Float::class.javaPrimitiveType,
                    Float::class.javaPrimitiveType,
                    Shader.TileMode::class.java
                )
            } catch (t: Throwable) {
                Log.w(TAG, "backdrop blur 反射不可用，降级为半透明玻璃: ${t.message}")
            }
        }
        val m = blurMethod ?: return null
        return try {
            m.invoke(null, radiusX, radiusY, Shader.TileMode.CLAMP) as RenderEffect
        } catch (t: Throwable) {
            Log.w(TAG, "backdrop blur 创建失败: ${t.message}")
            null
        }
    }
}
