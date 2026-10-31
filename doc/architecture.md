# 程序架构与实现

本文件覆盖程序底座：总体架构、数据层、后台服务与对外接口、构建发布。书源引擎与阅读器分别见 [booksource.md](booksource.md) 与 [reading.md](reading.md)。

## 目录

- [第一部分　总体架构](#第一部分总体架构)
- [第二部分　数据层与持久化](#第二部分数据层与持久化)
- [第三部分　后台服务、辅助模块与对外接口](#第三部分后台服务辅助模块与对外接口)
- [第四部分　构建与发布](#第四部分构建与发布)


## 第一部分　总体架构

> 本文取证基准：提交 `234543e1f`（2026-10-24，精简版首个提交）。行号只作导航，引用请以「文件 + 符号名」为准。
> 本仓库是 [LegadoTeam/legado](https://github.com/LegadoTeam/legado) 的自用精简版：已移除 MCP 服务、Firebase、PDF 书签目录、视频播放（含弹幕）；2026-10-25 又移除词典划词、局域网备份传输、漫画阅读（书源与替换净化相关的代码编辑器、键盘辅助、二维码、自动任务按用户要求保留）。`bookSourceType` 的音频/文件/视频类型标志与 media3 音频播放是**刻意保留**的，缺失的模块不代表 bug。

### 1. 项目定位与技术栈

一个 Android 本地/网络小说阅读器。核心能力：可编程书源（HTML/XPath/JSON/JS 规则）、RSS 订阅、离线缓存、TTS 听书、WebDAV 同步、本地 TXT/EPUB/UMD/PDF 阅读。

| 项 | 值 | 依据 |
|---|---|---|
| 语言 | Kotlin 为主（app 内 994 个 `.kt`、10 个 `.java`） | `find` 统计 |
| 构建 | Gradle + AGP 8.13.2 / Kotlin 2.4.10 / KSP 2.3.11 | `gradle/libs.versions.toml:3-5` |
| SDK | compileSdk 36 / targetSdk 36 / minSdk 23（debug 变体 26） | `build.gradle:5`、`app/build.gradle:41,65-66,207-213` |
| Java | JVM toolchain 21 + `coreLibraryDesugaring`（`desugar_jdk_libs_nio`） | `app/build.gradle:43-47,157-163` |
| UI | **XML + ViewBinding**（233 个布局，50 个 Activity / 19 个 Fragment），无 Compose、无 Navigation、无 DI | `app/build.gradle:104-106` |
| 数据库 | Room 2.8.4，版本 111，26 表 + 1 视图 | `data/AppDatabase.kt:79-89` |
| HTTP | OkHttp 5.4.0（主通道）+ Cronet 152（可选通道） | `help/http/HttpHelper.kt:50`、`lib/cronet/` |
| HTML/规则解析 | jsoup 1.23.2、JsoupXpath 2.5.3、json-path 3.0.0 | `app/build.gradle` 依赖段 |
| JS 引擎 | HtmlUnit core-js `5.3.0-legado.4`（Rhino 派生），封装在 `:modules:rhino` | `gradle/libs.versions.toml:58-59`、`modules/rhino/` |
| 音频 | media3 1.10.1（仅 exoplayer + datasource-okhttp） | `gradle/libs.versions.toml` |
| 图片 | Glide 5.0.7 | 同上 |

### 2. 模块划分

`settings.gradle` 只包含三个 Gradle 模块：

| 模块 | 类型 | 职责 |
|---|---|---|
| `:app` | Android application | 全部业务代码（`io.legado.app`） |
| `:modules:book` | Android library（`me.ag2s`） | EPUB 读写（epublib）与 UMD 解析（umdlib）两个纯 Java 库的 Android 化，供本地书解析与 EPUB 导出使用 |
| `:modules:rhino` | Android library（`com.script`） | JS 引擎封装：`RhinoScriptEngine`、`ScriptBindings`、`RhinoContext`、`RhinoClassShutter` 等；以 `api` 暴露 HtmlUnit core-js |

注意：`modules/web/`（Vue 前端）**不在** Gradle include 列表里，需 `pnpm build`，构建末尾会自动执行 `modules/web/scripts/sync.js`，把产物同步到 `app/src/main/assets/web/vue`。

### 3. 分层与依赖方向

项目没有 Repository 层、没有 Hilt/Koin/Dagger。实际分层是：

```
UI (Activity/Fragment/Dialog)
  ↓ 观察 LiveData / Flow，或直接回调
ViewModel（`by viewModels<T>()`）
  ↓ 直接调用
全局模型 / 引擎（object WebBook、object ReadBook、object AudioPlay、model/*）
  ↓ 直接调用
数据访问（全局单例 `appDb` 的 DAO） + 缓存（CacheManager / BookHelp / SharedJsScope）
  ↓
网络（AnalyzeUrl → OkHttp/Cronet/WebView）与文件（book_cache、shareJs、ACache）
```

依赖约束（现状，不是规范理想）：

- **允许**：任意层直接引用 `appDb`、`AppConfig`、`WebBook`、`CacheManager`（181 个文件直接引用 `appDb`）。
- **不要**新增 Repository 抽象层去「统一」这些调用——与现有代码风格冲突，且 `allowMainThreadQueries()` 已经放开了主线程访问。
- 引擎层（`model/`、`model/webBook/`、`model/analyzeRule/`）不应反向依赖 `ui/`；`help/` 可被任意层使用。

### 4. app 包职责

| 包 | 职责 | 代表符号 |
|---|---|---|
| `api/` | 对外 ContentProvider 与路由 | `ReaderProvider`、`ReaderProviderRoutes`、`ReturnData` |
| `base/` | Activity/Fragment/ViewModel/Service/Dialog 基类 | `BaseActivity`、`VMBaseFragment`、`BaseViewModel`、`BaseService`、`AppContextWrapper` |
| `constant/` | 全局常量 | `AppConst`、`PageAnim`、`AppPattern`、`NotificationId`、`PreferKey` |
| `data/` | Room 数据库 | `AppDatabase`、`DatabaseMigrations`、`entities/`、`dao/` |
| `exception/` | 自定义异常 | 如 `NoStackTraceException`、`CronetUnavailableException` |
| `help/` | 横切帮助类（无 UI） | `CacheManager`、`ConcurrentRateLimiter`、`JsExtensions`、`book/BookHelp`、`http/*`、`source/*`、`storage/*`（备份/WebDAV）、`config/*` |
| `lib/` | 内嵌第三方能力 | `cronet/`（Cronet 加载与拦截器）、`webdav/`、`prefs/`、`theme/`、`code/`（编辑器） |
| `model/` | 全局状态与业务模型 | `ReadBook`、`AudioPlay`、`ReadAloud`、`CacheBook`、`CheckSource`、`SearchModel`、`webBook/*`、`analyzeRule/*`、`localBook/*`、`jsSource/*` |
| `receiver/` | 广播接收器（多为动态注册） | `MediaButtonReceiver`、`NetworkChangedListener`、`TimeBatteryReceiver` |
| `service/` | 前台服务 | `TTSReadAloudService`、`HttpReadAloudService`、`AudioPlayService`、`CacheBookService`、`ExportBookService`、`CheckSourceService`、`DownloadService`、`WebService`、`AutoTaskJobService` 等 12 个 |
| `ui/` | 全部界面 | 见 [reading.md](reading.md) |
| `utils/` | 无状态工具 | `NetworkUtils`、`FileUtils`、`ACache`、`GSON`、`HtmlFormatter`、`EncodingDetect`、`ContextExtensions` |
| `web/` | 内置 HTTP/WebSocket 服务实现 | `HttpServer`、`WebSocketServer` |

### 5. 启动流程

入口链路：`WelcomeActivity` →（可选）`ReadBookActivity` → `MainActivity`。清单里有 7 个 Activity 带 `MAIN/LAUNCHER`：`WelcomeActivity` 加上 `Launcher1..6`，后者是 `android:enabled="false"` 的**图标别名**（用于换启动图标），默认不可启动。

`App : Application`（`app/src/main/java/io/legado/app/App.kt:71`）：

1. `attachBaseContext` 经 `AppContextWrapper.wrap` 覆盖语言（zh/tw/en）与字体缩放（0.8–1.6）。
2. `onCreate` **同步**执行：`CrashHandler` 安装 → 记录旧配置、监听系统夜间 → `WallpaperTheme.syncWithPreferences` → `ThemeConfig.applyDayNightInit` → `LifecycleHelp` 注册 → `AppConfig` 注册为 SharedPreferences 监听 → `initRhino()`（注册 `NativeBaseSource` 等 Rhino 可见类）。
3. 其余初始化丢进 `Coroutine.async` **异步**顺序执行：日志/设备信息 → 可选 Cronet 预下载 → 3 个通知渠道 → `LiveEventBus` 配置 → `DefaultData.upVersion` → 帧监控 → `ObsoleteUrlFactory` 接管 `URL` →（另起并行 launch：API<29 且设备有 GMS 时安装 Conscrypt/TLS provider）→ 封面预初始化（`BookCover.toString()`）→ 清过期缓存/搜索记录 → `BookHelp.clearInvalidCache` → 备份缓存清理 → `ReadBookConfig.clearBgAndCache` → 简繁转换预载 → 书源排序号校正 → 自动任务装载与调度 → 可选全量下载阅读进度。

启动清理与 UI 首帧并行，因此自动化测试要注意时序。

### 6. 并发模型

| 场景 | 机制 | 依据 |
|---|---|---|
| 全应用异步 | Kotlin 协程，`Coroutine.async` 包装 `Dispatchers.IO` | `App.kt` |
| 搜索（多源） | 固定线程池 `min(threadCount, AppConst.MAX_THREAD=9)`，单源 30s `withTimeout` | `model/webBook/SearchModel.kt:53,120` |
| 换源（多源） | `mapParallel(threadCount)` + 单源 60s `withTimeout` | `ui/book/changesource/ChangeBookSourceViewModel.kt:337-350` |
| 目录/正文多页 | `mapAsync(AppConfig.threadCount)` 并发抓分页 | `model/webBook/BookChapterList.kt:94-119`、`BookContent.kt:106-130` |
| 书源限流 | `ConcurrentRateLimiter` 按书源 `concurrentRate`（`n` 或 `n/毫秒`）；协程内 `delay`，JS 内 `Thread.sleep` | `help/ConcurrentRateLimiter.kt:16-46,55-98,103-121` |
| 取消传播 | `Call.await` 在协程取消时 `call.cancel()`；Rhino 执行期检查协程状态 | `help/http/OkHttpUtils.kt:62-78`、`modules/rhino/.../RhinoScriptEngine.kt:72-75` |
| 前台服务 | `BaseService` 统一 `startForegroundNotification` 与异常降级 | `base/BaseService.kt:26,52-64,84-89` |

### 7. 全局单例清单（改代码前必知）

| 单例 | 作用 | 风险 |
|---|---|---|
| `appDb` | 全应用唯一 Room 实例，`allowMainThreadQueries()` | 主线程查询不会崩，但会卡 UI；写操作别放主线程 |
| `ReadBook` | 阅读会话状态（书、章节、进度、缓存） | 跨 Activity 共享；进度有 `ReadPositionVersion` 竞态防护 |
| `AudioPlay` | 音频播放状态 | 与 `AudioPlayService` 强耦合 |
| `AppConfig` | SharedPreferences 门面，多为初始化时读入的 `var` | 只有部分 key 在 `onSharedPreferenceChanged` 里同步，新增设置项必须补监听分支 |
| `CacheManager` / `AppCacheManager` | KV 缓存（内存 LRU 50MB + `caches` 表 + ACache） | `deadline=0` 表示永久并常驻内存 |
| `WebBook`（object） | 书源四阶段入口 | 静态调用，无状态、可直接用 |

### 8. 核心路径速查

| 想知道 | 去哪里 |
|---|---|
| 书源一次请求怎么发出、怎么解析 | [booksource.md](booksource.md) 第五部分 |
| 数据库表/迁移/缓存存储 | 本文第二部分 |
| 阅读器分页、翻页、菜单、设置 | [reading.md](reading.md) |
| 听书/朗读/音频播放 | [listening.md](listening.md) |
| 后台服务、备份、WebDAV、对外 API | 本文第三部分 |
| 构建、签名、Cronet、版本号 | 本文第四部分 |
| 书源字段、规则语法与 JS API | [booksource.md](booksource.md) 第一至第三部分 |



## 第二部分　数据层与持久化

> 取证基准：提交 `234543e1f`，数据库版本 111。行号会漂移，以「文件 + 符号名」为准。

### 0. 先明确两个现状

1. **没有 Repository 层，也没有依赖注入**。全仓库检索 `Repository`、`@Inject`、Hilt/Koin/Dagger 均无命中。数据访问模式是：全局单例 `appDb` 的 DAO 被 ViewModel / Helper 直接调用。
2. 全局 `appDb` 位于 `data/AppDatabase.kt:70`（顶层 `lazy`），被 181 个 `.kt` 文件直接引用；构建时显式 `allowMainThreadQueries()`（`:74`）。

### 1. 数据库总览

| 项 | 值 | 位置 |
|---|---|---|
| 类 | `AppDatabase : RoomDatabase()` | `data/AppDatabase.kt:154` |
| 文件 | `legado.db`（Room 默认目录 `databases/`） | `AppDatabase.kt:71,184` |
| 版本 | `version = 111`，`exportSchema = true` | `AppDatabase.kt:79-80` |
| schema 导出 | `app/schemas/io.legado.app.data.AppDatabase/*.json`（1–104、106–111，**缺 105**） | `app/build.gradle:99` |
| 表 | 26 张表 + 1 个视图 | `AppDatabase.kt:82-89`、`schemas/.../111.json` |
| 打开策略 | `fallbackToDestructiveMigrationFrom(false, 1..9)` + 手写迁移 + 自动迁移 + Callback | `AppDatabase.kt:70-77` |
| 全文检索 | **无 FTS**。章节/书源/搜索均为 `LIKE '%'||:key||'%'` | `dao/BookChapterDao.kt:13`、`dao/BookDao.kt:101` |
| SQL | DAO 全部 `@Query` 原生 SQL；迁移用 `db.execSQL` | `data/DatabaseMigrations.kt` |

### 2. 实体与表

#### 2.1 书籍与阅读

| 实体 | 表 | 关键字段 | 说明 |
|---|---|---|---|
| `Book` | `books` | `bookUrl`(PK)、`name`、`author`、`origin`、`type`、`group`、`durChapterIndex/Pos/Time`、`totalChapterNum`、`readConfig`、`tocUrl`、`infoHtml`、`tocHtml`、`syncTime` | 书架书籍 + 阅读进度；`type` 是位掩码（文本/音频/本地/网络等），`group` 是分组位掩码 |
| `BookGroup` | `book_groups` | `groupId`(PK)、`groupName`、`order`、`show`、`enableRefresh`、`bookSort`、`onlyUpdateRead` | 负 ID 为内置分组（见 §6） |
| `BookChapter` | `chapters` | `url`+`bookUrl`(复合 PK)、`index`、`title`、`isVolume/isVip/isPay`、`start/end`、`resourceUrl`、`variable`、`imgUrl` | 外键 `bookUrl → books.bookUrl ON DELETE CASCADE` |
| `Bookmark` | `bookmarks` | `time`(PK)、`bookName`、`bookAuthor`、`chapterIndex/Pos/Name`、`bookText`、`content` | 全文书签 |
| `BookHighlight` | `highlights` | `time`(PK)、`bookUrl`、`chapterUrl`、`chapterIndex/Pos/PosEnd`、`bookText`、`style`、`note` | 正文高亮/批注 |
| `BookMemo` | `book_memos` | `bookUrl`(PK)、`content`、`updatedAt` | 每本书一条备忘录，无外键；列表查询 `inner join books` 过滤已删书 |
| `ReadRecord` | `readRecord` | `deviceId`+`bookName`+`author`(复合 PK)、`readTime`、`lastRead`、`lastChapterTitle/Index/Pos`、`coverUrl`、`resolvedAuthor` | 跨设备阅读记录；快照索引 `(bookName, author, lastRead DESC, deviceId, resolvedAuthor)` |
| `SearchBook` | `searchBooks` | `bookUrl`(PK)、`name`、`author`、`origin`、`tocUrl`、`time`、`originOrder` | 搜索结果缓存；外键 `origin → book_sources.bookSourceUrl CASCADE` |
| `SearchKeyword` | `search_keywords` | `word`(PK)、`usage`、`lastUseTime` | 搜索历史 |

#### 2.2 书源与规则

| 实体 | 表 | 关键字段 | 说明 |
|---|---|---|---|
| `BookSource` | `book_sources` | `bookSourceUrl`(PK)、`bookSourceName/Group/Type`、`enabled`、`enabledExplore`、`searchUrl/exploreUrl`、`ruleSearch/ruleExplore/ruleBookInfo/ruleToc/ruleContent` | 四个 rule 字段是 JSON（TypeConverter），映射到 `Rule*` 对象 |
| `BookSourceCheckState` | `book_source_check_states` | `bookSourceUrl`(PK/FK)、`revision`、`sourceRevision`、`status`(NEEDS_CHECK/PASSED/FAILED)、`checkedAt`、`detail` | 校验状态；外键级联删除；无独立 DAO，由 `BookSourceDao` 管理 |
| `ReplaceRule` | `replace_rules` | `id`(PK 自增)、`name`、`group`、`pattern`、`replacement`、`scope*`、`isEnabled`、`isRegex`、`timeoutMillisecond`、`sortOrder` | 正文/标题替换净化 |
| `HighlightRule` | `highlightRules` | `id`(PK 自增)、`uuid`(唯一)、`pattern`、`isRegex`、`scope`、`style`、`applyToTitle/Body`、`group` | 高亮规则 |
| `RuleSub` | `ruleSubs` | `id`(PK 自增)、`name`、`url`、`type`、`autoUpdate`、`updateInterval`、`js`、`showRule`、`sourceUrl` | 规则订阅 |
| `DictRule` | `dictRules` | `name`(PK)、`urlRule`、`showRule`、`enabled`、`sortNumber` | 词典规则；**划词功能已于 2026-10-25 移除，表与实体保留**（备份/恢复仍可读写） |
| `TxtTocRule` | `txtTocRules` | `id`(PK)、`name`、`rule`、`replacement`、`serialNumber`、`enable` | TXT 目录识别规则 |
| `KeyboardAssist` | `keyboardAssists` | `type`+`key`(复合 PK)、`value`、`serialNo` | 键盘快捷输入 |

#### 2.3 RSS

| 实体 | 表 | 关键字段 | 说明 |
|---|---|---|---|
| `RssSource` | `rssSources` | `sourceUrl`(PK)、`sourceName/Group`、`enabled`、`sortUrl`、`singleUrl`、`articleStyle`、`rule*` | RSS 源 |
| `RssArticle` | `rssArticles` | `origin`+`link`+`sort`(复合 PK)、`title`、`order`、`read`、`type`、`durPos` | 文章缓存 |
| `RssReadRecord` | `rssReadRecords` | `record`(PK)、`origin`、`sort`、`read`、`readTime`、`durPos` | 阅读记录 |
| `RssStar` | `rssStars` | `origin`+`link`(复合 PK)、`group`、`starTime`、`title` | 收藏 |

#### 2.4 系统与缓存

| 实体 | 表 | 关键字段 | 说明 |
|---|---|---|---|
| `Cache` | `caches` | `key`(PK)、`value`、`deadline` | KV 磁盘缓存；`deadline=0` 永久且同时进内存 |
| `Cookie` | `cookies` | `url`(PK)、`cookie` | 持久 Cookie |
| `Server` | `servers` | `id`(PK)、`name`、`type`、`config`(JSON)、`sortNumber` | WebDAV/远程备份服务器 |
| `HttpTTS` | `httpTTS` | `id`(PK)、`name`、`url`、`contentType`、`concurrentRate`、`loginUrl`、`header`、`jsLib` | 在线 TTS 配置 |
| `AutoTaskRule` | `auto_task_rules` | `id`(PK)、`name`、`enable`、`cron`、`script`、`jsLib`、`customOrder`、`lastRunAt/Result/Log` | 定时任务 |

#### 2.5 视图

`BookSourcePart` → `book_sources_part`（`data/entities/BookSourcePart.kt:11-26`）：书源表的轻量投影，左连接校验状态，`coalesce(c.status,'NEEDS_CHECK')`。书源列表基本都查这个视图（`BookSourceDao.flowAll`）。

### 3. DAO 清单（25 个）

| DAO | 关键方法 | 文件 |
|---|---|---|
| `BookDao` | `flowByGroup`、`flowAll`、`flowBookshelfBooks`、`getBook(bookUrl)`、`getBook(name,author)`、`insert/update/deleteRows` | `dao/BookDao.kt` |
| `BookChapterDao` | `search`(LIKE)、`getChapterList`、`getChapter(bookUrl,index)`、`insert`、`delByBook`、`updateContentMetadata` | `dao/BookChapterDao.kt` |
| `BookSourceDao` | `flowAll`(视图)、`getBookSource`、`insert/update`(含校验状态事务)、`saveCheckStates`、`finishCheck`、`delete`、`allGroups` | `dao/BookSourceDao.kt` |
| `BookGroupDao` | `getByID`、`flowAll`、`flowSelect`、`insert`、`getUnusedId`、`isInRules` | `dao/BookGroupDao.kt` |
| `BookmarkDao` / `BookHighlightDao` / `BookMemoDao` | 按书查询 Flow、搜索、增删 | `dao/` 同名文件 |
| `CacheDao` | `get(key[,now])`、`insert`、`delete`、`clearDeadline`、`getRuntimeSourceCaches` | `dao/CacheDao.kt` |
| `CookieDao` | `get(url)`、`getOkHttpCookies`、`insert`、`delete` | `dao/CookieDao.kt` |
| `ReadRecordDao` | `flowBooks`、`allShow`(按书名+作者汇总)、`search`、`getRecord`、`insert` | `dao/ReadRecordDao.kt` |
| `ReplaceRuleDao` | `flowEnabled`、`findEnabledByContentScope/TitleScope/SourceScope`、`enableAll` | `dao/ReplaceRuleDao.kt` |
| `HighlightRuleDao` | `flowAll`、`findEnabledByBook`、`importRules`、`replaceAll`、`move` | `dao/HighlightRuleDao.kt` |
| `RssArticleDao` | `flowByOriginSort`、`append`(IGNORE)、`clearOld`、`updateOrigin` | `dao/RssArticleDao.kt` |
| `RssReadRecordDao` / `RssStarDao` / `RssSourceDao` | RSS 阅读记录/收藏/源管理 | `dao/Rss*.kt` |
| `SearchBookDao` | `changeSourceByGroup`、`insert`(REPLACE)、`clearExpired` | `dao/SearchBookDao.kt` |
| `SearchKeywordDao` | `flowByUsage`、`flowByTime`、`insert`、`deleteAll` | `dao/SearchKeywordDao.kt` |
| `ServerDao` / `HttpTTSDao` / `DictRuleDao`（仅供数据与备份保留） / `RuleSubDao` / `TxtTocRuleDao` / `KeyboardAssistsDao` / `AutoTaskRuleDao` | 各自域的 CRUD/Flow | `dao/` 同名文件 |

### 4. 迁移与升级

- **自动迁移 61 条**：`@Database(autoMigrations = [...])`，覆盖 43→104（100→101 除外）与 110→111（`AppDatabase.kt:90-152`）。
- **手写迁移 39 条**：`DatabaseMigrations.migrations`（`DatabaseMigrations.kt:16-29`），含 10→43 的 32 条、100→101、104→110 的 7 条。
  - 1–9：直接重建（`fallbackToDestructiveMigrationFrom`）。
  - 15→17 合并为一条（`:170`）；100→101 给 `highlightRules` 加 `uuid` 并建唯一索引（`:116`）；106→107 重建 `readRecord` 把 `author` 并入主键（`:53`）。
  - 104→105 手写；schema 105 未提交导出（`:72-81` 注释说明），仓库里**缺 105.json** 属已知状态，不是遗漏错误。
- **AutoMigrationSpec 钩子 8 个**（`DatabaseMigrations.kt:425-588`）：`Migration_54_55`（书籍 type 位迁移）、`64_65`、`80_81`（重建 rssArticles 主键）、`83_84`、`84_85`、`96_97`（回填高亮 bookUrl/chapterUrl）、`98_99`（补全阅读记录作者）、`101_102`（旧封面路径）。
- **Callback**（`AppDatabase.kt:191-293`）：
  - `onCreate`：API≥23 设置 `db.setLocale(Locale.CHINESE)`。
  - `onOpen`（每次打开）：补齐 7 个内置分组、修正 `loginUi='null'`、`httpTTS.concurrentRate` 空值置 `'0'`、空 `keyboardAssists` 灌默认数据。

新增表/字段时：改实体 → 升版本 → 写 `app/schemas` 里新版本 JSON（构建自动生成）→ 在 `autoMigrations` 或手写 `Migration` 注册。

### 5. 缓存体系

| 缓存 | 位置 | 介质 | 策略 |
|---|---|---|---|
| KV 缓存 `CacheManager` | `help/CacheManager.kt:53` | 内存 LRU（50MB）+ `caches` 表 + `ACache` 文件 | `deadline=0` 永久并常驻内存；有 deadline 只落库并按秒校验过期 |
| 源变量/QueryTTF `AppCacheManager` | `help/CacheManager.kt:25` | 内存 | QueryTTF 4 项 LRU；可批量清源变量 |
| 正文/图片缓存 `BookHelp` | `help/book/BookHelp.kt:128` | `externalFiles/book_cache/` | 文件 `%05d-<titleMD5>.nb`；`contentSaveToken` + `ContentSaveFence` 版本栅栏防旧响应覆盖新缓存；删除正文时留 `.reversed` 标记 |
| JS 作用域缓存 `SharedJsScope` | `model/SharedJsScope.kt` | 内存强/弱引用 + 磁盘 | jsLib 的 MD5 为 key，LRU 16 弱引用；远程 jsLib 存 `cacheDir/shareJs`；CryptoJS 按 owner+线程缓存 |
| 详情/目录 HTML `Book.infoHtml/tocHtml` | `books` 表 | Room 字段 | 搜索命中详情页时写入，详情/目录/正文解析优先复用 |
| 音频缓存 `AudioCacheManager` | `help/audio/` | 用户选择的 SAF 目录 | 不支持 HLS 与多段音频 |
| HTTP 缓存 | Cronet 自带 50MB 磁盘缓存 | `lib/cronet/CronetHelper.kt` | OkHttp 通道**没有**配置 `Cache`，且每请求加 `Cache-Control: no-cache`（`help/http/HttpHelper.kt:80`） |

### 6. 结构约束与坑

1. **只有 3 处外键级联**：`chapters → books`、`searchBooks → book_sources`、`book_source_check_states → book_sources`。书签、高亮、阅读记录、RSS 文章/收藏、备忘录**无外键**——删书/删源不会自动清理，必须走 `SourceHelp.deleteBookSource` 等编排方法。
2. **书籍分组是位掩码**：`books.group` 与 `BookGroup.groupId` 按位与匹配（`BookDao.kt:98`、`BookGroupDao.kt:79-80`）。内置分组（`BookGroup.kt:31-40`）：全部 `-1`、本地 `-2`、音频 `-3`、网络未分组 `-4`、本地未分组 `-5`、视频 `-6`、更新失败 `-11`，另有根分组 `-100`。`onOpen` 每次补齐的是前 7 个（不含 `-100`）。
3. **复合主键**：`chapters(url,bookUrl)`、`rssArticles(origin,link,sort)`、`rssStars(origin,link)`、`readRecord(deviceId,bookName,author)`、`keyboardAssists(type,key)`。拼 SQL 时漏一个键就会命中多行。
4. **阅读记录双作者**：历史数据 `author` 可能为空，展示/聚合时用 `author='' ? resolvedAuthor : author`（`ReadRecordDao.kt:18-38`）；复合主键仍含 `author`。
5. **无全文索引**：所有搜索是 `LIKE '%key%'`，大库（章节数十万）时是主要性能瓶颈；不要在这个基础上再叠加每键一查的循环。
6. **主线程查询是放开的**：`allowMainThreadQueries()` 让主线程查询不崩，但书架/目录页仍应使用 Flow/LiveData，避免卡顿。
7. **`BookChapterReview` 不是实体**（`entities/BookChapterReview.kt` 无 `@Entity`），不在表清单里。
8. `book_memos` 一本书一条（PK=bookUrl），删书后靠 join 隐式过滤，不会自动删除。



## 第三部分　后台服务、辅助模块与对外接口

> 取证基准：提交 `234543e1f`。行号会漂移，以「文件 + 符号名」为准。

### 1. 前台/后台服务（清单注册 11 个 + 1 个抽象基类）

> 朗读（101）与音频播放（102）的完整链路见 [listening.md](listening.md)。

AndroidManifest 注册 11 个服务；表中 `BaseReadAloudService` 是抽象基类，**不在清单里**。

| 服务 | 作用 | 启动与通知 |
|---|---|---|
| `AudioPlayService` | 音频书播放：ExoPlayer + MediaSession + 音频焦点 + WakeLock | `AudioPlay.play()` `startService`；`startForeground(102)` |
| `BaseReadAloudService`（abstract） | 朗读基类：分页取文、音频焦点、通知按钮、定时/章数停止 | 子类启动；`startForeground(101)` |
| `TTSReadAloudService` | 系统 TTS 朗读 | `ReadAloud.play()`；ttsEngine 为空时选它 |
| `HttpReadAloudService` | 在线 TTS（`HttpTTS`），ExoPlayer + 128MB SimpleCache | ttsEngine 命中 `httpTTSDao` 时选它 |
| `CacheBookService` | 离线缓存章节，线程池 `AppConfig.threadCount`（上限 9） | `CacheBook.start()`；`startForeground(103)` |
| `ExportBookService` | 导出 txt / epub / pdf，带进度通知 | `CacheActivity` 启动；`startForeground(104)` |
| `CheckSourceService` | 批量校验书源 | `CheckSource.start()`；`startForeground(107)` |
| `DownloadService` | 文件/APK 下载（委托系统 `DownloadManager`，隐藏其通知，落到公共 Downloads） | `Download.start()`；`startForeground(106)` |
| `AudioCacheService` | 音频章节缓存到 SAF 目录 | 静态 `start()`；`startForeground(109)` |
| `WebService` | 内置 HTTP(1122) + WebSocket(1123) 服务，可选 WakeLock/WiFiLock | `start()`/`startForeground()`；`startForeground(105)` |
| `WebTileService` | Web 服务快捷开关（QS Tile） | 系统绑定 `TileService` |
| `AutoTaskJobService` | 定时任务执行（JobScheduler，`setPersisted(true)`，jobId 两个） | `AutoTaskScheduler` 调度 |

**`BaseService` 统一行为**（`base/BaseService.kt:26`）：

- `onStartCommand` 首次尝试 `startForegroundNotification()`；被系统以 `ForegroundServiceStartNotAllowedException` 拒绝时 `stopSelfResult(startId)` 并返回 `START_NOT_STICKY`；`onTimeout` 直接 `stopSelf`。
- `onCreate` 申请 `POST_NOTIFICATIONS`，SDK≥S 还申请忽略电池优化（同进程只弹一次理由）。
- 启动入口统一走 `Context.startForegroundServiceCompat`：**先 `startService`，仅 `IllegalStateException` 时降级 `startForegroundService`**（`utils/ContextExtensions.kt:150-156`）。

通知 ID 的唯一分配表在 `constant/NotificationId.kt`（101–109 服务、10000 下载、201 导出、30000/50000 自动任务）——**新增服务必须避开，注释明确写了「通知ID不能重复」**。

### 2. 核心后台流程

| 流程 | 入口 | 关键点 |
|---|---|---|
| 离线缓存正文 | `CacheBook.start()` → `CacheBookService` | 先查 `BookHelp.getContent` 再请求网络；`ChapterDownloadState` 管状态；支持暂停/恢复 |
| 批量校验书源 | `CheckSource.start()` → `CheckSourceService` | 结果写 `book_source_check_states`（`BookSourceDao.saveCheckStates/finishCheck`） |
| 导出书籍 | `CacheActivity` → `ExportBookService` | 三种格式；PDF 用 `android.graphics.pdf.PdfDocument` 逐页排版；本地 PDF 书不参与导出 |
| 系统下载 | `Download.start()` → `DownloadService` | 无存储权限会抛 `SecurityException` 并提示 |
| 内置 Web 服务 | `WebService` | HTTP 1122 / WS 1123；令牌 `X-Legado-Token`，WS 子协议 `legado.token.*` |
| 自动任务 | `AutoTask.all()` → `AutoTaskScheduler` → `AutoTaskJobService` | `auto_task_rules` 存 cron 与脚本；执行结果回写 `lastRunAt/Result/Log` |

### 3. 子模块

#### 3.1 `:modules:book`（namespace `me.ag2s`）

两个纯 Java 第三方库的 Android 化：

- **epublib**：EPUB 读写，`EpubReader.readEpub*`、`EpubWriter.write`、`AndroidZipFile`。
- **umdlib**：UMD 电子书解析，`UmdReader.read`、`UmdUtils` 编解码。

调用方：`model/localBook/EpubFile.kt`、`UmdFile.kt`（解析），`service/ExportBookService.kt`（导出 EPUB）。

#### 3.2 `:modules:rhino`（namespace `com.script`）

JS 引擎封装，见 [booksource.md](booksource.md) 第五部分。对外符号：`RhinoScriptEngine`（`eval`/`evalSuspend`/`compile`/`getRuntimeScope`）、`ScriptBindings`、`ScriptException`、`RhinoContext`、`RhinoWrapFactory`、`ReadOnlyJavaObject`、`RhinoClassShutter`。

- 依赖 `org.htmlunit:htmlunit-core-js:5.3.0-legado.4`，从仓库内 `third_party/maven` 解析（`settings.gradle:37-41` 只放行该 group）。
- 独立 `consumer-rules.pro` 保留 `org.htmlunit.corejs.javascript.**`。
- 装配在 `App.initRhino()`：注册 `NativeBaseSource`（BookSource/RssSource/HttpTTS）与 `ReadOnlyJavaObject`。
- `modules/rhino/lib/rhino-1.7.14.jar` 是未被引用的遗留物（依赖行已注释）。

### 4. 备份、恢复与同步

| 类 | 职责 |
|---|---|
| `Backup`（`help/storage/Backup.kt:108`） | 备份 zip：书架/书源/规则/设置/封面/背景/Cookie/源变量；自动备份；WebDAV 上传 |
| `Restore`（`help/storage/Restore.kt:145`） | 解包恢复；恢复前快照、互斥锁 |
| `BackupConfig`（`:14`） | 备份内容项开关、忽略项、Cookie/源变量文件名 |
| `BackupAES`（`:6`） | 备份口令加密 |
| `ImportOldData`（`help/storage/ImportOldData.kt:23`） | 导入旧版 `myBookShelf.json` / `myBookSource.json` |
| `AppWebDav`（`help/AppWebDav.kt:41`） | WebDAV：建目录、备份列表/下载恢复、书籍导出、阅读进度上传/全量下载、背景图 |

注意：`AppWebDav` 对象初始化里 `runBlocking { upConfig() }` 会走网络，源码注释明确「不要放到主线程」。

书源/订阅源的增删启停统一走 `SourceHelp`（`help/source/SourceHelp.kt:22`）：事务内删源、清源变量、校正排序号（`adjustSortNumber`）。分享口令编解码在 `SourceSharePassphrase`。

### 5. 对外接口

#### 5.1 ContentProvider

- 类：`io.legado.app.api.ReaderProvider`，authority `${applicationId}.readerProvider`（本 fork `applicationId = com.legado.app`，release 为 `com.legado.app.release.readerProvider`）。
- 清单里 `exported="true"`，**没有 read/write permission**（`api.md` 里提到的 `io.legado.READ_WRITE` 在本仓库不存在）。
- 路由（`api/ReaderProviderRoutes.kt:30-47`）：

| 方法 | 路由 |
|---|---|
| insert | `bookSource/insert`、`bookSources/insert`、`rssSource/insert`、`rssSources/insert`、`book/insert`（body 放 `ContentValues["json"]`） |
| query | `bookSource/query`、`bookSources/query`、`rssSource/query`、`rssSources/query`、`books/query`、`book/chapter/query`、`book/content/query`、`book/cover/query`、`book/refreshToc/query` |
| delete | `bookSources/delete`、`rssSources/delete`（selection 传 JSON） |

返回统一 `MatrixCursor(["result"])`，内容为 `ReturnData{isSuccess,errorMsg,data}` 的 JSON。`getType`/`update` 抛 `UnsupportedOperationException`；`onCreate` 返回 `false` 并顺带创建动态快捷方式（副作用式初始化）。`SaveBookProgress` 枚举与分支存在但无对应路由，实际不可达。

#### 5.2 Web 服务

`WebService` 承载 `web/HttpServer.kt` 与 `WebSocketServer.kt`；默认端口 **1122**（WebSocket 1123），`api.md` 里 1234/1235 是示例值。令牌校验 `X-Legado-Token`。

#### 5.3 文件与关联入口

- `FileProvider`：authority `${applicationId}.fileProvider`，`exported=false` + `grantUriPermissions`。
- `OnLineImportActivity`（VIEW，scheme `legado`/`yuedu`）、`FileAssociationActivity`（SEND/VIEW 多种 mime 与后缀）、`SharedReceiverActivity`（PROCESS_TEXT/SEND text/plain）。

### 6. 工具与横切帮助类

| 类 | 职责 |
|---|---|
| `AppConfig` / `LocalConfig` | 配置门面 / 本机私有配置（见 [reading.md](reading.md)） |
| `ReadBookConfig` / `ThemeConfig` / `ThemeStore` | 阅读样式 / 主题 / 颜色 |
| `CacheManager` / `AppCacheManager` / `WebCacheManager` | KV 缓存 / 源变量 / Web 缓存 |
| `BookHelp` | 正文与图片/EPUB 磁盘缓存、失效清理 |
| `ContentProcessor` | 每本书启用的替换规则缓存（WeakReference + CopyOnWriteArrayList） |
| `RuleBigDataHelp` | 书/章/订阅的大变量落盘与无效项清理 |
| `SourceLock` | 按 key 的 singleFlight / 互斥锁 |
| `SourceVerificationHelp` | 源校验的 single-flight 会话与验证码协作 |
| `DefaultData` | 版本升级时导入默认书源/TTS/目录规则/隐私政策（默认词典导入已随划词功能移除） |
| `CrashHandler` | 全局 `UncaughtExceptionHandler`，崩溃日志写文件 |
| `IntentData` | 用 Map 传大对象，替代 Intent extra |
| `MediaHelp` / `ExoPlayerHelper` | 音频焦点、静音播放、ExoPlayer 构建与自定义 header |
| `AppUpdate*` / `AppVariant` | 更新检查（GitHub Releases；自签 fork 落到 `UNKNOWN`） |
| `AudioCacheManager` / `AudioCachePolicy` | 音频缓存命名/去重/清理；不支持 HLS 与多段音频 |
| `utils/NetworkUtils` | 网络可用性、本地 IP |
| `utils/ACache` | 50MB 文件 LRU 缓存 |
| `utils/ArchiveUtils` / `compress/LibArchiveUtils` | zip/rar/7z |
| `utils/HtmlFormatter` / `EncodingDetect` / `ChineseUtils` | 正文排版、字符集检测、简繁转换 |
| `utils/FileUtils`、`LogUtils`、`MD5Utils`、`EncoderUtils`、`GSON` | 常规工具 |

### 7. 清单与权限要点

- 权限（`AndroidManifest.xml:5-26`）：INTERNET、WAKE_LOCK、RECEIVE_BOOT_COMPLETED、ACCESS_NETWORK_STATE/WIFI_STATE、前台服务（MEDIA_PLAYBACK/DATA_SYNC/SPECIAL_USE）、POST_NOTIFICATIONS、POST_PROMOTED_NOTIFICATIONS、REQUEST_INSTALL_PACKAGES、MANAGE_EXTERNAL_STORAGE、READ/WRITE_EXTERNAL_STORAGE、SYSTEM_ALERT_WINDOW 等。
- `application`：`android:name=".App"`、`allowBackup=true`、`networkSecurityConfig`、`requestLegacyExternalStorage=true`。
- 静态 Receiver 只有 `MediaButtonReceiver`；`NetworkChangedListener`、`TimeBatteryReceiver` 动态注册。
- 网络安全：全局 `cleartextTrafficPermitted="true"`，debug 额外信任 user CA。
- 备份规则排除 `js_source_api_credentials.xml`（含令牌）。

### 8. 注意点

1. **通知 ID 冲突**：`constant/NotificationId.kt` 是唯一分配表，新增服务必须登记。
2. **前台服务启动失败会静默停止**：`BaseService` 捕捉 `ForegroundServiceStartNotAllowedException` 后 `stopSelf`，用户侧表现为「点了没反应」，先查通知权限与后台限制。
3. **`ReaderProvider` 无权限保护**：任何应用都能调用；不要在这里新增敏感操作。
4. **`AppWebDav` 初始化即联网**：只能在 IO 线程触碰。
5. **Web 端口漂移**：默认 1122，文档里的 1234 是示例；改端口要同步改 `api.md` 与前端资源。
6. **导出格式差异**：PDF 由 `PdfDocument` 排版，样式与阅读器不等价；本地 PDF 书不能导出。



## 第四部分　构建与发布

> 取证基准：提交 `234543e1f`。数值以 `gradle/libs.versions.toml`、`app/build.gradle`、`gradle.properties` 为准。

### 1. 模块与插件

| Gradle 模块 | 类型 | 说明 |
|---|---|---|
| `:app` | Android application | 业务代码，`applicationId` 基础值 `com.legado.app` |
| `:modules:book` | Android library | EPUB/UMD 解析（`me.ag2s`），minSdk 21、Java 21 |
| `:modules:rhino` | Android library | JS 引擎封装（`com.script`），`api` 暴露 HtmlUnit core-js |

`modules/web/`（Vue 管理前端）不在 `settings.gradle` 里：需 `pnpm build`，其末尾会自动执行 `modules/web/scripts/sync.js`，把产物同步到 `app/src/main/assets/web/vue`。

### 2. 打包裁剪与渠道

| 配置 | 值 | 影响 |
|---|---|---|
| `resourceConfigurations` | `en`、`es-rES`、`ja-rJP`、`pt-rBR`、`vi`、`zh`、`zh-rCN`、`zh-rHK`、`zh-rTW`（`app/build.gradle:71-82`） | 砍掉依赖自带的其它语言资源；新增语言要同时改这里，否则字符串不生效 |
| `packaging.resources.excludes` | `META-INF/*`、`tables/Transcoder_*.bin`、`*.proto`、`src/**`、`kotlin/*.kotlin_builtins`、`kotlin/**/*.kotlin_builtins`（`app/build.gradle:164-188`） | 控包体；改依赖时若报重复资源/MERGED，先查这里 |
| `jniLibs.keepDebugSymbols` | 7 项白名单（同段） | 保留部分 `.so` 调试符号便于 crash 定位 |
| `productFlavors` | dimension `mode`，唯一 flavor `app`，`APP_CHANNEL_VALUE` 写入 manifest placeholder（`app/build.gradle:138-143`） | release 里还有 `applicationIdSuffix == ".releaseA"` 分支，但当前 flavor 结构下恒不成立 |

### 3. 版本矩阵

| 项 | 值 | 依据 |
|---|---|---|
| AGP | 8.13.2 | `gradle/libs.versions.toml:5` |
| Kotlin | 2.4.10 | `toml:3` |
| KSP | 2.3.11 | `toml:4` |
| R8 | 9.1.29（Kotlin 2.4 metadata 最低要求） | `settings.gradle:8-12` |
| compileSdk / targetSdk | 36 / 36 | `build.gradle:5`、`app/build.gradle:65-66` |
| minSdk | 23（debug 变体抬到 26） | `app/build.gradle:41,207-213` |
| Java | toolchain 21，source/target 21 | `app/build.gradle:43-47` |
| Desugaring | `desugar_jdk_libs_nio 2.1.5` | `app/build.gradle:157-163` |
| Room | 2.8.4，schema 导出 `app/schemas`，`room.generateKotlin=false` | `app/build.gradle:94-102` |
| Cronet | `152.0.7977.54` / 主版本 `152.0.0.0` 注入 `BuildConfig` | `gradle.properties:44-45` |

### 4. 关键依赖（版本锁定说明）

| 库 | 版本 | 备注 |
|---|---|---|
| okhttp | 5.4.0 | 网络主通道 |
| jsoup | 1.23.2 | 注释**禁止升级** |
| JsoupXpath | 2.5.3 | XPath 选择器 |
| json-path | 3.0.0 | JSONPath 选择器 |
| htmlunit-core-js | 5.3.0-legado.4 | 走 `third_party/maven` |
| media3 | 1.10.1 | 只引 exoplayer + datasource-okhttp；hls/ui/session 已注释 |
| glide | 5.0.7 | 图片加载 |
| nanohttpd | 2.3.1 | 内置 Web 服务 |
| libarchive | 1.1.6 | zip/rar/7z |
| hutool | 5.8.22 | 注释**禁止升级** |
| commons-text | 1.13.1 | 注释**禁止升级** |
| sora-editor | 0.24.6 | 规则代码编辑器 |
| markwon | 4.6.2 | Markdown 渲染 |
| bouncycastle | 1.85.2 / 1.85 | 加密（备份 AES 等） |
| room / paging / lifecycle / preference | 见 toml | AndroidX 常规栈 |

仓库约束（`settings.gradle`）：

- 强制 R8 `9.1.29`。
- `org.htmlunit:htmlunit-core-js` 只从 `third_party/maven` 解析。
- `com.github.*` 走 jitpack。
- 镜像仓库全部注释掉，需要时自行启用（注释写明「不要提交修改」）。

### 5. 构建类型与签名

- `release`：`minifyEnabled true`、`shrinkResources true`，ProGuard 含 `cronet-proguard-rules.pro`；applicationId 加后缀 `.release`。
- `debug`：不混淆，applicationId 后缀 `.debug`，minSdk 抬到 26（未混淆的 HtmlUnit 含 MethodHandle/VarHandle 调用）。
- 签名：仅当传入 `RELEASE_STORE_FILE`、`RELEASE_STORE_PASSWORD`、`RELEASE_KEY_ALIAS`、`RELEASE_KEY_PASSWORD` 时生成 `myConfig`；V1–V4 全开；release 与 debug 都使用该配置（未提供时用调试签名）。

### 6. 版本号

`app/build.gradle:17-37`：

- `versionName`：`3.<yyMMddHH>`。
- `versionCode`：`37540 + git rev-list 9639f027fbc8cfdf57ce136a9e0c98829a235aef..HEAD --no-merges --count`（**合并提交不计入**）；git 不可用时回退 `30000`。

**构建机没有 git 或历史不完整会导致 versionCode 回退**，覆盖安装时可能被系统判定降级。

### 7. Cronet 打包与下载

Cronet 是本项目构建链路中最特殊的一环：

1. **依赖**：`app/cronetlib/` 共 7 个 jar，其中 6 个直接参与编译（`fileTree` 排除 `cronet_impl_native_java.jar`），第 7 个经重写后以 `dynamicCronetJar` 形式引入；`app/cronet-loader.gradle` 用 ASM 重写 `CronetLibraryLoader` 后输出 `dynamicCronetJar`。`CronetVersion` 或该 jar 哈希变化会直接抛 `GradleException`。
2. **`.so` 不进 APK**：`app/src/main/assets/cronet.json` 只记录 5 个 ABI 的 **MD5 与 version**；下载 URL 由代码拼接（`lib/cronet/CronetLoader.kt:78-80`），运行时下载到 `getDir("cronet")` 并做 MD5 校验。
3. **刷新 assets**：`app/download.gradle` 的 `downloadCronet` 任务需手动执行（`de.undercouch.download` 插件），产物落到 `app/so` 与 `assets/cronet.json`。
4. **运行时失败行为**：Cronet 开关开启但引擎不可用时抛 `CronetUnavailableException`，不静默回退 OkHttp；带 `timeout`/`followRedirects`/`dnsIp` 的请求自动摘除 Cronet 拦截器改走 OkHttp。

没有 ABI `splits`：仅 `-ParmOnly=true` 时过滤为 `arm64-v8a`、`armeabi-v7a`；APK 体积主要来自依赖自带的 `.so`。

### 8. 构建命令

```bash
# 全量编译（Windows 下用 gradlew.bat）
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease

# 只出 arm 两种 ABI
./gradlew :app:assembleRelease -ParmOnly=true

# 单元测试
./gradlew :app:testDebugUnitTest
```

本机注意事项（Windows）：

- `GRADLE_USER_HOME` 指到非系统盘（如 `D:\Android\.gradle`），Gradle 发行包走腾讯镜像。
- 依赖解析失败时先确认 `third_party/maven` 里 `htmlunit-core-js` 存在；该模块不允许从 mavenCentral 解析。
- `org.gradle.jvmargs` 默认 6g、`configuration-cache=false`；不要为提速打开 configuration cache 后提交。

### 9. 精简版删除了什么（构建层）

提交 `234543e1f`：

- 删 `google-services` 插件与 `app/google-services.json`；删 firebase-bom / analytics / perf。
- 删 `pdfbox-android`、`gsyvideoplayer-java`/`exo2`、`DanmakuFlameMaster`。
- 删 `mcp.sdk.server`、`ktor.server.cio`，manifest 删 `McpService`、`VideoPlayService`、`VideoPlayerActivity`。
- ProGuard 删 PDFBox/GSY 规则；`libs.versions.toml` 删对应版本键。

当前源码已无 firebase/mcp/pdfbox 引用（仅 `app/build/**` 旧产物残留可忽略）。**media3 与 `bookSourceType` 的音频/文件/视频常量是保留项**，不要当残留清理。

### 10. 常见构建/发布问题

1. **versionCode 变小**：构建环境缺 git 历史，回退到 30000。
2. **R8 报 metadata 版本错误**：确认 `settings.gradle` 里 R8 9.1.29 未被改动。
3. **Cronet 初始化失败**：`assets/cronet.json` 的 MD5 与 `CronetVersion` 不匹配，或设备 ABI 不在列表；清应用数据后重试会触发重下。
4. **release 混淆后 JS 源报错**：检查 `modules/rhino/consumer-rules.pro` 是否仍保留 `org.htmlunit.corejs.javascript.**`。
5. **debug 装不上（minSdk 26）**：调试包故意抬高 minSdk，Android 7.x 只能装 release。
6. **签名变化**：未提供签名变量时 release 用调试签名，覆盖安装会被系统拒绝；发布前统一提供自己的 keystore。
7. **schema 文件缺失**：`104→105` 的 schema 105 历史上未导出（`DatabaseMigrations.kt:72-81` 有说明），不要为了「补齐」伪造 JSON。
