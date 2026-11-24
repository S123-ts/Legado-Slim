package io.legado.app.ui.about

import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import io.legado.app.R
import io.legado.app.constant.AppConst.appInfo
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.update.AppUpdate
import io.legado.app.ui.widget.dialog.TextDialog
import io.legado.app.utils.showDialogFragment

/**
 * 「检查更新 / 更新日志」两个入口的共用逻辑。
 * 入口放在设置的「其它」分组里，位于「关于」之上；关于页不再保留这两项。
 */
object AppUpdatePreferences {

    const val APP_UPDATE = "app_update"
    const val BOOKSOURCE_UPDATE = "booksource_update"
    const val UPDATE_LOG = "update_log"

    /**
     * 两条更新通道的地址都可能变，每次回到页面都重新取
     */
    fun refreshSummary(fragment: PreferenceFragmentCompat) {
        fragment.findPreference<Preference>(UPDATE_LOG)?.summary =
            "${fragment.getString(R.string.version)} ${appInfo.versionName}"
        val lanUrl = AppConfig.updateServerUrl
        val repo = AppConfig.updateGitHubRepo?.takeIf { it.isNotBlank() }
            ?: AppUpdate.DEFAULT_GITHUB_REPO
        val lanText = lanUrl?.takeIf { it.isNotBlank() } ?: AppUpdate.DEFAULT_LAN_URL
        fragment.findPreference<Preference>(APP_UPDATE)?.summary =
            fragment.getString(R.string.update_summary_lan, lanText) + "\n" +
                    fragment.getString(R.string.update_summary_github, repo)
        fragment.findPreference<Preference>(BOOKSOURCE_UPDATE)?.summary =
            fragment.getString(R.string.booksource_update_summary) + " · " +
                    fragment.getString(R.string.booksource_latest_version) +
                    " ${LocalConfig.onlineBookSourceVersion}"
    }

    fun onPreferenceClick(fragment: PreferenceFragmentCompat, key: String?): Boolean {
        when (key) {
            UPDATE_LOG -> {
                val mdText = String(
                    fragment.requireContext().assets.open("updateLog.md").readBytes()
                )
                fragment.showDialogFragment(
                    TextDialog(fragment.getString(R.string.update_log), mdText, TextDialog.Mode.MD)
                )
            }

            APP_UPDATE -> fragment.showDialogFragment<AppUpdateDialog>()
            BOOKSOURCE_UPDATE -> fragment.showDialogFragment<BookSourceUpdateDialog>()
            else -> return false
        }
        return true
    }

}
