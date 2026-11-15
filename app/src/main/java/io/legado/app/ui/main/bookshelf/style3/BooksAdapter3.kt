package io.legado.app.ui.main.bookshelf.style3

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.AsyncListDiffer
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.data.entities.Book
import io.legado.app.databinding.ItemBookshelfGrid2Binding
import io.legado.app.databinding.ItemBookshelfGridBinding
import io.legado.app.databinding.ItemBookshelfList2Binding
import io.legado.app.databinding.ItemBookshelfListBinding
import io.legado.app.databinding.ItemBookshelfSectionBinding
import io.legado.app.help.book.isLocal
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.main.bookshelf.updateBookshelfReadProgress
import io.legado.app.utils.gone
import io.legado.app.utils.invisible
import io.legado.app.utils.visible
import splitties.views.onLongClick

/**
 * 折叠分组书架适配器：组标题行（占满整行）+ 组内书籍（网格/列表）。
 * 书籍条目的绑定逻辑与 style2 保持一致，避免两种书架风格出现行为差异。
 */
class BooksAdapter3(
    private val context: Context,
    private val callBack: CallBack,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val inflater: LayoutInflater = LayoutInflater.from(context)
    private val showBookname = AppConfig.showBookname
    private val bookshelfLayout = AppConfig.bookshelfLayout

    private val diffItemCallback = object : DiffUtil.ItemCallback<Any>() {
        override fun areItemsTheSame(oldItem: Any, newItem: Any): Boolean {
            return when {
                oldItem is BookshelfSectionItem && newItem is BookshelfSectionItem ->
                    oldItem.groupId == newItem.groupId

                oldItem is Book && newItem is Book -> oldItem.bookUrl == newItem.bookUrl

                else -> false
            }
        }

        override fun areContentsTheSame(oldItem: Any, newItem: Any): Boolean {
            return when {
                oldItem is BookshelfSectionItem && newItem is BookshelfSectionItem ->
                    oldItem == newItem

                oldItem is Book && newItem is Book ->
                    oldItem.durChapterTime == newItem.durChapterTime &&
                            oldItem.durChapterTitle == newItem.durChapterTitle &&
                            oldItem.latestChapterTitle == newItem.latestChapterTitle &&
                            oldItem.name == newItem.name &&
                            oldItem.author == newItem.author &&
                            oldItem.lastCheckCount == newItem.lastCheckCount &&
                            oldItem.getDisplayCover() == newItem.getDisplayCover() &&
                            oldItem.getUnreadChapterNum() == newItem.getUnreadChapterNum()

                else -> false
            }
        }
    }

    private val differ by lazy { AsyncListDiffer(this, diffItemCallback) }

    fun submitList(items: List<Any>) {
        differ.submitList(items)
    }

    fun getItem(position: Int): Any? = differ.currentList.getOrNull(position)

    fun isSection(position: Int): Boolean = getItem(position) is BookshelfSectionItem

    fun notification(bookUrl: String) {
        for (i in 0 until itemCount) {
            val item = getItem(i)
            if (item is Book && item.bookUrl == bookUrl) {
                notifyItemChanged(i)
                return
            }
        }
    }

    /**
     * 布局配置变化（如未读角标开关）时，条目内容不变但展示需要重画
     */
    fun notifyAllChanged() {
        if (itemCount > 0) {
            notifyItemRangeChanged(0, itemCount)
        }
    }

    override fun getItemCount(): Int = differ.currentList.size

    override fun getItemViewType(position: Int): Int {
        return if (isSection(position)) TYPE_SECTION else TYPE_BOOK
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        if (viewType == TYPE_SECTION) {
            return SectionViewHolder(
                ItemBookshelfSectionBinding.inflate(inflater, parent, false)
            )
        }
        if (bookshelfLayout >= 2) {
            return if (showBookname == 2) {
                GridBookViewHolder2(ItemBookshelfGrid2Binding.inflate(inflater, parent, false))
            } else {
                GridBookViewHolder(ItemBookshelfGridBinding.inflate(inflater, parent, false))
            }
        }
        return if (bookshelfLayout == 0) {
            ListBookViewHolder(ItemBookshelfListBinding.inflate(inflater, parent, false))
        } else {
            ListBookViewHolder2(ItemBookshelfList2Binding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = getItem(position) ?: return
        when (holder) {
            is SectionViewHolder -> (item as? BookshelfSectionItem)?.let {
                holder.onBind(it)
            }

            is GridBookViewHolder -> (item as? Book)?.let {
                holder.registerListener(it)
                holder.onBind(it)
            }

            is GridBookViewHolder2 -> (item as? Book)?.let {
                holder.registerListener(it)
                holder.onBind(it)
            }

            is ListBookViewHolder -> (item as? Book)?.let {
                holder.registerListener(it)
                holder.onBind(it)
            }

            is ListBookViewHolder2 -> (item as? Book)?.let {
                holder.registerListener(it)
                holder.onBind(it)
            }
        }
    }

    inner class SectionViewHolder(val binding: ItemBookshelfSectionBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun onBind(item: BookshelfSectionItem) = binding.run {
            tvGroupName.text = item.groupName
            tvGroupCount.text =
                context.getString(R.string.bookshelf_section_count, item.bookCount)
            ivArrow.rotation = if (item.collapsed) 0f else 90f
            root.setOnClickListener {
                callBack.onSectionClick(item.groupId)
            }
            root.onLongClick {
                callBack.onSectionLongClick(item.groupId)
            }
        }
    }

    inner class GridBookViewHolder(val binding: ItemBookshelfGridBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun onBind(item: Book) = binding.run {
            if (showBookname == 1) {
                tvName.gone()
            } else {
                tvName.visible()
                tvName.text = item.name
            }
            ivCover.load(item, false)
            upRefresh(this, item)
        }

        fun registerListener(item: Book) {
            binding.root.setOnClickListener {
                callBack.onItemClick(item)
            }
            binding.root.onLongClick {
                callBack.onItemLongClick(item)
            }
        }

        private fun upRefresh(binding: ItemBookshelfGridBinding, item: Book) {
            binding.pbReadProgress.updateBookshelfReadProgress(item)
            if (!item.isLocal && callBack.isUpdate(item.bookUrl)) {
                binding.bvUnread.invisible()
                binding.rlLoading.visible()
            } else {
                binding.rlLoading.inVisible()
                if (AppConfig.showUnread) {
                    binding.bvUnread.setBadgeCount(item.getUnreadChapterNum())
                    binding.bvUnread.setHighlight(item.lastCheckCount > 0)
                } else {
                    binding.bvUnread.invisible()
                }
            }
        }
    }

    inner class GridBookViewHolder2(val binding: ItemBookshelfGrid2Binding) :
        RecyclerView.ViewHolder(binding.root) {

        fun onBind(item: Book) = binding.run {
            tvName.text = item.name
            ivCover.load(item, false)
            upRefresh(this, item)
        }

        fun registerListener(item: Book) {
            binding.root.setOnClickListener {
                callBack.onItemClick(item)
            }
            binding.root.onLongClick {
                callBack.onItemLongClick(item)
            }
        }

        private fun upRefresh(binding: ItemBookshelfGrid2Binding, item: Book) {
            binding.pbReadProgress.updateBookshelfReadProgress(item)
            if (!item.isLocal && callBack.isUpdate(item.bookUrl)) {
                binding.bvUnread.invisible()
                binding.rlLoading.visible()
            } else {
                binding.rlLoading.inVisible()
                if (AppConfig.showUnread) {
                    binding.bvUnread.setBadgeCount(item.getUnreadChapterNum())
                    binding.bvUnread.setHighlight(item.lastCheckCount > 0)
                } else {
                    binding.bvUnread.invisible()
                }
            }
        }
    }

    inner class ListBookViewHolder(val binding: ItemBookshelfListBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun onBind(item: Book) = binding.run {
            tvName.text = item.name
            tvAuthor.text = item.author
            tvRead.text = item.durChapterTitle
            tvLast.text = item.latestChapterTitle
            ivCover.load(item, false)
            flHasNew.visible()
            ivAuthor.visible()
            ivLast.visible()
            ivRead.visible()
            upRefresh(this, item)
        }

        fun registerListener(item: Book) {
            binding.root.setOnClickListener {
                callBack.onItemClick(item)
            }
            binding.root.onLongClick {
                callBack.onItemLongClick(item)
            }
        }

        private fun upRefresh(binding: ItemBookshelfListBinding, item: Book) {
            binding.pbReadProgress.updateBookshelfReadProgress(item, binding.tvReadPercent)
            if (!item.isLocal && callBack.isUpdate(item.bookUrl)) {
                binding.bvUnread.invisible()
                binding.rlLoading.visible()
            } else {
                binding.rlLoading.gone()
                if (AppConfig.showUnread) {
                    binding.bvUnread.setHighlight(item.lastCheckCount > 0)
                    binding.bvUnread.setBadgeCount(item.getUnreadChapterNum())
                } else {
                    binding.bvUnread.invisible()
                }
            }
        }
    }

    inner class ListBookViewHolder2(val binding: ItemBookshelfList2Binding) :
        RecyclerView.ViewHolder(binding.root) {

        fun onBind(item: Book) = binding.run {
            tvName.text = item.name
            tvAuthor.text = item.author
            tvRead.text = item.durChapterTitle
            tvLast.text = item.latestChapterTitle
            ivCover.load(item, false)
            flHasNew.visible()
            ivAuthor.visible()
            ivLast.visible()
            upRefresh(this, item)
        }

        fun registerListener(item: Book) {
            binding.root.setOnClickListener {
                callBack.onItemClick(item)
            }
            binding.root.onLongClick {
                callBack.onItemLongClick(item)
            }
        }

        private fun upRefresh(binding: ItemBookshelfList2Binding, item: Book) {
            binding.pbReadProgress.updateBookshelfReadProgress(item, binding.tvReadPercent)
            if (!item.isLocal && callBack.isUpdate(item.bookUrl)) {
                binding.bvUnread.invisible()
                binding.rlLoading.visible()
            } else {
                binding.rlLoading.gone()
                if (AppConfig.showUnread) {
                    binding.bvUnread.setHighlight(item.lastCheckCount > 0)
                    binding.bvUnread.setBadgeCount(item.getUnreadChapterNum())
                } else {
                    binding.bvUnread.invisible()
                }
            }
        }
    }

    interface CallBack {
        fun onItemClick(book: Book)
        fun onItemLongClick(book: Book)
        fun onSectionClick(groupId: Long)
        fun onSectionLongClick(groupId: Long)
        fun isUpdate(bookUrl: String): Boolean
    }

    private companion object {
        const val TYPE_SECTION = 0
        const val TYPE_BOOK = 1
    }
}
