package io.legado.app.ui.book.read.page.entities

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 跨页朗读高亮的契约。
 *
 * 朗读完整自然段时，同一自然段可能被分到相邻两页。旧实现只给「朗读起点所在页」
 * 打标记，翻到下一页时又无条件清除，表现为：朗读跨页就丢高亮，或高亮停在上一页。
 * 该用例锁住上游 #1447 的三处关键逻辑，防止回退成「只高亮当前页」。
 *
 * 说明：本类不做实例化断言。`TextChapter` 的初始化会触到 Android 布局类，
 * 纯 JVM 环境无法构造（本项目未引入 Robolectric），故沿用项目既有的源码契约方式。
 */
class ReadAloudCrossPageHighlightTest {

    private val textPage by lazy {
        projectFile("src/main/java/io/legado/app/ui/book/read/page/entities/TextPage.kt").readText()
            .replace("\r\n", "\n")
    }
    private val textChapter by lazy {
        projectFile("src/main/java/io/legado/app/ui/book/read/page/entities/TextChapter.kt").readText()
            .replace("\r\n", "\n")
    }
    private val pageFactory by lazy {
        projectFile("src/main/java/io/legado/app/ui/book/read/page/provider/TextPageFactory.kt").readText()
            .replace("\r\n", "\n")
    }
    private val readBookActivity by lazy {
        projectFile("src/main/java/io/legado/app/ui/book/read/ReadBookActivity.kt").readText()
            .replace("\r\n", "\n")
    }

    @Test
    fun `page span accepts an end offset so a paragraph can continue across pages`() {
        val span = textPage.substringAfter("fun upPageAloudSpan(")
            .substringBefore("\n    /**")
        assertTrue("缺少 aloudSpanEnd 参数", span.contains("aloudSpanEnd: Int = Int.MAX_VALUE"))
        assertTrue("未按结束位置关闭高亮", span.contains("if (lineStart >= aloudSpanEnd) inParagraph = false"))
        assertTrue(
            "起始位置越出本页时应整页续段高亮",
            span.contains("var inParagraph = aloudSpanStart < 0 && aloudSpanEnd > 0")
        )
        assertTrue(
            "起点不得越到结束位置之后",
            span.contains("aloudSpanStart < aloudSpanEnd")
        )
    }

    @Test
    fun `chapter marks the whole paragraph and clears stale pages`() {
        val span = textChapter.substringAfter("fun upParagraphAloudSpan(")
            .substringBefore("val lastPage")
        assertTrue("未按自然段定位", span.contains("getParagraphs(false).firstOrNull"))
        assertTrue("未用 chapterIndices 判定归属", span.contains("chapterStart in it.chapterIndices"))
        assertTrue("未计算段落结束位置", span.contains("it.chapterPosition + it.charSize"))
        assertTrue("未清除无关页的旧标记", span.contains("page.removePageAloudSpan()"))
        assertTrue("未按页内相对位置换算", span.contains("paragraphStart - page.chapterPosition"))
        assertTrue("未传入段落结束位置", span.contains("paragraphEnd - page.chapterPosition"))
    }

    @Test
    fun `paging keeps the highlight only while the same chapter is being read`() {
        val prepare = pageFactory.substringAfter("private fun TextPage.prepareAloudSpan()")
            .substringBefore("override val nextPage")
        assertTrue("未判断朗读是否进行中", prepare.contains("!BaseReadAloudService.isPlay()"))
        assertTrue("未排除按页朗读模式", prepare.contains("PreferKey.readAloudByPage"))
        assertTrue("未判断章节一致", prepare.contains("textChapter.position != ReadAloud.readAloudChapterIndex"))
        assertTrue("条件不满足时应清除标记", prepare.contains("removePageAloudSpan()"))
        assertFalse(
            "翻页时不得无条件清除，否则跨页高亮必然丢失",
            pageFactory.contains("?.removePageAloudSpan()")
        )
    }

    @Test
    fun `reader refreshes highlight after content updates and clears it on stop`() {
        assertTrue(readBookActivity.contains("private fun updateCrossPageAloudHighlight()"))
        val updates = Regex(
            "binding\\.readView\\.upContent\\(relativePosition, shouldResetPageOffset\\)\\s*\\n\\s*updateCrossPageAloudHighlight\\(\\)"
        ).findAll(readBookActivity).count()
        assertTrue("内容更新后应刷新跨页高亮，实际命中 $updates 处", updates >= 2)

        val stopBranch = readBookActivity.substringAfter("if (it == Status.STOP || it == Status.PAUSE)")
            .substringBefore("updateReadAloudFloatBar()")
        assertTrue("停止朗读应清除整章高亮，而非仅当前页", stopBranch.contains("textChapter.pages.forEach"))
    }

    private fun projectFile(pathInApp: String): File {
        return listOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull { it.isFile }
            ?: error("Missing project file: $pathInApp")
    }
}
