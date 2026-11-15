package io.legado.app.ui.main.bookshelf.style3

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.cnCompare
import kotlin.math.max

/**
 * 折叠分组的一行组标题。
 * [groupId] 为真实自定义分组 id；「未分组」使用 [UngroupedSectionId]。
 */
data class BookshelfSectionItem(
    val groupId: Long,
    val groupName: String,
    val bookCount: Int,
    val collapsed: Boolean,
)

/**
 * 「未分组」不是真实 BookGroup，用一个不会与实体 id 冲突的负数占位。
 * BookGroup 的系统分组只用到 -1 ~ -11，自定义分组为正数位掩码。
 */
internal const val UngroupedSectionId = -1000L

/**
 * 把书架里的全部书按自定义分组切成分组标题 + 组内书，
 * 未分组的书归入最后一组；折叠的分组只保留标题行。
 *
 * 一本书可以同时属于多个自定义分组（`group` 是位掩码）。
 * 折叠视图是同一个列表，重复出现会让用户以为书被复制了，
 * 所以只把书放进 `order` 最靠前的那个分组；一个可见分组都没有的书进「未分组」
 * （被隐藏的分组不产生标题行，它的书同样落到「未分组」，不会从书架消失）。
 */
internal fun buildSectionItems(
    groups: List<BookGroup>,
    shelfBooks: List<Book>,
    collapsedGroupIds: Set<Long>,
    ungroupedName: String,
): List<Any> {
    val userGroups = groups.filter { it.groupId > 0 }
    // 没有任何自定义分组时退回平铺书架，避免凭空多出一个「未分组」标题
    if (userGroups.isEmpty()) {
        return sortSectionBooks(shelfBooks, AppConfig.bookshelfSort)
    }
    val userGroupBits = userGroups.fold(0L) { acc, group -> acc or group.groupId }
    val items = ArrayList<Any>(shelfBooks.size + userGroups.size + 1)
    val assigned = HashSet<String>(shelfBooks.size)
    userGroups.forEach { group ->
        val groupBooks = shelfBooks.filter { book ->
            (book.group and group.groupId) > 0 && assigned.add(book.bookUrl)
        }
        if (groupBooks.isEmpty()) return@forEach
        val collapsed = group.groupId in collapsedGroupIds
        items.add(BookshelfSectionItem(group.groupId, group.groupName, groupBooks.size, collapsed))
        if (!collapsed) {
            items.addAll(sortSectionBooks(groupBooks, group.getRealBookSort()))
        }
    }
    val ungroupedBooks = shelfBooks.filter { (it.group and userGroupBits) == 0L }
    if (ungroupedBooks.isNotEmpty()) {
        val collapsed = UngroupedSectionId in collapsedGroupIds
        items.add(
            BookshelfSectionItem(UngroupedSectionId, ungroupedName, ungroupedBooks.size, collapsed)
        )
        if (!collapsed) {
            items.addAll(sortSectionBooks(ungroupedBooks, AppConfig.bookshelfSort))
        }
    }
    return items
}

internal fun sortSectionBooks(books: List<Book>, sort: Int): List<Book> {
    return when (sort) {
        1 -> books.sortedByDescending { it.latestChapterTime }
        2 -> books.sortedWith { first, second -> first.name.cnCompare(second.name) }
        3 -> books.sortedBy { it.order }
        4 -> books.sortedByDescending { max(it.latestChapterTime, it.durChapterTime) }
        else -> books.sortedByDescending { it.durChapterTime }
    }
}
