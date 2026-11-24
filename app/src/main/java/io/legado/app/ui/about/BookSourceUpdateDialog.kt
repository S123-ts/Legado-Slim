package io.legado.app.ui.about

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.data.entities.BookSource
import io.legado.app.databinding.DialogBooksourceUpdateBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.source.BuiltinSourceSync
import io.legado.app.help.update.AppUpdate
import io.legado.app.help.update.BookSourceUpdate
import io.legado.app.lib.theme.primaryColor
import io.legado.app.model.RuleUpdate
import io.legado.app.ui.association.ImportBookSourceDialog
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.setLayout
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding

/**
 * 书源在线更新，独立于应用更新：
 *
 * 检查到新版本后下载并校验书源包，然后把结果注入 [RuleUpdate.cacheBookSourceMap]，
 * 交给现有的 [ImportBookSourceDialog] 让用户勾选导入。这样不重复实现导入 UI，
 * 也不影响用户自定义改名与分组。
 */
class BookSourceUpdateDialog : BaseDialogFragment(R.layout.dialog_booksource_update) {

    private val binding by viewBinding(DialogBooksourceUpdateBinding::bind)
    private var pendingManifest: BookSourceUpdate.SourceManifest? = null
    private var currentBaseUrl: String? = null

    override fun onStart() {
        super.onStart()
        setLayout(0.92f, 0.82f)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.run {
            toolBar.setBackgroundColor(primaryColor)
            etLan.setText(
                AppConfig.updateServerUrl?.takeIf { it.isNotBlank() }
                    ?: AppUpdate.DEFAULT_LAN_URL
            )
            etGithub.setText(
                AppConfig.updateGitHubRepo?.takeIf { it.isNotBlank() }
                    ?: AppUpdate.DEFAULT_GITHUB_REPO
            )
            tvCurrentVersion.text = getString(R.string.booksource_latest_version) +
                    " ${LocalConfig.onlineBookSourceVersion}"
            val useLan = AppConfig.updateChannel == AppUpdate.CHANNEL_LAN
            if (useLan) rbLan.isChecked = true else rbGithub.isChecked = true
            rgChannel.setOnCheckedChangeListener { _, checkedId ->
                applyChannel(checkedId == R.id.rb_lan)
            }
            applyChannel(useLan)
            btnCheck.setOnClickListener { checkUpdate() }
            btnUpdate.setOnClickListener { downloadAndImport() }
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
            btnUpdate.isVisible = false
        }
        pendingManifest = null
        currentBaseUrl = null
        AppConfig.updateChannel = if (isLan) AppUpdate.CHANNEL_LAN else AppUpdate.CHANNEL_GITHUB
    }

    /**
     * 复用应用更新已保存的通道地址，避免两套配置
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
        binding.btnUpdate.isVisible = false
        binding.tvLog.text = ""
        binding.tvStatus.text = getString(R.string.booksource_checking)
        binding.btnCheck.isEnabled = false
        BookSourceUpdate.check(viewLifecycleOwner.lifecycleScope, baseUrl)
            .onSuccess { manifest ->
                if (!isAdded || view == null) return@onSuccess
                pendingManifest = manifest
                binding.tvLog.text = manifest.updateLog
                if (BookSourceUpdate.isNewer(manifest)) {
                    binding.tvStatus.text = getString(R.string.booksource_new_version) +
                            " ${manifest.version} · ${manifest.count} " +
                            getString(R.string.booksource_unit)
                    binding.btnUpdate.isVisible = true
                } else {
                    binding.tvStatus.text = getString(R.string.booksource_latest_version) +
                            " ${LocalConfig.onlineBookSourceVersion}"
                }
            }
            .onError { e ->
                if (!isAdded || view == null) return@onError
                pendingManifest = null
                binding.tvStatus.text = e.localizedMessage ?: e.toString()
            }
            .onFinally {
                if (!isAdded || view == null) return@onFinally
                binding.btnCheck.isEnabled = true
            }
    }

    private fun downloadAndImport() {
        val manifest = pendingManifest ?: return
        val baseUrl = currentBaseUrl ?: return
        binding.btnUpdate.isEnabled = false
        binding.tvStatus.text = getString(R.string.booksource_downloading)
        var useBuiltinSync = false
        execute {
            val json = BookSourceUpdate.downloadJson(baseUrl, manifest)
            val entries = BuiltinSourceSync.parse(json)
            if (entries.isNotEmpty()) {
                // 带编号：按编号完全覆盖内置源，用户自己导入的源不碰
                useBuiltinSync = true
                BuiltinSourceSync.sync(entries)
            } else {
                // 老包无编号：注入缓存，交给现有导入对话框让用户勾选
                val sources = GSON.fromJsonArray<BookSource>(json).getOrThrow()
                RuleUpdate.cacheBookSourceMap[BookSourceUpdate.packageUrl(baseUrl, manifest)] = sources
            }
            LocalConfig.onlineBookSourceVersion = manifest.version
        }.onSuccess {
            if (!isAdded || view == null) return@onSuccess
            if (useBuiltinSync) {
                toastOnUi(getString(R.string.booksource_synced))
                dismiss()
            } else {
                // 先打开导入弹窗（用 activity 的 FragmentManager），再关闭自己，
                // 否则 dismiss 后 childFragmentManager 已不可用。
                // 本工程的宿主都是 AppCompatActivity，转换失败时退化为自身 FragmentManager。
                val hostActivity = requireActivity()
                if (hostActivity is AppCompatActivity) {
                    hostActivity.showDialogFragment(
                        ImportBookSourceDialog(BookSourceUpdate.packageUrl(baseUrl, manifest))
                    )
                } else {
                    showDialogFragment(
                        ImportBookSourceDialog(BookSourceUpdate.packageUrl(baseUrl, manifest))
                    )
                }
                dismiss()
            }
        }.onError { e ->
            if (!isAdded || view == null) return@onError
            binding.tvStatus.text = e.localizedMessage ?: e.toString()
        }.onFinally {
            if (!isAdded || view == null) return@onFinally
            binding.btnUpdate.isEnabled = true
        }
    }
}
