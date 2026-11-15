package io.legado.app.help.webView

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `open()` 的分支契约。
 *
 * 2026-11-01 把 `RssJsExtensions` 上移并改名为 `SourceJsExtensions` 时，`"sort"` / `"rss"`
 * 两个分支被一并删掉，RSS 规则里的 `java.open("rss", …)` 变成静默返回。该用例锁住
 * 五个取值、各自跳转的目标，以及它们与内置帮助 `jsHelp.md` 的同步关系。
 *
 * 2026-11-05 起界面跳转改由 `SourceNavigation` 承接，故"跳到哪个界面"的断言落在
 * `DefaultSourceNavigation` 上，同时守住 `help/webView` 不反向依赖 `ui.*`。
 */
class SourceJsExtensionsOpenContractTest {

    private val source by lazy {
        readSource("src/main/java/io/legado/app/help/webView/SourceJsExtensions.kt")
    }

    private val navigation by lazy {
        readSource("src/main/java/io/legado/app/ui/navigation/DefaultSourceNavigation.kt")
    }

    private val help by lazy {
        readSource("src/main/assets/web/help/md/jsHelp.md")
    }

    private val openMethod by lazy {
        val start = source.indexOf("fun open(")
        val end = source.indexOf("/** AnalyzeRule实现 **/", start)
        require(start >= 0 && end > start) { "无法定位 open() 方法体" }
        source.substring(start, end)
    }

    @Test
    fun `open keeps every branch advertised by the built-in help`() {
        // 内置帮助用中文引号包裹取值，逐字比对 ASCII 引号会误判，故只在该行校验取值存在
        val advertised = help.lineSequence().firstOrNull { it.contains("@param name") }
            ?: error("内置帮助缺少 @param name 说明")
        val names = listOf("login", "sort", "rss", "search", "explore")
        names.forEach { name ->
            assertTrue("open() 缺少 \"$name\" 分支", openMethod.contains("\"$name\" ->"))
            assertTrue("内置帮助未描述 $name", advertised.contains(name))
        }
    }

    @Test
    fun `open branches keep their original order`() {
        val order = listOf("login", "sort", "rss", "search", "explore")
        val offsets = order.map { openMethod.indexOf("\"$it\" ->") }
        assertTrue("分支顺序应保持稳定", offsets.zipWithNext().all { (a, b) -> a >= 0 && b > a })
    }

    @Test
    fun `sort and rss branches still reach the rss screens`() {
        assertTrue(openMethod.contains("appDb.rssSourceDao.getByKey(o)"))
        assertTrue(openMethod.contains("openRssSort("))
        assertTrue(openMethod.contains("openRssRead("))
        assertTrue(openMethod.contains("appDb.rssReadRecordDao.insertRecord(rssReadRecord)"))
        assertTrue("rss 分支需支持 startHtml 的 @js: 预执行", openMethod.contains("toSource.evalJS("))

        assertTrue(navigation.contains("RssSortActivity.start(activity, sortUrl, sourceUrl)"))
        assertTrue(navigation.contains("ReadRssActivity.start(activity, singleTop, sourceUrl, title, url, startHtml)"))
        assertTrue(navigation.contains("SourceLoginActivity"))
    }

    @Test
    fun `js host does not depend on the ui package`() {
        // 分层约定：help/webView 是无 UI 的横切帮助层，界面跳转必须走 SourceNavigation
        assertFalse(
            "SourceJsExtensions 不应直接 import ui.*",
            source.contains("import io.legado.app.ui.")
        )
        assertTrue("SourceJsExtensions 应通过导航接口跳转", source.contains("SourceNavigationHolder.current"))
        assertTrue(navigation.contains("object DefaultSourceNavigation : SourceNavigation"))
    }

    private fun readSource(pathInApp: String): String {
        return projectFile(pathInApp).readText().replace("\r\n", "\n")
    }

    private fun projectFile(pathInApp: String): File {
        return listOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull { it.isFile }
            ?: error("Missing project file: $pathInApp")
    }
}
