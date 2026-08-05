package com.screenshare

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject

/**
 * 远程控制无障碍服务：解析 CONTROL 通道指令并模拟执行。
 * 仅执行坐标/按键/文本指令，不读取不上传任何屏幕内容。
 */
class RemoteControlService : AccessibilityService() {

    companion object {
        private const val TAG = "RemoteControlService"

        @Volatile
        var instance: RemoteControlService? = null

        /** 共享方「停止远程控制」开关，关闭后忽略全部控制指令 */
        @Volatile
        var controlEnabled: Boolean = true

        /** 指令执行结果回调（如文本输入失败），由 MainActivity 注入用于回发观看方 */
        @Volatile
        var execResultCallback: ((String) -> Unit)? = null

        /** 服务已开启且控制开关打开时返回 true，否则 false */
        @JvmStatic
        fun isActive(): Boolean = instance != null && controlEnabled

        @JvmStatic
        fun isAccessibilityOn(): Boolean = instance != null

        /** 由共享方控制通道分发调用；返回 false 表示服务不可用，上层应回发提示 */
        @JvmStatic
        fun handle(json: JSONObject): Boolean {
            val svc = instance ?: return false
            if (!controlEnabled) return false
            svc.mainHandler.post { svc.dispatch(json) }
            return true
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var screenW = 0
    private var screenH = 0

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        val dm = resources.displayMetrics
        screenW = dm.widthPixels
        screenH = dm.heightPixels
        Log.d(TAG, "远程控制无障碍服务已连接: ${screenW}x${screenH}")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun dispatch(json: JSONObject) {
        try {
            when (json.optString("type")) {
                "touch" -> execTouch(json)
                "key" -> execKey(json)
                "text" -> execText(json)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "指令执行异常: ${t.message}")
        }
    }

    private fun execTouch(json: JSONObject) {
        val nx = json.optDouble("nx", 0.5).toFloat()
        val ny = json.optDouble("ny", 0.5).toFloat()
        if (screenW <= 0 || screenH <= 0) return
        val (x, y) = CoordinateMapper.toScreenPx(nx, ny, screenW, screenH)
        val action = json.optString("action", "up")
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val duration = if (action == "move") 16L else 1L
        val stroke = GestureDescription.StrokeDescription(path, 0, duration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        if (!dispatchGesture(gesture, null, null)) {
            Log.w(TAG, "dispatchGesture 失败: action=$action x=$x y=$y")
        }
    }

    private fun execKey(json: JSONObject): Boolean {
        val global = when (json.optString("value")) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            else -> return false
        }
        return performGlobalAction(global)
    }

    private fun execText(json: JSONObject) {
        val text = json.optString("value")
        if (text.isEmpty()) return
        val node = focusedEditable()
        if (node == null) {
            Log.w(TAG, "无聚焦输入框")
            execResultCallback?.invoke("""{"type":"status-error","code":"no-focused-input"}""")
            return
        }
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        node.recycle()
        if (!ok) {
            execResultCallback?.invoke("""{"type":"status-error","code":"text-failed"}""")
        }
    }

    private fun focusedEditable(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val found = findFocusedEditable(root)
        root.recycle()
        return found
    }

    private fun findFocusedEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable && node.isFocused) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val hit = findFocusedEditable(child)
            child.recycle()
            if (hit != null) return hit
        }
        return null
    }
}
