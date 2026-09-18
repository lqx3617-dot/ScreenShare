package com.screenshare

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.screenshare.databinding.ActivityLiquidBinding
import kotlin.random.Random

/**
 * 液态玻璃主界面：持有全屏背景层（光斑/颗粒/漂浮爱心）、底部毛玻璃导航、
 * 内容区由 HomeFragment / FriendsFragment / SettingsFragment 承载，切换时淡入淡出。
 */
class LiquidHomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLiquidBinding
    private val handler = Handler(Looper.getMainLooper())
    private var toastRunnable: Runnable? = null

    companion object {
        private const val TAG = "LiquidHome"
    }

    /** 特性级容错：任一视觉特性失败不影响界面打开，并落盘日志便于定位 */
    private fun safe(name: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            Log.e(TAG, "特性[$name]初始化失败", t)
            AppLogger.app("LiquidHome 特性[$name]失败: ${Log.getStackTraceString(t)}")
        }
    }

    /** 光斑规格：颜色 / 直径dp / 位置（Gravity + 偏移dp）/ 透明度 / 漂浮参数 */
    private data class Blob(
        val color: Int, val sizeDp: Int, val gravity: Int,
        val dxDp: Int, val dyDp: Int, val alpha: Float,
        val moveX: Float, val moveY: Float, val scale: Float, val duration: Long
    )

    private val blobs = arrayOf(
        Blob(0xFFFF1493.toInt(), 380, Gravity.TOP or Gravity.START, -90, -100, 0.60f, 60f, 50f, 1.12f, 16000),
        Blob(0xFF6B21A8.toInt(), 320, Gravity.TOP or Gravity.END, -90, 60, 0.55f, -50f, 60f, 1.08f, 20000),
        Blob(0xFF0891B2.toInt(), 260, Gravity.BOTTOM or Gravity.START, 20, -40, 0.30f, 40f, -60f, 1.15f, 22000),
        Blob(0xFFEC4899.toInt(), 200, Gravity.TOP or Gravity.START, 110, 320, 0.40f, 60f, 50f, 0.95f, 18000)
    )

    private val homeFragment = HomeFragment()
    private val friendsFragment = FriendsFragment()
    private val settingsFragment = SettingsFragment()
    private var currentTab = -1

    /** 全部无限动画引用，销毁时统一取消防泄漏 */
    private val infiniteAnimators = ArrayList<ObjectAnimator>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLiquidBinding.inflate(layoutInflater)
        setContentView(binding.root)
        safe("沉浸式状态栏") { setupImmersive() }
        safe("背景光斑") { setupBlobs() }
        safe("底部导航") { setupTabs() }
        // 静默自动检查更新（12h 节流，与 MainActivity 行为一致）
        safe("检查更新") { UpdateChecker.check(this) }
    }

    /** 沉浸式状态栏：透明背景 + 深色底配白色图标 */
    private fun setupImmersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        val flags = window.decorView.systemUiVisibility and
                android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
        window.decorView.systemUiVisibility = flags
    }

    /** 4 个彩色光斑 + 背景颗粒 + 漂浮爱心 */
    private fun setupBlobs() {
        val density = resources.displayMetrics.density
        for (b in blobs) {
            val view = View(this)
            val size = (b.sizeDp * density).toInt()
            val gd = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(b.color)
            }
            view.background = gd
            view.alpha = b.alpha
            val lp = FrameLayout.LayoutParams(size, size, b.gravity)
            lp.setMargins((b.dxDp * density).toInt(), (b.dyDp * density).toInt(), 0, 0)
            binding.flBlobs.addView(view, lp)
            ObjectAnimator.ofPropertyValuesHolder(
                view,
                PropertyValuesHolder.ofFloat("translationX", 0f, b.moveX, -b.moveX / 2, 0f),
                PropertyValuesHolder.ofFloat("translationY", 0f, b.moveY, b.moveY / 2, 0f),
                PropertyValuesHolder.ofFloat("scaleX", 1f, b.scale, 1f - (b.scale - 1f) * 0.5f, 1f),
                PropertyValuesHolder.ofFloat("scaleY", 1f, b.scale, 1f - (b.scale - 1f) * 0.5f, 1f)
            ).apply {
                duration = b.duration
                repeatMode = ObjectAnimator.REVERSE
                repeatCount = ObjectAnimator.INFINITE
                interpolator = AccelerateDecelerateInterpolator()
                infiniteAnimators.add(this)
                start()
            }
        }
        setupGrains()
        setupFloatingHearts()
    }

    /** 背景颗粒（对应 JS 生成的 40 个 1-3px 白点，静态低开销） */
    private fun setupGrains() {
        val dm = resources.displayMetrics
        for (i in 0 until 40) {
            val v = View(this)
            v.setBackgroundColor(Color.WHITE)
            v.alpha = 0.1f + Random.nextFloat() * 0.4f
            val size = ((1 + Random.nextFloat()) * dm.density).toInt()
            val lp = FrameLayout.LayoutParams(size, size)
            lp.leftMargin = (Random.nextFloat() * dm.widthPixels).toInt()
            lp.topMargin = (Random.nextFloat() * dm.heightPixels).toInt()
            binding.flBlobs.addView(v, lp)
        }
    }

    /** 漂浮爱心（对应 JS 生成的 6 个 float-heart：从底部升到顶部 + 旋转，25s 级慢速） */
    private fun setupFloatingHearts() {
        val dm = resources.displayMetrics
        for (i in 0 until 6) {
            val iv = ImageView(this)
            iv.setImageResource(R.drawable.ic_heart_fill)
            iv.setColorFilter(0xFFFF6BB5.toInt())
            iv.alpha = 0.12f
            val size = ((14 + Random.nextFloat() * 18) * dm.density).toInt()
            val lp = FrameLayout.LayoutParams(size, size)
            lp.leftMargin = (Random.nextFloat() * dm.widthPixels).toInt()
            binding.flBlobs.addView(iv, lp)
            val dur = 18000L + (Random.nextFloat() * 15000L).toLong()
            val delay = (Random.nextFloat() * 25000L).toLong()
            ObjectAnimator.ofFloat(iv, "translationY", dm.heightPixels.toFloat(), -300f).apply {
                duration = dur
                startDelay = delay
                repeatCount = ObjectAnimator.INFINITE
                interpolator = LinearInterpolator()
                infiniteAnimators.add(this)
                start()
            }
            ObjectAnimator.ofFloat(iv, "rotation", 0f, 360f).apply {
                duration = dur
                startDelay = delay
                repeatCount = ObjectAnimator.INFINITE
                interpolator = LinearInterpolator()
                infiniteAnimators.add(this)
                start()
            }
        }
    }

    /** 底部 tab：点击切换内容区 Fragment，当前项粉色高亮 */
    private fun setupTabs() {
        val tabs = arrayOf(binding.tabHome, binding.tabFriends, binding.tabSettings)
        tabs.forEachIndexed { index, tab ->
            tab.setOnClickListener { switchTab(index) }
        }
        // 默认显示首页（首次不加切换动画）
        switchTab(0, animate = false)
    }

    private fun switchTab(index: Int, animate: Boolean = true) {
        if (index == currentTab) return
        val frag = when (index) {
            0 -> homeFragment
            1 -> friendsFragment
            else -> settingsFragment
        }
        val ft = supportFragmentManager.beginTransaction()
        if (animate) {
            ft.setCustomAnimations(R.anim.fade_in, R.anim.fade_out)
        }
        ft.replace(R.id.contentArea, frag)
        ft.commit()
        // tab 高亮：图标与文字颜色随选中态切换
        val tabs = arrayOf(binding.tabHome, binding.tabFriends, binding.tabSettings)
        tabs.forEachIndexed { i, t ->
            t.isActivated = i == index
            val color = if (i == index) Color.WHITE else 0x99FFFFFF.toInt()
            (t.getChildAt(0) as ImageView).setColorFilter(color)
            (t.getChildAt(1) as TextView).setTextColor(color)
        }
        currentTab = index
    }

    /** toast：底部滑入，2 秒后滑出；供三个 Fragment 共用 */
    fun showToast(msg: String) {
        toastRunnable?.let { handler.removeCallbacks(it) }
        with(binding.tvToast) {
            text = msg
            visibility = View.VISIBLE
            translationY = 60f
            alpha = 0f
            ObjectAnimator.ofPropertyValuesHolder(
                this,
                PropertyValuesHolder.ofFloat("translationY", 60f, 0f),
                PropertyValuesHolder.ofFloat("alpha", 0f, 1f)
            ).apply {
                duration = 350
                interpolator = android.view.animation.OvershootInterpolator(0.8f)
                start()
            }
        }
        val r = Runnable {
            ObjectAnimator.ofPropertyValuesHolder(
                binding.tvToast,
                PropertyValuesHolder.ofFloat("translationY", 0f, 60f),
                PropertyValuesHolder.ofFloat("alpha", 1f, 0f)
            ).apply {
                duration = 250
                interpolator = AccelerateDecelerateInterpolator()
                start()
            }
            handler.postDelayed({ binding.tvToast.visibility = View.GONE }, 250)
        }
        toastRunnable = r
        handler.postDelayed(r, 2000)
    }

    override fun onDestroy() {
        super.onDestroy()
        toastRunnable?.let { handler.removeCallbacks(it) }
        // 取消全部无限动画，避免 Activity 销毁后视图树被动画器永久持有
        infiniteAnimators.forEach { it.cancel() }
        infiniteAnimators.clear()
        handler.removeCallbacksAndMessages(null)
    }
}
