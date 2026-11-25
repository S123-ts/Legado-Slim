package io.legado.app.service

import java.io.File
import java.io.IOException
import java.io.InputStream

/** 音频流完整复制后才发布到缓存文件。 */
internal object HttpTtsFileCache {
    private val publishLock = Any()

    fun isReusable(file: File): Boolean = file.isFile && file.length() > 0L

    /** 调用方持有 [input]；下载失效时 [checkActive] 抛异常。 */
    fun write(target: File, input: InputStream, checkActive: () -> Unit): File {
        checkActive()
        if (isReusable(target)) return target
        val destination = target.absoluteFile
        val folder = destination.parentFile ?: throw IOException("Cache file has no parent")
        if (!folder.isDirectory && !folder.mkdirs() && !folder.isDirectory) {
            throw IOException("Cannot create audio cache directory")
        }
        val temporary = File.createTempFile("http-tts-", ".part", folder)
        try {
            temporary.outputStream().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    checkActive()
                    val count = input.read(buffer)
                    checkActive()
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
            }
            if (temporary.length() == 0L) {
                throw IOException("TTS server returned empty audio")
            }
            synchronized(publishLock) {
                checkActive()
                if (!isReusable(destination)) {
                    if (destination.exists() && !destination.delete()) {
                        throw IOException("Cannot replace invalid audio cache")
                    }
                    if (!temporary.renameTo(destination)) {
                        throw IOException("Cannot publish downloaded audio cache")
                    }
                }
            }
            return target
        } finally {
            temporary.delete()
        }
    }
}
