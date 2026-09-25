package com.screenshare

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.datepicker.MaterialDatePicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 情侣空间页：未绑定时展示好友码绑定入口与待处理邀请；已绑定时展示关系、在一起天数、
 * 伴侣位置与相册入口。页面可见时按固定间隔上报自身位置。
 */
class CoupleFragment : Fragment() {

    private var space: CoupleClient.CoupleSpace? = null
    private var locationJob: Job? = null
    private var locPermissionRequested = false
    private val timeFmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    // 背景选择对话框引用：Fragment 被 replace 销毁时 AlertDialog 不会随之消失，
    // 若不 dismiss，残留对话框的点击会调到已注销的 ActivityResultLauncher 而崩溃
    private var bgDialog: androidx.appcompat.app.AlertDialog? = null

    private val pickLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { r ->
        if (r.resultCode == Activity.RESULT_OK) {
            r.data?.data?.let { uri -> importBg(uri) }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_couple, container, false)
        view.findViewById<View>(R.id.btnInvite).setOnClickListener { onInvite() }
        view.findViewById<View>(R.id.cardAlbum).setOnClickListener { openAlbum() }
        view.findViewById<View>(R.id.btnDissolve).setOnClickListener { onDissolve() }
        view.findViewById<View>(R.id.tvBgCustom).setOnClickListener { showBgDialog() }
        view.findViewById<View>(R.id.cardAnniversary).setOnClickListener { showAnniversaryPicker() }
        view.findViewById<View>(R.id.btnCheckin).setOnClickListener { doCheckin() }
        view.findViewById<View>(R.id.cardWishlist).setOnClickListener {
            (requireActivity() as? LiquidHomeActivity)?.navigateToSubPage(WishlistFragment())
        }
        // 大写转换交给 inputType=textCapCharacters（XML 已声明）；勿在 TextWatcher 里改 Editable，
        // 否则破坏 IME composing 状态，输入法候选词无法上屏，表现为打不出字
        return view
    }

    override fun onResume() {
        super.onResume()
        applyBg()
        loadSpace()
        startLocationReport()
    }

    override fun onPause() {
        super.onPause()
        locationJob?.cancel()
        locationJob = null
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // 防止背景对话框在 Fragment 销毁后残留（残留对话框的点击会调已注销的 launcher）
        bgDialog?.dismiss()
        bgDialog = null
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // 用户当场同意定位权限后立即启动上报，否则要等下次 onResume 才会上报
        if (requestCode == 1001 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startLocationReport()
        }
    }

    /** 推送回调：邀请/绑定/解绑后刷新页面 */
    fun onCoupleChanged() {
        if (isAdded) loadSpace()
    }

    private fun token(): String = SessionStore.getToken(requireContext()) ?: ""

    private fun loadSpace() {
        val tk = token()
        if (tk.isBlank()) return
        lifecycleScope.launch {
            showLoading(true)
            when (val r = CoupleClient.getSpace(tk)) {
                is AccountClient.ApiResult.Success -> {
                    space = r.data
                    renderSpace(r.data)
                    if (!r.data.bound) loadInvitations(tk)
                }
                is AccountClient.ApiResult.Failure -> {
                    if (r.http == 401) requireActivity().runOnUiThread {
                        SessionStore.clear(requireContext())
                        startActivity(Intent(requireContext(), LoginActivity::class.java))
                    }
                    toast("加载失败：${r.message}")
                }
            }
            showLoading(false)
        }
    }

    private fun loadInvitations(tk: String) {
        lifecycleScope.launch {
            when (val r = CoupleClient.getInvitations(tk)) {
                is AccountClient.ApiResult.Success -> renderInvitations(r.data)
                is AccountClient.ApiResult.Failure -> Unit
            }
        }
    }

    private fun renderSpace(s: CoupleClient.CoupleSpace) {
        val v = view ?: return
        v.findViewById<View>(R.id.unboundView).visibility = if (s.bound) View.GONE else View.VISIBLE
        v.findViewById<View>(R.id.boundView).visibility = if (s.bound) View.VISIBLE else View.GONE
        if (!s.bound) return

        val me = SessionStore.getProfile(requireContext())
        bindAvatar(v.findViewById(R.id.avatarMe), me?.userId ?: "", me?.nickname ?: "我")
        v.findViewById<TextView>(R.id.tvNicknameMe).text = me?.nickname ?: "我"

        val p = s.partner
        bindAvatar(v.findViewById(R.id.avatarPartner), p?.userId ?: "", p?.nickname ?: "TA")
        v.findViewById<TextView>(R.id.tvNicknamePartner).text = p?.nickname ?: "TA"
        v.findViewById<TextView>(R.id.tvOnline).apply {
            text = if (p?.online == true) "在线" else "离线"
            setTextColor(if (p?.online == true) 0xFF4ADE80.toInt() else 0xFF99FFFFFF.toInt())
        }

        v.findViewById<TextView>(R.id.tvDays).text = s.days.toString()

        val loc = s.location
        v.findViewById<TextView>(R.id.tvLocation).text = if (loc == null) {
            "暂无位置信息"
        } else {
            // 地址来自服务端高德逆地理编码；未配置 Key 或解码失败时降级经纬度
            val place = loc.address?.takeIf { it.isNotBlank() }
                ?: "经纬度 ${"%.4f".format(loc.lng)}, ${"%.4f".format(loc.lat)}"
            "$place · ${timeFmt.format(Date(loc.reportedAt))}"
        }

        v.findViewById<TextView>(R.id.tvPhotoCount).text = "${s.photoCount} 项"
        renderAnniversary(s.anniversary)
        renderWeather(s.weather)
        renderCheckin(s.checkin)
    }

