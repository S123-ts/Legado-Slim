package io.legado.app.service

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BackgroundCoverSourceContractTest {

    @Test
    fun `background cover consumers keep the matching source identity`() {
        val loader = source("app/src/main/java/io/legado/app/help/glide/ImageLoader.kt")
        assertTrue(loader.contains("sourceOrigin: String? = null"))
        assertTrue(loader.contains("OkHttpModelLoader.sourceOriginOption"))

        val audio = source("app/src/main/java/io/legado/app/service/AudioPlayService.kt")
        val audioCover = audio.substringAfter("val book = AudioPlay.book")
            .substringBefore(".submit()")
        assertTrue(audioCover.contains("book?.getDisplayCover()"))
        assertTrue(audioCover.contains("book?.getCoverSourceOrigin()"))

        val readAloud = source("app/src/main/java/io/legado/app/service/BaseReadAloudService.kt")
        val readAloudCover = readAloud.substringAfter("val book = ReadBook.book")
            .substringBefore(".submit()")
        assertTrue(readAloudCover.contains("book?.getDisplayCover()"))
        assertTrue(readAloudCover.contains("book?.getCoverSourceOrigin()"))

        val export = source("app/src/main/java/io/legado/app/service/ExportBookService.kt")
        val exportCover = export.substringAfter("private fun setCover")
            .substringBefore("private suspend fun setEpubContent")
        assertTrue(exportCover.contains("book.getCoverSourceOrigin()"))
        assertTrue(exportCover.contains("OkHttpModelLoader.sourceOriginOption"))
    }

    private fun source(relativePath: String): String {
        return File(repositoryRoot(), relativePath).readText()
    }

    private fun repositoryRoot(): File {
        val userDir = requireNotNull(System.getProperty("user.dir"))
        return generateSequence(File(userDir)) { it.parentFile }
            .first { File(it, "app/src/main").isDirectory }
    }
}
