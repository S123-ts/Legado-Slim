package io.legado.app.data.dao

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「只对本书生效」必须按 bookUrl 精确匹配，不能靠 scope 书名子串。
 */
class ReplaceRuleBookScopeContractTest {

    private val daoSource by lazy {
        normalizedSource("src/main/java/io/legado/app/data/dao/ReplaceRuleDao.kt")
    }

    private val entitySource by lazy {
        normalizedSource("src/main/java/io/legado/app/data/entities/ReplaceRule.kt")
    }

    private val processorSource by lazy {
        normalizedSource("src/main/java/io/legado/app/help/book/ContentProcessor.kt")
    }

    private val editSource by lazy {
        normalizedSource("src/main/java/io/legado/app/ui/replace/edit/ReplaceEditActivity.kt")
    }

    private val databaseSource by lazy {
        normalizedSource("src/main/java/io/legado/app/data/AppDatabase.kt")
    }

    @Test
    fun `rule entity carries a book identity column`() {
        assertTrue(entitySource.contains("var bookUrl: String? = null"))
    }

    @Test
    fun `content and title scope queries match book url exactly`() {
        listOf(
            "fun findEnabledByContentScope(name: String, origin: String, bookUrl: String?)",
            "fun findEnabledByTitleScope(name: String, origin: String, bookUrl: String?)"
        ).forEach { signature ->
            assertTrue("缺少签名: $signature", daoSource.contains(signature))
            val query = queryBefore(signature)
            assertTrue(query.contains("(bookUrl IS NOT NULL and bookUrl = :bookUrl)"))
            assertTrue(query.contains("OR (bookUrl IS NULL"))
            assertTrue(query.contains("scope LIKE '%' || :name || '%'"))
        }
    }

    @Test
    fun `substring matching is only kept for rules without identity`() {
        val query = queryBefore(
            "fun findEnabledByContentScope(name: String, origin: String, bookUrl: String?)"
        )
        val identityBranch = query.indexOf("(bookUrl IS NOT NULL and bookUrl = :bookUrl)")
        val legacyBranch = query.indexOf("(bookUrl IS NULL")
        val substringMatch = query.indexOf("scope LIKE '%' || :name || '%'")
        assertTrue(identityBranch >= 0)
        assertTrue(legacyBranch > identityBranch)
        assertTrue(
            "书名子串匹配只能出现在 bookUrl 为空的分支里",
            substringMatch > legacyBranch
        )
    }

    @Test
    fun `content processor resolves the current book url`() {
        assertTrue(processorSource.contains("appDb.bookDao.getBookByOrigin(bookName, bookOrigin)?.bookUrl"))
        assertTrue(processorSource.contains("findEnabledByTitleScope(bookName, bookOrigin, bookUrl)"))
        assertTrue(processorSource.contains("findEnabledByContentScope(bookName, bookOrigin, bookUrl)"))
    }

    @Test
    fun `edit page saves the identity for the current book only`() {
        assertTrue(editSource.contains("bookUrl = currentBookUrl()"))
        assertTrue(editSource.contains("private fun currentBookUrl(): String?"))
        assertTrue(
            editSource.contains(
                "if (editingBookUrl != null && (typed.isEmpty() || typed == bookName))"
            )
        )
        assertTrue(
            "作用范围没改动时应保留原规则身份",
            editSource.contains(
                "if (sourceBookUrl != null && typed == source.scope.orEmpty().trim())"
            )
        )
    }

    @Test
    fun `database bumps once for the new column`() {
        assertTrue(databaseSource.contains("version = 113,"))
        val migrations = normalizedSource("src/main/java/io/legado/app/data/DatabaseMigrations.kt")
        assertTrue(migrations.contains("Migration(112, 113)"))
        assertTrue(migrations.contains("ADD COLUMN `bookUrl` TEXT"))
    }

    private fun queryBefore(functionMarker: String): String {
        val end = daoSource.indexOf(functionMarker)
        val start = daoSource.lastIndexOf("@Query(", end)
        require(start >= 0 && end > start)
        return daoSource.substring(start, end)
    }

    private fun normalizedSource(pathInApp: String): String {
        return projectFile(pathInApp)
            .readText()
            .replace("\r\n", "\n")
            .replace(Regex("\\s+"), " ")
    }

    private fun projectFile(pathInApp: String): File {
        return listOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull { it.isFile }
            ?: error("Missing project file: $pathInApp")
    }
}
