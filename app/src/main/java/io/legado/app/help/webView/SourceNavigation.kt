package io.legado.app.help.webView

import androidx.appcompat.app.AppCompatActivity
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.BookSource
import java.util.concurrent.atomic.AtomicReference

/**
 * JS 宿主与界面之间的边界。
 *
 * `help/webView` 按 `doc/architecture.md` 的定位是无 UI 的横切帮助层，不应直接依赖 `ui.*`。
 * 所有"打开某个界面"的动作都由 ui 层实现本接口，并在 [io.legado.app.App.onCreate] 注册；
 * [SourceJsExtensions] 只负责分发，不再引用具体 Activity 或 Dialog。
 */
interface SourceNavigation {

    fun isLoginScreen(activity: AppCompatActivity): Boolean

    /**
     * 打开源登录界面。
     * @return null 表示已跳转，否则为需要提示给用户的失败文案。
     */
    suspend fun openLogin(
        activity: AppCompatActivity,
        origin: String?,
        currentSource: BaseSource,
        bookType: Int,
    ): String?

    fun openSearch(activity: AppCompatActivity, key: String, searchScope: String? = null)

    fun openSearch(activity: AppCompatActivity, key: String, source: BookSource)

    fun openExplore(
        activity: AppCompatActivity,
        title: String?,
        sourceUrl: String,
        exploreUrl: String,
    )

    fun openRssSort(activity: AppCompatActivity, sortUrl: String?, sourceUrl: String)

    fun openRssRead(
        activity: AppCompatActivity,
        singleTop: Boolean,
        sourceUrl: String,
        title: String?,
        url: String?,
        startHtml: String?,
    )

    fun addToBookshelf(activity: AppCompatActivity, bookUrl: String)

    fun showPhoto(activity: AppCompatActivity, src: String, sourceKey: String?)
}

/**
 * [SourceNavigation] 的注册点。未注册时 JS 跳转会被跳过并留下一条日志，不会崩溃。
 */
object SourceNavigationHolder {

    private val delegate = AtomicReference<SourceNavigation?>(null)

    fun register(navigation: SourceNavigation) {
        delegate.set(navigation)
    }

    fun clear() {
        delegate.set(null)
    }

    val current: SourceNavigation?
        get() = delegate.get()
}
