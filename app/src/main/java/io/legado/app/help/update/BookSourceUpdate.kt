package io.legado.app.help.update

import androidx.annotation.Keep
import io.legado.app.data.entities.BookSource
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.http.newCallResponse
import io.legado.app.help.http.okHttpClient
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import kotlinx.coroutines.CoroutineScope
import okhttp3.OkHttpClient
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * 书源在线更新，与 [AppUpdate] 共用同一套通道地址配置：
 *
 * - 内网 [AppUpdate.CHANNEL_LAN]：同一局域网的电脑跑推送服务
 * - 外网 [AppUpdate.CHANNEL_GITHUB]：自己仓库的 release 资产
 *
 * 清单固定名 [MANIFEST_NAME]，包固定名 [PACKAGE_NAME]。
 * 注意这里不打包进 apk：包可用 publish-booksource.ps1 -SyncAssets 在下次出包时写回 assets。
 */
object BookSourceUpdate {

    const val MANIFEST_NAME = "booksource.json"
    const val PACKAGE_NAME = "bookSources.json"

    /** 校验书源包用的客户端，比全局客户端超时更短，失败时不要让用户干等 */
    private val updateClient: OkHttpClient by lazy {
        okHttpClient.newBuilder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    /**
     * booksource.json 的内容
     */
    @Keep
    data class SourceManifest(
        val version: Int = 0,
        val count: Int = 0,
        val sha256: String = "",
        val fileName: String = "",
        val size: Long = 0L,
        val updateLog: String = "",
        val createdAt: Long = 0L
    )

    /**
     * 清单地址：两条通道都是「基址 / booksource.json」
     */
    fun manifestUrl(baseUrl: String): String {
        return "${baseUrl.trimEnd('/')}/$MANIFEST_NAME"
    }

    /**
     * 包地址：清单里 fileName 优先，没填就用固定名
     */
    fun packageUrl(baseUrl: String, manifest: SourceManifest): String {
        val name = manifest.fileName.takeIf { it.isNotBlank() } ?: PACKAGE_NAME
        return "${baseUrl.trimEnd('/')}/$name"
    }

    /**
     * 拉取清单，只负责取回；是否是新版本由调用方判断
     */
    fun check(scope: CoroutineScope, baseUrl: String): Coroutine<SourceManifest> {
        return Coroutine.async(scope) {
            val response = updateClient.newCallResponse {
                url(manifestUrl(baseUrl))
                header("Cache-Control", "no-cache")
            }
            response.use { res ->
                if (!res.isSuccessful) {
                    throw IllegalStateException("书源清单访问失败(${res.code})")
                }
                val body = runCatching { res.body.string() }.getOrNull().orEmpty()
                if (body.isBlank()) {
                    throw IllegalStateException("书源清单返回内容为空")
                }
                val manifest = runCatching {
                    GSON.fromJson(body, SourceManifest::class.java)
                }.getOrNull() ?: throw IllegalStateException("booksource.json 无法解析")
                if (manifest.version <= 0) {
                    throw IllegalStateException("booksource.json 缺少 version")
                }
                if (manifest.count <= 0) {
                    throw IllegalStateException("booksource.json 缺少 count")
                }
                manifest
            }
        }
    }

    /**
     * 清单版本是否比本地已应用的新
     */
    fun isNewer(manifest: SourceManifest): Boolean {
        return manifest.version > LocalConfig.onlineBookSourceVersion
    }

    /**
     * 下载书源包并校验 sha256，返回解析后的书源列表。
     * 失败一律抛异常，调用方负责静默处理或提示。
     */
    suspend fun download(baseUrl: String, manifest: SourceManifest): List<BookSource> {
        val json = downloadJson(baseUrl, manifest)
        val sources = GSON.fromJsonArray<BookSource>(json).getOrThrow()
        if (sources.isEmpty()) {
            throw IllegalStateException("书源包为空")
        }
        if (sources.any { it.bookSourceUrl.isBlank() }) {
            throw IllegalStateException("书源包含无效书源")
        }
        return sources
    }

    /**
     * 下载书源包并校验 sha256，返回原始 JSON。
     * 调用方可用 [io.legado.app.help.source.BuiltinSourceSync.parse] 提取内置编号，
     * 老包无编号时回退到普通导入流程。
     */
    suspend fun downloadJson(baseUrl: String, manifest: SourceManifest): String {
        val response = updateClient.newCallResponse {
            url(packageUrl(baseUrl, manifest))
            header("Cache-Control", "no-cache")
        }
        val bytes = response.use { res ->
            if (!res.isSuccessful) {
                throw IllegalStateException("书源包下载失败(${res.code})")
            }
            res.body.bytes()
        }
        val expected = manifest.sha256.trim()
        if (expected.isNotBlank()) {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            val actual = digest.joinToString("") { "%02x".format(it) }
            if (!actual.equals(expected, ignoreCase = true)) {
                throw IllegalStateException("书源包校验失败，可能文件损坏或被篡改")
            }
        }
        return String(bytes, Charsets.UTF_8)
    }
}
