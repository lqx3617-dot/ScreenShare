package com.screenshare

import android.os.Bundle
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch
import java.util.Date

/**
 * 共同愿望清单：双方共享待办。任一方可添加、勾选（记录完成人）、删除；
 * 未完成在前，已完成的划线置灰。
 */
class WishlistFragment : Fragment() {

    // 删除确认对话框引用：Fragment 销毁时主动 dismiss，避免残留对话框悬空点击
    private var deleteDialog: android.app.AlertDialog? = null

    private val adapter by lazy {
        WishesAdapter(
            onToggle = { w ->
                val tk = token() ?: return@WishesAdapter
                lifecycleScope.launch {
                    when (val r = CoupleClient.toggleWish(tk, w.wishId, !w.done)) {
                        is AccountClient.ApiResult.Success -> load()
                        is AccountClient.ApiResult.Failure -> toast(r.message)
                    }
                }
            },
            onDelete = { w ->
                val act = activity
                if (act == null || !isAdded) {
                    return@WishesAdapter
                }
                android.app.AlertDialog.Builder(act)
                    .setMessage("删除这条愿望？")
                    .setPositiveButton("删除") { _, _ ->
                        if (!isAdded) return@setPositiveButton
                        val tk = token() ?: return@setPositiveButton
                        lifecycleScope.launch {
                            when (val r = CoupleClient.deleteWish(tk, w.wishId)) {
                                is AccountClient.ApiResult.Success -> load()
                                is AccountClient.ApiResult.Failure -> toast(r.message)
                            }
                        }
                    }
                    .setNegativeButton("取消", null)
                    .also { deleteDialog = it.show() }
            }
        )
    }

    private fun token(): String? = SessionStore.getToken(requireContext())

    private fun toast(msg: String) {
        (activity as? LiquidHomeActivity)?.showToast(msg)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_wishlist, container, false)
        view.findViewById<View>(R.id.tvBack).setOnClickListener {
            (requireActivity() as? LiquidHomeActivity)?.popSubPage()
        }
        view.findViewById<RecyclerView>(R.id.rvWishes).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@WishlistFragment.adapter
        }
        view.findViewById<View>(R.id.btnAdd).setOnClickListener { addWish() }
        return view
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // 删除确认对话框同样可能在 Fragment 销毁后残留，dismiss 避免悬空点击
        deleteDialog?.dismiss()
        deleteDialog = null
    }

    private fun load() {
        val tk = token() ?: return
        lifecycleScope.launch {
            when (val r = CoupleClient.getWishes(tk)) {
                is AccountClient.ApiResult.Success -> {
                    adapter.submitList(r.data)
                    view?.findViewById<View>(R.id.tvEmpty)?.visibility =
                        if (r.data.isEmpty()) View.VISIBLE else View.GONE
                }
                is AccountClient.ApiResult.Failure -> {
                    if (r.http == 401) {
                        toast("登录已失效，请重新登录")
                        val act = activity ?: return@launch
                        SessionStore.clear(act)
                        startActivity(android.content.Intent(act, LoginActivity::class.java))
                        act.finish()
                    } else toast(r.message)
                }
            }
        }
    }

    private fun addWish() {
        val tk = token() ?: return
        val et = view?.findViewById<android.widget.EditText>(R.id.etWish) ?: return
        val text = et.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) { toast("写点什么吧"); return }
        et.text?.clear()
        lifecycleScope.launch {
            when (val r = CoupleClient.addWish(tk, text)) {
                is AccountClient.ApiResult.Success -> load()
                is AccountClient.ApiResult.Failure -> {
                    toast("添加失败：${r.message}")
                    et.setText(text)
                }
            }
        }
    }

    private class WishesAdapter(
        private val onToggle: (CoupleClient.Wish) -> Unit,
        private val onDelete: (CoupleClient.Wish) -> Unit
    ) : ListAdapter<CoupleClient.Wish, WishesAdapter.VH>(DIFF) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_couple_wish, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val w = getItem(position)
            val v = holder.itemView
            v.findViewById<TextView>(R.id.tvText).apply {
                text = w.text
                paintFlags = if (w.done) {
                    paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
                } else {
                    paintFlags and android.graphics.Paint.STRIKE_THRU_TEXT_FLAG.inv()
                }
                alpha = if (w.done) 0.6f else 1f
            }
            v.findViewById<TextView>(R.id.tvCheck).apply {
                text = if (w.done) "✓" else "○"
                setTextColor(if (w.done) 0xFF34D399.toInt() else 0x99FFFFFF.toInt())
            }
            val meta = buildString {
                append("由 ${w.authorNickname.ifBlank { "TA" }} 添加")
                if (w.done && w.doneAt > 0) {
                    val d = DateFormat.format("MM-dd HH:mm", Date(w.doneAt))
                    append(" · ${w.doneBy.ifBlank { "TA" }} 完成 · $d")
                }
            }
            v.findViewById<TextView>(R.id.tvMeta).text = meta
            v.findViewById<View>(R.id.btnCheck).setOnClickListener { onToggle(w) }
            v.findViewById<View>(R.id.btnDelete).setOnClickListener { onDelete(w) }
        }

        class VH(v: View) : RecyclerView.ViewHolder(v)

        companion object {
            private val DIFF = object : DiffUtil.ItemCallback<CoupleClient.Wish>() {
                override fun areItemsTheSame(o: CoupleClient.Wish, n: CoupleClient.Wish) = o.wishId == n.wishId
                override fun areContentsTheSame(o: CoupleClient.Wish, n: CoupleClient.Wish) = o == n
            }
        }
    }
}
