package io.legado.app.help.book

import io.legado.app.constant.AppLog
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.SearchBook
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * 一条搜索返回的书。只留定位用的字段，不存简介正文，避免日志文件过大。
 */
data class SearchBookBrief(
    val name: String,
    val author: String,
    val kind: String? = null,
) {
    override fun toString(): String {
        val suffix = kind?.takeIf { it.isNotBlank() }?.let { "［$it］" } ?: ""
        return if (author.isBlank()) "$name$suffix" else "$name - $author$suffix"
    }
}

/**
 * 单个书源在一次查询中的结果。
 */
data class SourceSearchResult(
    val sourceName: String,
    val sourceUrl: String,
    val count: Int,
    val duration: Long,
    val books: List<SearchBookBrief>,
    val error: String? = null,
)

/**
 * 一次查询（搜索一页 / 换源一轮）的完整记录。
 *
 * 保存方式：
 * - 摘要写一条进 [AppLog]，在日志对话框里点开可看详情；
 * - 明细由 [SearchLogStorage] 追加到磁盘 JSONL 文件（一次搜索一行），重启后仍在；
 * - 「关于 - 日志」菜单里的「搜索日志」直接读磁盘记录列表。
 */
data class SearchLogRecord(
    val id: Long,
    val time: Long,
    val type: String,
    val keyword: String,
    val page: Int,
    val totalDuration: Long,
    val results: List<SourceSearchResult>,
) {

    private val okCount: Int
        get() = results.count { it.error == null }

    private val failCount: Int
        get() = results.count { it.error != null }

    private val emptyCount: Int
        get() = results.count { it.error == null && it.count == 0 }

    private val bookCount: Int
        get() = results.sumOf { it.count }

    val summary: String
        get() = buildString {
            append("[搜索#$id] $type 关键词=$keyword")
            if (page > 1) append(" 第${page}页")
            append(" | 源${results.size} 成功${okCount}")
            if (emptyCount > 0) append(" 空${emptyCount}")
            if (failCount > 0) append(" 失败${failCount}")
            append(" 共${bookCount}条 ${totalDuration}ms")
        }

    /**
     * 列表里一行展示：关键词 + 源/结果统计。
     */
    val listLine: String
        get() = buildString {
            append(type).append(" · ").append(keyword)
            if (page > 1) append("（第${page}页）")
            append(" · 源").append(results.size).append(" 成功").append(okCount)
            if (emptyCount > 0) append(" 空").append(emptyCount)
            if (failCount > 0) append(" 失败").append(failCount)
            append(" · ").append(bookCount).append("本")
        }

    val detail: String
        get() = buildString {
            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
            appendLine("搜索日志 #$id")
            appendLine("时间: ${dateFormat.format(Date(time))}")
            appendLine("类型: $type")
            appendLine("关键词: $keyword" + if (page > 1) "（第${page}页）" else "")
            appendLine(
                "书源: ${results.size} 个，成功 ${okCount}，空结果 ${emptyCount}，" +
                        "失败 ${failCount}，共 ${bookCount} 条"
            )
            appendLine("总耗时: ${totalDuration}ms")
            if (results.isEmpty()) {
                appendLine()
                appendLine("（未记录到书源结果，可能查询被提前取消）")
                return@buildString
            }
            appendLine()
            results.forEach { result ->
                appendLine("── ${result.sourceName}")
                appendLine("   URL: ${result.sourceUrl}")
                if (result.error != null) {
                    appendLine("   失败(${result.duration}ms): ${result.error}")
                    return@forEach
                }
                appendLine("   返回 ${result.count} 条，耗时 ${result.duration}ms")
                result.books.forEach { book ->
                    appendLine("   · $book")
                }
                val omitted = result.count - result.books.size
                if (omitted > 0) {
                    appendLine("   · …（省略 $omitted 条）")
                }
            }
        }

    companion object {
        private val idPattern = Regex("^\\[搜索#(\\d+)]")

        fun parseId(message: String): Long? {
            return idPattern.find(message)?.groupValues?.getOrNull(1)?.toLongOrNull()
        }
    }
}

/**
 * 查询过程中的累积器，线程安全，供并发书源调用。
 */
class SearchLogSession(
    private val type: String,
    private val keyword: String,
    private val page: Int,
) {

    companion object {
        /** 单个书源最多落盘多少条返回结果，超出只记条数 */
        private const val MAX_BOOKS_PER_SOURCE = 200
        private const val MAX_ERROR_LENGTH = 200
    }

    private val startedAt = System.currentTimeMillis()
    private val results = Collections.synchronizedList(arrayListOf<SourceSearchResult>())
    private var finished = false

    fun record(
        source: BookSource,
        books: List<SearchBook>,
        duration: Long,
        error: Throwable?,
    ) {
        if (finished) return
        results.add(
            SourceSearchResult(
                sourceName = source.bookSourceName,
                sourceUrl = source.bookSourceUrl,
                count = books.size,
                duration = duration,
                books = books.take(MAX_BOOKS_PER_SOURCE).map { book ->
                    SearchBookBrief(
                        name = book.name,
                        author = book.author,
                        kind = book.kind,
                    )
                },
                error = error?.let {
                    "${it.javaClass.simpleName}: ${it.localizedMessage.orEmpty()}"
                        .take(MAX_ERROR_LENGTH)
                },
            )
        )
    }

    fun finish() {
        if (finished) return
        finished = true
        val snapshot = synchronized(results) { results.sortedBy { it.sourceName } }
        SearchLogStore.add(
            SearchLogRecord(
                id = SearchLogStore.nextId(),
                time = System.currentTimeMillis(),
                type = type,
                keyword = keyword,
                page = page,
                totalDuration = System.currentTimeMillis() - startedAt,
                results = snapshot,
            )
        )
    }
}

/**
 * 搜索日志的内存索引 + 落盘入口。
 *
 * 内存里留最近 [MAX_RECORDS] 条，供日志对话框点摘要看详情；
 * 同时交给 [SearchLogStorage] 写文件，文件是唯一长期保存的地方。
 */
object SearchLogStore {

    private const val MAX_RECORDS = 200

    private val idGenerator = AtomicLong()
    @Volatile
    private var idSeeded = false
    private val records = ArrayDeque<SearchLogRecord>(MAX_RECORDS)

    fun nextId(): Long {
        if (!idSeeded) {
            synchronized(this) {
                if (!idSeeded) {
                    // 磁盘里已有记录时接着编号，避免重启后 id 和旧文件里的重复
                    idGenerator.set(SearchLogStorage.latestRecord()?.id ?: 0L)
                    idSeeded = true
                }
            }
        }
        return idGenerator.incrementAndGet()
    }

    @Synchronized
    fun add(record: SearchLogRecord) {
        records.addFirst(record)
        while (records.size > MAX_RECORDS) {
            records.removeLast()
        }
        AppLog.put(record.summary)
        SearchLogStorage.append(record)
    }

    @Synchronized
    fun get(id: Long): SearchLogRecord? {
        records.firstOrNull { it.id == id }?.let { return it }
        return SearchLogStorage.loadRecent(MAX_RECORDS).firstOrNull { it.id == id }
    }

    @Synchronized
    fun latest(limit: Int): List<SearchLogRecord> {
        return records.take(limit.coerceIn(1, MAX_RECORDS))
    }

    /**
     * 清空内存和磁盘。删除类操作由调用方确认后再执行。
     */
    @Synchronized
    fun clear() {
        records.clear()
        SearchLogStorage.clear()
    }
}
