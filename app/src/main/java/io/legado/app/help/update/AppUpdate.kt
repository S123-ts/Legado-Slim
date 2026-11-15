package io.legado.app.help.update

import androidx.annotation.Keep
import io.legado.app.constant.AppConst
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.http.newCallResponse
import io.legado.app.help.http.okHttpClient
import io.legado.app.utils.GSON
import kotlinx.coroutines.CoroutineScope
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * 应用更新，两条通道共用一个清单格式：
 *
 * - 内网 [CHANNEL_LAN]：同一局域网里电脑跑推送脚本，地址形如 http://192.168.1.10:8765
 * - 外网 [CHANNEL_GITHUB]：GitHub 仓库的 release 资产，
 *   基址是 https://github.com/<owner>/<repo>/releases/latest/download
 *
 * 两边都只需要两个文件：update.json 清单 + apk 包，命名固定，见 [MANIFEST_NAME]。
 */
object AppUpdate {

    const val MANIFEST_NAME = "update.json"

    const val CHANNEL_LAN = "lan"
    const val CHANNEL_GITHUB = "github"

    /** 默认外网通道仓库，可在 app 内改 */
    const val DEFAULT_GITHUB_REPO = "S123-ts/Legado-Slim"

    /** 内网更新的默认地址，只作初始值；用户改过之后以用户保存的为准 */
    const val DEFAULT_LAN_URL = "http://192.168.1.6:8765"

    private const val GITHUB_HOST = "github.com"

    private val repoPattern = Regex("""^[A-Za-z0-9._-]+/[A-Za-z0-9._-]+$""")

    /**
     * update.json 的内容
     */
    @Keep
    data class ReleaseManifest(
        val versionName: String = "",
        val versionCode: Long = 0L,
        val apkName: String = "",
        val apkUrl: String = "",
        val size: Long = 0L,
        val sha256: String = "",
        val updateLog: String = "",
        val createdAt: Long = 0L
    )

    /**
     * 内网地址规范化：补协议、去尾部斜杠与 update.json，容忍带路径前缀
     *
     * 返回 null 表示不是合法 http(s) 地址
     */
    fun normalizeServerUrl(raw: String?): String? {
        var text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        if (!text.startsWith("http://", true) && !text.startsWith("https://", true)) {
            text = "http://$text"
        }
        text = text.trimEnd('/')
        if (text.lowercase().endsWith("/$MANIFEST_NAME")) {
            text = text.dropLast(MANIFEST_NAME.length + 1)
        }
        text = text.trimEnd('/')
        val url = text.toHttpUrlOrNull() ?: return null
        return url.toString().trimEnd('/')
    }

    /**
     * GitHub 仓库规范化：owner/repo
     *
     * 容忍 S123-ts/Legado-Slim、https://github.com/S123-ts/Legado-Slim、
     * 带 .git 后缀、带 /releases... 后缀等写法。返回 null 表示认不出。
     */
    fun normalizeGitHubRepo(raw: String?): String? {
        var text = raw?.trim().orEmpty().replace('\\', '/')
        if (text.isEmpty()) return null
        val looksLikeUrl = text.contains("://") ||
                text.startsWith(GITHUB_HOST, true) ||
                text.startsWith("www.$GITHUB_HOST", true)
        if (looksLikeUrl) {
            val afterScheme = if (text.contains("://")) {
                text.substringAfter("://")
            } else {
                text
            }
            val parts = afterScheme.split('/').filter { it.isNotBlank() }
            if (parts.size < 3) return null
            text = "${parts[1]}/${parts[2]}"
        } else {
            text = text.trim('/')
        }
        text = text.removeSuffix(".git")
        return text.takeIf { repoPattern.matches(it) }
    }

    /**
     * GitHub 通道的清单基址，releases/latest/download 永远指向最新一次正式发布
     */
    fun githubBaseUrl(repo: String): String {
        return "https://$GITHUB_HOST/$repo/releases/latest/download"
    }

    fun manifestUrl(baseUrl: String): String {
        return "${baseUrl.trimEnd('/')}/$MANIFEST_NAME"
    }

