# 书源全解

本文件合并了书源的**数据结构**、**规则语法**、**JS 宿主接口**、**运行链路与实操**、**引擎与网络实现**五部分，均从本仓库源码取证，不是上游 wiki 的转述。与 App 内置帮助（`app/src/main/assets/web/help/md/*.md`）冲突时，以源码为准。

**本文只讲实现。制作、批量核对与修复的操作流程见 [guides/booksource-workflow.md](guides/booksource-workflow.md)。**

## 目录

- [第一部分　书源数据结构](#第一部分书源数据结构)
- [第二部分　规则字符串语法](#第二部分规则字符串语法)
- [第三部分　`@js:` 宿主接口](#第三部分js-宿主接口)
- [第四部分　运行链路与实操](#第四部分运行链路与实操)
- [第五部分　引擎与网络实现](#第五部分引擎与网络实现)

## 路径简写总表

正文用简写代替长路径；`K` / `A` / `T` 是**前缀**（如 `Kui/rss/` = `app/src/main/java/io/legado/app/ui/rss/`）。

| 简写 | 实际路径 |
|---|---|
| `K` | `app/src/main/java/io/legado/app/` |
| `A` | `app/src/main/` |
| `T` | `app/src/test/java/io/legado/app/` |
| `BS` | `data/entities/BookSource.kt` |
| `BST` / `BT` / `ST` / `AP` | `constant/BookSourceType.kt` / `BookType.kt` / `SourceType.kt` / `AppPattern.kt` |
| `BSE` / `SH` / `SVH` | `help/source/BookSourceExtensions.kt` / `SourceHelp.kt` / `SourceVerificationHelp.kt` |
| `DAO` | `data/dao/BookSourceDao.kt` |
| `ADB` / `DM` / `BSP` / `BSCS` | `data/AppDatabase.kt` / `DatabaseMigrations.kt` / `BookSourcePart.kt` / `BookSourceCheckState.kt` |
| `IOD` | `help/storage/ImportOldData.kt` |
| `CS` / `CSS` | `model/CheckSource.kt` / `service/CheckSourceService.kt` |
| `AR` / `AU` | `model/analyzeRule/AnalyzeRule.kt` / `AnalyzeUrl.kt` |
| `JX` / `EU` | `help/JsExtensions.kt` / `help/JsEncodeUtils.kt` |
| `RSS` | `ui/rss/read/RssJsExtensions.kt` |
| `RH` | `modules/rhino/src/main/java/com/script/rhino/` |
| `MD` | `app/src/main/assets/web/help/md/`（App 内置帮助） |


## 第一部分　书源数据结构

取证基准提交 `234543e1f`。路径简写：

| 简写 | 实际路径（相对仓库根） |
|---|---|
| `BS` | `app/src/main/java/io/legado/app/data/entities/BookSource.kt` |
| `rule/` | `app/src/main/java/io/legado/app/data/entities/rule/` |
| `BST` / `BT` / `ST` / `AP` | `app/src/main/java/io/legado/app/constant/BookSourceType.kt` / `BookType.kt` / `SourceType.kt` / `AppPattern.kt` |
| `BSE` / `SH` / `SVH` | `app/src/main/java/io/legado/app/help/source/BookSourceExtensions.kt` / `SourceHelp.kt` / `SourceVerificationHelp.kt` |
| `DAO` | `app/src/main/java/io/legado/app/data/dao/BookSourceDao.kt` |
| `ADB` / `DM` / `BSP` / `BSCS` | `app/src/main/java/io/legado/app/data/AppDatabase.kt` / `DatabaseMigrations.kt` / `data/entities/BookSourcePart.kt` / `BookSourceCheckState.kt` |
| `IOD` | `app/src/main/java/io/legado/app/help/storage/ImportOldData.kt` |
| `CS` / `CSS` | `app/src/main/java/io/legado/app/model/CheckSource.kt` / `service/CheckSourceService.kt` |

---

### 1. 顶层结构

Room 表 `book_sources`，主键就是书源地址 `bookSourceUrl`（`BS:33-34`）。

**三条决定「JSON 怎么写」的机制：**

1. **JSON 键名 == Kotlin 字段名，逐字大小写敏感。** `BookSource` 全类没有一处 `@SerializedName`，`GSON` / `INITIAL_GSON` 也没设 `FieldNamingPolicy`（`utils/GsonExtensions.kt:32-56`）。写错大小写不报错，只会静默取默认值。
2. **每个子规则对象在数据库里是一个 TEXT 列**，由 `Converters` 用 `GSON.toJson` 整体序列化（`BS:306-356`）。所以 `ruleSearch` 是一个嵌套对象，不是散开的平铺字段。
3. **子规则字段允许写成字符串。** 每个 rule 的 `jsonDeserializer` 都做了 `json.isJsonPrimitive → fromJson(json.asString)` 容错（例：`rule/SearchRule.kt:29-35`），即 `"ruleSearch": "{...}"` 这种「字符串里塞 JSON」也能解析。

`equals` / `hashCode` **只以 `bookSourceUrl` 为准**（`BS:114-120`）——同一地址导入两次是覆盖，不是并存。

### 2. 顶层字段

| 字段 | 类型 | 默认值 | 行号 | 作用 |
|---|---|---|---|---|
| `bookSourceUrl` | String | `""` | `BS:34` | 书源地址（主键），须含 http/https |
| `bookSourceName` | String | `""` | `BS:36` | 显示名 |
| `bookSourceGroup` | String? | null | `BS:38` | 分组，分隔符 `[,;，；]`（`AP:36`） |
| `bookSourceType` | Int | 0 | `BS:41` | 类型，见第 4 节 |
| `bookUrlPattern` | String? | null | `BS:43` | 详情页 URL 正则 |
| `customOrder` | Int | 0 | `BS:46` | 手动排序号，`@ColumnInfo(default="0")` |
| `enabled` | Boolean | true | `BS:49` | 是否启用 |
| `enabledExplore` | Boolean | true | `BS:52` | 是否启用发现 |
| `jsLib` | String? | null | `BS:54` | 前置 JS 库（覆写 `BaseSource`） |
| `enabledCookieJar` | Boolean? | true | `BS:57` | OkHttp CookieJar 自动保存。**注意 Java 默认 `true` 而 DB default `"0"`，两者不一致** |
| `concurrentRate` | String? | null | `BS:59` | 并发率限制，如 `100/1000` |
| `header` | String? | null | `BS:61` | 全局请求头 |
| `loginUrl` | String? | null | `BS:63` | 登录地址 |
| `loginUi` | String? | null | `BS:65` | 登录表单定义（v2 结构见 `BaseSource.kt:115-157`） |
| `loginCheckJs` | String? | null | `BS:67` | 登录态检测 JS |
| `coverDecodeJs` | String? | null | `BS:69` | 封面解密 JS |
| `bookSourceComment` | String? | null | `BS:71` | 注释；校验失败时会被前置 `// Error: `（`BS:202-214`） |
| `variableComment` | String? | null | `BS:73` | 自定义变量说明 |
| `lastUpdateTime` | Long | 0 | `BS:75` | 最后更新时间，用于排序 |
| `respondTime` | Long | 180000 | `BS:77` | 响应时间，用于排序 |
| `weight` | Int | 0 | `BS:79` | 智能排序权重。**全库只读不写**：仅 `checkContent()` 里归零（`BS:262`）与 `JsSourceUpsert.kt:157` 继承旧值，没有任何计算写入点 |
| `exploreUrl` | String? | null | `BS:81` | 发现页配置，格式见第四部分 |
| `exploreScreen` | String? | null | `BS:83` | 发现筛选规则。除 `JsSourceUpsert.kt:132` 的等值比较外**无消费者** |
| `ruleExplore` | ExploreRule? | null | `BS:85` | 发现规则 |
| `searchUrl` | String? | null | `BS:87` | 搜索 URL |
| `ruleSearch` | SearchRule? | null | `BS:89` | 搜索规则 |
| `ruleBookInfo` | BookInfoRule? | null | `BS:91` | 详情规则 |
| `ruleToc` | TocRule? | null | `BS:93` | 目录规则 |
| `ruleContent` | ContentRule? | null | `BS:95` | 正文规则 |
| `ruleReview` | ReviewRule? | null | `BS:97` | 段评规则 |
| `mainJs` | String? | null | `BS:99` | **单文件 JS 书源主脚本**；非空时优先走脚本抓取 |
| `eventListener` | Boolean | false | `BS:101` | 是否监听事件并回调规则 |
| `customButton` | Boolean | false | `BS:103` | 由书源控制的自定义按钮 |

### 3. 六个子规则对象

#### SearchRule（`rule/SearchRule.kt:12-25`）

`checkKeyWord`（校验关键字，String? = null）+ 下列 10 个字段。这 10 个来自接口 `rule/BookListRule.kt:6-17`，全部 `String? = null`：
`bookList` / `name` / `author` / `intro` / `kind` / `lastChapter` / `updateTime` / `bookUrl` / `coverUrl` / `wordCount`

#### ExploreRule（`rule/ExploreRule.kt:12-23`）

**只有** `BookListRule` 那 10 个字段，**没有 `checkKeyWord`**。

#### BookInfoRule（`rule/BookInfoRule.kt:12-25`）

`init`、`name`、`author`、`intro`、`kind`、`lastChapter`、`updateTime`、`coverUrl`、`tocUrl`、`wordCount`、`canReName`、`downloadUrls`

#### TocRule（`rule/TocRule.kt:9-20`）

`preUpdateJs`、`chapterList`、`chapterName`、`chapterUrl`、`formatJs`、`isVolume`、`isVip`、`isPay`、`updateTime`、`nextTocUrl`

#### ContentRule（`rule/ContentRule.kt:12-27`）

`content`、`subContent`（副文/歌词）、`title`、`nextContentUrl`、`webJs`、`sourceRegex`、`replaceRegex`、`imageStyle`、`imageDecode`、`payAction`、`callBackJs`、`contentBatch`、`maxBatchSize`（Int? = null）

`maxBatchSize` 为 null 表示**不支持批量正文**；实际取值被钳在 `1..50`：`(ruleContent?.maxBatchSize ?: 1).coerceIn(1, MAX_CONTENT_BATCH_SIZE)`（`BS:257`，常量 `BS:303`）。

#### ReviewRule（`rule/ReviewRule.kt:9-42`）

段评/书评规则：`reviewUrl`、`avatarRule`、`contentRule`、`postTimeRule`、`reviewQuoteUrl`、`voteUpUrl`、`voteDownUrl`、`postReviewUrl`、`postQuoteUrl`、`deleteUrl`、`enabled`(Boolean=false)、`reviewSummaryUrl`、`summaryListRule`、`summaryParagraphIndexRule`、`summaryParagraphDataRule`、`summaryCountRule`、`reviewDetailUrl`、`reviewDetailNextPageUrl`、`detailListRule`/`IdRule`/`AvatarRule`/`NameRule`/`BadgeRule`/`ContentRule`、`replyListRule`/`IdRule`/`AvatarRule`/`NameRule`/`BadgeRule`/`ContentRule`

> **本 fork 没有上游的这些字段**：`ruleCheck`、`RuleBookshelf`、`RuleStack`、`RuleSplit` 在 `app/src/main` 全库 0 命中；`getReviewRule()` 已被注释掉（`BS:157-162`）。

### 4. 类型常量

#### bookSourceType（`BST:8-12`）

| 值 | 常量 | 含义（注释原文） |
|---|---|---|
| 0 | `default` | 文本 |
| 1 | `audio` | 音频 |
| 2 | `image` | 图片 |
| 3 | `file` | 只提供下载服务的网站 |
| 4 | `video` | 视频 |

**只有 0–4，没有 5。** UI 下拉 5 项与此双向映射：`ui/book/source/edit/BookSourceEditActivity.kt:471-477`（枚举→序号）与 `:634-640`（序号→枚举），数组在 `res/values/arrays.xml:8-14`。

#### SourceType（`ST:8-9`）

`book = 0` / `rss = 1`，由 `help/source/BaseSourceExtensions.kt:37-43` 的 `getSourceType()` 按实例类型推导。

#### BookType（`BT:14-54`，持久化位掩码）

| 常量 | 值 | 常量 | 值 |
|---|---|---|---|
| `video` | 0b100 (4) | `webFile` | 128 |
| `text` | 0b1000 (8) | `local` | 256 |
| `updateError` | 16 | `archive` | 512 |
| `audio` | 32 | `notShelf` | 1024 |
| `image` | 64 | | |

`allBookType` / `allBookTypeLocal`（`BT:64-66`）；本地书标记 `localTag = "loc_book"`（`BT:71`）、WebDAV 标记 `webDavTag = "webDav::"`（`BT:76`）。

**bookSourceType → BookType 的唯一换算处**：`BSE:130-138` 的 `BookSource.getBookType()`——`file` → `text or webFile`；`image`/`audio`/`video` → 同名位；其余 → `text`。消费点 `model/webBook/BookList.kt:173,212`、`WebBook.kt:211,307`、`model/jsSource/JsSourceBook.kt:65,76,93`、`JsSourceMarshaller.kt:37`。

> **为什么 `video` 位还留着**：本 fork 已删掉视频播放器，但 `BookType.video` 是**已入库数据**的位掩码、`BookSourceType.video` 是**用户书源 JSON** 的一部分，删了会静默重分类既有书架与书源，所以刻意保留。

### 5. 派生逻辑（`help/source/`）

`BSE`：

| 成员 | 行号 | 作用 |
|---|---|---|
| `getExploreKindsKey()` | 31-33 | `md5(bookSourceUrl + exploreUrl)`——用 md5 是为了**改分类后自动重算**（注释在 `:23-25`） |
| `exploreKinds()` | 39-105 | suspend；支持 `@js:` / `<js>` 动态生成 + ACache 缓存 + Mutex 去重；解析两种格式：**JSON 数组** → `ExploreKind` 列表，**纯文本** → 按 `(&&\|\n)+` 分行、`::` 分 title/url（`:87-96`）；失败塞入 `ERROR:` 项（`:98`） |
| `clearExploreKindsCache()` | 107-121 | 清缓存 |
| `exploreKindsJson()` | 123-128 | 取 JSON 形式 |
| `getBookType()` | 130-138 | 见第 4 节 |

`ExploreKind` 本身在 `rule/ExploreKind.kt:6-14`（`title`/`url`/`type`/`action`/`chars`/`default`/`viewName`/`style`），type 常量在 `Type` object `:18-26`。

`SH`：

| 成员 | 行号 | 作用 |
|---|---|---|
| `getSource(key)` | 35-44 | 优先取当前阅读源（ReadBook / AudioPlay），再查书源表、RSS 表 |
| `getSource(key, type)` | 49-56 | 按 SourceType 直查 |
| `deleteBookSource(s)` | 65-112 | 删除 |
| `insertBookSource` | 133-146 | 导入，**内置 18+ 域名拦截并 toast**（`:148-161` 读 assets `18PlusList.txt` 的 base64） |
| `adjustSortNumber()` | 166-178 | 当 `maxOrder>99999 \|\| minOrder<-99999 \|\| hasDuplicateOrder` 时按当前顺序整体重排 `customOrder` |

禁用/排序/分组实际落在 `DAO`：`enable`(:353-361)、`enableExplore`(:363-371)、`upOrder`(:373-388)、`upGroup`(:390-401)、`dealGroups`/`allGroups`/`flowGroups`(:403-429，按 `AP:36` 拆分 + `cnCompare` 中文排序)。UI 排序枚举 `ui/book/source/manage/BookSourceSort.kt:3-5`（Default/Name/Url/Weight/Update/Enable/Respond），比较器在 `BookSourceViewModel.kt:233-272`、`BookSourceActivity.kt:426,446`。

> `SVH`（`SourceVerificationHelp.kt`）名字像「书源校验」，其实是**人机验证**（验证码 / 拉起浏览器）：`getVerificationResult`(:202-282)、`startBrowser`(:348-373)、`setResult`/`getResult`/`clearResult`/`checkResult`(:376-399)、同 host 合并等待的 `VerificationFlightRegistry`(:27-111)、`verificationFlightKey`(:129-144)。

### 6. 持久化、列演进与旧版兼容

#### 表与视图

- DB 版本 111，`exportSchema = true`，entities 含 `BookSource` 与 `BookSourceCheckState`（`ADB:80-87`），视图 `BookSourcePart`（`ADB:89`）。
- `onOpen` **不种子书源**，只补 book_groups 并做一次脏值修正 `update book_sources set loginUi = null where loginUi = 'null'`（`ADB:260-262`）。
- **书源列表不查全表**：走 `book_sources_part` 视图 + 900 条分块回表（`BSP:9`、`:107-127`）。视图派生列 `hasLoginUrl`（loginUrl 非空，或 mainJs 非空且 loginUi 去空白后不为 `[]`）、`hasExploreUrl`、`hasJs`、`checkStatus`（`coalesce(c.status,'NEEDS_CHECK')`），SQL 常量在 `BSP:11-26`。

#### book_sources 列的引入版本

由 `app/schemas/io.legado.app.data.AppDatabase/*.json` 的 createSql 逐版 diff 得出：

| 版本 | 变化 |
|---|---|
| 19→20 / 34→35 | `bookSourceComment`（`DM:195`）、`concurrentRate`（`DM:364`） |
| 35→36 | `loginUi`、`loginCheckJs`（`DM:370-371`） |
| 37→38 | `respondTime INTEGER NOT NULL DEFAULT 180000`（`DM:385`） |
| 43（基线快照） | 已含 bookSourceUrl…bookSourceComment、lastUpdateTime、respondTime、weight、exploreUrl、ruleExplore、searchUrl、ruleSearch、ruleBookInfo、ruleToc、ruleContent |
| 49 / 50 / 53 / 54 | `enabledCookieJar` / `variableComment` / `enabledReview`+`ruleReview` / `coverDecodeJs` |
| 64 / 65 | `exploreScreen` / **删除** `enabledReview`（`DM:466-469` `@DeleteColumn`） |
| 85 / 86 / 91 | `eventListener` / `customButton` / `mainJs` |
| 90→91 起 | `book_sources_part` 视图重建为 `DROP VIEW` + `CREATE VIEW`（`DM:94-95`） |

`books.type` 老值→位掩码的一次性换算在 `DM:423-456`（`Migration_54_55`）。

#### 校验状态表

`BookSourceCheckState`（`BSCS:20-32`，表 `book_source_check_states`，外键 CASCADE 到 book_sources）：`revision`/`sourceRevision` 为 UUID，`status ∈ NEEDS_CHECK / PASSED / FAILED`。注释明示**该表不参与导入导出 JSON**（`BSCS:9`）。

写入链：`DAO.insert`(:276-280) → `update`(:285-294，先比 `checkContent()` 再决定是否重置状态) → `beginCheck`(:296-309) → `finishCheck`(:311-313，带 `status='NEEDS_CHECK'` 的 CAS) → `completeCheck`(:318-326，写 `respondTime`)。

**「改排序/开关不影响校验状态」的指纹算法**就是 `BS:260-263` 的 `checkContent()`：把 `customOrder`/`enabled`/`enabledExplore`/`lastUpdateTime`/`respondTime`/`weight` 归零后再序列化比对。

#### 旧版书源字段名兼容

唯一实现在 `IOD`，**不是**靠 `@SerializedName` 别名（`oldBookSourceUrlPattern` / `oldExploreUrl` / `oldRuleCheck` 全库不存在）。
入口 `fromOldBookSources`(:165-174) → `fromOldBookSource(jsonItem: DocumentContext)`(:176-245)，用 jsonPath 读旧键名：

| 旧键 | 新去向 | 行号 |
|---|---|---|
| `ruleBookUrlPattern` | `bookUrlPattern` | `IOD:188` |
| `serialNumber` | `customOrder` | `:189` |
| `httpUserAgent` | `uaToHeader` → `header` | `:190` |
| `ruleSearchUrl` | `toNewUrl(searchUrl)` | `:191` |
| `ruleFindUrl` | `toNewUrls(exploreUrl)` | `:192` |
| `bookSourceType == "AUDIO"` | `bookSourceType = audio` | `:193-194` |
| `enable` | `enabled` | `:195` |
| 无发现 url | `enabledExplore = false` | `:196-198` |
| `ruleSearchList/Name/Author/Introduce/Kind/NoteUrl/CoverUrl/LastChapter` | → SearchRule | `:199-208` |
| `ruleFind*` | → ExploreRule | `:209-218` |
| `ruleBookInfoInit/ruleBookName/ruleBookAuthor/ruleIntroduce/ruleBookKind/ruleCoverUrl/ruleBookLastChapter/ruleChapterUrl` | → BookInfoRule | `:219-228` |
| `ruleChapterList/Name/ruleContentUrl/ruleChapterUrlNext` | → TocRule | `:229-234` |
| `ruleBookContent/ruleBookContentReplace/ruleContentUrlNext` | → ContentRule（并去掉裸 `$` 前缀） | `:235-243` |

**旧规则语法换算** `toNewRule`(:252-283)：`-`→`reverse`、`+`→`allinone`、`#regex#rep`→`##`、`|`→`||`、`&&`→`&&`；对已以 `@CSS:` / `@XPath:` / `//` / `##` / `:` 开头或含 `@js:` / `<js>` 的规则**跳过换算**（`:265-272`）。

### 7. 「校验」到底读哪些字段

> 注意：**没有 `ruleCheck` 字段**，也没有 `ui/book/source/check/` 这个包。实际三处：`CS`（配置+启动）、`service/CheckSourceService.kt`（执行）、`ui/config/CheckSourceConfig.kt`（设置页）。入口 `BookSourceActivity.kt:599-606` 与 HTTP API `api/controller/BookSourceCheckController.kt:68-69`。

配置项 `CS:20-31`：`keyword = "我的"`、`timeout`（缓存键 `checkSourceTimeout`，默认 180000L）、`wSourceComment`（失败写进注释）、`checkDomain` / `checkSearch` / `checkDiscovery` / `checkInfo` / `checkCategory` / `checkContent`；`putConfig()`(:69-78)、`upSummary()`(:80-93)。

校验关键字取 `ruleSearch.checkKeyWord`：`BS:216-223` 的 `getCheckKeyword(default)`，要求非空且不含 `http` / `::` / `++` / `--`，否则回落到设置页的默认词。

判定流程 `doCheckSource`（`CSS:314-371`）：

1. `removeInvalidGroups()` + 可选 `removeErrorComment()`（`:316-319`）
2. **checkDomain**：解析 `bookSourceUrl` 端点，TCP 2s/1600ms 连不上 → 加分组「域名失效」并抛异常（`:321-332`）
3. **checkSearch**：要求 `mainJs` 非空或 `searchUrl` 非空，走 `WebBook.searchBookAwait`；结果为空 → 「搜索失效」，否则对首本书做 `checkBook`（`:334-348`）
4. **checkDiscovery** 且 `exploreUrl` 非空：`exploreKinds()` 取首个带 url 的分类，取不到就抛「发现规则为空」，`exploreBookAwait` 为空 → 「发现失效」（`:350-366`）
5. 收尾 `getInvalidGroupNames()` 非空即抛 `NoStackTraceException`（`:367-370`）

`checkBook`（`CSS:376-416`）依次跑 `ruleBookInfo`→`getBookInfoAwait`（仅当 tocUrl 为空时）、`ruleToc`→`getChapterListAwait`、`ruleContent`→`getContentAwait`；`ContentEmptyException`→「${搜索|发现}正文失效」，`TocEmptyException`→「…目录失效」，其他异常向上抛（`:404-415`）。

成功/失败：`checkSource`(:278-300) 用 `runCatching` + `withTimeout(CheckSource.timeout)` 包裹，**无异常即 `succeeded=true`、message="校验成功"**。失败按异常类型归组：`TimeoutCancellationException`→「校验超时」、`ScriptException|WrappedException`→「js失效」、非 `NoStackTraceException`→「网站失效」（`:289-292`）。`respondTime = Debug.getRespondTime(...)`（`:298`，`model/Debug.kt:282-285`：成功记耗时、失败记 `timeout + 耗时`）。写回 `completeCheck(selected, succeeded, detail, respondTime)`（`:228-230`）；CAS 未命中（书源已被改动/删除）记 `NOT_COMPLETED`（`:231-242`）。
分组失效判据：`BS:225-229` 的 `getInvalidGroupNames()`——分组名含「失效」或等于「校验超时」。

### 8. AppPattern 里与书源规则相关的正则（`AP`）

| 常量 | 行号 | 正则原文 | 用途 |
|---|---|---|---|
| `JS_PATTERN` | 7-8 | `<js>([\w\W]*?)</js>\|@js:([\w\W]*)` (CASE_INSENSITIVE) | 内联 JS 抽取；`BaseSource.extractInlineJs`(:103-113) 用它判定 loginUi/loginUrl/header |
| `WebJS_PATTERN` | 9-10 | `@webjs:([\w\W]{5,})` | WebView 注入型规则（`ContentRule.webJs`） |
| `EXP_PATTERN` | 11 | `\{\{([\w\W]*?)\}\}` | `{{js}}` 表达式求值 |
| `useHtmlRegex` | 17 | `<usehtml>.*?</usehtml>` DOT_MATCHES_ALL | 自定义 html 保留标记 |
| `dataUriRegex` | 20 | `^data:.*?;base64,(.*)` | dataURL 图片 |
| `imgRegex` | 22 | `(.*)((?:data\|https?):[\s\S]+)$` | 章节标题内嵌段评图 |
| `wordCountRegex` | 24 | `(?:^\|字数[：:、]?\|\s+)([0-9万千百\.]{1,6}字)` | 对应 `wordCount` 规则 |
| `noWordCountRegex` | 27 | `[\s\u200B-\u200F\uFEFF]` | 不计入字数的字符 |
| `domainRegex` | 30 | `^https?://([^:/]+)` IGNORE_CASE | 域名 |
| `nameRegex` / `authorRegex` | 32-33 | `\s+作\s*者.*\|\s+\S+\s+著` / `^\s*作\s*者[:：\s]+\|\s+著` | 书名/作者清洗 |
| `splitGroupRegex` | 36 | `[,;，；]` | **bookSourceGroup 分隔符**（`BS:173-196` 的 addGroup/removeGroup/hasGroup、`DAO:406`） |
| `debugMessageSymbolRegex` | 40 | `[⇒◇┌└≡]` | 书源调试信息符号剥离 |
| `jsFileRegex` | 45 | `.*\.js$` IGNORE_CASE | **JS 单文件书源**导入判据（对应 `mainJs`） |

**两个「不在这里」的事实**（易误查）：
1. `AppPattern` 里**没有** `bookUrlPattern` 专用正则——它就是被直接 `toRegex()` 使用的：`ui/main/bookshelf/BookshelfViewModel.kt:79,94`、`ui/association/AddToBookshelfDialog.kt:134`；`"NONE"` 由 SQL 侧排除（`DAO:173`、`:184-185`）。
2. 也**没有** xpath/css 判定正则——判定散在解析器里：`model/analyzeRule/AnalyzeRule.kt:665`（`startsWith("@CSS:", true)`）、`:841`（`startsWith("//")`）、`AnalyzeByJSoup.kt:90,138,510-512`。

### 9. 本 fork 与上游 legado 的差异（勿照抄 wiki）

| 差异 | 证据 |
|---|---|
| `bookSourceType` 只有 0–4，无 5 | `BST:8-12` |
| 无 `ruleCheck` / `RuleBookshelf` / `RuleStack` / `RuleSplit`；`getReviewRule()` 已注释 | `app/src/main` 全库 0 命中；`BS:157-162` |
| 无视频播放器与弹幕：`java.openVideoPlayer`、`VideoPlay`、`ui/video`、`gsyVideoPlayer`、`DanmakuFlameMaster` 全部不存在 | 全库 0 命中 |
| 无 MCP / Firebase | `web/mcp`、`McpService`、`google-services.json` 已删除 |
| 本 fork 独有：`mainJs`(v91)、`eventListener`(v85)、`customButton`(v86)、`coverDecodeJs`(v54)、`exploreScreen`(v64)、`ContentRule.contentBatch`/`maxBatchSize`、`loginUi` v2、独立校验状态表 | `BS:99,101,103,69,83`；`rule/ContentRule.kt:12-27`；`BaseSource.kt:115-157`；`BSCS` |



## 第二部分　规则字符串语法

取证基准提交 `234543e1f`。路径前缀 `AR` = `app/src/main/java/io/legado/app/model/analyzeRule/`，`AP` = `app/src/main/java/io/legado/app/constant/AppPattern.kt`。

> 本部分全部以解析器源码为准。**上游流传的若干写法在本仓不存在**（`<%`、`@正则=`、`:regex=`、`@text:`、`@allInOne`、`<get,post>`、`getStrings`），见第 11 节冲突清单。

---

### 1. 心智模型

一条规则字符串被处理的层次：

```
① JS/插值抽取   @js: … / <js>…</js> / {{…}} / @get:{…} / @put:{…}   → splitSourceRule
② 模式判定      @CSS: / @@ / @XPath: / @Json: / $. $[ / // / :       → AnalyzeRule:663-696
③ 分隔符切分    && / || / %% / @                                    → 各解析器 splitRule
④ 选择器与索引  CSS 前缀 key + 索引组 + 末段取值 key                 → AnalyzeByJSoup
⑤ 后处理        $N 选组 + ## 替换                                    → AnalyzeRule
```

**两段匹配**是 `RuleAnalyzer` 的内部机制，不是「联想规则」：
- 首段匹配 `splitRule(vararg split)`（`AR\RuleAnalyzer.kt:165`）——用 `consumeToAny` 让多个候选分隔符**竞争**，谁先出现谁赢，并把 `elementsType` 记成「真正切开这条规则的那个分隔符」。
- 二段匹配私有 `splitRule()`（`:239-240`，`@JvmName("splitRuleNext")`）——`elementsType` 已定型，用 `consumeTo` 快速拉。
- 分隔符若落在 `[...]` / `(...)` 内部，先 `chompBalanced` 跳过再递归（`:204-236`）。

> 所以「优先级」的真实含义是：**一条规则里混用多个分隔符时，只有最外层先命中的那个决定合并策略**，其余的会被当作选择器内容。

### 2. RuleAnalyzer 的切分原语

`RuleAnalyzer` **不是分隔符常量表**——类内唯一常量是转义符 `private const val ESC = '\\'`（`AR\RuleAnalyzer.kt:375`），分隔符全部由调用方传入。

| 原语 | 行为 | 行号 |
|---|---|---|
| `trim()` | 剥掉规则开头的 `@` 以及所有 ASCII ≤ 空格的字符（`queue[pos] < '!'`） | `:15-22` |
| `consumeTo(seq)` | 拉到分隔符前，不切片；区分大小写 | `:35-42` |
| `consumeToAny(vararg)` | 多分隔符逐字节试探，命中即记 `step = 分隔符长度` | `:49-66` |
| `findToAny(vararg Char)` | 找选择器起点 `[` / `(` | `:73-86` |
| `chompCodeBalanced` | **代码平衡组**：`[` `]` 恒定计入深度；引号内不解析；`ESC` 跳 1 字符 | `:91-126` |
| `chompRuleBalanced` | **规则平衡组**：只平衡 open/close；引号内失效；**仅引号外** `\` 转义 | `:131-159` |
| 构造参数 `code` | `true` → 代码平衡组（只有 JSONPath 侧用：`RuleAnalyzer(rule, true)`） | `:368` |

**内嵌规则**（真正的「外层 + 内层」两段）：

| 写法 | 机制 | 行号 |
|---|---|---|
| `{$.xxx}` | `innerRule(inner, startStep=1, endStep=1, fr)`，`{`/`}` 代码平衡拉出 | `:308-332`；调用 `AR\AnalyzeByJSonPath.kt:44,82` |
| `{{ … }}` | `innerRule(startStr, endStr, fr)` 字面首尾对 | `:339-365`；调用 `AR\AnalyzeUrl.kt:221` |

括号不平衡时**跳过分隔符继续当普通文本**（`:326`）；`startX == 0` 表示「一条内嵌规则都没替换成功」，调用方据此判断（`AR\AnalyzeByJSonPath.kt:46`）。

### 3. 分隔符与各解析器的支持矩阵

| 分隔符 | 语义 | AnalyzeByJSoup | AnalyzeByXPath | AnalyzeByJSonPath |
|---|---|---|---|---|
| `&&` | 各段结果**顺序拼接** | `:84,135` | `:58,96,135` | `:38,78,136` |
| `\|\|` | **短路**：第一段非空即 `break` | `:84,135`（break `:102,142,170`） | `:69,109,151` | `:65,104,152` |
| `%%` | **按索引交叉（拉链）合并**，以 `results[0]` 长度为外层 | `:106-114,176-183` | `:74-82` | `:110-117,157-164` |
| `@` | 末段取值 key 的分隔（链式下钻后，最后一段交 `getResultLast`） | `:153,208` | — | — |
| `,` | `<…>` 内翻页数组分隔 | — | — | — （在 `AR\AnalyzeUrl.kt:235`） |
| `,` + `{` | `url,{"json"}` 的 options 分隔 | — | — | — （`AR\AnalyzeUrl.kt:817`） |

**三个必须记住的坑：**
1. `AnalyzeByJSonPath.getString`（`:38`）和 `AnalyzeByXPath.getString`（`:135`）**只切 `&&` / `||`，不支持 `%%`**；而它们的 `getStringList` / `getElements` / `getList` 支持。也就是说 **同一个 `%%` 写在取单值和取列表的规则里行为不同**。
2. `%%` 的拉链长度**以第一段为准**——第一段比后段短时，后段多余元素被丢掉。
3. `RuleAnalyzer` 的 `trim()` 会吃掉开头的 `@`，所以 `@@$.data.list` 的「强制 JSoup 解析」要靠剥 2 字符实现（见下节）。

### 4. 选择器前缀判定（优先级即 `when` 顺序）

`AR\AnalyzeRule.kt:663-696` 的 `when` 顺序就是优先级：

| 写法示例 | 判定条件 | 结果 | 行号 |
|---|---|---|---|
| `@CSS:.novelist@text` | `startsWith("@CSS:", ignoreCase)` | `Mode.Default`，**保留前缀**（由 JSoup 侧再 `substring(5)`） | `:665-668`；`AR\AnalyzeByJSoup.kt:511-516` |
| `@@$.data.list` | `startsWith("@@")` | 强制 `Mode.Default`，剥 2 字符——**内容是 JSON 也改用 JSoup 解析** | `:670-673` |
| `@XPath://a/@href` | `startsWith("@XPath:", ignoreCase)` | `Mode.XPath`，`substring(7)` | `:675-678` |
| `@Json:$.name` | `startsWith("@Json:", ignoreCase)` | `Mode.Json`，`substring(6)` | `:680-683` |
| `$.name` / `$[0]`，或内容本身是 JSON | `isJSON \|\| startsWith("$.") \|\| startsWith("$[")` | `Mode.Json`（**前缀不剥**） | `:685-688` |
| `//div/a` | `startsWith("/")`（注释：XPath 特征明显，无需标头） | `Mode.XPath` | `:690-693` |
| `:(.*?)作\s*者(.*)` | 首字符 `:` **且 `allInOne == true`**（只有 `getElement`/`getElements` 传 true） | `Mode.Regex`，`start = 1` | `:593-605`；调用 `:391,425,456` |
| 以上都不是 | — | `Mode.Default`（CSS / 索引混写） | `:695` |

> `@CSS:` **保留**原串给 JSoup 层剥，而 `@Json:` / `@XPath:` / `@@` 在 AnalyzeRule 层就剥掉了——两者剥皮时机不同，写规则时别指望 `@CSS:` 出现在内嵌 `{{}}` 里还能被识别。
> 内容类型判定：`isJSON = (content !is Node) && content.toString().isJson()`（`:107-110`）；每次 `setContent` 会清空三套子解析器（`:112-114`）。

### 5. JS 与插值写法

| 写法 | 判定 | 语义 | 行号 |
|---|---|---|---|
| `@js:` + 到末尾 | `AP:8` `JS_PATTERN = <js>([\w\W]*?)</js>\|@js:([\w\W]*)` | `Mode.Js`，`evalJS(rule, result)`；**形参 `result` 就是上一步结果** | `AR\AnalyzeRule.kt:607-617`、`:259,330,349,406` |
| `<js> … </js>` | 同一正则的另一支 | 同上；区别只在于 `@js:` 吃到串尾，`<js>` 有闭合标签 | `AP:7-8` |
| `@webjs:` + **≥5 字符** | `AP:10` `WebJS_PATTERN = @webjs:([\w\W]{5,})` | `Mode.WebJs`，走 BackstageWebView 渲染后取内容 | `AR\AnalyzeRule.kt:618-628`、`:256,348` |
| `{{$.isbn}}` / `{{java.…}}` | `AP:11` `EXP_PATTERN = \{\{([\w\W]*?)\}\}` | 若 `isRule()` 为真→**当规则递归 `getString`**，否则→`evalJS` | `:1028-1029,723-726,794-811,837-842` |
| `@get:{bookName}` | `evalPattern` 第一支，`ruleType = -2` | 读变量 | `:718-721,814-816` |
| `@put:{"name":"$.title"}` | `putPattern = @put:(\{[^}]+?\})`（忽略大小写） | 先 `splitPutRule` 剥离（`:516-539`），再 `putRule` 递归求值后存入（`:507-511`） | |
| `$1` … `$99` | `regexPattern = \$\d{1,2}` | 从**上一步结果 List 的第 N 个分组**取值（含 `$0` 全匹配） | `:1030,744-771,783-791` |

**`{{}}` / `@get:` 会改写整条规则的 mode**：若它们位于串首、或前文不含 `##`，规则被切成 `Mode.Regex`——即整条退化成「字符串拼装」，`getString` 走 `else -> rule` 原样返回（`:704-710`、`:263,358`）。

**本仓没有 `<%…%>`**（全库 0 命中），`%` 单独也无意义，只有 `%%`。

### 6. 正则写法（真实实现只有两种）

`AR\AnalyzeByRegex.kt` 全文 59 行，`@Keep object`，**只有两个函数**：

| 函数 | 行为 | 行号 |
|---|---|---|
| `getElement(res, regs, index)` | 多段管道：非末段把所有 find 匹配结果**串联**再喂给下一段；末段取**第一次匹配**的全部捕获组（含 `$0`） | `:9-31`（`:16-22`） |
| `getElements(res, regs, index)` | 末段**每次匹配一组**，返回 `List<List<String>>` | `:32-58`（`:39-51`） |
| 无匹配 | `getElement` → `null`；`getElements` → 空表 | `:11-14,34-37,47` |

- 多段管道 `正则1&&正则2` 是由调用方 `rule.splitNotBlank("&&")` 传 `Array<String>` 实现的（`AR\AnalyzeRule.kt:400-403,433-436,464-467`）。
- **`=` 号语法、`[0,1]` 取多组、`##` 替换都不在这个文件里**：选组在 `AnalyzeRule:744-771`，替换在 `:824-834` + `:544-568`。

### 7. `##` 替换语法

`rule.split("##")` 四段切分（`AR\AnalyzeRule.kt:824-834`），执行在 `:544-568`：

| 写法 | 含义 |
|---|---|
| `规则##匹配` | 用「匹配」正则替换为空 |
| `规则##匹配##替换` | 替换成指定文本 |
| `规则##匹配##替换###` | **末尾多一个 `##`** → 只替换**首个**匹配（`:549-559`） |

### 8. 取值 API：取第一个 vs 取全部

| API | 行为 | 行号 |
|---|---|---|
| `getString(ruleStr)` | 多值时 JSoup 侧 `joinToString("\n")`；`unescape` 控制 HTML4 实体还原（`:369-373`）；**`isUrl = true` 时改用 `getString0` 只取第一个**并转绝对 URL | `:302,308,315-382`（`:352-356,374-380`） |
| `getStringList` | 取全部；`String` 结果再按 `\n` 切（`:279-281`）；`isUrl = true` 逐个绝对化 + **去重** | `:205,212-296`（`:282-293`） |
| `getElement` | 单个对象；`Mode.Json → getObject`、`Mode.Regex → AnalyzeByRegex.getElement` | `:387-417` |
| `getElementsRaw` / `getElements` | 取列表；`getElements` 额外剔除 `null` / `Scriptable.NOT_FOUND` 并展平 `NativeArray` | `:422-450,453-502` |
| `getObject` | **AnalyzeRule 没有这个公开方法**；只有 `AR\AnalyzeByJSonPath.kt:128-130`（`ctx.read(rule)` 原样返回） | |
| `getString0` | 「只取第一个」的实现本体 | `AR\AnalyzeByJSoup.kt:62-63` |

JS 对象短路：内容是 `NativeObject` / `LinkedTreeMap` 时直接按**键名**访问；`getParamSize() > 1` 时返回 `{{}}` 拼好的字面量（`AR\AnalyzeRule.kt:221-247,325-339`）。

> **没有 `getStrings`** 这个方法名，等价物是 `getStringList`。

### 9. AnalyzeByJSoup 的写法全集

#### 前置规则 key（`beforeRule.split(".")` 后判定，`AR\AnalyzeByJSoup.kt:308-316`）

| key | 效果 | 行号 |
|---|---|---|
| `children` | `temp.children()` | `:310` |
| `class.xxx` | `getElementsByClass` | `:311` |
| `tag.xxx` | `getElementsByTag` | `:312` |
| `id.xxx` | `Collector.collect(Evaluator.Id(...))` | `:313` |
| `text.xxx` | `getElementsContainingOwnText`（**ownText 匹配**，非全文） | `:314` |
| 其它任意串 | `temp.select(beforeRule)` → 走 CSS | `:315` |
| 空 beforeRule | `temp.children()`（此时索引可当根元素用） | `:306` |

#### 末段取值 key（最后一个 `@` 之后，`getResultLast` `:224-275`）

| key | 效果 | 行号 |
|---|---|---|
| `text` | `element.text()` | `:227` |
| `textNodes` | 子节点文本、逐条 trim、`\n` 合并 | `:234` |
| `ownText` | `element.ownText()` | `:248` |
| `html` | **剔除 script/style 后**的 outerHtml | `:255` |
| `all` | 整体 outerHtml | `:264` |
| **其它任意字符串** | 当作**属性名** `element.attr(lastRule)`（跳过空白、结果去重） | `:265-272` |

写法示例：`tag.a.0@href`、`.item@textNodes`、`@CSS:.box@html`、`tag.div!0:3`。

> **没有** `@text:` / `@defaultText` / `allElements` / `@allInOne` 这些写法。`allInOne` 只是 `splitSourceRule` 的布尔形参（`AR\AnalyzeRule.kt:593`）。

#### 索引语法（`ElementsSingle`，`:289-506`）

| 风格 | 写法 | 说明 | 行号 |
|---|---|---|---|
| 阅读传统式 | `tag.div.-1:10:2`、`tag.div!0:3` | `:` 分隔索引，`.` 选择 / `!` 排除，支持负数 | `:477-501`（注释 `:277-287`） |
| JSONPath 式 | `tag.div[-1, 3:-2:-10, 2]` | 方括号组 | `:413-476` |
| 排除 | `[!…]` | 落地时**倒序 `deselect`**，不动原 DOM | `:456-461`、`:381-385` |
| 区间 | `[start:end]` / `[start:end:step]` | 省略 start = 0，省略 end = len-1 | `:337,340` |
| 越界 | — | 钳制到边界 | `:343-352` |
| 反序 | `[-1:0]` | `end < start` 自动反向，故可**整表反转** | `:365`（注释 `:286`） |

#### XML

内容串首是 `<?xml` → 用 `Parser.xmlParser()`（`:28-32`），否则走 `parseSourceHtml`（`:33`）。

### 10. URL 字段的高级写法

结构：`<URL主体> , {"UrlOption JSON"}`，切分正则 `paramPattern = \s*,\s*(?=\{)`（`AR\AnalyzeUrl.kt:817`）；`baseUrl` 也先按同一 pattern 剥（`:137-138`）。`AR\CustomUrl.kt:13-23` 用同 pattern 解析属性，`toString()` = `url + "," + GSON.toJson(attr)`（`:42-47`）。

| 写法 | 语义 | 行号 |
|---|---|---|
| `@js:result.replace(…)` / `<js></js>` | **最先执行**，`@result` 占位符回填前段结果 | `:178,188-211`（`:196,206`） |
| `{{ … }}` | 内嵌 JS 或字符串；Double 的整值会格式化成 `%.0f` | `:218-230` |
| `<1,2,3>` / `<{"p":1},{"p":2}>` | **翻页规则**，`pagePattern = <(.*?)>`，逗号切分；**page 越界取最后一段** | `:818,232-241` |
| `/s?q={{key}}&p=<{{page*2}}>` | 先算 `{{}}` 再套 `<…>`（注释说明了顺序原因） | `:216` |
| `,{"method":"POST","body":"…"}` | 逗号后 JSON 反序列化为 `UrlOption`；不规范 JSON 降级 GSON 并告警 | `:257-265` |
| `charset=GBK` / `charset:"escape"` | 非 UTF-8 走 `charset(name)`；`escape` 走 `EncoderUtils.escape` | `:333-389`（`:336-338,385`） |
| GET query / POST form | GET 解析 `?` 后为 query（`:309-315`）；POST body 非 JSON/XML 且无 Content-Type 时按 `a=b&c=` 解析（`:302-307,325-327`） | |
| body 三种发法 | 有 form → `postForm`；有 Content-Type → 原始 body + 该 mediaType；否则 → `postJson` | `:522-534,600-612` |
| `"headers":{…}` | **覆盖式**合并进 `headerMap`（LinkedHashMap） | `:274-276,108` |
| `"webView":true` + `"webJs"` + `"webViewDelayTime"` | BackstageWebView 渲染，带延迟 | `:283-284,298,482-517` |
| `"followRedirects":false` | 3xx **在进 WebView 之前**就直接返回 | `:290,494-496`；`AR\AnalyzeUrlNetworkOptions.kt:72-75` |
| `"dnsIp":"1.2.3.4,::1"`（别名 `resolveIp`） | 仅对目标 host 生效的固定 DNS；与 proxy **互斥**（require 抛错） | `:291,649-653`；`AnalyzeUrlNetworkOptions.kt:66-70,126-149` |
| `"js":"…"` | 解析完 options 后执行，**结果覆盖 url** | `:292-296` |
| `"bodyJs":"…"` | 拿到响应后二次处理 body | `:285,548-551` |
| `"type":"…"` | 响应转 hex 字符串返回（`:461-463`）；也用于 `postMultipart`（`:744`） | |
| `"retry":3` | 重试次数 | `:282,914-916` |
| `"serverID"` | 透传 | `:132,297` |
| `"origin":"书源地址"` | **指定用哪个书源解析这条 URL**。字段 `:837`（注释「源Url」`:835`）、`setOrigin:902-904`、`getOrigin:906-908`。消费点：书架找源 `Kui/main/bookshelf/BookshelfViewModel.kt:71-77` 与入架对话框 `Kui/association/AddToBookshelfDialog.kt:114-117`——两者都用 `paramPattern` 剥出逗号后的 options 段，`GSON.fromJsonObject<AnalyzeUrl.UrlOption>(…).getOrNull()?.getOrigin()`，再 `bookSourceDao.getBookSource(origin)` + `bookUrl.matches(bs.bookUrlPattern!!.toRegex())` 定位书源 | `:837,902-908` |
| `data:` URI | `getByteArrayAwait` / `getInputStreamAwait` 直接 base64 解码，**不发请求** | `:683-694,699-704,715-720` |

**没有 `<get,post>`、`@media` 这种写法。** 方法在 `UrlOption.method`（`:830,267-273`），媒体在 `getMediaItem()` → `ExoPlayerHelper.createMediaItem(url, headerMap)`（`:821-824`）。
**`base_url` 不是 URL 字面量语法**，它是构造参数 `baseUrl`（`:88`）与 JS 绑定名 `baseUrl`（`:398`）；`AnalyzeUrl` 内部会用地址反推更新 `baseUrl`（`:254-256`）。

#### 网络选项的取值约束（`AR\AnalyzeUrlNetworkOptions.kt`，全 internal，不引入新语法）

| 字段 | 取值/校验 | 行号 |
|---|---|---|
| `timeout` | 数值或数字串；**必须整数化**（Float/Double 仅在 `x % 1 == 0` 时接受）；范围 `1..Int.MAX_VALUE` ms | `:12-23` |
| 派生 callTimeout | 未显式给 `callTimeout` 时 = `max(60_000, 2 × readTimeout)` | `:9-10,77-84`；`AnalyzeUrl.kt:111-115` |
| `followRedirects` | 只认 `true/1/false/0`（忽略大小写与空格），其余视为未设置 | `:25-40` |
| `dnsIp`/`resolveIp` | 逗号分隔 IPv4/IPv6 字面量，可 `[::1]` 包裹；含空白或 `%` zone 非法 | `:42-48,126-149` |
| `proxy` | **不是 UrlOption 字段**，来自 header 里的 `proxy`（`AnalyzeUrl.kt:143-146` → `getProxyClient` `:640`）；与 `dnsIp` 互斥（`:639`） | |
| Cronet | 设了 `timeout` / `followRedirects` / `dnsIp` 任一 → 从 client 里**移除 `Cronet.interceptor`**（这三项与 Cronet 引擎互斥） | `AnalyzeUrl.kt:654-669`；`AnalyzeUrlNetworkOptions.kt:86-124` |
| cookie | 书源 `enabledCookieJar` → header `cookieJarHeader=1`（`AnalyzeUrl.kt:126,769-773`）；`setCookie` 优先级：urlOption 临时 > 库（`:748-774`） | |
| 并发 | `ConcurrentRateLimiter(source)`（`:129,467`） | |

登录头的跨域保护：`proxy` 从 header 取出并删除（`:139-147`）；token 类头**仅同站发送**（`:154-170`）。

#### `SourceHtmlParser`（全文 16 行）

**不提供「解析器选择」开关**，只有一个内部函数 `parseSourceHtml(html): Document`（`AR\SourceHtmlParser.kt:9-16`）——在 `TagSet.Html()` 上把所有 HTML 命名空间标签注册为 `Tag.SelfClose`，让 `<a/>` 能自闭合（`:12-13`），从而 **CSS 与 XPath 共用同一 DOM**（注释 `:10-11`）。消费方仅 `AnalyzeByJSoup.kt:26,33` 与 `AnalyzeByXPath.kt:40`。

### 11. 与上游记忆 / 内置 `ruleHelp.md` 的冲突清单

| 常见写法 | 源码事实 |
|---|---|
| 「联想规则 / 原生规则」 | **本仓无此术语**（`app/src/main` + assets 全库零命中）。真实机制是 `RuleAnalyzer` 的「首段匹配 `:165` / 二段匹配 `:240`」，以及「外层规则 vs 内嵌规则」（`innerRule :308` / `:339`） |
| `:` `=` `$.` `//` 是 RuleAnalyzer 分隔符 | RuleAnalyzer 只有 `ESC = '\\'`（`:375`）；分隔符由调用方传入。`$.` `$[` `//` `:` 是 **AnalyzeRule 的 mode 判定前缀**（`:685-693`、`:599-603`），不是切分符；`=` 在任何层都不是分隔符（只在 URL query/form 解析 `AnalyzeUrl.kt:357`） |
| `@正则=`、`:regex=` | **不存在**。正则只有 `:整串正则`（依赖 `allInOne`，仅 getElement/getElements 生效）与 `$N` + `##` 组合 |
| `@text:` | 不存在。是 `@` 之后的末段 key `text`（`AnalyzeByJSoup.kt:227`），**无冒号** |
| `<%…%>`、`%` 循环 | 全仓无 `<%`。`%` 单独无意义，只有 `%%`（按索引交叉），且 JSON/XPath 的 `getString` 不支持 |
| `@allInOne`、`@defaultText`、`allElements` | 均不存在。可用末段 key 仅 `text` / `textNodes` / `ownText` / `html` / `all` / 任意属性名 |
| `<get,post>`、`@media` | 均不存在。方法在 `UrlOption.method`，媒体在 `getMediaItem()` |
| `getStrings` | 无此方法名，等价物是 `getStringList`；「只取第一个」是 `getString0` |
| `getObject` 属于 AnalyzeRule | AnalyzeRule 无该方法，只有 `AnalyzeByJSonPath.getObject`（`:128`） |
| 内置变量 `bookUrl`、`searchKey` | 绑定名是 `book`（对象，走 `book.bookUrl`）与 `key`，**没有 `bookUrl` / `searchKey`** |
| `@CSS:` 会被 AnalyzeRule 剥掉 | AnalyzeRule 层**保留**原串（`:665-668`），由 `AnalyzeByJSoup.kt:511-516` 剥 5 字符；与 `@Json:` / `@XPath:` 的剥皮时机不同 |



## 第三部分　`@js:` 宿主接口

取证基准提交 `234543e1f`。路径简写：

| 简写 | 实际路径 |
|---|---|
| `JX` | `app/src/main/java/io/legado/app/help/JsExtensions.kt` |
| `EU` | `app/src/main/java/io/legado/app/help/JsEncodeUtils.kt` |
| `AR` | `app/src/main/java/io/legado/app/model/analyzeRule/AnalyzeRule.kt` |
| `AU` | `app/src/main/java/io/legado/app/model/analyzeRule/AnalyzeUrl.kt` |
| `RSS` | `app/src/main/java/io/legado/app/ui/rss/read/RssJsExtensions.kt` |
| `RH` | `modules/rhino/src/main/java/com/script/rhino/` |
| `MD` | `app/src/main/assets/web/help/md/jsHelp.md`（App 内置帮助） |

---

### 0. 最重要的一条：`@JavascriptInterface` 不等于「可调集合」

书源 JS 里 `java.` 后面能调什么，取决于 **public 方法全集**，而不是被 `@JavascriptInterface` 标注的那部分：

- 全仓**没有任何** `isAnnotationPresent` / `JavascriptInterface::class` 的反射检查（已 grep 确认 0 命中）——即注解**不参与** Rhino 侧的准入判断。
- Rhino 侧的限制是**类级**屏蔽（`RH\RhinoClassShutter.kt:49-121`，见第 5 节），**没有方法级白名单**。Rhino 核心本身来自依赖（`modules/rhino` 只有 `com/script/**` 与 `org/htmlunit/**`，未内联 `NativeJavaObject` 源码），其对象包装按 Java 可见性走。
- `@JavascriptInterface` 只在**真 WebView 桥接**里才有效果：`help/webView/WebJsExtensions.kt:28` 继承 `RssJsExtensions`，被 `BackstageWebView.kt:125`、`WebViewActivity.kt:137`、`ReadRssActivity.kt:460`、`BookInfoActivity.kt:708-709` 通过 `addJavascriptInterface` 注入——**那条路径由 Android WebView 自己按注解过滤**。

> 结论：写书源 `@js:` 时按 `JsExtensions`（+ 继承的 `JsEncodeUtils`）的 public 方法全集来用；只有在**网页里**（`ContentRule.webJs`、RSS 页面内 JS、登录界面）才受 WebView 的注解限制。

### 1. JsExtensions 方法分组

#### 日志与提示

| 签名 | 行号 | 作用 |
|---|---|---|
| `log(msg: Any?): Any?` | `JX:1186` | 输出到书源调试 |
| `logType(any: Any?)` | `JX:1198` | 打印值的类型 |
| `toast(msg: Any?)` | `JX:1170` | 轻提示 |
| `longToast(msg: Any?)` | `JX:1178` | 长提示 |

#### 网络

| 签名 | 行号 | 作用 |
|---|---|---|
| `ajax(url: Any): String?` / `ajax(url, callTimeout: Long?)` | `JX:174` / `178` | 取响应文本。**`callTimeout` 是 `Long?`，内置文档写的 `Int?` 是错的**（`MD:203`） |
| `ajaxAll(list, skipRateLimit)` | `JX:198` / `201` | 批量请求；内部 `runBlocking` |
| `ajaxTestAll(list, timeout, skipRateLimit)` | `JX:217` / `220` | 批量试请求 |
| `connect(url, header, callTimeout: Long?)` | `JX:238` / `254` / `258` | 返回 `StrResponse`（含 header/编码）。**`MD:212` 的 `Int?` 同样过时** |
| `get` / `head` / `post(url, headers, timeout: Int?)` | `JX:556` / `583` / `610` | 便捷请求 |
| `webView(html, url, js)` / `+(cacheFirst)` | `JX:277` / `289` | 后台 WebView 渲染取内容；`runBlocking`，**主线程调用直接 error** |
| `webViewGetSource` / `webViewGetOverrideUrl` | `JX:315` / `350` | 取渲染后源码 / 拦截到的跳转地址 |
| `startBrowser(url, title)` / `+(html?)` | `JX:380` / `384` | 拉起浏览器做人机验证，**不阻塞** |
| `startBrowserAwait(url, title)` / `+(refetchAfterSuccess)` / `+(…, html?)` | `JX:393` / `397` / `401` | 同上但**阻塞等待结果**，要求后台线程 |
| `getVerificationCode(imageUrl): String` | `JX:423` | 弹验证码图让人输入，阻塞 |
| `getCookie(tag)` / `getCookie(tag, key?)` | `JX:480` / `485` | 读 Cookie（**内置文档只写了 `cookie.getCookie`，漏了这条**，见 `MD:609`） |

#### 加解密

| 签名 | 行号 | 作用 |
|---|---|---|
| `base64Encode` / `base64Decode` / `base64DecodeToByteArray` | `JX:656-690`（`:661,666,685,690` / `:671,678`） | Base64 |
| `hexDecodeToByteArray` / `hexDecodeToString` / `hexEncodeToString` | `JX:696` / `701` / `707` | Hex |
| `md5Encode` / `md5Encode16` | `EU:21` / `26` | MD5 |
| `createSymmetricCrypto(...)` | `EU:45,54,61,68` | 对称加密句柄 |
| `createAsymmetricCrypto` / `createSign` | `EU:80` / `87` | 非对称 / 签名 |
| `aesDecodeToString` / `aesBase64DecodeToString` / `aesEncodeToString` / `aesEncodeToBase64String` / `aesDecodeArgsBase64Str` / `aesEncodeArgsBase64Str` | `EU:123` / `187` / `222` / `257` / `144` / `279` | AES 便捷函数 |
| `des*` / `tripleDES*` | `EU:295-328` / `EU:350-431` | DES / 3DES 便捷函数 |
| `digestHex` / `digestBase64Str` / `HMacHex` / `HMacBase64` | `EU:455` / `470` / `487` / `505` | 摘要与 HMAC |

> **内置文档只写了 `createXxx` + `cipher` 链，`aes*` / `des*` / `tripleDES*` 这批便捷函数在 `MD` 里没有对应章节。**

#### 文本 / 时间 / URL

| 签名 | 行号 | 作用 |
|---|---|---|
| `encodeURI(str)` / `+(str, enc)` | `JX:733` / `742` | 编码 |
| `htmlFormat(str)` / `+(str, redirectUrl)` | `JX:751` / `756` | HTML 格式化 |
| `t2s(text)` / `s2t(text)` | `JX:762` / `766` | 繁↔简 |
| `timeFormatUTC` / `timeFormat` | `JX:715` / `727` | 时间格式化 |
| `toNumChapter(s)` | `JX:1148` | 「第一章」→ 数字 |
| `toURL(urlStr)` / `toURL(url, baseUrl?)` | `JX:1159` / `1163` | 构造 `JsURL` |
| `getWebViewUA()` | `JX:771` | 取 WebView UA |
| `strToBytes` / `bytesToStr` | `JX:636` / `645` | 字节互转 |

#### 文件 / 缓存 / 归档

| 签名 | 行号 | 作用 |
|---|---|---|
| `cacheFile(urlStr)` / `+(urlStr, saveTime)` | `JX:451` / `460` | 落地缓存并返回本地路径 |
| `importScript(path)` | `JX:436` | 引入外部 JS 库 |
| `downloadFile(url)` / `downloadFile(content, url)` | `JX:499` / `535`（后者 `@Deprecated`） | 下载 |
| `getFile(path)` / `readFile(path)` / `readTxtFile` | `JX:783` / `798` / `806,816` | **文档缺失**：`MD` 里没有 `getFile`/`readFile` |
| `deleteFile` | `JX:828` | 删除 |
| `unzipFile` / `un7zFile` / `unrarFile` / `unArchiveFile` | `JX:839` / `849` / `859` / `869` | 解压 |
| `getTxtInFolder` | `JX:883` | 列目录内文本文件 |
| `getZipStringContent` / `getRar…` / `get7z…`（含 charset 重载） | `JX:908,915` / `927,934` / `946,953` | 从压缩包取文本 |
| `get{Zip,Rar,7z}ByteArrayContent` | `JX:965` / `993` / `1011` | 从压缩包取字节 |
| `cache`（绑定对象，非本类方法） | 见 `AR:900`、`AU:400` | `cache.deleteMemory(key)` 等，文档在 `MD:640` 附近 |

#### 字体反混淆

| 签名 | 行号 | 作用 |
|---|---|---|
| `queryTTF(data, useCache?)` / `queryTTF(data)` | `JX:1044` / `1088` | 解析自定义字体为 `QueryTTF` |
| `replaceFont(...)` | `JX:1098` / `1135` | 按字体映射还原文本 |
| `queryBase64TTF` | `JX:1033` | **已废弃** |

> 这一整组在内置 `MD` 中**没有文档**。

#### 跳转 / UI / 刷新

| 签名 | 行号 | 作用 |
|---|---|---|
| `openUrl(url, mimeType?)` | `JX:1220` / `1229` | 跳外部链接或应用；`legado://` / `yuedu://` 直接进应用内导入确认页（`JX:1233` 附近） |
| `showBrowser(url, html?, preloadJs?, config?)` | `JX:1249-1256` | 应用内浏览器展示 |
| `refreshBookInfo` / `refreshBookToc` / `refreshContent` | `JX:155` / `159` / `163` | 让当前书的重取详情 / 目录 / 正文（**文档缺失**） |
| `cacheContent(chapter, content)` | `JX:147` | 仅在 `contentBatch` 批量正文上下文中可用，否则抛错（`JX:150` 附近） |
| `canOpenSourceUi()` | `JX:1280`（private） | 受 `AppConfig.blockSourceNavigation` 抑制的内部闸门；上面几个跳转方法都过它 |

#### 变量存取（不在 JX 里）

| 成员 | 位置 | 说明 |
|---|---|---|
| `put(key, value)` | `AR:856` | 作用域链 `chapter → book → ruleData → source` |
| `get(key)` | `AR:875` | `localBindings` 最优先 |
| `setLocal(key, value)` | `AR:867` | 只作用于本次解析实例（**文档缺失**） |
| `put` / `get` | `AU:426` / `435` | URL 侧，只到 `chapter → ruleData` |
| `put` / `get` | `RSS:61` / `67` | RSS 侧**只写源变量** |

#### 配置 / 标识 / 并发

| 签名 | 行号 | 作用 |
|---|---|---|
| `getReadBookConfig()` / `getReadBookConfigMap()` | `JX:1341` / `1345` | 取阅读配置 |
| `getThemeMode()` / `getThemeConfig()` / `getThemeConfigMap()` | `JX:1353` / `1360` / `1366` | 取主题 |
| `randomUUID()` / `androidId()` | `JX:1210` / `1215` | 标识 |
| `singleFlight(name, action, timeoutMs)` | `JX:1286` / `1303` | 同名请求合并 |
| `lock(...)` | `JX:1308` / `1319` | 具名锁 |
| `tick(name): Int` | `JX:1324` | 单调递增计数（做翻页/请求序号） |

### 2. RssJsExtensions 独有（RSS 源、`webJs`、登录界面）

`RSS` 继承 `JX`，额外提供（`app/src/main/java/io/legado/app/ui/rss/read/RssJsExtensions.kt`）：

| 成员 | 行号 | 作用 |
|---|---|---|
| `put(key, value)` / `get(key)` | `RSS:61` / `67` | **只写/读源级变量**，无章节与书籍级 |
| `searchBook(key, searchScope?)` / `searchBook(key, BookSource)` | `RSS:73` / `79` | 从 RSS 里搜书并入架 |
| `addBook(bookUrl)` | `RSS:86` | 加入书架 |
| `showPhoto(src)` | `RSS:91` | 显示图片 |
| `open(name, url?, title?, origin?)` | `RSS:98` | 打开 sort / rss / explore / search / login 页面（分支 `:103-242`） |
| `setContent(content, baseUrl?)` | `RSS:277` | 设定后续 `getString` 的解析内容 |
| `setBaseUrl(url)` / `setRedirectUrl(url)` | `RSS:282` / `287` | **文档缺失** |
| `getStringList` / `getString(rule, mContent, isUrl)` / `getString(rule, unescape)` | `RSS:292` / `297` / `302` | 对内部 `analyzeRule`（`RSS:271`）的包装 |
| `getElement` / `getElements` | `RSS:306` / `310` | 同上 |

内部 `book` / `chapter` 取自 `ReadBook` 与 `AudioPlay`（`RSS:248-267`）。
`WebJsExtensions`（`help/webView/WebJsExtensions.kt:28`）在此之上再加 `upConfig`（`:39`）与 `request`（`:49`）。

### 3. 引擎与两种 JS 写法

| 项 | 结论 | 证据 |
|---|---|---|
| 引擎 | HtmlUnit Core JS `5.3.0-legado.4`（Rhino 系），**不是 QuickJS** | `gradle/libs.versions.toml:59`；入口 `RH\RhinoScriptEngine.kt:148,159,292` |
| `@js:` 与 `<js>…</js>` | **同一正则** `AP:8`，`AR:607-617` 切成 `Mode.Js`，再由 `AR:259,330,349,406` 调 `evalJS(rule, result)` | 差别只在 `@js:` 吃到串尾、`<js>` 有闭合标签 |
| `<%…%>` | **不存在**（全库 0 命中） | — |
| 字符串插值 | `{{js}}`（`AP:11`）与 `@get:{k}`（`AR:1028-1029`）；`@put:{…}` 在 `AR:1027,518` | |
| URL 内 | `{{}}` 由 `AU:218-229` 求值，`@js:` 由 `AU:188-211` 求值 | |

### 4. 全同步语义（没有 Promise）

- **没有 Promise / async / import**（`MD:1052-1053` 亦如此说明）。阻塞靠 `runBlocking(context)` 实现，例：`webView`（`JX:289-293`，**主线程调用直接 error**）、`ajaxAll`（`JX:202`）。
- `startBrowser` 只启 Activity，**不阻塞**（`help/source/SourceVerificationHelp.kt:362`）。
- `startBrowserAwait` / `getVerificationCode` **阻塞等待**：要求后台线程（`SourceVerificationHelp.kt:214`），用 `CountDownLatch`（`:35`）+ `unpark`（`:380`）。

### 5. 安全屏蔽

`RH\RhinoClassShutter.kt:49-121` 屏蔽了一批类，写书源时不要指望能直接用：

| 被屏蔽 | 行号 |
|---|---|
| `java.lang.Runtime` | `:54` |
| `java.io.File` / `FileDescriptor` / `FileInputStream` / `FileOutputStream` / `FileReader` / `FileWriter` | `:58-64` |
| `java.lang.Class` / `reflect` | 清单内 |
| `android.*` 敏感类 | 清单内 |
| `io.legado.app.data.dao` / `AppDatabase` | `:115` |
| `com.script`、`sun` / `libcore` | 清单内 |

> `java` 这个变量名已被宿主占用，要在 JS 里调 JDK 包得写 `Packages.java.*`。
> 需要文件操作请用 `JX` 提供的 `getFile` / `readFile` / `cacheFile`，不要试图 `new java.io.File(...)`。

### 6. 作用域层级

- 每次 `evalJS` 新建一个 `ScriptBindings`（`AR:897-915`），挂在 `jsLib` / `CryptoJS` 的父层上（`SharedJsScope.kt:74,103`）；`ScriptBindings.kt:32` 的写**不落父层**。`globalThis` 可按源共享（`AR:916-919`）。
- `java.put` / `java.get` 的可见范围**三处不同**，这是最容易造成「变量明明写了却读不到」的原因：

| 场景 | 作用域链 | 持久化 |
|---|---|---|
| 内容解析（`AR`） | `localBindings` → chapter → book → ruleData → source（`AR:860-863,877-889`） | 是 |
| URL 解析（`AU`） | chapter → ruleData（`AU:436-448`），且 `get` 先查 `extraParams` | 是 |
| RSS / webJs（`RSS`） | **只到源**（`RSS:61-69`） | 是 |
| `setLocal`（`AR:867`） | 仅本次实例 | 否 |

- 大变量：`putVariable` 按长度分流（`AR\RuleDataInterface.kt:7-28`）——`null` 时删除；`value.length < 10000` 存进 `variableMap`；**≥ 10000 才走 `putBigVariable`**。读取是 `getVariable(key) = variableMap[key] ?: getBigVariable(key) ?: ""`（`:32-34`）。
  **真正会落盘的大变量实现方**：`Kdata/entities/BaseBook.kt:38`、`Kdata/entities/BookChapter.kt:105`、`Kdata/entities/BaseRssArticle.kt:25`（书/章节级 `variable` 列）。而 `AnalyzeRule` 的兜底持有者 `RuleData`（`AR\RuleData.kt:11-21`）里 `putBigVariable` **直接写回 `variableMap`**、`getBigVariable` **恒返回 null**——所以**没有书/章节载体时，大变量退化成普通内存变量，不会持久化**。`AnalyzeRule` 的 `ruleData` 由 `setRuleData`（`AR\AnalyzeRule.kt:64,1037`）注入。
- `put("bookName", …)` / `put("title", …)` 会告警「特定情况下被覆盖」（`AR:857-859`），读取时 `bookName`→`book.name`、`title`→`chapter.title` 是硬编码（`AR:877-885`）。

### 7. JS 里可直接引用的内置变量

#### 内容侧（`AR:897-915`）

`java` `cookie` `cache` `source` `book` `result` `baseUrl` `chapter` `chapters` `title` `src` `nextChapterUrl` `rssArticle` `fromBookInfo`，以及来自 `setLocal` 的 `paraIndex` `paraData` `page`（`page` 会尝试转 Int，`:914`）

#### URL 侧（`AU:396-412`）

`java` `baseUrl` `cookie` `cache` `page` `key` `speakText` `speakSpeed` `book` `source` `result` `infoMap` + `extraParams` 展开的自定义参数（`AU:409`）

#### 其它注入点

| 上下文 | 变量 | 证据 |
|---|---|---|
| 单文件 JS 源 | `java` `cookie` `cache` `source` `sourceApi` `baseUrl` | `model/jsSource/JsSourceEngine.kt:108-110`；`data/entities/BaseSource.kt:411-416` |
| 目录格式化 | `gInt` `index` `chapter` `title` | `model/webBook/BookChapterList.kt:143-…` |
| 本地 txt | `result` `book` `index` `prevTitle` `prevLength` `lastVolumeTitle` `java` | `model/localBook/TextFile.kt` |
| 净化 / 替换规则 | `result` `chapter` `book` `java` | `utils/RegexExtensions.kt` |
| 书名作者 | `name` `author` `epubIndex` | `help/book/BookExtensions.kt` |
| 本地书 | `src` | `model/localBook/LocalBook.kt` |

> **两个不存在的变量**：本仓**没有 `bookUrl` 也没有 `searchKey`** 内置变量。书籍地址走 `book.bookUrl`，搜索关键字绑定名是 `key`。
> `isFromBookInfo` 也是错的——实际绑定名是 `fromBookInfo`（`AR:911`），内置文档 `MD:34` 写成了 `isFromBookInfo`。

### 8. 内置文档与代码的差异清单

#### 文档写了、代码不是那样

| 文档位置 | 问题 | 源码事实 |
|---|---|---|
| `MD:34` | 变量名写作 `isFromBookInfo` | 绑定名是 `fromBookInfo`（`AR:911`） |
| `MD:121-124` | 描述 `AnalyzeUrl.initUrl()`（登录检测 JS 中重新解析 URL） | 方法存在且 public：`AU:175`（`init` 在 `:148` 调用）；`java` 绑定的就是 AnalyzeUrl 实例，故 `java.initUrl()` 可调用 |
| `MD:203` / `MD:212` | `callTimeout: Int?` | 实际是 `Long?`（`JX:178` / `JX:258`） |

#### 代码有、文档缺失

| 位置 | 缺失项 |
|---|---|
| `JX:480,485` | `java.getCookie(tag, key?)`（文档只有 `cookie.getCookie`，`MD:609`） |
| `JX:1044,1088,1098,1135` | `queryTTF` / `replaceFont` 字体反混淆整组 |
| `JX:1148` | `toNumChapter` |
| `JX:783,798` | `getFile` / `readFile` |
| `JX:147,155,159,163` | `cacheContent`、`refreshBookInfo` / `refreshBookToc` / `refreshContent` |
| `EU:123-432` | `aes*` / `des*` / `tripleDES*` 便捷加解密（文档只写 `createXxx` + cipher 链） |
| `AR:867` | `setLocal` |
| `AR:985,1010` | `reGetBook`、`refreshTocUrl` |
| `AR:302,306,310` + `RSS:277-310` | `setContent` / `getString` / `getStringList` / `getElement` / `getElements`（RSS 语境） |
| `RSS:61,67,282,287` | `java.put` / `java.get`（RSS 场景）、`setBaseUrl`、`setRedirectUrl` |
| `RSS:73,79,86,91,98` | `searchBook`、`addBook`、`showPhoto`、`open` |
| `AR:906,912-914` / `AU:401-404,411` | 变量 `chapters` `paraIndex` `paraData` `page` `key` `speakText` `speakSpeed` `infoMap` 未进 `MD:20-34` 的变量表 |
| `BaseSource.kt:413` / `JsSourceEngine.kt:110` | `sourceApi` 只在 `MD:726` 说过，未进变量表 |

#### 编辑器补全表（`ui/code/RuntimeObjectCompletionLanguage.kt:99-115`）

- **没有失效项**：逐条 grep 验证过（含 `putConcurrent` / `evalLoginUiV2` / `refreshJSLib`、`getPlaySpeed` / `getTtsEngine` / `upCustomIntro`、`getAbsoluteURL` / `putImgUrl` / `putLyric`），**视频与 MCP 残留为 0**。
- **缺失（代码有、表里没有）**：`cacheContent`、`refreshBookInfo/Toc/Content`、`setLocal`、`reGetBook`、`refreshTocUrl`、`setContent`/`getString`/`getStringList`/`getElement`/`getElements`、`put`、`open`/`searchBook`/`addBook`/`showPhoto`/`setBaseUrl`/`setRedirectUrl`、`aes*`/`des*`/`tripleDES*`。

### 9. 本 fork 已不存在的接口

全库 `openVideoPlayer` / `videoIsPlay` / `barrage` / `MCP` / `Firebase` **0 命中**。

- `java.openVideoPlayer(url, title[, isFloat])` 已随视频模块删除；内置文档正文里那段「视频播放器函数」也一并删掉了（现 `MD` 无该节）。
- `java.call_JS`、MCP 相关接口从未存在/已移除。
- **写旧书源时若看到 `java.openVideoPlayer`，在本 fork 上调用会直接失败**（JS 里 `java` 对象没有该方法）。



## 第四部分　运行链路与实操

取证基准提交 `234543e1f`。路径前缀 `K` = `app/src/main/java/io/legado/app/`，`A` = `app/src/main/`，`T` = `app/src/test/java/io/legado/app/`。

---

### 1. 总览：四阶段

顺序 **搜索 → 详情 → 目录 → 正文**，每一阶段开头都会先分流「单文件 JS 书源」：

| 阶段 | 入口 | JS 源分支 |
|---|---|---|
| 搜索 | `Kmodel/webBook/WebBook.kt:53-114` | `:60` |
| 发现 | `WebBook.kt:136`（`exploreBook`） | `:136` |
| 详情 | `WebBook.kt:202-265` | `:207` |
| 目录 | `WebBook.kt:283-367` | `:303` |
| 正文 | `WebBook.kt:395-509` | `:410` / `:523` |

书源调试页按同一顺序串起来：`Kui/book/source/debug/BookSourceDebugActivity.kt` → `Kmodel/Debug.kt:523 → 545 → 573 → 597`。

### 2. 搜索阶段

| 字段 | 读取处 | 作用与**空值回退** |
|---|---|---|
| `searchUrl` | `WebBook.kt:63-66` | 空 → 抛「搜索url不能为空」 |
| `loginCheckJs` | `WebBook.kt:77-101` | 空 → 跳过登录检测；请求异常时也会走它再重试 |
| `bookUrlPattern` | `Kmodel/webBook/BookList.kt:62-81` | baseUrl 命中 → **直接按详情页解析**，并把 html 存进 `book.infoHtml`（省一次详情请求） |
| `ruleSearch.bookList` | `BookList.kt:84-88,90-96` | **搜索阶段恒用 `ruleSearch.bookList`**；只有**发现**阶段（`isSearch=false`）在 `ruleExplore.bookList` 为空时才回退到 `ruleSearch.bookList`。前缀 `-` 倒序、`+` 剥离 |
| （列表整体为空） | `BookList.kt:100-108` | 且 `bookUrlPattern` 也为空 → **把搜索地址本身当详情页解析** |
| `name` | `BookList.kt:110,220` | 空 → **整条结果丢弃**（`:222`）；经 `BookHelp.formatBookName`（`:810`） |
| `author` / `kind` / `wordCount` / `lastChapter` / `intro` / `coverUrl` | `BookList.kt:112-117,225-278` | 各自 try/catch，规则为空只记日志。**`getString` 空规则返回 `""`、`getStringList` 返回 `null`**（`Kmodel/analyzeRule/AnalyzeRule.kt:302,205`），即**空字段 = 空值，不报错** |
| `bookUrl` | `BookList.kt:111,281-284` | 空 → 回退当前 baseUrl |
| `checkKeyWord` | **不参与搜索** | 只给校验用：`BookSource.getCheckKeyword`（`Kdata/entities/BookSource.kt:216-223`，含 `http`/`::`/`++`/`--` 视为无效）；默认词从 `Kui/book/source/debug/BookSourceDebugActivity.kt:103` 取 |

### 3. 详情阶段

`WebBook.kt:202-265`；字段处理在 `Kmodel/webBook/BookInfo.kt`。

| 字段 | 行为 | 行号 |
|---|---|---|
| （`book.infoHtml` 非空） | **不联网**，直接复用已抓到的 html | `WebBook.kt:212-220` |
| `ruleBookInfo.init` | 空则跳过 | `BookInfo.kt:58-64` |
| `canReName` | 门控 `mCanReName = canReName && !infoRule.canReName.isNullOrBlank()`（`BookInfo.kt:65`）——**`canReName` 留空 = 详情永不改名**；仅当原名与 `canReName` 都不成立、且原值为空时才填 | `:65,68-81` |
| `name` / `author` | 受上面的 `mCanReName` 控制 | `:68-81` |
| `kind` / `wordCount` / `lastChapter` / `intro` / `coverUrl` | 常规取值 | `:83-153` |
| `tocUrl` | 空 → 用 `baseUrl`；**等于 baseUrl 时把 html 存成 `book.tocHtml`**，让目录阶段免请求 | `:157-161`；`WebBook.kt:312` |
| `downloadUrls` | **仅 `bookSourceType = file` 才走**（分支 `:155`）；空 → 抛「下载链接为空」 | `:165-174` |

**是否访问详情页由调用方决定**，`ruleBookInfo` 自身没有开关：`Kui/book/read/ReadBookViewModel.kt:156`（`!isLocal && tocUrl.isEmpty()` 才 `loadBookInfo`，`:214-224` 传 `canReName = false`）、`Debug.kt:546-551`（已有 tocUrl 就跳过）。
本仓**没有** `hasDetailUrl` / `startWebActivity` 这类判定。`ruleBookInfo` 也**没有 header 字段**（`Kdata/entities/rule/BookInfoRule.kt:12-25`）。

### 4. 目录阶段

`WebBook.kt:283-367` + `Kmodel/webBook/BookChapterList.kt`。

| 字段/机制 | 行为 | 行号 |
|---|---|---|
| `ruleToc.preUpdateJs` | 抓目录前执行（只 runPerJs，不取返回值） | `WebBook.kt:283-295` |
| （tocHtml 缓存） | tocUrl 命中缓存则免请求 | `WebBook.kt:312-320` |
| `chapterList` | `-` 前缀倒序会影响最终整表反转 | `BookChapterList.kt:53-60`、`:124` |
| `nextTocUrl` | **1 条 → 串行 while**；**多条 → `mapAsync` 并发**（并发度 `AppConfig.threadCount`） | `:211-226`、`:70-92`、`:99-119` |
| `chapterName` / `chapterUrl` / `isVip` / `isPay` / `updateTime` / `isVolume` | 逐章取值；**url 为空时**：卷用「标题 + index」，普通章用 `baseUrl`（`:262-276`）；**title 为空则丢弃该章** | `:230-286` |
| `formatJs` | **逐章改写标题** | `:139-157` |
| （空列表） | 抛 `TocEmptyException` | `:122` |
| （去重与回填） | 章节去重 `:129`；回填 `book.totalChapterNum` / `latestChapterTitle` `:162-186` | |

### 5. 正文阶段

`WebBook.kt:395-509` + `Kmodel/webBook/BookContent.kt`。

| 字段/机制 | 行为 | 行号 |
|---|---|---|
| （磁盘缓存） | 先查 `BookHelp.contentSaveToken` / `getContent` | `WebBook.kt:402-409` |
| （卷章） | `bookChapter.isVolume && url.startsWith(title)` → **直接返回 `""`，不解析规则** | `WebBook.kt:427-430` |
| **`ruleContent.content` 为空** | **不报错，直接把章节 URL 当正文返回**（`return bookChapter.url`） | `WebBook.kt:431-435`；测试 `Tmodel/webBook/WebBookTest.kt:22-53` |
| `webJs` / `sourceRegex` | 透传给 `AnalyzeUrl` | `WebBook.kt:458-461` |
| `content` | 抽正文 | `BookContent.kt:236` |
| `nextContentUrl` | 翻页；**串行或并发**取决于 `contentBatch`；遇到下一章 URL 即停 | `:256-263`、`:76-129`（`:80-83`） |
| `subContent` | 副文（音频歌词） | `:131-162` |
| `replaceRegex` | 正文内替换 | `:165-172` |
| `title` | 顺带抽取 `imgUrl` | `:173-195` |
| （结果为空白） | 抛 `ContentEmptyException` | `:200-202` |
| `contentBatch` / `maxBatchSize` | 批量正文；上限 50 | `:277-326`；`BookSource.kt:246-258` |

> **`ruleContent` 没有 `purify` 字段**——`Kdata/entities/rule/ContentRule.kt:13-27` 的 13 个字段里没有它（`purify` 一词在源码里只作为字符串资源名 `replace_purify` 出现，与正文净化无关）。净化实际来自：内置格式化 `HtmlFormatter.formatKeepImg`（`BookContent.kt:246`）、用户替换规则（`Khelp/book/ContentProcessor.kt:64,111-120` 的 `contentReplaceRules`），书源侧另有 `ruleContent.replaceRegex`（`BookContent.kt:165-168`）。
> `Kmodel/analyzeRule/ReviewRuleParser.kt` **不属于四阶段**，只服务段评：`Kapi/controller/ReviewController.kt:19,211,302,385`、`Kmodel/jsSource/JsSourceReview.kt:49`。

### 6. 发现页 `exploreUrl` 的写法

解析在 `Khelp/source/BookSourceExtensions.kt:43-105`；缓存键 `md5(bookSourceUrl + exploreUrl)`（`:31-33`）+ `ACache("explore")`（`:29`）。`exploreUrl` 为空 → `emptyList`（`:47`）。

| 写法 | 处理 | 行号 |
|---|---|---|
| `@js:` + 脚本 | **整段当 JS 求值**，结果再解析 | `:57-70` |
| `<js>…</js>` | 取标签内内容求值 | `:71-84` |
| 结果是 **JSON 数组** | 直接反序列化成 `ExploreKind` 列表 | `:87-90` |
| 结果是**纯文本** | 按 `(&&\|\n)+` 分行，每行按 `::` 分割，**只取 `[0]`=标题、`[1]`=URL，第三段被静默忽略** | `:92-94` |
| 解析抛异常 | 生成一个 `ERROR:<msg>` 的 tab（url 位置放堆栈） | `:97-98` |

`ExploreKind` 字段：`title` / `url` / `type` / `action` / `chars` / `default` / `style` / `viewName`（`Kdata/entities/rule/ExploreKind.kt:6-15`）。
**想用 `type` / `action` / `style` 这些扩展字段，就必须写成 JSON 数组**——文本行格式只能给标题和 URL。

JSON 不规范时的提示逻辑见 `BookList.kt:291-306` + `exploreKindsJson`（`:123-128`）。访问分类时把 `infoMap` 注入 JS（`:59-64`；`WebBook.kt:141-150`）。

### 7. 登录与请求头

`header` / `loginUrl` / `loginUi` **都在 `BaseSource`**（`Kdata/entities/BaseSource.kt:68-83`；`BookSource.kt:54-65` 落库），**与 `ruleBookInfo` 无关**。

`getHeaderMap(hasLoginHeader)`（`BaseSource.kt:196-217`）的合成顺序：
1. `header` 支持内联 JS（`:199-201`）；
2. 缺 UA 时自动补（`:209-211`）；
3. 叠加登录头（`:212-215`，键 `loginHeader_<url>`，`:223-241`）。
实际注入点：`Kmodel/analyzeRule/AnalyzeUrl.kt:140`。

UI 分流 `Kui/login/SourceLoginActivity.kt:26-34`：

| 条件 | 走哪条 |
|---|---|
| `hasLoginForm()`（`loginUi` 非空且去空白后非 `[]`，`BaseSource.kt:165-168`） | `SourceLoginDialog`（表单） |
| 否则 | `WebViewLoginFragment`（`loginUrl` 当网页地址，`:162-171`；Cookie 采集 `:101,107`） |

表单登录流程：`SourceLoginDialog.kt:832-866` 先 `putLoginInfo`，再 eval `loginUrl + login()`；行渲染支持 `text` / `password` / `select` / `button` / `toggle`（`:308-648`）；`onDismiss` 自动存草稿（`:868-876`）。
loginUi v2（动态 UI）：`isLoginUiV2`（`BaseSource.kt:115-117`）、`evalLoginUiV2` / `evalLoginActionV2`（`:119-157`）；JS 源会自动置标记（`Kmodel/jsSource/JsSourceConfig.kt:138-151`）。

**登录态存哪里**（没有 `BookSourceLoginInfo` 这个实体）：

| 内容 | 位置 | 行号 |
|---|---|---|
| 表单账号（内存） | `SourceLoginViewModel.loginInfo: MutableMap<String,String>` | `Kui/login/SourceLoginViewModel.kt:27,66` |
| 表单账号（持久化） | `CacheManager` 键 `userInfo_<源key>`，**AES 加密，密钥取 androidId 前 16 字节** | `BaseSource.kt:254-263,305-315,317-320` |
| 登录头 | `CacheManager` 键 `loginHeader_<源key>` | `BaseSource.kt:224,241,245` |
| Cookie | `CookieStore` | |
| 源变量 | `CacheManager` 键 `sourceVariable_<源key>` | `BaseSource.kt:328,330,341,343` |

菜单里的登录入口可见性：`BookSourceEditActivity.kt:179` → `hasLogin()`。

### 8. 书源编辑器：界面字段 ↔ JSON 键

**7 个 tab**（基础 / 搜索 / 发现 / 详情 / 目录 / 正文 / 段评）：`Kui/book/source/edit/BookSourceEditActivity.kt:312-332`，标题串在 `Ares/values/strings.xml:707-713`。

| 映射 | 行号 |
|---|---|
| tab → 该页可编辑字段集 | `setEditEntities:447-461` + `findEditEntity:225-236` |
| 各 tab 的字段清单 | 基础 `:485-499`、搜索 `:503-515`、发现 `:519-530`、详情 `:533-546`、目录 `:549-560`、正文 `:565-579`、段评 `:583-606` |
| 顶部可折叠选项面板（类型下拉 + 6 个开关） | `:374-425` |
| **界面 ↔ JSON 键的集中映射** | `getSource():629-844`（如 `"bookUrlPattern"` → `:660`、`"checkKeyWord"` → `:672`、`"contentBatch"` → `:805`），回写 `ruleX` 在 `:834-843` |
| 「补全开关」影响规则前缀 | `BookSourceEditViewModel.kt:148-153` |

**三种编辑模式**：

| 模式 | 入口 |
|---|---|
| 分字段编辑（本 Activity） | 每个字段可用 `menu_fullscreen_edit` 弹 `CodeEditActivity` 大编辑（`:195-223`） |
| 整份 JSON | `menu_copy` / `paste` / `share`（`:276-283`）→ `importSource` 支持 URL / 数组 / 对象 / **旧字段兼容**（`BookSourceEditViewModel.kt:112-140` → `Khelp/storage/ImportOldData.fromOldBookSource`） |
| 单文件 JS | `isJsSource` / `hasJsSource` 时**强制**跳 `JsSourceEditActivity`（`:101-106,131-132,155-160`），用 CodeEditActivity 编辑整段 js（`Kui/book/source/edit/JsSourceEditActivity.kt:109-121`，语言 `source.js`），模板来自 `:85-88`，保存走 `JsSourceUpsert.save`（`:186`） |

落库前的校验与清缓存：`BookSourceEditViewModel.kt:52-85`。

### 9. 单文件 JS 书源

模板 `Aassets/js_source_template.js`：

| 顶层声明 | 行号 | 必须？ |
|---|---|---|
| `var config`（bookSourceUrl / Name / Type / Group / Comment / loginUi / exploreUrl / lastUpdateTime） | `:10-21` | **必须**（但旧名 `source` 仍兼容，见下方说明） |
| `var Jsoup = org.jsoup.Jsoup` | `:23` | 可选；模板预置，脚本内可直接用 Jsoup 解析 |
| `login()` | `:26-29` | 声明了 `loginUi` 才必须 |
| `search(key, page)` → 书籍数组 | `:37-42` | **必须** |
| `explore(url, page)` | `:45-48` | 声明了 `exploreUrl` 才必须 |
| `getBookInfo(book)` → 字段对象；`tocUrl` 空则用 `bookUrl` | `:51-59` | **`bookSourceType = file` 时必需** |
| `getChapters(book)` → `{title, url}` 数组，**数组顺序即目录顺序** | `:62-67` | **必须**（file 源可省） |
| `getContent(chapter, book, nextChapterUrl)` → 字符串 | `:70-73` | **必须**（file 源可省） |
| 段评三函数 `getReviewSummary` / `getReviewDetail` / `getReviewReplies` | `:84` / `:89` / `:95` | 成对声明 |

校验规则（`Kmodel/jsSource/JsSourceConfig.kt`）：

| 规则 | 行号 |
|---|---|
| 必备 `search` / `getChapters` / `getContent`；file 源改判 `search` / `getBookInfo` | `:36-37,123-132` |
| `exploreUrl` ⇒ 必须有 `explore` | `:133-137` |
| `loginUi` ⇒ 必须有 `login`；`loginUi` 与 `loginAction` 成对 | `:147-151`、`:138-146` |
| 段评函数成对 | `:167-177` |
| `maxBatchSize` ⇔ `getContentBatch` | `:182-190` |
| 顶层配置对象查找：`config` 优先；**`config` 不完整时会回落到旧名 `source`**；两者都没有才抛「JS源缺少顶层 config 配置对象（兼容旧版 source）」 | `findConfig:213-231`，常量 `:31-32` |
| 导入时剥离 `ruleSearch`…`ruleReview` | `:39-47,112` |

`bookSourceType` 取 **0–4**（`Kconstant/BookSourceType.kt:8-12`）。运行时入参与结果处理见 `Kmodel/jsSource/JsSourceBook.kt:27-181`。
导入判据：文件名匹配 `AP:45` 的 `jsFileRegex = .*\.js$`。

### 10. `bookUrlPattern` 到底怎么匹配

**纯 Kotlin 正则 + `String.matches`**（**全串匹配**，不是 `find`）：

| 用途 | 行号 |
|---|---|
| 搜索/发现的重定向判定 | `Kmodel/webBook/BookList.kt:62-64` |
| 网页地址入架 | `Kui/association/AddToBookshelfDialog.kt:131-134` |
| 书架内找源 | `Kui/main/bookshelf/BookshelfViewModel.kt:79,91-94` |

字段声明与「详情页url正则」注释：`BookSource.kt:42-43`。
SQL 侧以「非空且 != `'NONE'`」作为「可精准搜索」的筛选条件（`Kdata/dao/BookSourceDao.kt:171-188`）——所以 **`NONE` 是显式关闭精准搜索的写法**。
`NetworkUtils` 不参与该匹配（它只提供 `getAbsoluteURL` / `getDomain` 等，`Kutils/NetworkUtils.kt:192,213,239`）。

> 因此可写任意 Java 正则（`.*`、`\d`、分组），但**域名通配要自己转义**，例如 `https://.*\.qidian\.com/.*`。

### 11. 可直接参考的真实样例

| 文件 | 覆盖了什么 |
|---|---|
| `Aassets/defaultData/bookSources.json` | 一个音频源：`exploreUrl = @js:` 动态生成数组（`:11`）、`loginUi`（`:15`）、`loginUrl` 是登录脚本且含 `source.putLoginHeader`（`:16`）、**空 `ruleBookInfo {}`**（`:18`）、`ruleContent.payAction`（`:19-22`）、`ruleExplore` / `ruleSearch` 混用 JSONPath + `@js:` + `{{page}}` + `java.put`（`:23-40`）、`ruleToc.chapterUrl` 里的 `{{…}}` 内联求值（`:41-45`） |
| `app/src/androidTest/assets/issue1180-content-rules.json` | 2 个真实站点的纯 `ruleContent`（`content` / `nextContentUrl` / `replaceRegex`，以及 `<js>` base64 解码与 webView 兜底）；由 `SourceContentCompatibilityTest.kt:22` 加载 |
| `Tmodel/webBook/WebBookTest.kt:12-19` | Kotlin 构造的最小书源，**用来证明「`ruleContent.content` 为空 → 返回章节 URL」** |
| `Tmodel/jsSource/JsSourceBookTest.kt:82-103` | 单文件 JS 书源的脚本样例 |
| `Tmodel/CryptoJsCompatibilityTest.kt:136,206` | CryptoJS 兼容用法样例 |
| `Aassets/defaultData/rssSources.json` | RSS 源（字段结构与书源不同，见 `Aassets/web/help/md/rssRuleHelp.md`） |

### 12. 写书源时的检查清单

1. `bookSourceUrl` 必须是真实地址且带 scheme——它是主键，同址覆盖。
2. JSON 键名逐字对齐字段名（无 `@SerializedName`），大小写写错**不报错只失效**。
3. 想启用发现页：`enabledExplore = true` **且** `exploreUrl` 非空 **且** `ruleExplore.bookList` 非空。
4. 想要精准搜索：`bookUrlPattern` 非空且不是 `NONE`；用它可省掉一次详情页请求。
5. 正文规则为空不会报错，而是**把章节 URL 当正文**——排查「正文是一串链接」时先看 `ruleContent.content`。
6. 详情改名字：`ruleBookInfo.canReName` 留空 = 永不改名，别指望 `name` 规则自动更新书名。
7. `exploreUrl` 想用 `type`/`action`/`style` 必须写 JSON 数组；文本行格式只有「标题::URL」。
8. 书源 JS 里**没有** `bookUrl` / `searchKey` 变量，也没有 `java.openVideoPlayer`（本 fork 已删视频模块）。
9. 需要强制某条地址由指定书源解析时，在 URL 后追加 `,{"origin":"书源地址"}`——`UrlOption.origin` 有真实消费点（`BookshelfViewModel.kt:71-77`、`AddToBookshelfDialog.kt:114-117`），不是死字段。



## 第五部分　引擎与网络实现

> 四阶段链路总览见第四部分；本部分侧重请求构造、规则解析、JS 执行、缓存与网络通道的实现细节。
>
> 取证基准：提交 `234543e1f`。行号会漂移，以「文件 + 符号名」为准。
> 书源的**字段定义**见本文第一部分，**规则语法**见第二部分，**JS 可调 API** 见第三部分。本部分（第五部分）讲运行时链路与实现。

### 1. 分层与入口

```
WebBook（object，四阶段入口）
 ├─ BookList.analyzeBookList         搜索/发现：列表 → SearchBook
 ├─ BookInfo.analyzeBookInfo         详情 → Book
 ├─ BookChapterList.analyzeChapterList 目录 → BookChapter 列表
 └─ BookContent.analyzeContent       正文 → 字符串
        ↓
AnalyzeUrl（构造请求、限流、执行、Cookie、WebView/JS 选项）
        ↓
OkHttp（主通道） 或 Cronet（可选通道） 或 BackstageWebView（webView 选项 / <webjs>）
        ↓
AnalyzeRule（解析 HTML/JSON/正则/JS）
 ├─ AnalyzeByJSoup / AnalyzeByXPath / AnalyzeByJSonPath / AnalyzeByRegex
 └─ Rhino（@js: / <js>）与 WebView（<webjs>）
```

`WebBook` 入口一览（`model/webBook/WebBook.kt`）：

| 阶段 | 同步 API | 挂起 API | 关键行为 |
|---|---|---|---|
| 搜索 | `searchBook` | `searchBookAwait` | JS 源（`mainJs` 非空）走 `JsSourceBook.searchAwait`，否则 `AnalyzeUrl(searchUrl)` → `BookList.analyzeBookList` |
| 发现 | `exploreBook` | `exploreBookAwait` | 走 `exploreUrl` 的分类 → 列表 |
| 详情 | `getBookInfo` | `getBookInfoAwait` | `book.infoHtml` 命中则跳过网络 |
| 目录 | `getChapterList` | `getChapterListAwait` | 先跑 `ruleToc.preUpdateJs`；`bookUrl == tocUrl` 且 `tocHtml` 命中则跳过网络 |
| 正文 | `getContent` | `getContentAwait` | 先查正文缓存令牌 → 缓存命中直接返回；再走网络 → `BookContent.analyzeContent` → `BookHelp.saveContent` |

#### 1.1 搜索链路（`WebBook.searchBookAwait`）

1. `bookSource.isJsSource()`（`mainJs` 非空，`data/entities/BookSource.kt:239`）→ `JsSourceBook.searchAwait` → `JsSourceEngine.callFunction("search", key, page)`。
2. 声明式源：`AnalyzeUrl(mUrl=searchUrl, key, page, baseUrl=bookSource.bookSourceUrl, source, ruleData)` → `getStrResponseAwait()`。
3. 若配置 `loginCheckJs`：对成功响应执行；请求抛异常时用伪造的 500 `StrResponse` 执行，只有检测结果仍为 500 才抛原异常（`WebBook.kt:80-101`）。
4. `BookList.analyzeBookList(baseUrl=res.url, body=res.body, isRedirect=...)`。

#### 1.2 正文链路（`WebBook.getContentAwait`）

1. 取正文缓存令牌 `BookHelp.contentSaveToken`；`version > 0` 且 `BookHelp.getContent` 命中 → 直接返回缓存。
2. JS 源 → `JsSourceBook.getContentAwait`。
3. 一级卷（`isVolume`）直接返回空串；正文规则为空时返回章节链接。
4. `chapter.url == book.bookUrl` 且 `tocHtml` 非空 → 直接用 `tocHtml`。
5. 否则 `AnalyzeUrl(chapter.getAbsoluteURL(), baseUrl=tocUrl, chapter=...)` → `getStrResponseAwait(jsStr=contentRule.webJs, sourceRegex=...)` → `BookContent.analyzeContent` → `BookHelp.saveContent`。
6. 保存失败（版本已变）抛「正文缓存已更新,请重试」，防止并发刷新时旧响应覆盖新缓存（`BookHelp.kt:238-281`）。

### 2. AnalyzeUrl：请求构造与执行

文件：`model/analyzeRule/AnalyzeUrl.kt`（`@Keep class`）。

#### 2.1 处理顺序

`init` → 提取 header（`source.getHeaderMap`，header 中的 `proxy` 被取出作代理并从 header 移除）→ `initUrl()`：

1. `analyzeJs()`：按 `JS_PATTERN`（`<js>...</js>|@js:...`）求值，之前字面串里的 `@result` 替换为上一段结果。
2. `replaceKeyPageJs()`：`{{js表达式}}` 求值（整数 Double 去小数点）。
3. `analyzeUrl()`：按 `,\s*{` 切出 URL 与 JSON 选项（`paramPattern`），反序列化为 `UrlOption`；页码 `<a,b,c>` 按 `page` 取值。

#### 2.2 URL 选项（`UrlOption`）

| 字段 | 作用 |
|---|---|
| `method` | 仅识别 `POST`/`HEAD`，其它一律按 GET |
| `charset` | **只影响 query/form 的编码**，不参与响应解码 |
| `headers` | 追加/覆盖请求头（对象或 JSON 字符串） |
| `body` | POST 体（对象/数组/字符串） |
| `retry` | 仅对「非 2xx 且非 3xx」响应重发 |
| `type` | 二进制资源，响应转十六进制字符串返回 |
| `webView` / `webJs` / `webViewDelayTime` | 用 `BackstageWebView` 发请求、延迟、再用 JS 处理 |
| `timeout` | 设置 readTimeout（毫秒）；callTimeout 自动取 `max(60s, 2×read)` |
| `followRedirects` | 关闭 OkHttp 的普通与 SSL 重定向跟随 |
| `dnsIp`（别名 `resolveIp`） | 对该 host 使用指定 IP，与 `proxy` 同配会抛异常 |
| `js` | URL 绝对化后执行，结果替换 URL |
| `bodyJs` | 对响应体执行，结果作为新 body |
| `serverID` | 规则引擎内未消费，仅 WebDAV 使用 |

#### 2.3 GET / POST 判定

- 默认 GET；`method` 只认 POST/HEAD。
- POST：`urlNoQuery` + body。body 非 JSON/XML 且无 `Content-Type` 时先按 `charset` 编码为 form；发送时按「form / 显式 Content-Type / `application/json`」三选一。
- GET：查询串经 `encodeParams` 编码；`charset` 为空用 UTF-8；`charset == "escape"` 走 `EncoderUtils.escape`；已编码的 query/form 值原样保留。
- 响应解码：显式编码 → `Content-Type` charset → `EncodingDetect` 自动检测（`help/http/OkHttpUtils.kt:80-96`）。**书源里写 `"charset":"gbk"` 不会让响应按 GBK 解码**——如果站点不声明 Content-Type，需要靠自动检测或 `bodyJs` 处理。

#### 2.4 超时、重试与代理

- OkHttp 基线：connect 15s / write 15s / read 60s / call 60s（`help/http/HttpHelper.kt:58-61`）。
- URL 级 `timeout` 只改 readTimeout，callTimeout 自动放大。
- `retry` 不捕获 IO 异常；网络异常直接抛出。
- 代理来源：书源 header 里的 `proxy` 键；`getProxyClient` 按代理配置缓存 client，SOCKS5 支持账号，HTTP 代理带 407 认证计数；**代理 client 会移除 Cronet 拦截器**（`HttpHelper.kt:130-188`，移除点 `:158`）。

#### 2.5 Cronet 通道

- 开关 `AppConfig.isCronet`；引擎由 `lib/cronet/CronetHelper.kt` 创建，50MB 磁盘缓存、QUIC、HTTP/2、Brotli、AsyncDNS。
- 拦截器 `CronetInterceptor`：开关关闭 → 直接 `proceed`；开关打开但引擎不可用 → **抛 `CronetUnavailableException`，不静默回退 OkHttp**（fork 刻意行为）。
- **一个 URL 请求只要设置了 `timeout`、`followRedirects` 或 `dnsIp` 任一项，就会把 Cronet 拦截器从该请求的 client 中移除，交回 OkHttp**（`AnalyzeUrl.kt:654-669`、`AnalyzeUrlNetworkOptions.kt:103-110`）。
- `.so` 不打进 APK：`CronetLoader` 运行时按 ABI + MD5 从 Google 存储下载（`app/src/main/assets/cronet.json` 与 `CronetVersion 152.0.7977.54` 对应）。

### 3. AnalyzeRule：规则解析

文件：`model/analyzeRule/AnalyzeRule.kt`。

#### 3.1 模式判定（`SourceRule.init`）

判定表与优先级见**第二部分 §4**；实现落点 `AR:663-696`。注意 `@CSS:` 的前缀不在 AnalyzeRule 剥除，而是 `AnalyzeByJSoup.kt:510-516` 的 `substring(5)`。

#### 3.2 通用语法

语义见**第二部分 §3 / §5 / §7 / §8**；实现落点：`##` 四段切分 `AR:824-834`、替换执行 `AR:544-568`（以 `###` 结尾只替换首个）；变量读写优先级 `AR:856-891`；`&&`/`||`/`%%` 分散在各解析器——`AnalyzeByJSoup:106-114,176-183`、`AnalyzeByXPath:58,96,135`、`AnalyzeByJSonPath:38,78,136`，其中**XPath 与 JSONPath 的 `getString` 只切 `&&`/`||`，不支持 `%%`**（正则模式同样只支持 `&&`）。

#### 3.3 实例缓存

`AnalyzeRule` 持有实例级的 `stringRuleCache`、`regexCache`(LRU 16)、`scriptCache`(LRU 16)。复用一个实例处理不同内容时注意 `isRegex` **一旦置真不会复位**（`AnalyzeRule.kt:80,599-605`）。

### 4. JS 引擎

- 实现：HtmlUnit core-js（Rhino 派生）→ `modules/rhino` 的 `RhinoScriptEngine`（`eval`/`compile`/`evalSuspend`），带协程取消检查、递归限制、`allowScriptRun` 闸门。
- 作用域：`ScriptBindings.chainTo` 支持「共享 jsLib 全局作用域 + 来源级 `globalThis`」，由 `SharedJsScope` 按 jsLib 的 MD5 缓存（LRU 16 弱引用）。
- 三类绑定：

| 场景 | 可用变量 | 位置 |
|---|---|---|
| 规则内 `@js:` | `java`(=JsExtensions)、`cookie`、`cache`、`source`、`book`、`result`、`src`、`baseUrl`、`chapter`、`chapters`、`title`、`nextChapterUrl`、`rssArticle`、`fromBookInfo`、`paraIndex`/`paraData`/`page` | `AnalyzeRule.evalJS` |
| URL/请求级 `@js:` | `java`(=AnalyzeUrl)、`baseUrl`、`cookie`、`cache`、`page`、`key`、`speakText`、`speakSpeed`、`book`、`source`、`result`、`extraParams`、`infoMap` | `AnalyzeUrl.evalJS` |
| 单文件 JS 源 | `java`、`source`、`sourceApi`、`baseUrl`、`cookie`、`cache` + 调用参数 | `JsSourceEngine` |

- `java` 宿主 API（`help/JsExtensions.kt`）：`ajax/ajaxAll/ajaxTestAll/connect`、`webView*`、`get/head/post`（底层 Jsoup，`followRedirects(false)`）、`downloadFile`、`cacheFile/cacheContent`、`getCookie`、`queryTTF`、`singleFlight/lock/tick`（默认 15s，上限 300s）、`base64/hex/encodeURI`、`toast/log`、`getReadBookConfig*`、`getThemeConfig*`。
- `<webjs>` 由 `BackstageWebView`（默认超时 60s）执行，注入 `java`/`source`/`cache`。

### 5. 缓存与限流

| 机制 | 说明 |
|---|---|
| 正文缓存 | `BookHelp`：`externalFiles/book_cache/<book>/%05d-<titleMD5>.nb`；`ContentSaveFence` 版本栅栏 |
| 目录/详情 HTML | `Book.infoHtml` / `Book.tocHtml` 字段，搜索命中时写入并复用 |
| JS 作用域 | `SharedJsScope`（MD5 → 弱引用 ScriptBindings）；远程 jsLib 落 `cacheDir/shareJs` |
| 书源级限流 | `concurrentRate`（`n` 或 `n/毫秒`）；`getStrResponseAwait` 支持 `skipRateLimit` |
| 搜索/换源并发 | 搜索：`min(threadCount, 9)` 线程池 + 单源 30s；换源：`mapParallel(threadCount)` + 单源 60s |
| 分页并发 | `mapAsync(AppConfig.threadCount)` 抓 `nextTocUrl` / `nextContentUrl` |
| Cronet HTTP 缓存 | 50MB；OkHttp 无缓存且每请求 `Cache-Control: no-cache` |

### 6. 排障要点

1. **搜索无结果先看是不是 302**：不少 CMS 的搜索接口先 302 到结果页。OkHttp/Cronet 都会跟随，POST 在 302 后按规范转 GET；`curl` 脚本要加 `-L` 才能复现。
2. **`charset` 不负责解码**：站点返回 GBK 又没声明 `Content-Type` 时，表现为乱码或选择器不命中；用 `bodyJs` 或改站点理解，而不是指望 URL 选项。
3. **`@js:` 会吞掉其后所有内容**（`JS_PATTERN` 中 `@js:([\w\W]*)` 无终止符）；需要多段请用 `<js>...</js>`。
4. **`isRegex` 粘滞**：同一个 `AnalyzeRule` 实例上跑过 `:正则` 后，后续规则都会被当正则。
5. **`retry` 不重试异常**：只重试 HTTP 状态码；超时/连接失败直接抛。
6. **Cronet 的「不静默回退」**：开关开着但引擎不可用时请求会失败，而不是慢速走 OkHttp；排查网络异常的源时先确认 `CronetUnavailableException`。
7. **限流是双层**：批量正文整批只取一次并发额度，批内每个请求各自再限流；改并发相关表现时要同时看 `BookContent.analyzeContentBatch` 与 `ConcurrentRateLimiter`。
8. **正文版本栅栏**：手动改正文/重复刷新时出现「正文缓存已更新,请重试」属正常保护，不是 bug。
