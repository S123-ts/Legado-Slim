package io.legado.app.ui.about

import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.constant.AppConst
import io.legado.app.databinding.DialogAppUpdateBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.help.update.AppUpdate
import io.legado.app.lib.theme.primaryColor
import io.legado.app.model.Download
import io.legado.app.utils.ConvertUtils
import io.legado.app.utils.setLayout
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding

/**
 * 检查更新：内网（局域网电脑）与 GitHub 仓库两条通道，
 * 地址都可随时改，检测到新版本后走系统下载器下载并安装
 */
class AppUpdateDialog : BaseDialogFragment(R.layout.dialog_app_update) {

    private val binding by viewBinding(DialogAppUpdateBinding::bind)
    private var pendingManifest: AppUpdate.ReleaseManifest? = null
    private var currentBaseUrl: String? = null

    override fun onStart() {
        super.onStart()
        setLayout(0.92f, 0.82f)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.run {
            toolBar.setBackgroundColor(primaryColor)
            etLan.setText(AppConfig.updateServerUrl?.takeIf { it.isNotBlank() } ?: AppUpdate.DEFAULT_LAN_URL)
            etGithub.setText(
                AppConfig.updateGitHubRepo?.takeIf { it.isNotBlank() }
                    ?: AppUpdate.DEFAULT_GITHUB_REPO
            )
            tvCurrentVersion.text =
                "${getString(R.string.version)} ${AppConst.appInfo.versionName}"
            val useLan = AppConfig.updateChannel == AppUpdate.CHANNEL_LAN
            if (useLan) rbLan.isChecked = true else rbGithub.isChecked = true
            rgChannel.setOnCheckedChangeListener { _, checkedId ->
                applyChannel(checkedId == R.id.rb_lan)
            }
            applyChannel(useLan)
            btnCheck.setOnClickListener { checkUpdate() }
            btnDownload.setOnClickListener { downloadAndInstall() }
        }
    }

    private fun applyChannel(isLan: Boolean) {
        binding.run {
            tilLan.isVisible = isLan
            tvLanTip.isVisible = isLan
            tilGithub.isVisible = !isLan
            tvGithubTip.isVisible = !isLan
            tvStatus.text = ""
            tvLog.text = ""
            btnDownload.isVisible = false
        }
        pendingManifest = null
        currentBaseUrl = null
        AppConfig.updateChannel = if (isLan) AppUpdate.CHANNEL_LAN else AppUpdate.CHANNEL_GITHUB
    }

    /**
     * 读取当前通道并返回清单基址，地址不合法时给出提示
     */
    private fun resolveTargetBaseUrl(): String? {
        return if (binding.rbLan.isChecked) {
            val lanUrl = AppUpdate.normalizeServerUrl(binding.etLan.text?.toString())
            if (lanUrl == null) {
                binding.tvStatus.text = getString(R.string.intranet_server_invalid)
                return null
            }
            AppConfig.updateServerUrl = lanUrl
            binding.etLan.setText(lanUrl)
            lanUrl
        } else {
            val repo = AppUpdate.normalizeGitHubRepo(binding.etGithub.text?.toString())
            if (repo == null) {
                binding.tvStatus.text = getString(R.string.github_repo_invalid)
                return null
            }
            AppConfig.updateGitHubRepo = repo
            binding.etGithub.setText(repo)
            AppUpdate.githubBaseUrl(repo)
        }
    }

    private fun checkUpdate() {
        val baseUrl = resolveTargetBaseUrl() ?: return
        currentBaseUrl = baseUrl
        pendingManifest = null
        binding.btnDownload.isVisible = false
        binding.tvLog.text = ""
        binding.tvStatus.text = getString(R.string.intranet_checking)
        binding.btnCheck.isEnabled = false
        AppUpdate.check(lifecycleScope, baseUrl)
            .onSuccess { manifest ->
                if (!isAdded) return@onSuccess
                pendingManifest = manifest
                val size = if (manifest.size > 0L) {
                    " · " + ConvertUtils.formatFileSize(manifest.size)
                } else {
                    ""
                }
                if (AppUpdate.isNewer(manifest)) {
                    binding.tvStatus.text = getString(R.string.intranet_new_version) +
                            " ${manifest.versionName}$size"
                    binding.tvLog.text = manifest.updateLog
                    binding.btnDownload.isVisible = true
                } else {
                    binding.tvStatus.text = getString(R.string.intranet_latest_version) +
                            " ${AppConst.appInfo.versionName}"
                    binding.tvLog.text = manifest.updateLog
                }
            }
            .onError { e ->
                if (!isAdded) return@onError
                pendingManifest = null
                binding.tvStatus.text = e.localizedMessage ?: e.toString()
            }
            .onFinally {
                binding.btnCheck.isEnabled = true
            }
    }

    private fun downloadAndInstall() {
        val manifest = pendingManifest ?: return
        val baseUrl = currentBaseUrl ?: return
        val url = AppUpdate.apkUrl(baseUrl, manifest)
        val name = manifest.apkName.ifBlank { "legado_${manifest.versionName}.apk" }
            .let { if (it.endsWith(".apk", ignoreCase = true)) it else "$it.apk" }
        Download.start(requireContext(), url, name, isAppUpdate = true)
        toastOnUi(R.string.download_start)
        dismiss()
    }

}
