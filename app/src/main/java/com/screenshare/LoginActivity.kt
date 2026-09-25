package com.screenshare

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.screenshare.databinding.ActivityLoginBinding
import kotlinx.coroutines.launch

/**
 * 登录/注册页（昵称 + 密码，无邮箱无验证码）。
 * 昵称即登录标识，注册时需未被占用；密码至少 8 位且含两类字符。
 * 登录成功后写本地会话并进入 LiquidHomeActivity。
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private var registerMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnSubmit.setOnClickListener { submit() }
        binding.btnSwitchMode.setOnClickListener { switchMode() }
    }

    private fun switchMode() {
        registerMode = !registerMode
        binding.btnSubmit.text = if (registerMode) "注册" else "登录"
        binding.btnSwitchMode.text = if (registerMode) "已有账号？去登录" else "没有账号？去注册"
        binding.tvSubtitle.text = if (registerMode) "选一个昵称并设置密码完成注册" else "登录后添加好友，一键发起屏幕共享"
        binding.tvError.visibility = View.GONE
    }

    private fun submit() {
        val nickname = binding.etNickname.text?.toString()?.trim().orEmpty()
        val password = binding.etPassword.text?.toString().orEmpty()
        if (nickname.length !in 2..20) {
            showError("昵称需为 2-20 个字符")
            return
        }
        if (password.length < 8) {
            showError("密码至少 8 位")
            return
        }
        setLoading(true)
        lifecycleScope.launch {
            val result = if (registerMode) AccountClient.register(nickname, password)
            else AccountClient.login(nickname, password)
            setLoading(false)
            when (result) {
                is AccountClient.ApiResult.Success -> {
                    SessionStore.saveSession(
                        this@LoginActivity,
                        token = result.data.token,
                        userId = result.data.userId,
                        nickname = result.data.profile.nickname,
                        avatar = result.data.profile.avatar,
                        friendCode = result.data.profile.friendCode
                    )
                    AppLogger.app("[LOGIN] ${if (registerMode) "注册" else "登录"}成功 nickname=${result.data.profile.nickname} code=${result.data.profile.friendCode}")
                    // 登录后立即上报极光标识，离线好友邀请才能推到通知栏
                    JPushRegistrar.register(this@LoginActivity)
                    startActivity(Intent(this@LoginActivity, LiquidHomeActivity::class.java))
                    finish()
                }
                is AccountClient.ApiResult.Failure -> {
                    AppLogger.app("[LOGIN] ${if (registerMode) "注册" else "登录"}失败: ${result.message}")
                    showError(result.message)
                }
            }
        }
    }

    private fun showError(msg: String) {
        binding.tvError.text = msg
        binding.tvError.visibility = View.VISIBLE
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun setLoading(loading: Boolean) {
        binding.progress.visibility = if (loading) View.VISIBLE else View.GONE
        binding.btnSubmit.isEnabled = !loading
    }
}
