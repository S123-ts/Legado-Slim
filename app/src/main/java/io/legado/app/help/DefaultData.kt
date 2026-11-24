package io.legado.app.help

import io.legado.app.constant.AppConst
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.HttpTTS
import io.legado.app.data.entities.KeyboardAssist
import io.legado.app.data.entities.RssSource
import io.legado.app.data.entities.TxtTocRule
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.source.BuiltinSourceRegistry
import io.legado.app.help.source.BuiltinSourceSync
import io.legado.app.help.source.SourceHelp
import io.legado.app.help.source.clearSharedGlobalState
import io.legado.app.model.BookCover
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.printOnDebug
import io.legado.app.utils.putString
import splitties.init.appCtx
import java.io.File

object DefaultData {

    fun upVersion() {
        if (LocalConfig.versionCode < AppConst.appInfo.versionCode) {
            Coroutine.async {
                if (LocalConfig.needUpHttpTTS) {
                    importDefaultHttpTTS()
                }
                if (LocalConfig.needUpTxtTocRule) {
                    importDefaultTocRules()
                }
                if (LocalConfig.needUpRssSources) {
                    importDefaultRssSources()
                }
                if (LocalConfig.needUpBookSources) {
                    importDefaultBookSources()
                }
            }.onError {
                it.printOnDebug()
            }
        }
    }

    val bookSources: List<BookSource> by lazy {
        val json = String(
            appCtx.assets.open("defaultData${File.separator}bookSources.json")
                .readBytes()
        )
        GSON.fromJsonArray<BookSource>(json).getOrDefault(emptyList())
    }

    val httpTTS: List<HttpTTS> by lazy {
        val json =
            String(
                appCtx.assets.open("defaultData${File.separator}httpTTS.json")
                    .readBytes()
            )
        HttpTTS.fromJsonArray(json).getOrElse {
            emptyList()
        }
    }

    val readConfigs: List<ReadBookConfig.Config> by lazy {
        val json = String(
            appCtx.assets.open("defaultData${File.separator}${ReadBookConfig.configFileName}")
                .readBytes()
        )
        GSON.fromJsonArray<ReadBookConfig.Config>(json).getOrNull()
            ?: emptyList()
    }

    val txtTocRules: List<TxtTocRule> by lazy {
        val json = String(
            appCtx.assets.open("defaultData${File.separator}txtTocRule.json")
                .readBytes()
        )
        GSON.fromJsonArray<TxtTocRule>(json).getOrNull() ?: emptyList()
    }

    val themeConfigs: List<ThemeConfig.Config> by lazy {
        val json = String(
            appCtx.assets.open("defaultData${File.separator}${ThemeConfig.configFileName}")
                .readBytes()
        )
        GSON.fromJsonArray<ThemeConfig.Config>(json).getOrNull() ?: emptyList()
    }

    val rssSources: List<RssSource> by lazy {
        val json = String(
            appCtx.assets.open("defaultData${File.separator}rssSources.json")
                .readBytes()
        )
        GSON.fromJsonArray<RssSource>(json).getOrDefault(emptyList())
    }

    val coverRule: BookCover.CoverRule by lazy {
        val json = String(
            appCtx.assets.open("defaultData${File.separator}coverRule.json")
                .readBytes()
        )
        GSON.fromJsonObject<BookCover.CoverRule>(json).getOrThrow()
    }

    val keyboardAssists: List<KeyboardAssist> by lazy {
        val json = String(
            appCtx.assets.open("defaultData${File.separator}keyboardAssists.json")
                .readBytes()
        )
        GSON.fromJsonArray<KeyboardAssist>(json).getOrThrow()
    }

