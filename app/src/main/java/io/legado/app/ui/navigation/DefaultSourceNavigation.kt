package io.legado.app.ui.navigation

import androidx.appcompat.app.AppCompatActivity
import io.legado.app.data.appDb
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.BookSource
import io.legado.app.help.webView.SourceNavigation
import io.legado.app.ui.association.AddToBookshelfDialog
import io.legado.app.ui.book.explore.ExploreShowActivity
import io.legado.app.ui.book.search.SearchActivity
import io.legado.app.ui.login.SourceLoginActivity
import io.legado.app.ui.login.createSourceLoginRoute
import io.legado.app.ui.login.resolveLoginSource
import io.legado.app.ui.rss.article.RssSortActivity
import io.legado.app.ui.rss.read.ReadRssActivity
import io.legado.app.ui.widget.dialog.PhotoDialog
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.withContext

/**
 * [SourceNavigation] 的默认实现。所有 `ui.*` 依赖集中在此，JS 宿主侧不再引用界面类。
 */
object DefaultSourceNavigation : SourceNavigation {

    override fun isLoginScreen(activity: AppCompatActivity): Boolean {
        return activity is SourceLoginActivity
    }

    override suspend fun openLogin(
        activity: AppCompatActivity,
        origin: String?,
        currentSource: BaseSource,
        bookType: Int,
    ): String? {
        val toSource = resolveLoginSource(
            origin = origin,
            currentSource = currentSource,
            findBookSource = { appDb.bookSourceDao.getBookSource(it) },
            findRssSource = { appDb.rssSourceDao.getByKey(it) },
        ) ?: return "未找到指定源"
        if (!toSource.hasLogin()) return "源未配置登录"
        val route = createSourceLoginRoute(toSource, currentSource, bookType)
            ?: return "不支持该源类型登录"
        withContext(Main) {
            activity.startActivity<SourceLoginActivity> {
                putExtra("type", route.type)
                putExtra("key", route.key)
                route.bookType?.let { putExtra("bookType", it) }
            }
        }
        return null
    }

    override fun openSearch(activity: AppCompatActivity, key: String, searchScope: String?) {
        SearchActivity.start(activity, key, searchScope)
    }

    override fun openSearch(activity: AppCompatActivity, key: String, source: BookSource) {
        SearchActivity.start(activity, source, key)
    }

    override fun openExplore(
        activity: AppCompatActivity,
        title: String?,
        sourceUrl: String,
        exploreUrl: String,
    ) {
        activity.startActivity<ExploreShowActivity> {
            putExtra("exploreName", title)
            putExtra("sourceUrl", sourceUrl)
            putExtra("exploreUrl", exploreUrl)
        }
    }

    override fun openRssSort(activity: AppCompatActivity, sortUrl: String?, sourceUrl: String) {
        RssSortActivity.start(activity, sortUrl, sourceUrl)
    }

    override fun openRssRead(
        activity: AppCompatActivity,
        singleTop: Boolean,
        sourceUrl: String,
        title: String?,
        url: String?,
        startHtml: String?,
    ) {
        ReadRssActivity.start(activity, singleTop, sourceUrl, title, url, startHtml)
    }

    override fun addToBookshelf(activity: AppCompatActivity, bookUrl: String) {
        activity.showDialogFragment(AddToBookshelfDialog(bookUrl))
    }

    override fun showPhoto(activity: AppCompatActivity, src: String, sourceKey: String?) {
        activity.showDialogFragment(PhotoDialog(src, sourceKey))
    }
}
