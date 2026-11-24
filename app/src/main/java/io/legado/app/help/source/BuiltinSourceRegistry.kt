package io.legado.app.help.source

import io.legado.app.help.DefaultData
import io.legado.app.help.config.LocalConfig
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray

/**
 * 内置书源识别与「用户删除的内置源」黑名单。
 *
 * 判定口径：当前安装包 assets/defaultData/bookSources.json 里的 URL 即内置源，
 * 不在其中的为用户自己导入。无需改数据库表或写入新字段。
 *
 * 黑名单只影响升级/内置包更新时的自动恢复，不影响界面上的来源标识。
 */
object BuiltinSourceRegistry {

    /** 书源管理搜索框的来源筛选前缀，与 group: 用法一致 */
    const val BUILTIN_QUERY_PREFIX = "builtin:"
    const val IMPORTED_QUERY_PREFIX = "imported:"

    /** 当前包内置源的 URL 集合 */
    val builtinUrls: Set<String> by lazy {
        DefaultData.bookSources.map { it.bookSourceUrl }.toSet()
    }

    /** 用户主动删除、升级时不应再自动恢复的内置源 URL */
    val removedUrls: Set<String>
        get() = runCatching {
            GSON.fromJsonArray<String>(LocalConfig.removedBuiltinSourceUrls)
                .getOrDefault(emptyList()).toSet()
        }.getOrElse { emptySet() }

    fun isBuiltin(url: String): Boolean = url in builtinUrls

    fun isRemoved(url: String): Boolean = url in removedUrls

    /** 删除书源时调用：只把内置源记入黑名单，用户导入的源不记 */
    fun markRemoved(urls: Collection<String>) {
        val builtinRemoved = urls.filter { it in builtinUrls }.toSet()
        if (builtinRemoved.isEmpty()) return
        LocalConfig.removedBuiltinSourceUrls = GSON.toJson((removedUrls + builtinRemoved).toList())
    }
}
