package io.legado.app.ui.about

import android.content.Context
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.databinding.DialogRecyclerViewBinding
import io.legado.app.databinding.ItemAppLogBinding
import io.legado.app.help.book.SearchLogRecord
import io.legado.app.help.book.SearchLogStorage
import io.legado.app.help.book.SearchLogStore
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.primaryColor
import io.legado.app.ui.widget.dialog.TextDialog
import io.legado.app.utils.LogUtils
import io.legado.app.utils.applyOpenTint
import io.legado.app.utils.applyTint
import io.legado.app.utils.installMd3OverflowMenu
import io.legado.app.utils.setLayout
import io.legado.app.utils.share
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.views.onClick
import java.io.File
import java.util.Date

/**
 * 搜索日志列表：直接读 [SearchLogStorage] 落盘的记录，一次搜索一行。
 * 点一行看该次搜索每个书源返回的结果，菜单里可导出全文或清空。
 */
class SearchLogDialog : BaseDialogFragment(R.layout.dialog_recycler_view),
    Toolbar.OnMenuItemClickListener {

    companion object {
        private const val MAX_SHOW = 100
        private const val MAX_SHARE_TEXT = 64_000
    }

    private val binding by viewBinding(DialogRecyclerViewBinding::bind)
    private val adapter by lazy { LogAdapter(requireContext()) }
    private var records: List<SearchLogRecord> = emptyList()

    override fun onStart() {
        super.onStart()
        setLayout(0.9f, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.run {
            toolBar.setBackgroundColor(primaryColor)
            toolBar.setTitle(R.string.search_log_title)
            toolBar.inflateMenu(R.menu.search_log)
            toolBar.menu.applyTint(requireContext())
            toolBar.installMd3OverflowMenu(
                showIcons = true,
                onOpenCustomMenu = { it.applyOpenTint(requireContext()) }
            )
            toolBar.setOnMenuItemClickListener(this@SearchLogDialog)
            recyclerView.layoutManager = LinearLayoutManager(requireContext())
            recyclerView.adapter = adapter
        }
        loadRecords()
    }

    private fun loadRecords() {
        viewLifecycleOwner.lifecycleScope.launch {
            val data = withContext(Dispatchers.IO) { SearchLogStorage.loadRecent(MAX_SHOW) }
            records = data
            adapter.setItems(data)
            if (data.isEmpty()) {
                toastOnUi(R.string.no_log)
            }
        }
    }

    override fun onMenuItemClick(item: MenuItem?): Boolean {
        when (item?.itemId) {
            R.id.menu_clear -> alert(R.string.clear, R.string.clear_log_confirm) {
                yesButton {
                    SearchLogStore.clear()
                    records = emptyList()
                    adapter.clearItems()
                    toastOnUi(R.string.no_log)
                }
                noButton()
            }

            R.id.menu_export -> exportRecords()
        }
        return true
    }

    private fun exportRecords() {
        if (records.isEmpty()) {
            toastOnUi(R.string.no_log)
            return
        }
        val text = records.joinToString(separator = "\n\n") { it.detail }
        if (text.length <= MAX_SHARE_TEXT) {
            requireContext().share(text, getString(R.string.search_log_title))
            return
        }
        runCatching {
            val file = File(requireContext().cacheDir, "search-log.txt")
            file.writeText(text)
            requireContext().share(file, "text/plain")
        }.onFailure {
            toastOnUi(R.string.can_not_share)
        }
    }

    inner class LogAdapter(context: Context) :
        RecyclerAdapter<SearchLogRecord, ItemAppLogBinding>(context) {

        override fun getViewBinding(parent: ViewGroup): ItemAppLogBinding {
            return ItemAppLogBinding.inflate(inflater, parent, false)
        }

        override fun convert(
            holder: ItemViewHolder,
            binding: ItemAppLogBinding,
            item: SearchLogRecord,
            payloads: MutableList<Any>
        ) {
            binding.textTime.text = LogUtils.logTimeFormat.format(Date(item.time))
            binding.textMessage.text = item.listLine
        }

        override fun registerListener(holder: ItemViewHolder, binding: ItemAppLogBinding) {
            binding.root.onClick {
                getItem(holder.layoutPosition)?.let { record ->
                    showDialogFragment(
                        TextDialog(getString(R.string.search_log_title), record.detail)
                    )
                }
            }
        }
    }
}
