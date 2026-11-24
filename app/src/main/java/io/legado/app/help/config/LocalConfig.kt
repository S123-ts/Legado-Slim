package io.legado.app.help.config

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import io.legado.app.utils.getBoolean
import io.legado.app.utils.putBoolean
import io.legado.app.utils.putLong
import io.legado.app.utils.putString
import io.legado.app.utils.remove
import splitties.init.appCtx

@Suppress("ConstPropertyName")
object LocalConfig : SharedPreferences
by appCtx.getSharedPreferences("local", Context.MODE_PRIVATE) {

    private const val versionCodeKey = "appVersionCode"

    /**
     * 本地密码,用来对需要备份的敏感信息加密,如 webdav 配置等
     */
    var password: String?
        get() = getString("password", null)
        set(value) {
            if (value != null) {
                putString("password", value)
            } else {
                remove("password")
            }
        }

    var lastBackup: Long
        get() = getLong("lastBackup", 0)
        set(value) {
            putLong("lastBackup", value)
        }

    var privacyPolicyOk: Boolean
        get() = getBoolean("privacyPolicyOk")
        set(value) {
            putBoolean("privacyPolicyOk", value)
        }

    val readHelpVersionIsLast: Boolean
        get() = isLastVersion(1, "readHelpVersion", "firstRead")

    val backupHelpVersionIsLast: Boolean
        get() = isLastVersion(1, "backupHelpVersion", "firstBackup")

    val readMenuHelpVersionIsLast: Boolean
        get() = isLastVersion(1, "readMenuHelpVersion", "firstReadMenu")

    val bookSourcesHelpVersionIsLast: Boolean
        get() = isLastVersion(1, "bookSourceHelpVersion", "firstOpenBookSources")

    val webDavBookHelpVersionIsLast: Boolean
        get() = isLastVersion(1, "webDavBookHelpVersion", "firstOpenWebDavBook")

    val ruleHelpVersionIsLast: Boolean
        get() = isLastVersion(1, "ruleHelpVersion")

    val needUpHttpTTS: Boolean
        get() = !isLastVersion(8, "httpTtsVersion")

    val needUpTxtTocRule: Boolean
        get() = !isLastVersion(3, "txtTocRuleVersion")

    val needUpRssSources: Boolean
        get() = !isLastVersion(7, "rssSourceVersion")

    val needUpBookSources: Boolean
        // 2026-11-23 提到 9：内置书源 43→1（按用户要求只保留 https://sudugu520.com），不提版本老设备不会触发导入与剔除
        get() = !isLastVersion(9, "bookSourceVersion")

    /**
     * 在线书源包已应用的版本。未设置时回退到内置版本戳，
     * 这样新装用户不会因为在线清单版本等于内置版本而被误提示更新。
     */
    var onlineBookSourceVersion: Int
        get() = getInt("onlineBookSourceVersion", getInt("bookSourceVersion", 0))
        set(value) {
            edit { putInt("onlineBookSourceVersion", value) }
        }

    /**
     * 内置书源编号 -> URL 的基线（JSON 对象）。
     * 同步时按编号识别内置源：只更新/新增/下架基线内有编号的源，
     * 用户自己导入的源不在此映射中，不会被改动。
     * 空字符串表示尚无基线（老用户首次升级），此时只建立基线不删除。
     */
    var builtinSourceIds: String
        get() = getString("builtinSourceIds", "") ?: ""
        set(value) {
            edit { putString("builtinSourceIds", value) }
        }

    /**
     * 用户在书源管理里主动删除的内置源 URL（JSON 数组）。
     * 升级/内置包更新时跳过这些 URL，避免删掉后又被自动导回来。
     * 只影响自动导入，不影响用户手动导入或在线包主动更新。
     */
    var removedBuiltinSourceUrls: String
        get() = getString("removedBuiltinSourceUrls", "[]") ?: "[]"
        set(value) {
            edit { putString("removedBuiltinSourceUrls", value) }
        }

    var versionCode
        get() = getLong(versionCodeKey, 0)
        set(value) {
            edit { putLong(versionCodeKey, value) }
        }

    val isFirstOpenApp: Boolean
        get() {
            val value = getBoolean("firstOpen", true)
            if (value) {
                edit { putBoolean("firstOpen", false) }
            }
            return value
        }

    @Suppress("SameParameterValue")
    private fun isLastVersion(
        lastVersion: Int,
        versionKey: String,
        firstOpenKey: String? = null
    ): Boolean {
        var version = getInt(versionKey, 0)
        if (version == 0 && firstOpenKey != null) {
            if (!getBoolean(firstOpenKey, true)) {
                version = 1
            }
        }
        if (version < lastVersion) {
            edit { putInt(versionKey, lastVersion) }
            return false
        }
        return true
    }

    var bookInfoDeleteAlert: Boolean
        get() = getBoolean("bookInfoDeleteAlert", true)
        set(value) {
            putBoolean("bookInfoDeleteAlert", value)
        }

    var deleteBookOriginal: Boolean
        get() = getBoolean("deleteBookOriginal")
        set(value) {
            putBoolean("deleteBookOriginal", value)
        }

    var uploadImportedBookToWebDav: Boolean
        get() = getBoolean("uploadImportedBookToWebDav")
        set(value) {
            putBoolean("uploadImportedBookToWebDav", value)
        }

    var appCrash: Boolean
        get() = getBoolean("appCrash")
        set(value) {
            putBoolean("appCrash", value)
        }

}
