package io.legado.app.help

import android.os.Build
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.config.AppConfig
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.update.AppUpdate
import io.legado.app.utils.compress.ZipUtils
import io.legado.app.utils.createFileReplace
import io.legado.app.utils.createFolderIfNotExist
import io.legado.app.utils.externalCache
import io.legado.app.utils.getFile
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import splitties.init.appCtx
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 日志上传（内网）
 *
 * 把 externalCache 下的 logs、crash、logcat 和设备信息打成一个 zip，
 * 直接 POST 给同一局域网里的电脑（body 是 zip 原始字节，文件名在 X-File-Name 头）。
 *
 * 接收端返回 JSON 里带 url / link / path 就当作下载链接，什么都不返回也算上传成功。
 * 电脑端用 tools/intranet-update/serve-logs.ps1 收，收到后落盘成 zip。
 */
object LogUpload {

    /** 接收端默认端口，和更新服务（8765）分开，避免抢端口 */
    const val DEFAULT_PORT = 8766

    /** 上传文件名放这个头，接收端照抄成落盘文件名 */
    const val FILE_NAME_HEADER = "X-File-Name"

    /** logcat 只留最近这么多行，避免整包过大 */
    private const val LOGCAT_LINES = 5000

    private val urlKeys = arrayOf("url", "downloadUrl", "download_url", "link", "path")

