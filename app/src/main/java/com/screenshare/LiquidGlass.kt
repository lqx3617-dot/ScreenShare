package com.screenshare

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView

/**
 * 液态玻璃工具（v1.389 重写）。
 *
 * ## 背景
 * Android 的 RenderEffect 并不存在 createBackdropBlurEffect（@hide 也不存在，
 * AOSP 12/14/main 与官方文档均无此方法）。此前版本靠反射调用一个不存在的方法，
 * 反射必然失败 → 玻璃从未被施加，所以设置页调参数毫无效果。
 *
 * ## 现方案：内容截取真模糊
 * RenderEffect.createBlurEffect 是真实存在的公开 API，但它只能模糊「视图自身
 * 的绘制内容」，无法采样视图背后的画面。因此在玻璃视图内部叠一层全尺寸
 * ImageView（blurLayer），把背景层（光斑/颗粒/爱心 flBlobs + 内容区
 * contentArea）在玻璃区域内的画面手动画进一张 Bitmap 交给它，再施加
 * createBlurEffect——得到的就是货真价实的背景模糊，参数实时生效。
 *
 * 分级策略：
 * - Android 12+（API 31+）：blurLayer 显示截取位图并施加 createBlurEffect；
 *   Android 13+ 额外叠 RuntimeShader 透镜折射 + 色散。
 * - Android 11 及以下：RenderEffect 不可用，blurLayer 隐藏，回落到 drawable
 *   自带的半透明叠层。
 *
 * 内容刷新：玻璃区域的内容会随列表滚动/切 tab/动画而变化，通过
 * [installContentTracker] 在内容区添加滚动与布局监听，变化时重截。
 */
object LiquidGlass {

    private const val TAG = "LiquidGlass"

    /** 设置页持久化键：液态玻璃三参数（与 SettingsFragment 共用） */
    private const val PREFS = "liquid_settings"
    const val KEY_RADIUS = "blur_radius"
    const val KEY_REFRACTION = "refraction"
    const val KEY_CHROMATIC = "chromatic"

    /** 默认值：与 v1.382 内置参数一致，保证升级用户无感 */
    const val DEFAULT_RADIUS = 20f
    const val DEFAULT_REFRACTION = 3f
    const val DEFAULT_CHROMATIC = 0.2f

    /** 截取层圆角（与 bg_liquid_glass_pill 的 18dp 对齐） */
    private const val CORNER_RADIUS_DP = 18f
    private var cornerRadiusPx = 0f

    /** 读用户自定义参数，越界值钳回合法区间，避免 shader 崩溃 */
    fun params(context: Context): FloatArray {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val radius = p.getFloat(KEY_RADIUS, DEFAULT_RADIUS).coerceIn(0f, 80f)
        val refraction = p.getFloat(KEY_REFRACTION, DEFAULT_REFRACTION).coerceIn(0f, 30f)
        val chromatic = p.getFloat(KEY_CHROMATIC, DEFAULT_CHROMATIC).coerceIn(0f, 1f)
        return floatArrayOf(radius, refraction, chromatic)
    }

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

    /**
     * 玻璃视图与其截取层、参数的绑定关系。热更新时按视图复用。
     */
    private data class GlassState(
        var radius: Float = 0f,
        var refraction: Float = 0f,
        var chromatic: Float = 0f,
        var layer: ImageView? = null,
        /** 内容区变化时的重截回调，卸载/重建时取消防泄漏 */
        var refresher: (() -> Unit)? = null
    )

    private val states = java.util.WeakHashMap<View, GlassState>()

    /**
     * 对 [views] 施加液态玻璃，背景画面取自 [backdrops]（绘制顺序：从底到顶）。
     *
     * - [backdrops] 是位于玻璃之下、需要被模糊的画面来源，例如光斑层与内容区。
     *   它们的绘制内容会在玻璃区域被截取进 bitmap。
     * - 无背景层时直接返回：玻璃视图保持自身 drawable 的半透明叠层观感
     *   （压在 SurfaceView 视频流上的按钮无法用 Canvas 截取，走此降级）。
     * - 该方法可重复调用：设置页热更新时，参数变化会重建截取层效果，并立即重截
     *   一次画面。
     */
    fun apply(
        vararg views: View,
        backdrops: Array<out View> = emptyArray(),
        baseDrawable: android.graphics.drawable.Drawable? = null,
        radiusX: Float = 24f,
        radiusY: Float = 24f,
        refraction: Float = 3f,
        chromatic: Float = 0.2f
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        if (backdrops.isEmpty()) return
        for (v in views) {
            applyToView(v, backdrops, baseDrawable, radiusX, radiusY, refraction, chromatic)
        }
    }

