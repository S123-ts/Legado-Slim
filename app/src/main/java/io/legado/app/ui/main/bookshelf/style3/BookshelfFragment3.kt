package io.legado.app.ui.main.bookshelf.style3

import android.graphics.Rect
import android.os.Bundle
import android.view.View
import android.view.ViewConfiguration
import androidx.appcompat.widget.SearchView
import androidx.core.view.isGone
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.databinding.FragmentBookshelf3Binding
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.primaryColor
import io.legado.app.ui.book.group.GroupEditDialog
import io.legado.app.ui.book.group.GroupManageDialog
import io.legado.app.ui.book.info.BookInfoActivity
import io.legado.app.ui.book.search.SearchActivity
import io.legado.app.ui.main.bookshelf.BaseBookshelfFragment
import io.legado.app.utils.flowWithLifecycleAndDatabaseChangeFirst
import io.legado.app.utils.observeEvent
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import io.legado.app.utils.startActivityForBook
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

/**
 * 书架界面 · 折叠分组
 *
 * 与另外两种风格的差异：
 * - style1（标签）：每个分组一个 Tab，组间横向切换；
 * - style2（文件夹）：分组显示成文件夹方块，点开进入该分组；
 * - style3（折叠分组）：同一个列表里按分组纵向排列，组标题可点开/收起，参考起点、番茄的交互。
 *
 * 折叠状态持久化在 `AppConfig.bookshelfCollapsedGroups`，重启后保持不变。
 */
