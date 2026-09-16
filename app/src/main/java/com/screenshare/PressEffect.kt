package com.screenshare

import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator

/**
 * 通用按压反馈：按下时缩到 0.94 并回弹，替代生硬的无反馈点击。
 * 返回 true 接管事件序列，ACTION_UP 时手动 performClick，
 * 因此不影响已注册的 OnClickListener。
 */
object PressEffect {

    private const val DOWN_SCALE = 0.94f
    private const val DOWN_DURATION = 110L
    private const val UP_DURATION = 170L

    fun bind(view: View) {
        view.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate().scaleX(DOWN_SCALE).scaleY(DOWN_SCALE)
                        .setDuration(DOWN_DURATION)
                        .setInterpolator(AccelerateDecelerateInterpolator())
                        .start()
                }
                MotionEvent.ACTION_UP -> {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(UP_DURATION).start()
                    if (v.hasOnClickListeners()) v.performClick()
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(UP_DURATION).start()
                }
            }
            true
        }
    }

    /** 容器内所有可点击子 View 统一绑定（递归，工具条、面板批量设置用） */
    fun bindChildren(container: View) {
        if (container !is android.view.ViewGroup) return
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i) ?: continue
            if (child.isClickable && child.hasOnClickListeners()) {
                bind(child)
            }
            if (child is android.view.ViewGroup) {
                bindChildren(child)
            }
        }
    }
}