    private fun applyToView(
        view: View,
        backdrops: Array<out View>,
        baseDrawable: android.graphics.drawable.Drawable?,
        radiusX: Float,
        radiusY: Float,
        refraction: Float,
        chromatic: Float
    ) {
        // 药丸是 FrameLayout 容器：截取层在第 0 位，玻璃叠层（圆角描边/高光）在其上。
        // 模糊按矩形作用，圆角外侧会露出直角模糊边；给截取层固定圆角轮廓并裁剪，
        // 模糊就被裁成药丸的圆角形状。
        val st = states.getOrPut(view) { GlassState() }
        st.radius = radiusX
        st.refraction = refraction
        st.chromatic = chromatic

        val host = view as? ViewGroup
        if (host == null) {
            Log.w(TAG, "玻璃视图不是容器，无法施加截取层: ${view.javaClass.simpleName}")
            return
        }

        val layer = (st.layer ?: createBlurLayer(view).also { st.layer = it })
        if (layer.parent == null) {
            // 截取层放第 0 位：绘制最早，玻璃叠层与 tab 图标文字都在其上
            host.addView(layer, 0, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ))
        }
        Log.d(TAG, "apply: rx=$radiusX host=${view.width}x${view.height} layer=${layer.width}x${layer.height}")
        AppLogger.app("[$TAG] apply rx=$radiusX rf=$refraction ch=$chromatic host=${view.width}x${view.height} layer=${layer.width}x${layer.height}")