class BookshelfFragment3() : BaseBookshelfFragment(R.layout.fragment_bookshelf3),
    SearchView.OnQueryTextListener,
    BooksAdapter3.CallBack {

    constructor(position: Int) : this() {
        val bundle = Bundle()
        bundle.putInt("position", position)
        arguments = bundle
    }

    private val binding by viewBinding(FragmentBookshelf3Binding::bind)
    private val bookshelfLayout by lazy { AppConfig.bookshelfLayout }
    private val booksAdapter by lazy { BooksAdapter3(requireContext(), this) }
    private var bookGroups: List<BookGroup> = emptyList()
    private val bookGroupsFlow = MutableStateFlow<List<BookGroup>>(emptyList())
    private val collapsedGroupsFlow = MutableStateFlow(AppConfig.bookshelfCollapsedGroups)
    private var booksFlowJob: Job? = null
    override var groupId = BookGroup.IdRoot
    override var books: List<Book> = emptyList()
    override var onlyUpdateRead = false
    private val bookshelfMargin by lazy { AppConfig.bookshelfMargin }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        setSupportToolbar(binding.titleBar.toolbar)
        binding.titleBar.title = getString(R.string.bookshelf)
        bindShelfHeader(binding.shelfHeader)
        initRecyclerView()
        initBookGroupData()
        initBooksData()
    }

    private fun initRecyclerView() {
        binding.rvBookshelf.setEdgeEffectColor(primaryColor)
        binding.fastScroller.attachRecyclerView(binding.rvBookshelf)
        upFastScrollerBar()
        binding.refreshLayout.setColorSchemeColors(accentColor)
        binding.refreshLayout.setOnRefreshListener {
            binding.refreshLayout.isRefreshing = false
            activityViewModel.upToc(books, onlyUpdateRead)
        }
        if (bookshelfLayout >= 2) {
            val spanCount = bookshelfLayout
            binding.rvBookshelf.layoutManager = GridLayoutManager(context, spanCount).apply {
                spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                    override fun getSpanSize(position: Int): Int {
                        // 分组标题永远独占整行
                        return if (booksAdapter.isSection(position)) spanCount else 1
                    }
                }
            }
        } else {
            binding.rvBookshelf.layoutManager = LinearLayoutManager(context)
        }
        binding.rvBookshelf.adapter = booksAdapter
        /**
         * 上书架条目更新频繁，沿用 style2 的做法关掉条目动画
         */
        binding.rvBookshelf.itemAnimator = null
        binding.rvBookshelf.addItemDecoration(object : RecyclerView.ItemDecoration() {
            override fun getItemOffsets(
                outRect: Rect,
                view: View,
                parent: RecyclerView,
                state: RecyclerView.State
            ) {
                val position = parent.getChildAdapterPosition(view)
                if (position == RecyclerView.NO_POSITION) {
                    outRect.set(0, 0, 0, 0)
                    return
                }
                val isSection = booksAdapter.isSection(position)
                val isLast = position == booksAdapter.itemCount - 1
                val top = when {
                    position == 0 -> bookshelfMargin + 24
                    isSection -> bookshelfMargin
                    else -> 0
                }
                val bottom = when {
                    isLast -> bookshelfMargin + 24
                    isSection -> 0
                    else -> bookshelfMargin
                }
                if (bookshelfLayout >= 2) {
                    outRect.set(bookshelfMargin, top, bookshelfMargin, bottom)
                } else {
                    outRect.set(0, top, 0, bottom)
                }
            }
        })
    }

    private fun upFastScrollerBar() {
        val show = AppConfig.showBookshelfFastScroller
        binding.fastScroller.isEnabled = show
        binding.rvBookshelf.scrollBarSize = if (show) {
            0
        } else {
            ViewConfiguration.get(requireContext()).scaledScrollBarSize
        }
    }

    override fun upGroup(data: List<BookGroup>) {
        if (data != bookGroups) {
            bookGroups = data
            bookGroupsFlow.value = data
        }
    }

    override fun upSort() {
        initBooksData()
    }

    private fun initBooksData() {
        booksFlowJob?.cancel()
        val ungroupedName = getString(R.string.bookshelf_ungrouped)
        booksFlowJob = viewLifecycleOwner.lifecycleScope.launch {
            val dataFlow = combine(
                appDb.bookDao.flowByGroup(BookGroup.IdAll),
                bookGroupsFlow,
                collapsedGroupsFlow,
            ) { shelfBooks, groups, collapsedGroups ->
                buildSectionItems(groups, shelfBooks, collapsedGroups, ungroupedName) to shelfBooks
            }.flowWithLifecycleAndDatabaseChangeFirst(
                viewLifecycleOwner.lifecycle,
                Lifecycle.State.RESUMED,
                AppDatabase.BOOK_TABLE_NAME,
                AppDatabase.BOOK_GROUP_TABLE_NAME,
            )
            dataFlow.catch {
                AppLog.put("书架更新出错", it)
            }.conflate().flowOn(Dispatchers.Default).collect { (items, shelfBooks) ->
                books = shelfBooks
                booksAdapter.submitList(items)
                upSpanCache()
                binding.tvEmptyMsg.isGone = items.isNotEmpty()
                binding.refreshLayout.isEnabled = items.isNotEmpty()
            }
        }
    }

    private fun upSpanCache() {
        val layoutManager = binding.rvBookshelf.layoutManager as? GridLayoutManager
            ?: return
        layoutManager.spanSizeLookup.apply {
            invalidateSpanIndexCache()
            invalidateSpanGroupIndexCache()
        }
    }

    private fun toggleSection(sectionId: Long) {
        val current = collapsedGroupsFlow.value
        val updated = if (sectionId in current) {
            current - sectionId
        } else {
            current + sectionId
        }
        collapsedGroupsFlow.value = updated
        AppConfig.bookshelfCollapsedGroups = updated
    }

    override fun onQueryTextSubmit(query: String?): Boolean {
        SearchActivity.start(requireContext(), query)
        return false
    }

    override fun onQueryTextChange(newText: String?): Boolean {
        return false
    }

    override fun gotoTop() {
        if (AppConfig.isEInkMode) {
            binding.rvBookshelf.scrollToPosition(0)
        } else {
            binding.rvBookshelf.smoothScrollToPosition(0)
        }
    }

    override fun onItemClick(book: Book) {
        startActivityForBook(book)
    }

    override fun onItemLongClick(book: Book) {
        startActivity<BookInfoActivity> {
            putExtra("name", book.name)
            putExtra("author", book.author)
        }
    }

    override fun onSectionClick(groupId: Long) {
        toggleSection(groupId)
    }

    override fun onSectionLongClick(groupId: Long) {
        val group = bookGroups.firstOrNull { it.groupId == groupId && it.groupId > 0 }
        if (group != null) {
            showDialogFragment(GroupEditDialog(group))
        } else {
            // 「未分组」不是真实分组，长按进入分组管理
            showDialogFragment<GroupManageDialog>()
        }
    }

    override fun isUpdate(bookUrl: String): Boolean {
        return activityViewModel.isUpdate(bookUrl)
    }

    override fun observeLiveBus() {
        super.observeLiveBus()
        observeEvent<String>(EventBus.UP_BOOKSHELF) {
            booksAdapter.notification(it)
        }
        observeEvent<String>(EventBus.BOOKSHELF_REFRESH) {
            initBooksData()
            booksAdapter.notifyAllChanged()
            upFastScrollerBar()
        }
    }

    override fun onDestroyView() {
        binding.fastScroller.detachRecyclerView()
        super.onDestroyView()
    }
}
