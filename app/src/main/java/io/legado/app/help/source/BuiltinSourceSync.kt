package io.legado.app.help.source

import io.legado.app.data.entities.BookSource
import io.legado.app.help.config.LocalConfig
import com.google.gson.JsonParser
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject

/**
 * 内置书源按编号同步。
 *
 * 包里每条书源带 builtinId（稳定编号），本地用 [LocalConfig.builtinSourceIds]
 * 记一份「编号 -> URL」基线。同步时：
 *   - 编号是新的              -> 新增
 *   - 编号存在但 URL 变了     -> 删旧 URL、插新源（站点换域名也能认出是同一个内置源）
 *   - 编号存在且 URL 未变     -> 用内置版完全覆盖
 *   - 基线有但新包没有的编号  -> 下架删除
 *   - 不在基线里的源          -> 用户自己导入的，完全不碰
 *
 * 首次同步（无基线）只建立基线并覆盖同名 URL，不删除任何源，避免误伤老设备。
 */
object BuiltinSourceSync {

    /** 带编号的书源条目 */
    data class Entry(val id: Int, val source: BookSource)

    /**
     * 解析带 builtinId 的书源包。没有编号的条目会被跳过；
     * 如果整包都没有编号，返回空列表，调用方应回退到旧的按 URL 导入逻辑。
     */
    fun parse(json: String): List<Entry> {
        val array = runCatching {
            JsonParser.parseString(json).asJsonArray
        }.getOrElse { return emptyList() }
        return array.mapNotNull { element ->
            val obj = if (element.isJsonObject) element.asJsonObject else return@mapNotNull null
            val id = runCatching {
                obj.get("builtinId")?.asInt ?: 0
            }.getOrElse { 0 }
            if (id <= 0) return@mapNotNull null
            val source = runCatching {
                GSON.fromJson(element, BookSource::class.java)
            }.getOrNull() ?: return@mapNotNull null
            if (source.bookSourceUrl.isBlank()) return@mapNotNull null
            Entry(id, source)
        }
    }

    /**
     * 按编号同步内置源，返回统计：新增 / 更新 / 下架。
     */
    fun sync(entries: List<Entry>): Result {
        if (entries.isEmpty()) return Result(0, 0, 0)

        val newMap = linkedMapOf<String, String>()
        entries.forEach { newMap[it.id.toString()] = it.source.bookSourceUrl }

        val baselineJson = LocalConfig.builtinSourceIds
        val oldMap = if (baselineJson.isBlank()) {
            emptyMap()
        } else {
            GSON.fromJsonObject<HashMap<String, String>>(baselineJson).getOrElse { emptyMap() }
        }
        val hasBaseline = oldMap.isNotEmpty()

        var added = 0
        var updated = 0
        var removed = 0

        val toInsert = mutableListOf<BookSource>()
        val toDelete = mutableSetOf<String>()

        entries.forEach { entry ->
            // 用户在书源管理里删过的内置源，编号同步也不再插回；
            // 基线仍写入新 URL，避免下次同步当成全新编号再次处理。
            if (BuiltinSourceRegistry.isRemoved(entry.source.bookSourceUrl)) return@forEach
            val key = entry.id.toString()
            val oldUrl = oldMap[key]
            when {
                oldUrl == null -> {
                    // 新编号：新增。若该 URL 恰好已存在（用户手动装过同 URL），REPLACE 覆盖即可
                    toInsert.add(entry.source)
                    added++
                }

                oldUrl != entry.source.bookSourceUrl -> {
                    // 换 URL：删旧、插新
                    toDelete.add(oldUrl)
                    toInsert.add(entry.source)
                    updated++
                }

                else -> {
                    // URL 未变：用内置版完全覆盖
                    toInsert.add(entry.source)
                    updated++
                }
            }
        }

        if (hasBaseline) {
            // 基线有、新包没有 -> 下架
            val newKeys = newMap.keys
            oldMap.forEach { (key, url) ->
                if (key !in newKeys) {
                    toDelete.add(url)
                    removed++
                }
            }
        }

        // 先删后插，避免换 URL 时主键冲突
        toDelete.forEach { SourceHelp.deleteBookSource(it) }
        if (toInsert.isNotEmpty()) {
            SourceHelp.insertBookSource(*toInsert.toTypedArray())
        }

        // 写回基线
        LocalConfig.builtinSourceIds = GSON.toJson(newMap)

        return Result(added, updated, removed)
    }

    data class Result(val added: Int, val updated: Int, val removed: Int)
}
