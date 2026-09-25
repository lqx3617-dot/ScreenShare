package com.screenshare

import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatImageView

/**
 * 以宽度决定高度的正方形 ImageView。
 * 网格 item 在 bind 时尚未测量完成，用布局测量代替运行时改 layoutParams，
 * 避免首屏 item 高度为 0 或复用后尺寸错乱。
 */
class SquareImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, widthMeasureSpec)
    }
}