    // 2026-11-01 批次从内置名单剔除的源（大厂/言情/防爬乱文），升级时一并清掉
    // 2026-11-13 追加两批共 11 条：8 条站点不可达/服务端错误 + 3 条搜《凡人修仙传》无相关，删源留档见 legado-booksource/README.md
    private val removedDefaultBookSourceUrls = setOf(
        "https://h5.17k.com",
        "http://appi.kuwo.cn",
        "http://m.yuedu.163.com#yc1101",
        "http://cread.com#",
        "http://zhuishushenqi.com/",
        "http://m.zhizihuan.com/",
        "https://g.hongshu.com/",
        "https://www.00shu.info",
        "http://www.gashuw.com/",
        "https://www.huiysj.com",
        "http://www.gdbzkz.com/",
        "https://m.ajnnan.com##响海",
        "http://www.22pq.com",
        "http://www.gebiqu.com/",
        "https://m.ibiquge.info/",
        "https://www.ijjxsxzw.com",
        "http://m.suixkan.com",
        "https://sma.yueyouxs.com/",
        // 2026-11-13 第三批：搜索彻底不可用（空站 / 结果恒空 / 404 / 搜索锁定 / JS 渲染 / 站点搜索故障）
        "http://www.azycjd.com#yc1101",
        "https://www.biquge.company",
        "https://www.aqxsw123.com",
        "https://www.linovel.net#yc1101",
        "https://www.huanmengacg.com",
        "https://m.libahao.com",
        // 2026-11-13 第四批：搜《凡人修仙传》无正本、bookList 把整页导航 li 当结果（同站镜像两条）
        "http://www.biqun.cc",
        "https://biquge.site",
        // 2026-11-13 第五批：与保留源同站/同书库的重复镜像，5 组各留 1 条
        // 玄幻（5→1，全 301 到 xuanhuange.info，首页 md5 相同）
        "http://www.xuankuks.com#",
        "http://www.xuanyge.net/",
        "http://www.xuanhuange.info#yc1101",
        "http://www.xuanyge.com",
        // 阅读（2→1，均落到 yeudusk.com）
        "http://www.yeudusk.com/",
        // 武道（2→1，umiwx.com 301 到 bookwenxue.com）
        "http://www.umiwx.com",
        // 玩文学系（5→1，同一批 15 本书；冰清 searchUrl 内 ajax m.bqgcq.com）
        "http://www.baowx.com",
        "https://m.bqgcn.com/",
        "http://m.bqgcq.com",
        "http://wap.zwkan.cc",
        // 夜天系（2→1，同一批 30 本书）
        "http://www.rulianshi.cc",
        // 2026-11-15 第六批：与「恩施轻语(代)」同站（规则里写死 esjzone.one），按用户要求删除
        "https://www.esjzone.cc",
        // 2026-11-23 按用户要求内置清单只保留 https://sudugu520.com，
        // 其余 43 条旧内置源全部下架，老设备升级时由 importDefaultBookSources 清除
        "https://www.deqixss.com",
        "https://www.chamabooks.net",
        "https://www.suduguu.com",
        "http://www.xyetianlian.com",
        "http://www.92xs.info",
        "https://www.20xs.org",
        "http://www.changduge.com",
        "http://www.81shuku.info",
        "https://www.sjks88.com",
        "http://m.xxxbiquge.net",
        "https://ixdzs.tw/",
        "https://www.esjzone.one",
        "https://www.shuzhaige.com",
        "http://www.booksky.cc",
        "http://www.wensang.net",
        "http://www.yuedsk.com",
        "http://wap.ujxsw.org/##@鱼",
        "http://www.4020xs.com",
        "https://m.jhsssd.com",
        "https://www.kadokado.cn",
        "https://tybook.taoyuewenhua.net",
        "https://www.dbxsd.com",
        "https://www.tycqzw.la/",
        "http://www.biquw.info/",
        "https://www.douyinxs.com/",
        "http://www.77shuku.net/",
        "https://www.wxscs.com#",
        "http://m.2kk.la#🎃",
        "http://m.5859ycdh.com",
        "https://www.biquge5200.cc",
        "https://www.pinshuzhai.com ",
        "https://www.dcrbk.com#🎃",
        "https://m.qbiqus.com#🎃",
        "https://www.ybswa.com",
        "http://m.xinbqg.com",
        "https://www.biquluo.la",
        "http://www.bookwenxue.com",
        "http://www.xuankuge.com/",
        "http://www.biqugere.net",
        "https://www.biqusan.com/",
        "https://www.ibiquge.la",
        "https://www.wanwx.com",
        "https://www.kushuge.com",
    )

    // 旧内置朗读引擎里 id 为正数的遗留项，deleteDefault() 的 id < 0 条件覆盖不到
    private const val legacyPositiveDefaultHttpTtsId = 1768175007437L

