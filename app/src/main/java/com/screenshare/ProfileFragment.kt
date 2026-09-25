package com.screenshare

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * 个人资料页：修改昵称与好友码。
 * 昵称即登录标识（唯一，2-20 字符）；好友码 6 位字母数字（不含 I/O/0/1），随时可改，
 * 改码后他人用旧码发来的待处理邀请自动失效。
 *
 * 输入框大写转换全部交给 inputType/textAllCaps，不在 TextWatcher 里改 Editable
 * （会破坏 IME composing 状态，见 v1.346 好友码输入框修复）。
 */
class ProfileFragment : Fragment() {

    private var saving = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_profile, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<View>(R.id.tvBack).setOnClickListener {
            (requireActivity() as? LiquidHomeActivity)?.popSubPage()
        }

        val p = SessionStore.getProfile(requireContext())
        val etNickname = view.findViewById<android.widget.EditText>(R.id.etNickname)
        val etCode = view.findViewById<android.widget.EditText>(R.id.etFriendCode)
        etNickname.setText(p?.nickname.orEmpty())
        etCode.setText(p?.friendCode.orEmpty())

        view.findViewById<View>(R.id.btnSave).setOnClickListener {
            if (saving) return@setOnClickListener
            val nickname = etNickname.text.toString().trim()
            val code = etCode.text.toString().trim().uppercase()
            if (nickname.length !in 2..20) {
                toast("昵称需为 2-20 个字符"); return@setOnClickListener
            }
            if (!code.matches(Regex("^[A-Z2-9]{6}$")) || code.any { it == 'I' || it == 'O' }) {
                // 服务端 alphabet 剔除 I/O/0/1，正则 [A-Z2-9] 外再显式排除 I、O（0/1 已被排除）
                toast("好友码需为 6 位字母或数字（不含 I、O、0、1）"); return@setOnClickListener
            }
            val token = SessionStore.getToken(requireContext()).orEmpty()
            if (token.isBlank()) { toast("请先登录"); return@setOnClickListener }

            // 好友码变化时二次确认：旧码的待处理邀请将作废
            if (code != p?.friendCode) {
                android.app.AlertDialog.Builder(requireContext())
                    .setTitle("修改好友码")
                    .setMessage("好友码将改为 $code，他人用旧码发来的待处理邀请会自动失效。确定修改？")
                    .setPositiveButton("修改") { _, _ -> doSave(token, nickname, code) }
                    .setNegativeButton("取消", null)
                    .show()
                return@setOnClickListener
            }
            doSave(token, nickname, code)
        }
    }

    private fun doSave(token: String, nickname: String, friendCode: String) {
        saving = true
        toast("正在保存…")
        lifecycleScope.launch {
            when (val r = AccountClient.updateProfile(token, nickname = nickname, friendCode = friendCode)) {
                is AccountClient.ApiResult.Success -> {
                    SessionStore.updateProfile(
                        requireContext(), r.data.nickname, r.data.avatar, r.data.friendCode
                    )
                    toast("已保存")
                    (requireActivity() as? LiquidHomeActivity)?.popSubPage()
                }
                is AccountClient.ApiResult.Failure -> toast("保存失败：${r.message}")
            }
            saving = false
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
    }
}