    /** 打卡卡：双方今日状态 + 连续天数；已打卡时按钮禁用 */
    private fun renderCheckin(ck: CoupleClient.CheckinStatus?) {
        val v = view ?: return
        if (ck == null) {
            v.findViewById<View>(R.id.cardCheckin).visibility = View.GONE
            return
        }
        v.findViewById<View>(R.id.cardCheckin).visibility = View.VISIBLE
        val me = ck.me
        val p = ck.partner
        v.findViewById<TextView>(R.id.tvCheckinStreak).apply {
            text = "已连续 ${me.streak} 天"
            visibility = if (me.streak > 0) View.VISIBLE else View.GONE
        }
        v.findViewById<TextView>(R.id.tvCheckinStatus).text = buildString {
            append(if (me.today) "你今天已打卡" else "你今天还没打卡")
            append(if (me.streak > 0) " · 连续 ${me.streak} 天" else "")
            append("\n")
            append(if (p.today) "TA 今天已打卡" else "TA 今天还没打卡")
            append(if (p.streak > 0) " · 连续 ${p.streak} 天" else "")
        }
        v.findViewById<TextView>(R.id.btnCheckin).apply {
            isEnabled = !me.today
            alpha = if (me.today) 0.5f else 1f
            text = if (me.today) "今天已打卡" else "今日打卡"
        }
    }

    private fun doCheckin() {
        val tk = token()
        if (tk.isBlank()) return
        lifecycleScope.launch {
            when (val r = CoupleClient.checkin(tk)) {
                is AccountClient.ApiResult.Success -> { toast("打卡成功"); loadSpace() }
                is AccountClient.ApiResult.Failure -> toast(r.message)
            }
        }
    }

    /** 天气行：服务端用伴侣位置的 adcode 查高德实时天气；无位置/未配 Key/失败时降级提示 */
    private fun renderWeather(w: CoupleClient.Weather?) {
        val v = view ?: return
        v.findViewById<TextView>(R.id.tvWeather).text = if (w == null || (w.text.isBlank() && w.temp.isBlank())) {
            "暂无天气信息"
        } else {
            // 「北京市 · 多云 24°C · 湿度 46% · 北风 3 级」按字段有无拼接
            buildString {
                if (w.city.isNotBlank()) append("${w.city} · ")
                if (w.text.isNotBlank()) append(w.text)
                if (w.temp.isNotBlank()) append(" ${w.temp}°C")
                if (w.humidity.isNotBlank()) append(" · 湿度 ${w.humidity}%")
                if (w.wind.isNotBlank()) append(" · ${w.wind}")
            }.trim().trimEnd('·').trim()
        }
    }

    /** 纪念日卡：未设时引导设置；已设时显示日期与距下一个周年的天数 */
    private fun renderAnniversary(ann: String?) {
        val v = view ?: return
        val tvAnn = v.findViewById<TextView>(R.id.tvAnniversary)
        val tvDays = v.findViewById<TextView>(R.id.tvAnnivDays)
        val tvLabel = v.findViewById<TextView>(R.id.tvAnnivLabel)
        if (ann.isNullOrBlank()) {
            tvAnn.text = "点击设置你们的纪念日"
            tvDays.visibility = View.GONE
            tvLabel.visibility = View.GONE
            return
        }
        val parts = ann.split("-")
        if (parts.size != 3) return
        val y = parts[0].toInt(); val m = parts[1].toInt(); val d = parts[2].toInt()
        tvAnn.text = "$y 年 $m 月 $d 日 · 点击修改"
        tvDays.visibility = View.VISIBLE
        tvLabel.visibility = View.VISIBLE
        val today = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        var nextYear = today.get(Calendar.YEAR)
        val next = Calendar.getInstance().apply { set(nextYear, m - 1, d, 0, 0, 0) }
        if (next.before(today)) {
            nextYear += 1
            next.set(Calendar.YEAR, nextYear)
        }
        val daysLeft = ((next.timeInMillis - today.timeInMillis) / 86_400_000L).toInt()
        val anniversaryCount = nextYear - y
        if (daysLeft == 0) {
            tvDays.text = "今天"
            tvLabel.text = "是你们第 $anniversaryCount 周年"
        } else {
            tvDays.text = "$daysLeft"
            tvLabel.text = "天后是第 $anniversaryCount 周年"
        }
    }