    // 上一批内置书源的 url -> name，用来区分「内置下发的名字」和「用户自己改的名字」
    private var lastDefaultBookSourceNames: Map<String, String>
        get() = GSON.fromJsonObject<HashMap<String, String>>(
            LocalConfig.getString("defaultBookSourceNames", null) ?: "{}"
        ).getOrNull() ?: emptyMap()
        set(value) {
            LocalConfig.putString("defaultBookSourceNames", GSON.toJson(value))
        }

    fun importDefaultBookSources() {
        // 内置包带 builtinId 时走编号同步：按编号更新/新增/下架，完全用内置版覆盖，
        // 用户自己导入的源（无编号、不在基线里）不碰。
        // 老包没有编号时 entries 为空，回退到下面按 URL 的旧逻辑。
        val rawJson = String(
            appCtx.assets.open("defaultData${File.separator}bookSources.json").readBytes()
        )
        val entries = BuiltinSourceSync.parse(rawJson)
        if (entries.isNotEmpty()) {
            BuiltinSourceSync.sync(entries)
            return
        }

        val existing = appDb.bookSourceDao.all
        val existingUrls = existing.map { it.bookSourceUrl }.toSet()
        // 用户在书源管理里删掉的内置源，升级时不再自动导回
        val userRemoved = BuiltinSourceRegistry.removedUrls
        // 增量导入不会删旧源，需显式剔除上一批内置里已下架的
        // 走 SourceHelp 而不是 DAO：裸删只清 book_sources，会残留 sourceVariables 与 SourceConfig
        (existingUrls intersect removedDefaultBookSourceUrls).forEach {
            SourceHelp.deleteBookSource(it)
        }
        val defaultByUrl = bookSources.associateBy { it.bookSourceUrl }
        // 内置源改名后同步到已有书源，只改名字不动 URL
        val lastNames = lastDefaultBookSourceNames
        existing.forEach { source ->
            if (source.bookSourceUrl in userRemoved) return@forEach
            val defaultName = defaultByUrl[source.bookSourceUrl]?.bookSourceName
            if (defaultName != null && defaultName != source.bookSourceName) {
                // 只在现名仍是内置下发过的名字时覆盖，用户自改的名字保留。
                // 首次带基线升级时基线为空，此时把「去结尾括号后等于新内置名」也视作内置名，
                // 这样老设备上带 (主机名) 后缀的旧名称才能被纠正
                val stillDefaultName = source.bookSourceName == lastNames[source.bookSourceUrl] ||
                    (lastNames.isEmpty() && source.bookSourceName
                        .replace(Regex("""[（(][^）)]*[）)]\s*$"""), "")
                        .trim() == defaultName)
                if (stillDefaultName) {
                    appDb.bookSourceDao.updateSources(source.copy(bookSourceName = defaultName))
                }
            }
        }
        lastDefaultBookSourceNames =
            bookSources.associate { it.bookSourceUrl to it.bookSourceName }
        val toInsert = bookSources.filter {
            it.bookSourceUrl !in existingUrls && it.bookSourceUrl !in userRemoved
        }
        if (toInsert.isNotEmpty()) {
            appDb.bookSourceDao.insert(*toInsert.toTypedArray())
        }
    }

    fun importDefaultHttpTTS() {
        appDb.httpTTSDao.all
            .filter { it.id < 0 || it.id == legacyPositiveDefaultHttpTtsId }
            .forEach { it.clearSharedGlobalState() }
        appDb.httpTTSDao.deleteDefault()
        appDb.httpTTSDao.get(legacyPositiveDefaultHttpTtsId)?.let {
            appDb.httpTTSDao.delete(it)
        }
        appDb.httpTTSDao.insert(*httpTTS.toTypedArray())
    }

    fun importDefaultTocRules() {
        appDb.txtTocRuleDao.deleteDefault()
        appDb.txtTocRuleDao.insert(*txtTocRules.toTypedArray())
    }

    fun importDefaultRssSources() {
        appDb.rssSourceDao.all
            .filter { it.sourceGroup == "legado" }
            .forEach { it.clearSharedGlobalState() }
        appDb.rssSourceDao.deleteDefault()
        appDb.rssSourceDao.insert(*rssSources.toTypedArray())
    }

}
