package com.screenshare

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.fragment.app.Fragment
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
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
        // v1.302: 夜空配色——冷色为主（品红/靛蓝/翡翠/亮品红），暖色比例降低
        Blob(0xFFEC4899.toInt(), 420, Gravity.TOP or Gravity.START, -90, -100, 0.45f, 60f, 50f, 1.12f, 16000),
        Blob(0xFF6366F1.toInt(), 360, Gravity.TOP or Gravity.END, -90, 60, 0.38f, -50f, 60f, 1.08f, 20000),
        Blob(0xFF14B8A6.toInt(), 300, Gravity.BOTTOM or Gravity.START, 20, -40, 0.30f, 40f, -60f, 1.15f, 22000),
        Blob(0xFFD946EF.toInt(), 240, Gravity.TOP or Gravity.START, 110, 320, 0.25f, 60f, 50f, 0.95f, 18000)
    )

    private var homeFragment: HomeFragment? = null
    private var friendsFragment: FriendsFragment? = null
    private var settingsFragment: SettingsFragment? = null
    private var currentTab = -1
    /** 当前打开的子页面（如音频设置），非 null 时隐藏底部 tab */
    private var subFragment: Fragment? = null

    /** 全部无限动画引用，销毁时统一取消防泄漏 */
    private val infiniteAnimators = ArrayList<ObjectAnimator>()

    /** 账号在线状态长连接（auth 认领后接收 presence/好友邀请/共享邀请） */
    var presenceClient: PresenceClient? = null
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 登录拦截：未登录先进登录页，避免主界面向服务端发起无身份的请求
        if (!SessionStore.isLoggedIn(this)) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }
        binding = ActivityLiquidBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // 复用 FragmentManager 恢复的 Fragment 实例（进程被杀重建时保留用户已输入内容与当前页）
        supportFragmentManager.findFragmentById(R.id.contentArea)?.let { restored ->
            when (restored) {
                is HomeFragment -> { homeFragment = restored; currentTab = 0 }
                is FriendsFragment -> { friendsFragment = restored; currentTab = 1 }
                is SettingsFragment -> { settingsFragment = restored; currentTab = 2 }
                else -> {
                    // 恢复的是设置子页面：保持子页面状态，tabBar 继续隐藏
                    subFragment = restored
                    currentTab = 2
                }
            }
        }
        safe("沉浸式状态栏") { setupImmersive() }
        safe("背景光斑") { setupBlobs() }
        safe("底部导航") { setupTabs() }
        // 静默自动检查更新（12h 节流，与 MainActivity 行为一致）
        safe("检查更新") { UpdateChecker.check(this) }
        // 账号在线状态长连接：auth 认领 + presence 广播 + 好友/共享邀请
        safe("账号连接") { connectPresence() }
    }

    private val presenceListener = object : PresenceClient.Listener {
        override fun onAuthed(userId: String) {
            Log.d(TAG, "账号连接已认领: $userId")
        }

        override fun onAuthFailed() {
            // 令牌无效/过期：清本地会话并跳登录页
            Log.w(TAG, "账号令牌失效，需重新登录")
            SessionStore.clear(this@LiquidHomeActivity)
            startActivity(Intent(this@LiquidHomeActivity, LoginActivity::class.java))
            finish()
        }

        override fun onPresence(userId: String, online: Boolean) {
            runOnUiThread { friendsFragment?.onPresenceChanged(userId, online) }
        }

        override fun onFriendRequest(requestId: String, fromUserId: String, fromNickname: String) {
            runOnUiThread {
                showToast("收到 $fromNickname 的好友申请")
                friendsFragment?.onFriendRequestReceived(requestId, fromUserId, fromNickname)
            }
        }

        override fun onFriendAccepted(friendUserId: String, friendNickname: String) {
            runOnUiThread {
                showToast("已和 $friendNickname 成为好友")
                friendsFragment?.refresh()
            }
        }

        override fun onShareInvite(inviteId: String, code: String, fromUserId: String, fromNickname: String) {
            runOnUiThread { showShareInviteDialog(inviteId, code, fromNickname) }
        }

        override fun onShareInviteResult(inviteId: String, accepted: Boolean, reason: String) {
            runOnUiThread {
                showToast(if (accepted) "对方已接受共享邀请" else "对方未接受邀请${if (reason.isNotEmpty()) "：$reason" else ""}")
            }
        }

        override fun onRetrying(message: String) {
            Log.d(TAG, message)
        }

        override fun onError(message: String) {
            Log.e(TAG, "账号连接错误: $message")
        }
    }

    private fun connectPresence() {
        val token = SessionStore.getToken(this) ?: return
        presenceClient = PresenceClient(BuildConfig.SIGNAL_URL, presenceListener).also {
            it.connect(token)
        }
    }

    /** 收到好友的共享邀请：接受则进观看端，拒绝则通知对方 */
    private fun showShareInviteDialog(inviteId: String, code: String, fromNickname: String) {
        val dialog = android.app.Dialog(this)
        val dv = com.screenshare.databinding.DialogLiquidConfirmBinding.inflate(layoutInflater)
        dialog.setContentView(dv.root)
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.CENTER)
        }
        dv.tvDialogTitle.text = "共享邀请"
        dv.tvDialogMessage.text = "$fromNickname 邀请你观看 TA 的屏幕\n房间号 $code"
        dv.btnPositive.text = "观看"
        dv.btnNegative.text = "拒绝"
        dv.btnPositive.setOnClickListener {
            dialog.dismiss()
            presenceClient?.acceptShareInvite(inviteId)
            val intent = android.content.Intent(this, MainActivity::class.java)
                .putExtra(MeetingActivity.EXTRA_MEETING_ACTION, MeetingActivity.ACTION_JOIN)
                .putExtra(MeetingActivity.EXTRA_MEETING_CODE, code)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            startActivity(intent)
        }
        dv.btnNegative.setOnClickListener {
            dialog.dismiss()
            presenceClient?.rejectShareInvite(inviteId)
        }
        dialog.show()
    }

    /** 沉浸式状态栏：透明背景 + 深色底配浅色图标，背景铺满系统栏区 */
    private fun setupImmersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        setupInsets()
    }

    /** 内容区/导航栏/toast 单独应用系统栏 inset；光斑层保持全屏铺满 */
    private fun setupInsets() {
        val bars = WindowInsetsCompat.Type.systemBars()
        ViewCompat.setOnApplyWindowInsetsListener(binding.contentArea) { v, insets ->
            val s = insets.getInsets(bars)
            v.setPadding(0, s.top, 0, s.bottom)
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.tabBar) { v, insets ->
            v.updatePadding(bottom = insets.getInsets(bars).bottom)
            WindowInsetsCompat.CONSUMED
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.tvToast) { v, insets ->
            v.updatePadding(bottom = insets.getInsets(bars).bottom)
            WindowInsetsCompat.CONSUMED
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 声明了 configChanges 不重建 Activity，背景层坐标仍是旧尺寸，需要重建
        rebuildBackground()
    }

    /** 旋转/折叠后用新尺寸重建光斑层 */
    private fun rebuildBackground() {
        infiniteAnimators.forEach { it.cancel() }
        infiniteAnimators.clear()
        binding.flBlobs.removeAllViews()
        binding.flBlobs.post { if (!isDestroyed) setupBlobs() }
    }

    /** 4 个彩色光斑 + 背景颗粒 + 漂浮爱心 */
    private fun setupBlobs() {
        if (isDestroyed) return
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

    /** 底部 tab：点击切换内容区 Fragment，当前项高亮 */
    private fun setupTabs() {
        val tabs = arrayOf(binding.tabHome, binding.tabFriends, binding.tabSettings)
        tabs.forEachIndexed { index, tab ->
            tab.setOnClickListener { switchTab(index) }
        }
        // 恢复场景：FragmentManager 已 attach 旧 Fragment，只更新高亮；否则显示首页
        if (subFragment != null) {
            // 恢复到子页面：保持隐藏 tabBar，不切页
            binding.tabBar.visibility = View.GONE
            updateTabHighlight(currentTab)
        } else if (currentTab == -1) {
            switchTab(0, animate = false)
        } else {
            updateTabHighlight(currentTab)
        }
    }

    /** 打开设置子页面：替换内容区并隐藏底部 tab */
    fun navigateToSubPage(fragment: Fragment) {
        subFragment = fragment
        val ft = supportFragmentManager.beginTransaction()
        ft.setCustomAnimations(R.anim.fade_in, R.anim.fade_out)
        ft.replace(R.id.contentArea, fragment)
        ft.commit()
        binding.tabBar.visibility = View.GONE
    }

    /** 关闭子页面，回到当前 tab（默认设置页） */
    fun popSubPage() {
        if (subFragment == null) return
        subFragment = null
        val target = when (currentTab) {
            0 -> homeFragment ?: HomeFragment().also { homeFragment = it }
            1 -> friendsFragment ?: FriendsFragment().also { friendsFragment = it }
            else -> settingsFragment ?: SettingsFragment().also { settingsFragment = it }
        }
        val ft = supportFragmentManager.beginTransaction()
        ft.setCustomAnimations(R.anim.fade_in, R.anim.fade_out)
        ft.replace(R.id.contentArea, target)
        ft.commit()
        binding.tabBar.visibility = View.VISIBLE
    }

    override fun onBackPressed() {
        if (subFragment != null) {
            popSubPage()
        } else {
            super.onBackPressed()
        }
    }

    private fun switchTab(index: Int, animate: Boolean = true) {
        if (index == currentTab) return
        val frag = when (index) {
            0 -> homeFragment ?: HomeFragment().also { homeFragment = it }
            1 -> friendsFragment ?: FriendsFragment().also { friendsFragment = it }
            else -> settingsFragment ?: SettingsFragment().also { settingsFragment = it }
        }
        val ft = supportFragmentManager.beginTransaction()
        if (animate) {
            ft.setCustomAnimations(R.anim.fade_in, R.anim.fade_out)
        }
        ft.replace(R.id.contentArea, frag)
        ft.commit()
        updateTabHighlight(index)
        currentTab = index
    }

    /** tab 高亮：图标与文字颜色随选中态切换 */
    private fun updateTabHighlight(index: Int) {
        val tabs = arrayOf(binding.tabHome, binding.tabFriends, binding.tabSettings)
        tabs.forEachIndexed { i, t ->
            t.isActivated = i == index
            val color = if (i == index) Color.WHITE else 0x99FFFFFF.toInt()
            (t.getChildAt(0) as ImageView).setColorFilter(color)
            (t.getChildAt(1) as TextView).setTextColor(color)
        }
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
        presenceClient?.disconnect()
        presenceClient = null
    }
}