    private fun showAnniversaryPicker() {
        val tk = token()
        if (tk.isBlank()) return
        val cur = space
        // 默认选已设纪念日；没设就用绑定日（多数情况纪念日就是绑定日附近）
        val selection = when {
            cur?.anniversary?.length == 10 -> {
                val p = cur.anniversary!!.split("-")
                localToUtcMs(p[0].toInt(), p[1].toInt(), p[2].toInt())
            }
            cur != null && cur.boundAt > 0 -> cur.boundAt
            else -> MaterialDatePicker.todayInUtcMilliseconds()
        }
        val picker = MaterialDatePicker.Builder.datePicker()
            .setTitleText("选择在一起纪念日")
            .setSelection(selection)
            .build()
        picker.addOnPositiveButtonClickListener { ms ->
            // ms 是选中日期 UTC 0 点，按本地时区取出年月日
            val cal = Calendar.getInstance().apply { timeInMillis = ms }
            val iso = "%04d-%02d-%02d".format(
                cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH)
            )
            lifecycleScope.launch {
                when (val r = CoupleClient.setAnniversary(tk, iso)) {
                    is AccountClient.ApiResult.Success -> { toast("纪念日已更新"); loadSpace() }
                    is AccountClient.ApiResult.Failure -> toast("设置失败：${r.message}")
                }
            }
        }
        picker.show(parentFragmentManager, "anniversary")
    }

    /** 本地日期 0 点 → 该日期 UTC 0 点的毫秒（MaterialDatePicker 内部按 UTC 解释） */
    private fun localToUtcMs(y: Int, m: Int, d: Int): Long {
        val cal = Calendar.getInstance().apply {
            set(y, m - 1, d, 0, 0, 0); set(Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis - java.util.TimeZone.getDefault().getOffset(cal.timeInMillis).toLong()
    }

    private fun bindAvatar(box: View, userId: String, nickname: String) {
        val res = intArrayOf(
            R.drawable.bg_avatar_pink, R.drawable.bg_avatar_violet, R.drawable.bg_avatar_cyan
        )
        box.setBackgroundResource(res[(userId.hashCode() and 0x7fffffff) % res.size])
        (box as? ViewGroup)?.let { it.removeAllViews(); it.addView(TextView(requireContext()).apply {
            text = nickname.firstOrNull()?.toString() ?: "?"
            textSize = 26f
            gravity = android.view.Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
        }) }
    }

    private fun renderInvitations(list: List<CoupleClient.CoupleInvitation>) {
        val v = view ?: return
        val container = v.findViewById<LinearLayout>(R.id.invitationsContainer)
        container.removeAllViews()
        v.findViewById<View>(R.id.tvInvitationsTitle).visibility =
            if (list.isEmpty()) View.GONE else View.VISIBLE
        for (inv in list) {
            val item = layoutInflater.inflate(R.layout.item_couple_invitation, container, false)
            item.findViewById<TextView>(R.id.tvNickname).text = inv.fromNickname
            item.findViewById<View>(R.id.btnAccept).setOnClickListener { accept(inv) }
            item.findViewById<View>(R.id.btnReject).setOnClickListener { reject(inv) }
            container.addView(item)
        }
    }

    private fun onInvite() {
        val v = view ?: return
        val code = v.findViewById<android.widget.EditText>(R.id.etFriendCode).text.toString().trim().uppercase()
        if (code.length != 6) { toast("请输入 6 位好友码"); return }
        val tk = token()
        lifecycleScope.launch {
            showLoading(true)
            when (val r = CoupleClient.invite(tk, code)) {
                is AccountClient.ApiResult.Success -> {
                    if (r.data.optBoolean("accepted")) toast("已绑定成功") else toast("邀请已发送，等待对方确认")
                    loadSpace()
                }
                is AccountClient.ApiResult.Failure -> toast("发送失败：${r.message}")
            }
            showLoading(false)
        }
    }

    private fun accept(inv: CoupleClient.CoupleInvitation) {
        val tk = token()
        lifecycleScope.launch {
            when (val r = CoupleClient.accept(tk, inv.invitationId)) {
                is AccountClient.ApiResult.Success -> { toast("已绑定"); loadSpace() }
                is AccountClient.ApiResult.Failure -> toast("接受失败：${r.message}")
            }
        }
    }

    private fun reject(inv: CoupleClient.CoupleInvitation) {
        val tk = token()
        lifecycleScope.launch {
            when (val r = CoupleClient.reject(tk, inv.invitationId)) {
                is AccountClient.ApiResult.Success -> loadInvitations(tk)
                is AccountClient.ApiResult.Failure -> toast("操作失败：${r.message}")
            }
        }
    }

    private fun onDissolve() {
        val ctx = requireContext()
        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle("解除绑定")
            .setMessage("解除后，你们共享的照片、视频和位置信息都将对彼此不可见，且需要重新邀请才能绑定。确定解除绑定吗？")
            .setPositiveButton("解除绑定") { d, _ ->
                d.dismiss()
                val tk = token() ?: return@setPositiveButton
                lifecycleScope.launch {
                    when (val r = CoupleClient.dissolve(tk)) {
                        is AccountClient.ApiResult.Success -> {
                            // 清理本地相册缓存：过期关系的内容不再保留
                            CoupleMediaCache.clear(requireContext())
                            toast("已解除绑定"); space = null; loadSpace()
                        }
                        is AccountClient.ApiResult.Failure -> toast("解绑失败：${r.message}")
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun openAlbum() {
        startActivity(Intent(requireContext(), CouplePhotosActivity::class.java))
    }

    // ==================== 自定义背景 ====================

    private fun bgFile(): File = File(requireContext().filesDir, BG_FILE)

    private fun showBgDialog() {
        val items = arrayOf("从相册选择照片", "使用原版背景")
        bgDialog = androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("情侣空间背景")
            .setItems(items) { d, which ->
                // 对话框可能在 Fragment 销毁后仍残留（Activity 未重建时 replace 不自动 dismiss）
                if (!isAdded) { d.dismiss(); return@setItems }
                when (which) {
                    0 -> {
                        val intent = Intent(Intent.ACTION_PICK).apply {
                            setDataAndType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*")
                        }
                        pickLauncher.launch(intent)
                    }
                    1 -> {
                        bgFile().delete()
                        applyBg()
                        toast("已恢复原版背景")
                    }
                }
                d.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun importBg(uri: Uri) {
        try {
            requireContext().contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(bgFile()).use { out -> input.copyTo(out) }
            } ?: run { toast("无法读取图片"); return }
            applyBg()
            toast("背景已更新")
        } catch (t: Throwable) {
            toast("背景设置失败：${t.message}")
        }
    }

    /** 有自定义图则铺满背景，无则隐藏透出全局渐变（原版） */
    private fun applyBg() {
        val iv = view?.findViewById<ImageView>(R.id.ivCoupleBg) ?: return
        val file = bgFile()
        if (!file.exists()) {
            iv.setImageDrawable(null)
            iv.visibility = View.GONE
            return
        }
        try {
            iv.setImageBitmap(decodeSampled(file.absolutePath))
            iv.visibility = View.VISIBLE
        } catch (t: Throwable) {
            iv.visibility = View.GONE
            toast("背景解码失败，已恢复原版")
        }
    }

    /** 降采样到约 1080 宽，RGB_565 省内存，防止大图 OOM */
    private fun decodeSampled(path: String): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        var w = bounds.outWidth
        while (w / 2 >= 1080) {
            sample *= 2
            w /= 2
        }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return BitmapFactory.decodeFile(path, opts)
    }

    companion object {
        private const val BG_FILE = "couple_bg.jpg"
    }

    /** 页面可见时每 60s 上报一次自身位置（需定位权限，无权限时请求一次） */
    @SuppressLint("MissingPermission")
    private fun startLocationReport() {
        locationJob?.cancel()
        if (!hasLocationPermission()) {
            if (!locPermissionRequested) {
                locPermissionRequested = true
                requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION), 1001)
            }
            return
        }
        locationJob = lifecycleScope.launch {
            while (isActive) {
                reportOnce()
                delay(60_000)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun reportOnce() = withContext(Dispatchers.IO) {
        val tk = token()
        if (tk.isBlank()) return@withContext
        try {
            val lm = requireContext().getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                ?: return@withContext
            val loc = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: return@withContext
            CoupleClient.reportLocation(tk, loc.latitude, loc.longitude)
        } catch (e: Exception) {
            AppLogger.app("[couple] 位置上报失败：${e.message}")
        }
    }

    private fun hasLocationPermission(): Boolean = ContextCompat.checkSelfPermission(
        requireContext(), Manifest.permission.ACCESS_COARSE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    private fun showLoading(loading: Boolean) {
        view?.findViewById<View>(R.id.loading)?.visibility =
            if (loading) View.VISIBLE else View.GONE
    }

    private fun toast(msg: String) {
        android.widget.Toast.makeText(requireContext(), msg, android.widget.Toast.LENGTH_SHORT).show()
    }
}
