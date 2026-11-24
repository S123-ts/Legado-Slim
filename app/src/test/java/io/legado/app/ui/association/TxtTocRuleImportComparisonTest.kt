package io.legado.app.ui.association

import io.legado.app.data.entities.TxtTocRule
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TxtTocRuleImportComparisonTest {

    private fun rule(
        id: Long = 1L,
        name: String = "默认",
        rule: String = "第(\\d+)章",
        replacement: String = "第$1章",
        example: String? = "第一章",
        serialNumber: Int = 1,
        enable: Boolean = true
    ) = TxtTocRule(
        id = id,
        name = name,
        rule = rule,
        replacement = replacement,
        example = example,
        serialNumber = serialNumber,
        enable = enable
    )

    @Test
    fun `missing local rule counts as import content`() {
        assertFalse(rule().hasSameImportContent(null))
    }

    @Test
    fun `identical rule is recognized as unchanged`() {
        assertTrue(rule().hasSameImportContent(rule()))
    }

    @Test
    fun `same id with changed rule body is recognized as an update`() {
        val imported = rule(rule = "第(\\d+)节")
        val local = rule()

        assertFalse(imported.hasSameImportContent(local))
    }

    @Test
    fun `same id with changed replacement is recognized as an update`() {
        assertFalse(rule(replacement = "第$1节").hasSameImportContent(rule()))
    }

    @Test
    fun `same id with changed name is recognized as an update`() {
        assertFalse(rule(name = "改名了").hasSameImportContent(rule()))
    }

    @Test
    fun `same id with toggled enable is recognized as an update`() {
        assertFalse(rule(enable = false).hasSameImportContent(rule()))
    }

    @Test
    fun `same id with changed example or serial number is recognized as an update`() {
        assertFalse(rule(example = null).hasSameImportContent(rule()))
        assertFalse(rule(serialNumber = 2).hasSameImportContent(rule()))
    }

    @Test
    fun `different id is treated as new`() {
        assertFalse(rule(id = 2L).hasSameImportContent(rule(id = 1L)))
    }
}