    @Suppress("SimpleDateFormat")
    private val fileNameFormat = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.US)

    @Suppress("SimpleDateFormat")
    private val infoTimeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    /**
     * 默认上传地址：跟着内网更新地址走，只换端口和路径，
     * 电脑换了 IP 只要改内网更新地址，这里会一起跟上
     */
    fun defaultUrl(): String {
        val base = AppConfig.updateServerUrl ?: AppUpdate.DEFAULT_LAN_URL
        val url = base.toHttpUrlOrNull()
        val scheme = url?.scheme ?: "http"
        val host = url?.host ?: "127.0.0.1"
        return "$scheme://$host:$DEFAULT_PORT/logs"
    }

    /**
     * 补协议、校验地址，返回 null 表示填的不是合法 http(s) 地址
     */
    fun normalizeUrl(raw: String?): String? {
        var text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        if (!text.startsWith("http://", true) && !text.startsWith("https://", true)) {
            text = "http://$text"
        }
        return text.toHttpUrlOrNull()?.toString()
    }

    /**
     * 当前生效的上传地址
     */
    fun currentUrl(): String {
        return AppConfig.logUploadUrl ?: defaultUrl()
    }

    /**
     * 打包并上传，返回给用户看的结果（下载链接或"已上传 xxx.zip"）
     */
    fun uploadBundle(url: String): String {
        val zipFile = buildBundle()
        return try {
            upload(url, zipFile)
        } finally {
            zipFile.delete()
        }
    }

    /**
     * 打包日志，返回 zip 文件
     *
     * 包内结构：logs/、crash/、logcat.txt、info.txt
     */
    fun buildBundle(): File {
        val cache = appCtx.externalCache
        val outDir = cache.getFile("logUpload").createFolderIfNotExist()
        val time = System.currentTimeMillis()
        val zipFile = outDir
            .getFile("legado-logs-${fileNameFormat.format(Date(time))}-$time.zip")
            .createFileReplace()

        val logDir = cache.getFile("logs")
        val crashDir = cache.getFile("crash")
        val logcatFile = cache.getFile("logcat.txt").createFileReplace()
        dumpLogcat(logcatFile)
        val infoFile = outDir.getFile("info-$time.txt").createFileReplace()
        infoFile.writeText(buildInfo())

        val sources = arrayListOf<File>()
        if (logDir.exists()) sources.add(logDir)
        if (crashDir.exists()) sources.add(crashDir)
        if (logcatFile.length() > 0L) sources.add(logcatFile)
        sources.add(infoFile)

        ZipUtils.zipFiles(sources, zipFile)
        logcatFile.delete()
        infoFile.delete()
        if (!zipFile.exists() || zipFile.length() == 0L) {
            throw NoStackTraceException("没有可上传的日志")
        }
        return zipFile
    }

    /**
     * 上传 zip，返回下载链接或成功提示
     *
     * 协议：POST，body 就是 zip 原始字节，文件名放 X-File-Name 头。
     * 这样接收端不用解析 multipart，二进制整段落盘就行。
     */
    fun upload(url: String, zipFile: File): String {
        val request = Request.Builder()
            .url(url)
            .post(zipFile.asRequestBody("application/zip".toMediaTypeOrNull()))
            .header(FILE_NAME_HEADER, zipFile.name)
            .build()
        okHttpClient.newCall(request).execute().use { response ->
            val text = runCatching { response.body.string() }.getOrNull().orEmpty()
            if (!response.isSuccessful) {
                throw NoStackTraceException(
                    "上传失败 HTTP ${response.code}\n${text.take(200)}"
                )
            }
            return parseLink(text).ifBlank { "已上传 ${zipFile.name}" }
        }
    }

    /**
     * 从响应里找下载链接，容忍 json / 纯文本两种返回
     */
    private fun parseLink(body: String): String {
        val text = body.trim()
        if (text.isEmpty()) return ""
        runCatching {
            val json = JSONObject(text)
            pickUrl(json).takeIf { it.isNotEmpty() }?.let { return it }
            json.optJSONObject("data")?.let { data ->
                pickUrl(data).takeIf { it.isNotEmpty() }?.let { return it }
            }
        }
        val firstLine = text.lineSequence().firstOrNull()?.trim().orEmpty()
        return if (firstLine.startsWith("http://", true) || firstLine.startsWith("https://", true)) {
            firstLine
        } else {
            ""
        }
    }

    private fun pickUrl(json: JSONObject): String {
        for (key in urlKeys) {
            val value = json.optString(key).trim()
            if (value.startsWith("http://", true) || value.startsWith("https://", true)) {
                return value
            }
        }
        return ""
    }

    /**
     * info.txt：设备与版本信息 + 内存里的 AppLog，看日志时先看这个
     */
    private fun buildInfo(): String {
        val cache = appCtx.externalCache
        return buildString {
            appendLine("time=${infoTimeFormat.format(Date())}")
            appendLine("MANUFACTURER=${Build.MANUFACTURER}")
            appendLine("BRAND=${Build.BRAND}")
            appendLine("MODEL=${Build.MODEL}")
            appendLine("SDK_INT=${Build.VERSION.SDK_INT}")
            appendLine("RELEASE=${Build.VERSION.RELEASE}")
            appendLine("packageName=${appCtx.packageName}")
            appendLine("versionName=${AppConst.appInfo.versionName}")
            appendLine("versionCode=${AppConst.appInfo.versionCode}")
            appendLine("recordLog=${AppConfig.recordLog}")
            appendLine("recordHeapDump=${AppConfig.recordHeapDump}")
            appendLine("crashFiles=${cache.getFile("crash").listFiles()?.size ?: 0}")
            appendLine("logFiles=${cache.getFile("logs").listFiles()?.size ?: 0}")
            appendLine("uploadUrl=${currentUrl()}")
            appendLine()
            appendLine("---- AppLog（内存中最近 100 条）----")
            appendLine(AppLog.exportText(AppLog.logs))
        }
    }

    /**
     * 导出 logcat，只留最近若干行；导不出来不影响打包
     */
    private fun dumpLogcat(file: File) {
        runCatching {
            val process = Runtime.getRuntime().exec("logcat -d -v time -t $LOGCAT_LINES")
            file.outputStream().use { output ->
                process.inputStream.copyTo(output)
            }
            process.waitFor()
        }.onFailure {
            AppLog.putNotSave("导出 logcat 失败\n${it.localizedMessage}")
        }
    }

}
