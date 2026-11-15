package io.legado.app.help.webView

import android.webkit.JavascriptInterface
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.script.rhino.runScriptWithContext
import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.RssReadRecord
import io.legado.app.data.entities.RssSource
import io.legado.app.help.JsExtensions
import io.legado.app.model.AudioPlay
import io.legado.app.model.ReadBook
import io.legado.app.model.analyzeRule.AnalyzeRule
import io.legado.app.model.analyzeRule.AnalyzeRule.Companion.setChapter
import io.legado.app.utils.LogUtils
import io.legado.app.utils.isJsonObject
import io.legado.app.utils.openUrl
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.net.URL


@Suppress("unused")
open class SourceJsExtensions(
    activity: AppCompatActivity?,
    source: BaseSource?,
    val bookType: Int = 0
) : JsExtensions {

    val activityRef: WeakReference<AppCompatActivity> = WeakReference(activity)
    val sourceRef: WeakReference<BaseSource?> = WeakReference(source)

    override fun getSource(): BaseSource? {
        return sourceRef.get()
    }

    override fun getTag(): String? {
        return getSource()?.getTag()
    }

    private var navigationMissingLogged = false

    /** 界面跳转由 ui 层注册的实现提供；未注册时只记一次日志并跳过，不抛异常。 */
    private fun navigation(): SourceNavigation? {
        val nav = SourceNavigationHolder.current
        if (nav == null && !navigationMissingLogged) {
            navigationMissingLogged = true
            LogUtils.d("SourceJsExtensions", "SourceNavigation 未注册，JS 界面跳转被跳过")
        }
        return nav
    }

    @JavascriptInterface
    fun put(key: String, value: String): String {
        getSource()?.put(key, value)
        return value
    }

    @JavascriptInterface
    fun get(key: String): String {
        return getSource()?.get(key) ?: ""
    }

    @JavascriptInterface
    @JvmOverloads
    fun searchBook(key: String, searchScope: String? = null) {
        activityRef.get()?.let {
            navigation()?.openSearch(it, key, searchScope)
        }
    }

    fun searchBook(key: String, source: BookSource) {
        activityRef.get()?.let {
            navigation()?.openSearch(it, key, source)
        }
    }

    @JavascriptInterface
    fun addBook(bookUrl: String) {
        activityRef.get()?.let {
            navigation()?.addToBookshelf(it, bookUrl)
        }
    }

    @JavascriptInterface
    fun showPhoto(src: String) {
        activityRef.get()?.let {
            navigation()?.showPhoto(it, src, getSource()?.getKey())
        }
    }


    @JavascriptInterface
    @JvmOverloads
    fun open(name: String, url: String? = null, title: String? = null, origin: String? = null) {
        val activity = activityRef.get() ?: return
        activity.lifecycleScope.launch(IO) {
            val source = getSource() ?: return@launch
            when (name) {
                "login" -> {
                    val nav = navigation() ?: return@launch
                    if (nav.isLoginScreen(activity)) {
                        activity.toastOnUi("已在登录界面")
                        return@launch
                    }
                    nav.openLogin(activity, origin, source, bookType)
                        ?.let { activity.toastOnUi(it) }
                }

                "sort" -> {
                    val toSource = origin?.let { o ->
                        appDb.rssSourceDao.getByKey(o)
                    } ?: (source as? RssSource) ?: return@launch
                    val sortUrl = if (url.isJsonObject()) {
                        url
                    } else {
                        title?.let {
                            JSONObject().put(title, url).toString()
                        } ?: url
                    }
                    val sourceUrl = toSource.sourceUrl
                    navigation()?.openRssSort(activity, sortUrl, sourceUrl)
                }

                "rss" -> {
                    val toSource = origin?.let { o ->
                        appDb.rssSourceDao.getByKey(o)
                    } ?: (source as? RssSource) ?: return@launch
                    val title = title ?: toSource.sourceName
                    val sourceUrl = toSource.sourceUrl
                    val singleTop = sourceUrl == source.getKey()
                    if (url.isNullOrBlank()) {
                        if (toSource.singleUrl) {
                            if (sourceUrl.startsWith("http", true)) {
                                navigation()?.openRssRead(
                                    activity,
                                    singleTop,
                                    sourceUrl,
                                    title,
                                    null,
                                    null
                                )
                            } else {
                                activity.openUrl(sourceUrl)
                            }
                            return@launch
                        }
                        val startHtml = toSource.startHtml?.let {
                            when {
                                it.startsWith("@js:") -> runScriptWithContext {
                                    toSource.evalJS(it.substring(4)).toString()
                                }

                                it.startsWith("<js>") -> runScriptWithContext {
                                    toSource.evalJS(it.substring(4, it.lastIndexOf("<"))).toString()
                                }

                                else -> it
                            }
                        }
                        if (startHtml.isNullOrBlank()) {
                            navigation()?.openRssSort(activity, null, sourceUrl)
                        } else {
                            navigation()?.openRssRead(
                                activity,
                                singleTop,
                                sourceUrl,
                                title,
                                null,
                                startHtml
                            )
                        }
                        return@launch
                    }
                    val rss = appDb.rssStarDao.get(sourceUrl, url)?.toRecord()
                        ?: appDb.rssArticleDao.getByLink(sourceUrl, url)?.toRecord()
                    val rssReadRecord = rss ?: RssReadRecord(
                        record = url,
                        title = title,
                        origin = sourceUrl,
                        readTime = System.currentTimeMillis()
                    )
                    appDb.rssReadRecordDao.insertRecord(rssReadRecord) //留下历史记录
                    withContext(Main) {
                        navigation()?.openRssRead(
                            activity,
                            singleTop,
                            sourceUrl,
                            title,
                            url,
                            null
                        )
                    }
                }

                "search" -> {
                    title?.let {
                        origin?.let { o  ->
                            appDb.bookSourceDao.getBookSource(o)?.let { s ->
                                navigation()?.openSearch(activity, it, s)
                                return@launch
                            }
                        }
                        navigation()?.openSearch(activity, it, null)
                    }
                }

                "explore" -> {
                    if (url.isNullOrBlank()) return@launch
                    val toSource = origin?.let { o ->
                        appDb.bookSourceDao.getBookSource(o)
                    } ?: (source as? BookSource) ?: return@launch
                    val sourceUrl = toSource.bookSourceUrl
                    withContext(Main) {
                        navigation()?.openExplore(activity, title, sourceUrl, url)
                    }
                }
            }
        }
    }

    /** AnalyzeRule实现 **/
    private val bookAndChapter by lazy {
        var book: Book? = null
        var chapter: BookChapter? = null
        when (bookType) {
            BookType.text -> {
                book = ReadBook.book?.also {
                    chapter = appDb.bookChapterDao.getChapter(
                        it.bookUrl,
                        ReadBook.durChapterIndex
                    )
                }
            }

            BookType.audio -> {
                book = AudioPlay.book
                chapter = AudioPlay.durChapter
            }
        }
        Pair(book, chapter)
    }
    private val book: Book? get() = bookAndChapter.first
    private val chapter: BookChapter? get() = bookAndChapter.second

    val analyzeRule by lazy {
        AnalyzeRule(book, source = getSource()).setChapter(chapter)
    }

    @JavascriptInterface
    @JvmOverloads
    fun setContent(content: Any?, baseUrl: String? = null): AnalyzeRule {
        return analyzeRule.setContent(content, baseUrl)
    }

    @JavascriptInterface
    fun setBaseUrl(baseUrl: String?): AnalyzeRule {
        return analyzeRule.setBaseUrl(baseUrl)
    }

    @JavascriptInterface
    fun setRedirectUrl(url: String): URL? {
        return analyzeRule.setRedirectUrl(url)
    }

    @JvmOverloads
    fun getStringList(rule: String?, mContent: Any? = null, isUrl: Boolean = false): List<String>? {
        return analyzeRule.getStringList(rule, mContent, isUrl)
    }

    @JvmOverloads
    fun getString(ruleStr: String?, mContent: Any? = null, isUrl: Boolean = false): String {
        return analyzeRule.getString(ruleStr, mContent, isUrl)
    }

    @JavascriptInterface
    fun getString(ruleStr: String?, unescape: Boolean): String {
        return analyzeRule.getString(ruleStr, unescape)
    }

    fun getElement(ruleStr: String): Any? {
        return analyzeRule.getElement(ruleStr)
    }

    fun getElements(ruleStr: String): List<Any> {
        return analyzeRule.getElements(ruleStr)
    }

}