    /**
     * 清单里 apkUrl 优先，没填就用 apkName；都支持相对路径
     */
    fun apkUrl(baseUrl: String, manifest: ReleaseManifest): String {
        val base = baseUrl.trimEnd('/')
        val raw = manifest.apkUrl.takeIf { it.isNotBlank() } ?: manifest.apkName.trim()
        return when {
            raw.startsWith("http://", true) || raw.startsWith("https://", true) -> raw
            raw.startsWith("/") -> "$base$raw"
            else -> "$base/$raw"
        }
    }

    /**
     * 拉取清单，只负责取回，是否是新版本由调用方判断
     */
    fun check(scope: CoroutineScope, rawBaseUrl: String?): Coroutine<ReleaseManifest> {
        val base = normalizeServerUrl(rawBaseUrl)
            ?: throw NoStackTraceException("更新地址无效")
        return Coroutine.async(scope) {
            val response = try {
                okHttpClient.newCallResponse {
                    url(manifestUrl(base))
                    header("Cache-Control", "no-cache")
                }
            } catch (e: Throwable) {
                throw describe(e)
            }
            response.use { res ->
                if (!res.isSuccessful) {
                    throw NoStackTraceException(describeHttpCode(res.code))
                }
                val body = runCatching { res.body.string() }.getOrNull().orEmpty()
                if (body.isBlank()) {
                    throw NoStackTraceException("服务器返回内容为空")
                }
                val manifest = runCatching {
                    GSON.fromJson(body, ReleaseManifest::class.java)
                }.getOrNull() ?: throw NoStackTraceException("update.json 无法解析")
                if (manifest.versionName.isBlank() && manifest.versionCode <= 0L) {
                    throw NoStackTraceException("update.json 缺少版本信息")
                }
                manifest
            }
        }
    }

    /**
     * 清单上的版本是否比当前安装的版本新
     */
    fun isNewer(manifest: ReleaseManifest): Boolean {
        val local = AppConst.appInfo
        return isNewerVersion(
            remoteVersionCode = manifest.versionCode,
            remoteVersionName = manifest.versionName,
            localVersionCode = local.versionCode,
            localVersionName = local.versionName
        )
    }

    private fun describeHttpCode(code: Int): String {
        return when (code) {
            404 -> "地址上没有 $MANIFEST_NAME（404），检查地址或 release 是否已发布"
            401, 403 -> "没有访问权限($code)，仓库可能是私有的"
            else -> "访问失败($code)"
        }
    }

    private fun describe(e: Throwable): Throwable {
        return when (e) {
            is NoStackTraceException -> e
            is SocketTimeoutException -> NoStackTraceException("连接超时，电脑上的更新服务可能没启动")
            is ConnectException -> NoStackTraceException("连接被拒绝，检查 IP、端口和电脑防火墙")
            is UnknownHostException -> NoStackTraceException("地址无法解析，检查地址或网络")
            is IOException -> NoStackTraceException("网络错误：${e.localizedMessage}")
            else -> e
        }
    }

}

/**
 * versionCode 是内网构建最可靠的比较依据（每次出包单调递增），
 * 清单没给 versionCode 时再退回比较 versionName。
 */
internal fun isNewerVersion(
    remoteVersionCode: Long,
    remoteVersionName: String,
    localVersionCode: Long,
    localVersionName: String
): Boolean {
    if (remoteVersionCode > 0L && localVersionCode > 0L) {
        return remoteVersionCode > localVersionCode
    }
    return compareVersionName(remoteVersionName, localVersionName) > 0
}

/**
 * 按数字段比较版本号，3.26111510 按 3 / 26111510 两段比
 */
internal fun compareVersionName(left: String?, right: String?): Int {
    val leftText = left?.trim().orEmpty()
    val rightText = right?.trim().orEmpty()
    if (leftText.isEmpty()) return if (rightText.isEmpty()) 0 else -1
    if (rightText.isEmpty()) return 1
    val leftParts = leftText.split('.', '-', '_')
    val rightParts = rightText.split('.', '-', '_')
    for (index in 0 until maxOf(leftParts.size, rightParts.size)) {
        val leftValue = leftParts.getOrNull(index)?.toLongOrNull() ?: 0L
        val rightValue = rightParts.getOrNull(index)?.toLongOrNull() ?: 0L
        if (leftValue != rightValue) {
            return if (leftValue > rightValue) 1 else -1
        }
    }
    return 0
}
