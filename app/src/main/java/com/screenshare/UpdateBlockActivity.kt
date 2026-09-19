package com.screenshare

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject

/**
 * 版本门禁拦截页：本地版本低于服务端要求的最低版本时启动（CLEAR_TASK 清空回退栈）。
 * 用户只能立即更新或退出应用，无法返回旧版本界面。
 */
class UpdateBlockActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_update_block)

        val infoStr = intent.getStringExtra(EXTRA_INFO) ?: ""
        val info = if (infoStr.isNotEmpty()) JSONObject(infoStr) else JSONObject()
        val newName = info.optString("versionName", "新")
        val sizeText = UpdateChecker.formatSize(info.optLong("size", 0L))

        findViewById<TextView>(R.id.tvBlockDesc).text = buildString {
            append("当前版本 v${BuildConfig.VERSION_NAME} 已不可用\n")
            append("请更新到 v$newName 后继续使用")
            if (sizeText.isNotEmpty() && sizeText != "0 B") append("（$sizeText）")
        }

        findViewById<Button>(R.id.btnUpdate).setOnClickListener {
            it.isEnabled = false
            UpdateChecker.downloadUpdate(this, info)
        }
        findViewById<Button>(R.id.btnExit).setOnClickListener {
            finishAffinity()
        }
    }

    // 拦截页禁止返回：返回退出应用，而不是回到被拦截的旧版本界面
    override fun onBackPressed() {
        finishAffinity()
    }

    companion object {
        const val EXTRA_INFO = "update_info"
    }
}