        try {
            layer.outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, v.width, v.height, cornerRadiusPx)
                }
            }
            layer.clipToOutline = true
        } catch (t: Throwable) {
            Log.w(TAG, "轮廓裁剪不可用，玻璃保持矩形: ${t.message}")
        }

        applyEffect(layer, radiusX, radiusY, refraction, chromatic)
        // 截取与模糊都依赖 layer 的真实尺寸；布局未完成时 post 到下一帧再装配，
        // 否则 width==0 会让 update() 提前返回，模糊永远施加不上
        if (layer.width <= 0 || layer.height <= 0) {
            layer.post {
                applyEffect(layer, radiusX, radiusY, refraction, chromatic)
                capture(view, layer, backdrops, baseDrawable)
            }
        } else {
            capture(view, layer, backdrops, baseDrawable)
        }
    }

    private fun createBlurLayer(host: View): ImageView {
        if (cornerRadiusPx == 0f) {
            cornerRadiusPx = CORNER_RADIUS_DP * host.resources.displayMetrics.density
        }
        return ImageView(host.context).apply {
            // 背景透明，避免覆盖玻璃叠层的半透明白底
            background = null
            scaleType = ImageView.ScaleType.FIT_XY
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
    }

    /** 内容区画面变化（滚动/切页/动画）时重截玻璃背后的画面 */
    fun refresh(
        host: View,
        backdrops: Array<out View>,
        baseDrawable: android.graphics.drawable.Drawable? = null
    ) {
        val host = host as? ViewGroup ?: return
        val layer = host.getChildAt(0) as? ImageView ?: return
        capture(host, layer, backdrops, baseDrawable)
    }

    /**
     * 施加模糊 + 折射色散链。Android 13+ 用 RuntimeShader 包外层，
     * Android 12 只有模糊（可叠轻微饱和度提升）。
     */
    private fun applyEffect(
        layer: ImageView,
        radiusX: Float,
        radiusY: Float,
        refraction: Float,
        chromatic: Float
    ) {
        // createBlurEffect 是公开 API，直接调用；边缘用 CLAMP 防止采样越界出现黑边
        val blur = try {
            RenderEffect.createBlurEffect(radiusX, radiusY, Shader.TileMode.CLAMP)
        } catch (t: Throwable) {
            Log.w(TAG, "模糊创建失败: ${t.message}")
            layer.visibility = View.GONE
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // 透镜折射需要视图尺寸，布局就绪后装配并跟随尺寸变化重算。
            // 饱和度提升与 Android 12 分支一致：玻璃背后的缓变底色提亮后才看得出
            // 模糊/折射变化，否则纯色区上三参数调整全部不可见
            val boosted = chainColorBoost(blur) ?: blur
            applyLens(layer, boosted, refraction, chromatic)
        } else {
            val boosted = chainColorBoost(blur)
            setEffectSafely(layer, boosted ?: blur)
        }
    }

    /**
     * 截取背景层在玻璃区域内的画面，交给截取层显示。
     *
     * 玻璃视图与其截取层同坐标系：截取层铺满玻璃视图，所以背景视图只需按
     * (背景.left - 玻璃.left, 背景.top - 玻璃.top) 平移后绘制，落进玻璃区域的部分
     * 即为应被模糊的画面。每次绘制前清空画布，避免残影。
     */
    fun capture(host: View, layer: ImageView, backdrops: Array<out View>) {
        capture(host, layer, backdrops, baseDrawable = null)
    }

    /**
     * 截取背景层在玻璃区域内的画面，交给截取层显示。
     *
     * [baseDrawable] 是整窗底色（如根布局的深紫渐变）：Fragment 根布局通常是透明
     * 的，光斑层也只有稀疏半透明圆盘，若不铺底色，截出来的位图几乎全透明，
     * 模糊一张透明图自然看不到任何效果。
     */
    fun capture(
        host: View,
        layer: ImageView,
        backdrops: Array<out View>,
        baseDrawable: android.graphics.drawable.Drawable?
    ) {
        if (host.width <= 0 || host.height <= 0) {
            // 布局未完成：等下一次布局再截
            host.post { capture(host, layer, backdrops, baseDrawable) }
            return
        }
        val w = host.width
        val h = host.height
        if (w <= 0 || h <= 0) return

        val bmp: Bitmap
        try {
            bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        } catch (t: Throwable) {
            Log.w(TAG, "截取位图分配失败: ${t.message}")
            return
        }
        val canvas = Canvas(bmp)

        // 药丸在窗口中的位置：底色与各背景层都按它换算相对偏移
        val hostPos = IntArray(2); host.getLocationOnScreen(hostPos)

        // 先铺整窗底色，避免透明背景导致模糊不可见
        if (baseDrawable != null) {
            // 底色按窗口原尺寸铺在 canvas 上，药丸覆盖区域自然只取对应那一片。
            // 必须用副本：setBounds 会改原 drawable 状态，导致根视图背景错位
            val base = baseDrawable.constantState?.newDrawable()?.mutate() ?: baseDrawable
            base.setBounds(-hostPos[0], -hostPos[1],
                -hostPos[0] + host.resources.displayMetrics.widthPixels,
                -hostPos[1] + host.resources.displayMetrics.heightPixels)
            base.draw(canvas)
        }

        for (src in backdrops) {
            if (src.visibility != View.VISIBLE) continue
            if (src.width <= 0 || src.height <= 0) continue
            val srcPos = IntArray(2); src.getLocationOnScreen(srcPos)
            val dx = (srcPos[0] - hostPos[0]).toFloat()
            val dy = (srcPos[1] - hostPos[1]).toFloat()
            canvas.save()
            canvas.translate(dx, dy)
            // clipRect 在 translate 之后的坐标系里解释：直接裁 src 自身范围即可，
            // 落在 bitmap（玻璃区域）之外的部分会被舍弃，不必再算偏移
            canvas.clipRect(0, 0, src.width, src.height)
            src.draw(canvas)
            canvas.restore()
        }

        // 落盘诊断限频：滚动时每帧都会调 capture，全量落盘会刷掉有用日志
        if (captureLogCount.incrementAndGet() % 30 == 1) {
            // getRenderEffect 未公开（android.jar 只有 setRenderEffect），
            // 用已装配标记代替。
            // std=截取画面的亮度标准差：过低（<5）说明药丸背后基本是纯色/缓变底色，
            // 模糊与折射的参数调整在这种内容上天然不可见
            var sum = 0.0
            var sumSq = 0.0
            var samples = 0
            var y = 0
            while (y < h) {
                var x = 0
                while (x < w) {
                    val c = bmp.getPixel(x, y)
                    val lum = 0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)
                    sum += lum
                    sumSq += lum * lum
                    samples++
                    x += 7
                }
                y += 7
            }
            val mean = if (samples > 0) sum / samples else 0.0
            val std = if (samples > 0) kotlin.math.sqrt(kotlin.math.max(0.0, sumSq / samples - mean * mean)) else 0.0
            AppLogger.app("[$TAG] capture ${w}x${h} backdrops=${backdrops.size} applied=${states[host]?.layer?.parent != null} std=${"%.1f".format(std)} mean=${"%.0f".format(mean)}")
        }

        layer.setImageBitmap(bmp)
    }

    /** 截取落盘诊断限频计数器 */
    private val captureLogCount = java.util.concurrent.atomic.AtomicInteger(0)

    /** 记录每个视图已挂载的尺寸监听，热更新参数时先移除旧监听，避免叠加 */
    private val lensListeners = java.util.WeakHashMap<View, View.OnLayoutChangeListener>()

    /**
     * Android 13+ 透镜折射：blur 为 inner（先采样背景并模糊），lens 为 outer（折射+色散）。
     * 折射依赖视图尺寸，尺寸为 0 时跳过，布局变化时重算。
     * 可重复调用（设置页热更新）：先摘掉旧监听再重建，防止监听叠加。
     */
    private fun applyLens(view: View, blur: RenderEffect, refraction: Float, chromatic: Float) {
        lensListeners.remove(view)?.let { view.removeOnLayoutChangeListener(it) }
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
        val listener = View.OnLayoutChangeListener { _, l, t, r, b, ol, ot, or_, ob ->
            if (r - l != or_ - ol || b - t != ob - ot) update()
        }
        lensListeners[view] = listener
        view.addOnLayoutChangeListener(listener)
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
