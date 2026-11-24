package io.legado.app.help.book

import io.legado.app.utils.GSON
import io.legado.app.utils.externalFiles
import splitties.init.appCtx
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * 搜索日志落盘。
 *
 * 位置：`Android/data/<包名>/files/search-logs/`（取不到外部目录时退回应用私有目录）
 * 文件：`search-yyyyMMdd.jsonl`，**一次搜索一行 JSON**，按天分文件
 * 内容：关键词、类型（搜索/换源）、页码、总耗时，以及每个书源返回的条数、耗时、错误和书名列表
 * 清理：保留最近 [KEEP_DAYS] 天，写入时顺带删过期文件
 *
 * 写入与清空都走同一个单线程队列，不占搜索线程，也不阻塞主线程；
 * 读盘前会先等排队中的清空落地，避免读回已清记录。
 */
object SearchLogStorage {

    private const val DIR_NAME = "search-logs"
    private const val KEEP_DAYS = 14L
    private const val FILE_PREFIX = "search-"
    private const val FILE_SUFFIX = ".jsonl"

    private val dayFormat = SimpleDateFormat("yyyyMMdd", Locale.US)
    private val writer = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "search-log-writer").apply { isDaemon = true }
    }

    private var lastCleanDay = ""

    /** 最近一次「清空」任务：读盘前先等它完成，避免把已清记录读回来 */
    @Volatile
    private var pendingClear: Future<*>? = null

    val directory: File
        get() = File(appCtx.externalFiles, DIR_NAME)

    /**
     * 追加一条记录。IO 失败只吞掉，不影响搜索本身。
     */
    fun append(record: SearchLogRecord) {
        writer.execute {
            runCatching {
                // SimpleDateFormat 非线程安全，日期只在写线程里格式化
                val day = dayFormat.format(Date(record.time))
                val dir = directory
                if (!dir.isDirectory && !dir.mkdirs()) return@runCatching
                File(dir, "$FILE_PREFIX$day$FILE_SUFFIX")
                    .appendText(GSON.toJson(record) + "\n", Charsets.UTF_8)
                cleanOldFiles(dir, day)
            }
        }
    }

    /**
     * 读取最近的记录，按时间倒序。会从最新的文件往旧文件读。
     */
    fun loadRecent(limit: Int): List<SearchLogRecord> {
        if (limit <= 0) return emptyList()
        awaitPendingClear()
        val dir = directory
        if (!dir.isDirectory) return emptyList()
        val files = dir.listFiles()
            ?.filter { it.isFile && it.name.startsWith(FILE_PREFIX) && it.name.endsWith(FILE_SUFFIX) }
            ?.sortedByDescending { it.name }
            ?: return emptyList()
        val result = ArrayList<SearchLogRecord>(limit)
        for (file in files) {
            if (result.size >= limit) break
            val lines = runCatching { file.readLines() }.getOrDefault(emptyList())
            for (index in lines.indices.reversed()) {
                if (result.size >= limit) break
                val line = lines[index]
                if (line.isBlank()) continue
                val record = runCatching {
                    GSON.fromJson(line, SearchLogRecord::class.java)
                }.getOrNull() ?: continue
                result.add(record)
            }
        }
        return result.sortedWith(compareByDescending { it.time })
    }

    /**
     * 磁盘上最新的一条，用来给 id 计数器接续编号。
     */
    fun latestRecord(): SearchLogRecord? = loadRecent(1).firstOrNull()

    /**
     * 当前日志目录里总共有多少条记录（只数行，不解析）。
     */
    fun countRecords(): Int {
        awaitPendingClear()
        val dir = directory
        if (!dir.isDirectory) return 0
        return dir.listFiles()
            ?.filter { it.isFile && it.name.startsWith(FILE_PREFIX) && it.name.endsWith(FILE_SUFFIX) }
            ?.sumOf { file -> runCatching { file.useLines { lines -> lines.count { it.isNotBlank() } } }.getOrDefault(0) }
            ?: 0
    }

    /**
     * 清空磁盘。和 [append] 提交到同一个单线程队列，保证排在已提交的写入之后执行；
     * 否则队列里还没跑的写入会在删除后把当天文件重新建出来。
     */
    fun clear() {
        val dir = directory
        pendingClear = writer.submit {
            runCatching {
                if (dir.isDirectory) {
                    dir.listFiles()?.forEach { file -> runCatching { file.delete() } }
                }
            }
            // 清空后重新做过期文件清理
            lastCleanDay = ""
        }
    }

    private fun awaitPendingClear() {
        pendingClear?.let { runCatching { it.get(5, TimeUnit.SECONDS) } }
    }

    private fun cleanOldFiles(dir: File, today: String) {
        if (lastCleanDay == today) return
        lastCleanDay = today
        val expireBefore = dayFormat.format(Date(System.currentTimeMillis() - KEEP_DAYS * 24 * 60 * 60 * 1000))
        dir.listFiles()?.forEach { file ->
            val name = file.name
            if (!name.startsWith(FILE_PREFIX) || !name.endsWith(FILE_SUFFIX)) return@forEach
            val day = name.removePrefix(FILE_PREFIX).removeSuffix(FILE_SUFFIX)
            if (day.length == 8 && day < expireBefore) {
                runCatching { file.delete() }
            }
        }
    }
}
