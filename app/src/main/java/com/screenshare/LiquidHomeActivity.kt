package com.screenshare

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.widget.FrameLayout
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.screenshare.databinding.ActivityLiquidBinding
import com.screenshare.databinding.ItemRecentBinding

/**
 * 液态玻璃主界面（HTML 原型转换）：深紫渐变背景 + 漂浮光斑 + 毛玻璃卡片。
 * 与现有 MainActivity 业务隔离，作为独立预览界面，后续可替换为主入口。
 */
class LiquidHomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLiquidBinding
    private val handler = Handler(Looper.getMainLooper())
    private var toastRunnable: Runnable? = null
    private val codeEdits = ArrayList<EditText>()

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

    /** 最近会议数据（对应原型 5 条记录） */
    private data class Recent(val code: String, val meta: String, val isCreate: Boolean)

    private val recentList = arrayListOf(
        Recent("1314", "加入 · 16分钟前", false),
        Recent("6883", "创建 · 22小时前", true),
        Recent("1698", "加入 · 1天前", false),
        Recent("3678", "加入 · 15天前", false),
        Recent("4710", "创建 · 15天前", true)
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLiquidBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupImmersive()
        setupBlobs()
        setupCodeInputs()
        setupRecentList()
        setupClicks()
        animateEntrance()
        // 静默自动检查更新（12h 节流，与 MainActivity 行为一致）
        UpdateChecker.check(this)
    }

    /** 沉浸式状态栏：透明背景 + 深色底配白色图标 */
    private fun setupImmersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        // 深色背景需要浅色状态栏图标（清除亮色图标标记）
        val flags = window.decorView.systemUiVisibility and
                android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
        window.decorView.systemUiVisibility = flags
    }

    /** 添加 6 个彩色光斑并启动缓慢漂浮动画（对应 .blob + @keyframes floatN） */
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
            // Android 12+ 用 RenderEffect 模拟 CSS filter:blur(70px)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                view.setRenderEffect(RenderEffect.createBlurEffect(70f, 70f, Shader.TileMode.DECAL))
            }
            val lp = FrameLayout.LayoutParams(size, size, b.gravity)
            lp.setMargins((b.dxDp * density).toInt(), (b.dyDp * density).toInt(), 0, 0)
            binding.flBlobs.addView(view, lp)
            // 漂浮：4 关键帧（0% / 33% / 66% / 100%）自动均匀分布，对应 HTML 的 33%、66% 节点
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
                start()
            }
        }
    }

    /** 4 位数字输入：输满自动跳下一格，Backspace 空格回退到上一格 */
    private fun setupCodeInputs() {
        codeEdits.apply {
            add(binding.etCode0); add(binding.etCode1); add(binding.etCode2); add(binding.etCode3)
        }
        for (i in codeEdits.indices) {
            val et = codeEdits[i]
            et.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

                override fun afterTextChanged(s: Editable?) {
                    val text = s?.toString().orEmpty()
                    if (text.length == 1 && i < codeEdits.size - 1) {
                        codeEdits[i + 1].requestFocus()
                    }
                    // 已填充态：selector 用 state_activated 呈现加亮描边
                    et.isActivated = text.isNotEmpty()
                }
            })
            // 空格按 Backspace 时回退到上一格
            et.setOnKeyListener { _, keyCode, event ->
                if (keyCode == android.view.KeyEvent.KEYCODE_DEL &&
                    event.action == android.view.KeyEvent.ACTION_DOWN &&
                    et.text.isNullOrEmpty() && i > 0
                ) {
                    codeEdits[i - 1].requestFocus()
                    codeEdits[i - 1].setText("")
                    true
                } else {
                    false
                }
            }
        }
    }

    /** 填充最近会议列表（动态 inflate，删除单项即时刷新） */
    private fun setupRecentList() {
        binding.llRecentList.removeAllViews()
        for (r in recentList) {
            val item = ItemRecentBinding.inflate(layoutInflater, binding.llRecentList, false)
            item.tvRecentCode.text = r.code
            item.tvRecentMeta.text = r.meta
            item.ivRecentIcon.setImageResource(if (r.isCreate) R.drawable.ic_liquid_clock else R.drawable.ic_liquid_login)
            item.ivRecentDelete.setOnClickListener {
                recentList.remove(r)
                setupRecentList()
                showToast("已删除该记录")
            }
            item.root.setOnClickListener { showToast("正在加入房间 ${r.code}...") }
            binding.llRecentList.addView(item.root)
        }
    }

    private fun setupClicks() {
        binding.tvCheckUpdate.setOnClickListener { UpdateChecker.check(this, manual = true) }

        // 创建房间：按钮内 spinner 1.5s 后恢复并提示
        binding.btnCreate.setOnClickListener {
            binding.btnCreate.text = "创建中..."
            binding.btnCreate.alpha = 0.8f
            handler.postDelayed({
                binding.btnCreate.text = "创建房间"
                binding.btnCreate.alpha = 1f
                showToast("创建房间成功！")
            }, 1500)
        }

        binding.btnJoin.setOnClickListener {
            val code = codeEdits.joinToString("") { it.text.toString() }
            showToast("正在加入房间 $code...")
        }

        binding.btnCopy.setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("房间号", binding.tvRoomNumber.text))
            showToast("房间号已复制")
        }

        binding.btnCallTa.setOnClickListener {
            showToast("已发送呼叫通知")
            handler.postDelayed({
                // 对方上线：状态点变绿 + 脉冲 + 文案更新
                binding.viewStatusDot.setBackgroundResource(R.drawable.dot_online)
                binding.tvRoomStatus.text = "你是观看方（你看TA的屏幕）· 对方在线"
                pulseStatusDot()
            }, 1500)
        }

        binding.btnClearRecent.setOnClickListener {
            recentList.clear()
            setupRecentList()
            showToast("已清空历史记录")
        }

        // 底部 tab 切换
        val tabs = arrayOf(binding.tabHome, binding.tabFriends, binding.tabSettings)
        val labels = arrayOf("首页", "好友", "设置")
        tabs.forEachIndexed { index, tab ->
            tab.setOnClickListener {
                tabs.forEachIndexed { i, t ->
                    t.isActivated = i == index
                    // 图标与文字颜色随选中态切换
                    val color = if (i == index) Color.WHITE else 0x99FFFFFF.toInt()
                    val icon = (t.getChildAt(0) as ImageView)
                    val label = (t.getChildAt(1) as TextView)
                    icon.setColorFilter(color)
                    label.setTextColor(color)
                }
                showToast("切换到「${labels[index]}」")
            }
        }
        binding.tabHome.isActivated = true
    }

    /** 状态点脉冲（对应 @keyframes pulse 的 box-shadow 呼吸） */
    private fun pulseStatusDot() {
        ObjectAnimator.ofPropertyValuesHolder(
            binding.viewStatusDot,
            PropertyValuesHolder.ofFloat("scaleX", 1f, 1.3f, 1f),
            PropertyValuesHolder.ofFloat("scaleY", 1f, 1.3f, 1f)
        ).apply {
            duration = 2000
            repeatMode = ObjectAnimator.REVERSE
            repeatCount = ObjectAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    /** 卡片错峰入场（对应 fadeInUp，延迟 0.2/0.35/0.5s） */
    private fun animateEntrance() {
        val cards = arrayOf(binding.cardJoin, binding.cardRoom, binding.cardRecent)
        cards.forEachIndexed { i, card ->
            card.translationY = 48f
            card.alpha = 0f
            ObjectAnimator.ofPropertyValuesHolder(
                card,
                PropertyValuesHolder.ofFloat("translationY", 48f, 0f),
                PropertyValuesHolder.ofFloat("alpha", 0f, 1f)
            ).apply {
                startDelay = 200L + i * 150L
                duration = 600L
                interpolator = OvershootInterpolator(0.8f)
                start()
            }
        }
    }

    /** toast：底部滑入，2 秒后滑出 */
    private fun showToast(msg: String) {
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
            ).apply { duration = 350; interpolator = OvershootInterpolator(0.8f); start() }
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
    }
}
