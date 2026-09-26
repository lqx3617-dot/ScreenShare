package com.screenshare

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.util.Log
import android.view.View

/**
 * 液态玻璃工具（v1.375 / v1.382 增强）：
 * 对半透明玻璃视图应用系统级背景模糊（backdrop blur）+ 透镜折射。
 *
 * 分级策略（不引入外部依赖，避开 Compose 混排与第三方库的 RenderThread 原生崩溃）：
 * - Android 13+（API 33+）：背景模糊 → 透镜折射 + 色散（RuntimeShader，AGSL），
 *   用 RenderEffect.createChainEffect(lens, blur) 叠加，lens 为 outer、blur 为 inner。
 * - Android 12（API 31~32）：无 RuntimeShader，降级为背景模糊 + 轻微饱和度提升
 *   （ColorMatrix，公开 API），玻璃观感通透但不做折射。
 * - Android 11 及以下：setRenderEffect 不可用，静默返回，由 drawable 自带的半透明
 *   叠层提供一致观感。
 *
 * createBackdropBlurEffect 为 @hide 方法，用反射调用；不可用时整条链路降级。
 * 实现参考 iOS 26 Liquid Glass 与 AndroidLiquidGlass 库的同一系统底层能力。
 */
object LiquidGlass {

    private const val TAG = "LiquidGlass"

    /**
     * 透镜折射着色器（AGSL，Android 13+）：
     * 以视图中心为原点做径向凸透镜位移，中段位移最大、边缘归零；R/B 通道位移量
     * 略有差异形成色散（chromatic aberration），模拟厚玻璃边缘的折射光。
     */
    private const val LENS_SHADER = """
        uniform shader input;
        uniform float2 size;
        uniform float refraction;
        uniform float chromatic;
        half4 main(float2 fragCoord) {
            float2 center = size * 0.5;
            float2 d = fragCoord - center;
            float dist = length(d);
            float2 n = dist > 0.001 ? d / dist : float2(0.0);
            float maxR = max(size.x, size.y) * 0.5;
            float bulge = 1.0 - smoothstep(0.0, maxR, dist);
            float2 disp = n * refraction * bulge;
            half4 c;
            c.r = input.eval(fragCoord + disp * (1.0 + chromatic)).r;
            c.g = input.eval(fragCoord + disp).g;
            c.b = input.eval(fragCoord + disp * (1.0 - chromatic)).b;
            c.a = input.eval(fragCoord).a;
            half lum = dot(c.rgb, half3(0.2126, 0.7152, 0.0722));
            c.rgb = lum + (c.rgb - lum) * 1.15;
            return c;
        }
    """

    /** 缓存反射结果，避免每个视图都查一次方法 */
    @Volatile private var blurMethod: java.lang.reflect.Method? = null
    @Volatile private var methodChecked = false

    /**
     * 对 [views] 应用液态玻璃。
     * @param radiusX 横向模糊半径（像素）
     * @param radiusY 纵向模糊半径
     * @param refraction 透镜折射最大位移（像素，仅 Android 13+ 生效）
     * @param chromatic 色散强度 0~1（仅 Android 13+ 生效）
     */
    fun apply(
        vararg views: View,
        radiusX: Float = 24f,
        radiusY: Float = 24f,
        refraction: Float = 3f,
        chromatic: Float = 0.2f
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        for (v in views) {
            applyToView(v, radiusX, radiusY, refraction, chromatic)
        }
    }

    private fun applyToView(
        view: View,
        radiusX: Float,
        radiusY: Float,
        refraction: Float,
        chromatic: Float
    ) {
        val blur = createBackdropBlur(radiusX, radiusY) ?: return
        // 先把模糊立即生效，后续折射升级失败时也至少保留模糊
        setEffectSafely(view, blur)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+：透镜折射需要视图尺寸，布局就绪后装配并跟随尺寸变化重算
            applyLens(view, blur, refraction, chromatic)
        } else {
            // Android 12：无 RuntimeShader，叠一层轻微饱和度提升
            val boosted = chainColorBoost(blur)
            if (boosted != null) setEffectSafely(view, boosted)
        }
    }

    /** 背景模糊（@hide createBackdropBlurEffect，反射调用），失败返回 null */
    private fun createBackdropBlur(radiusX: Float, radiusY: Float): RenderEffect? {
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
            Log.w(TAG, "backdrop blur 创建失败，降级为半透明玻璃: ${t.message}")
            null
        }
    }

    /**
     * Android 13+ 透镜折射：blur 为 inner（先采样背景并模糊），lens 为 outer（折射+色散）。
     * 折射依赖视图尺寸，尺寸为 0 时跳过，布局变化时重算。
     */
    private fun applyLens(view: View, blur: RenderEffect, refraction: Float, chromatic: Float) {
        val update = {
            val w = view.width
            val h = view.height
            if (w > 0 && h > 0) {
                try {
                    val shader = RuntimeShader(LENS_SHADER)
                    shader.setFloatUniform("size", w.toFloat(), h.toFloat())
                    shader.setFloatUniform("refraction", refraction)
                    shader.setFloatUniform("chromatic", chromatic)
                    val lens = RenderEffect.createRuntimeShaderEffect(shader, "input")
                    setEffectSafely(view, RenderEffect.createChainEffect(lens, blur))
                } catch (t: Throwable) {
                    Log.w(TAG, "透镜折射失败，保持仅模糊: ${t.message}")
                }
            }
        }
        update()
        view.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or_, ob ->
            if (r - l != or_ - ol || b - t != ob - ot) update()
        }
    }

    /** Android 12：在模糊结果上叠轻微饱和度提升（公开 API），玻璃更通透 */
    private fun chainColorBoost(blur: RenderEffect): RenderEffect? {
        return try {
            val cf = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(1.2f) })
            RenderEffect.createColorFilterEffect(cf, blur)
        } catch (t: Throwable) {
            Log.w(TAG, "色彩增强失败: ${t.message}")
            null
        }
    }

    private fun setEffectSafely(view: View, effect: RenderEffect) {
        try {
            view.setRenderEffect(effect)
        } catch (t: Throwable) {
            Log.w(TAG, "液态玻璃装配失败: ${t.message}")
        }
    }
}
