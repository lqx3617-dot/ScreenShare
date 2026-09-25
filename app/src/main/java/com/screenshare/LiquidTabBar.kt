package com.screenshare

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 底部导航：点击切换 + 横向滑动选择。
 *
 * 手指横滑超过阈值时拦截手势，实时切换到手指所在的 tab（画面跟手移动），
 * 松手后停留在落点 tab；点击仍直接切到对应 tab。
 * 选中态背景由 tab 自身 drawable 的 state_activated 提供，图标/文字颜色在此同步。
 */
class LiquidTabBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    /** 宿主回调：切换到指定 tab（切换 Fragment 等） */
    var onTabSelected: ((Int) -> Unit)? = null

    private var currentIndex = -1
    private var previewIndex = -1
    private var dragging = false
    private var downX = 0f
    private var downY = 0f
    private val baseTouchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var sensitivityFactor = 1f
    private val touchSlop get() = (baseTouchSlop * sensitivityFactor).roundToInt()

    /** 灵敏度系数：越小越灵敏（轻滑即触发），范围 0.3~3 */
    fun setSensitivity(factor: Float) {
        sensitivityFactor = factor.coerceIn(0.3f, 3f)
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        for (i in 0 until childCount) {
            getChildAt(i).setOnClickListener { select(i) }
        }
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
        for (i in 0 until childCount) {
            val tab = getChildAt(i) as? ViewGroup ?: continue
            val active = i == index
            tab.isActivated = active
            val color = if (active) Color.WHITE else INACTIVE_COLOR
            (tab.getChildAt(0) as? ImageView)?.setColorFilter(color)
            (tab.getChildAt(1) as? TextView)?.setTextColor(color)
        }
    }

    private fun tabIndexAt(x: Float): Int {
        val usable = width - paddingLeft - paddingRight
        if (usable <= 0 || childCount == 0) return currentIndex.coerceAtLeast(0)
        val tabWidth = usable / childCount
        return ((x - paddingLeft) / tabWidth).roundToInt().coerceIn(0, childCount - 1)
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
