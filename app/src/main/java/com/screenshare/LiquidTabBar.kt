package com.screenshare

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 底部导航（v1.387 重构）：点击切换 + 横向滑动选择。
 *
 * 结构：导航条本体是透明框架（bg_liquid_tab 只留描边），第 0 个子视图是一颗
 * 「玻璃药丸」——它随选中项滑动，真实背景模糊 + 透镜折射只施加在药丸上一次，
 * 切换 tab 时移动药丸而非重建玻璃。tab 项本身全透明，只负责图标/文字颜色。
 *
 * 以前的做法是把玻璃贴在选中 tab 的背景上，于是要为首/末 tab 单独定制圆角
 * drawable、要追踪旧宿主摘 RenderEffect、还要回调宿主迁移，三者互相耦合。
 * 药丸内缩 4dp、固定 16dp 圆角，永远不碰框架的 22dp 圆角，对齐问题从根上消失。
 *
 * 手指横滑超过阈值时拦截手势，实时切换到手指所在的 tab（画面跟手移动），
 * 松手后停留在落点 tab；点击仍直接切到对应 tab。
 */
class LiquidTabBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    /** 宿主回调：切换到指定 tab（切换 Fragment 等） */
    var onTabSelected: ((Int) -> Unit)? = null

    /**
     * 玻璃药丸：内缩于框架，随选中项滑动。
     * v1.389：药丸本身是 FrameLayout 容器，第 0 个子视图是截取层（显示被模糊的
     * 背景画面），第 1 个子视图是玻璃叠层（bg_liquid_glass_pill 的半透明白底 +
     * 描边 + 顶部高光）。截取层在底，玻璃叠层压在其上，tab 图标文字再压在最上层。
     */
    private val pill: FrameLayout = FrameLayout(context).apply {
        background = null
        isClickable = false
        isFocusable = false
    }

    /** 玻璃叠层：半透明白底 + 描边 + 顶部高光，盖在截取层之上 */
    private val glassOverlay: View = View(context).apply {
        background = ContextCompat.getDrawable(context, R.drawable.bg_liquid_glass_pill)
        isClickable = false
        isFocusable = false
    }

    init {
        pill.addView(glassOverlay, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
    }

    private val pillInsetV = (resources.displayMetrics.density * 6).toInt()
    private val pillInsetH = (resources.displayMetrics.density * 2).toInt()

    private var currentIndex = -1
    private var previewIndex = -1
    private var dragging = false
    private var pillAnimating = false
    private var downX = 0f
    private var downY = 0f
    private val baseTouchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var sensitivityFactor = 1f
    private val touchSlop get() = (baseTouchSlop * sensitivityFactor).roundToInt()

    /** 灵敏度系数：越小越灵敏（轻滑即触发），范围 0.3~3 */
    fun setSensitivity(factor: Float) {
        sensitivityFactor = factor.coerceIn(0.3f, 3f)
    }

    /** tab 数 = 全部子视图去掉药丸 */
    private val tabCount get() = childCount - 1

    private fun tabViewAt(i: Int): View = getChildAt(i + 1)

    override fun onFinishInflate() {
        super.onFinishInflate()
        // 药丸放第 0 位，绘制顺序最早 → tab 项的图标文字叠在玻璃之上
        addView(pill, 0, LayoutParams(0, 0))
        for (i in 0 until tabCount) {
            tabViewAt(i).setOnClickListener { select(i) }
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        // 尺寸变化（旋转、系统栏 inset）时重定位药丸；动画进行中不打断
        // positionPill 内部会顺带重截玻璃画面，无需在此重复刷新
        if (changed && !pillAnimating) positionPill(currentIndex, animate = false)
    }

    /** 仅更新视觉（Activity 恢复场景用），不触发切换回调 */
    fun selectSilent(index: Int) {
        currentIndex = index
        applyHighlight(index)
    }

    /** 切到 index 并回调宿主；index 未变时直接返回（防回调乒乓） */
    fun select(index: Int) {
        if (index == currentIndex) return
        currentIndex = index
        applyHighlight(index)
        onTabSelected?.invoke(index)
    }

    private fun applyHighlight(index: Int) {
        for (i in 0 until tabCount) {
            val tab = tabViewAt(i) as? ViewGroup ?: continue
            val active = i == index
            tab.isActivated = active
            val color = if (active) Color.WHITE else INACTIVE_COLOR
            (tab.getChildAt(0) as? ImageView)?.setColorFilter(color)
            (tab.getChildAt(1) as? TextView)?.setTextColor(color)
        }
        positionPill(index, animate = true)
    }

    /** 把药丸移到第 index 个 tab 的位置上，带滑动动画 */
    private fun positionPill(index: Int, animate: Boolean) {
        if (index !in 0 until tabCount) return
        val tab = tabViewAt(index)
        // 布局未完成时 tab 尺寸为 0，等 onLayout 再定位
        if (tab.width == 0 || tab.height == 0) return

        // 药丸是容器（截取层 + 玻璃叠层是其子视图），但它在 LinearLayout 里的
        // LayoutParams 是 0x0（靠手动 layout 定位）。ViewGroup 的子视图按
        // measuredWidth 布局，不 measure 就全是 0 → 药丸整体不可见。
        // 因此先按目标尺寸 measure，再 layout。
        val pw = (tab.right - tab.left) - 2 * pillInsetH
        val ph = (tab.bottom - tab.top) - 2 * pillInsetV
        if (pw > 0 && ph > 0) {
            pill.measure(
                android.view.View.MeasureSpec.makeMeasureSpec(pw, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(ph, android.view.View.MeasureSpec.EXACTLY)
            )
        }

        // 记录移动前的视觉位置，layout 改坐标后用 translationX 补偿再滑归零
        val fromX = pill.x
        pill.layout(
            tab.left + pillInsetH, tab.top + pillInsetV,
            tab.right - pillInsetH, tab.bottom - pillInsetV
        )
        val toX = pill.x

        if (animate && abs(fromX - toX) > 1f) {
            pill.translationX = fromX - toX
            pillAnimating = true
            pill.animate()
                .translationX(0f)
                .setDuration(180)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .withEndAction {
                    pillAnimating = false
                    // 滑动落位后药丸覆盖的背景区域变了，重截一次
                    refreshGlass()
                }
                .start()
        } else {
            pill.translationX = 0f
        }
        refreshGlass()
    }

    /**
     * 对药丸施加液态玻璃（设置页热更新时重复调用安全）。
     *
     * v1.389：真正的背景模糊需要把玻璃背后的画面截进 bitmap，背景层由宿主通过
     * [setBackdrops] 提供（光斑层 + 内容区）。截取层是药丸的子视图，随药丸一起
     * 滑动，所以切 tab 时不需要额外同步。
     */
    private var backdrops: Array<out View> = emptyArray()
    private var baseDrawable: android.graphics.drawable.Drawable? = null

    /**
     * 背景画面来源：绘制顺序从底到顶，例如 [光斑层, 内容区]。
     * [base] 为整窗底色（根布局背景），Fragment 与光斑层多为透明，不铺底色
     * 会截出透明位图，模糊也就看不见。
     */
    fun setBackdrops(vararg views: View, base: android.graphics.drawable.Drawable? = null) {
        backdrops = views
        baseDrawable = base
    }

    fun applyLiquidGlass() {
        if (backdrops.isEmpty()) {
            AppLogger.app("[LiquidTabBar] applyLiquidGlass 跳过：backdrops 为空")
            return
        }
        val (radius, refraction, chromatic) = LiquidGlass.params(context)
        AppLogger.app("[LiquidTabBar] applyLiquidGlass rx=$radius pill=${pill.width}x${pill.height}")
        try {
            LiquidGlass.apply(
                pill,
                backdrops = backdrops,
                baseDrawable = baseDrawable,
                radiusX = radius, radiusY = radius,
                refraction = refraction, chromatic = chromatic
            )
        } catch (t: Throwable) {
            // 与 MainActivity 同理：覆盖安装后 AOT 缓存可能残留旧方法签名
            AppLogger.app("[LiquidTabBar] 液态玻璃装配失败: ${t.javaClass.simpleName} ${t.message}")
        }
    }

    /** 内容区画面变化（滚动/切页/动画）时重截药丸背后的画面 */
    fun refreshGlass() {
        if (backdrops.isEmpty()) return
        LiquidGlass.refresh(pill, backdrops, baseDrawable)
    }

    private fun tabIndexAt(x: Float): Int {
        val usable = width - paddingLeft - paddingRight
        if (usable <= 0 || tabCount == 0) return currentIndex.coerceAtLeast(0)
        val tabWidth = usable / tabCount
        return ((x - paddingLeft) / tabWidth).roundToInt().coerceIn(0, tabCount - 1)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - downX
                val dy = ev.y - downY
                // 明确的横向滑动才接管：纵向滚动（列表）不抢
                if (!dragging && abs(dx) > touchSlop && abs(dx) > abs(dy)) {
                    dragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }
        }
        return super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!dragging) return super.onTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val idx = tabIndexAt(ev.x)
                if (idx != previewIndex) {
                    previewIndex = idx
                    // 画面跟手：实时切到手指所在 tab（select 在 index 未变时返回，applyHighlight 兜底高亮）
                    select(idx)
                    applyHighlight(idx)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val target = previewIndex
                dragging = false
                previewIndex = -1
                // 已在 move 中实时切换；松手时确保落在最终位置
                if (target >= 0) select(target)
            }
        }
        return true
    }

    companion object {
        private const val INACTIVE_COLOR = 0x99FFFFFF.toInt()
    }
}
