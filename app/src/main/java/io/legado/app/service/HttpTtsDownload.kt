package io.legado.app.service

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.io.IOException
import java.io.InputStream
import java.io.PushbackInputStream
import java.util.concurrent.atomic.AtomicBoolean

/** 即使下载实现忽略中断，也取消底层下载。 */
internal suspend fun downloadCancellable(download: () -> Unit, cancel: () -> Unit) {
    coroutineScope {
        val cancellationWatcher = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                cancel()
            }
        }
        try {
            runInterruptible(Dispatchers.IO) { download() }
        } finally {
            cancellationWatcher.cancelAndJoin()
        }
    }
}

/** 不消费首字节地拒绝空响应，同时保持可取消。 */
internal suspend fun checkedAudioStream(input: InputStream): InputStream {
    val checked = PushbackInputStream(input, 1)
    val probeCompleted = AtomicBoolean()
    try {
        downloadCancellable(download = {
            val firstByte = checked.read()
            if (firstByte < 0) throw IOException("TTS returned empty audio")
            checked.unread(firstByte)
            probeCompleted.set(true)
        }, cancel = {
            if (!probeCompleted.get()) checked.close()
        })
        currentCoroutineContext().ensureActive()
        return checked
    } catch (error: Throwable) {
        runCatching { checked.close() }.onFailure { error.addSuppressed(it) }
        throw error
    }
}
