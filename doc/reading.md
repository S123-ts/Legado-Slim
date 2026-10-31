# 看书：界面与阅读器

> 取证基准：提交 `234543e1f`。行号会漂移，以「文件 + 符号名」为准。

## 1. 技术栈与基础类

**XML + ViewBinding，无 Compose、无 Jetpack Navigation、无 DI。** 证据：`app/build.gradle:104-106` 只启用 `buildConfig`/`viewBinding`；`ui/` 下 50 个 Activity、19 个 Fragment；233 个 XML 布局；全仓库无 `@Composable`/`setContent {}`。

| 基类 | 作用 | 文件 |
|---|---|---|
| `BaseActivity<VB>` | 主题、沉浸式系统栏、背景图、ViewBinding、预测性返回、Toolbar 着色 | `base/BaseActivity.kt:51` |
| `VMBaseActivity<VB,VM>` | 增加抽象 `viewModel` 属性 | `base/VMBaseActivity.kt:9` |
| `BaseFragment` / `VMBaseFragment<VM>` | Fragment 基类，支持 Toolbar/Menu | `base/BaseFragment.kt:20`、`VMBaseFragment.kt:5` |
| `BaseViewModel` | `AndroidViewModel` + `execute/submit/executeLazy` 协程封装 | `base/BaseViewModel.kt:17` |
| `BaseDialogFragment` / `BasePrefDialogFragment` | 通用对话框 / 内嵌 Preference 的对话框；E-ink 下关闭遮罩与动画 | `base/` |
| `BaseReadBookActivity` | 阅读 Activity 基类：绑定、菜单显隐、方向、沉浸式、字符集、下载 | `ui/book/read/BaseReadBookActivity.kt:86` |
| `BaseBookshelfFragment` | 两种书架样式共用基类 | `ui/main/bookshelf/BaseBookshelfFragment.kt:62` |
| `PreferenceFragment` | 设置页基类 | `lib/prefs/fragment/PreferenceFragment.kt:19` |

自定义 ViewBinding 委托：`utils/viewbindingdelegate/ActivityViewBindings.kt`、`FragmentViewBindings.kt`。

## 2. 页面清单（按功能域）

### 2.1 主框架

| 页面 | 职责 | 关键类 |
|---|---|---|
| `MainActivity` | 底部导航 + ViewPager + 4 个 Tab（书架/发现/RSS/我的），`singleTask` | `ui/main/MainActivity.kt:86`、`MainViewModel.kt:54` |
| 书架 Tab | 书籍列表、分组、刷新、搜索 | `BookshelfFragment2:56`（卡片/横滑分组）、`BookshelfFragment1:35`（TabLayout+ViewPager）、`BookshelfViewModel:45` |
| 发现 Tab | 书源发现入口 | `ExploreFragment:77`、`ExploreViewModel:10` |
| RSS Tab | 订阅源列表/分组 | `RssFragment:50`、`RssViewModel:11` |
| 我的 Tab | 设置、关于、备份等入口 | `MyFragment`、`ConfigActivity` |

书架样式由 `AppConfig.bookGroupStyle` 决定，`MainActivity.getFragmentId()` 二选一（`MainActivity.kt:609-615`）。

### 2.2 书籍与搜索

| 页面 | 职责 |
|---|---|
| `SearchActivity` | 多书源搜索、精度/范围筛选（`SearchViewModel:27` + `SearchModel`） |
| `BookInfoActivity` | 详情：简介、目录、换源、加书架、开始阅读（`BookInfoViewModel:97`） |
| `BookInfoEditActivity` | 书籍信息编辑 |
| `TocActivity` | 目录 / 书签 / 高亮 三 Tab（`TocViewModel:28`） |
| `ExploreShowActivity` | 发现书源的书籍列表（`ExploreShowViewModel:42`） |
| `BookshelfManageActivity` | 书架批量管理：分组、换源、清缓存、封面（`BookshelfManageViewModel:49`） |
| `CacheActivity` | 离线缓存、批量下载、导出（`CacheViewModel:16`） |
| `AllBookmarkActivity` | 全部书签 |
| `ReadRecordActivity` | 阅读记录汇总 |

### 2.3 阅读

| 页面 | 职责 |
|---|---|
| `ReadBookActivity` | 文本阅读主界面（3093 行，巨型控制器） |
| `AudioPlayActivity` | 音频书播放（media3 ExoPlayer） |
| `ReadRssActivity` | RSS 文章阅读（WebView）、收藏、TTS |

### 2.4 书源/规则管理

`BookSourceActivity`（列表/分组/排序/导入）、`BookSourceEditActivity`、`JsSourceEditActivity`（单文件 JS 源）、`BookSourceDebugActivity`（调试）、`RssSourceActivity`、`ReplaceRuleActivity`、`HighlightRuleActivity`、`RuleSubActivity`。

### 2.5 设置与其它

`ConfigActivity`（设置容器，按 `ConfigTag` 动态 `replace` 各 Preference Fragment）、`MyPreferenceFragment`、`ThemeConfigFragment`、`BackupConfigFragment`、`OtherConfigFragment`、`ImportBookActivity`（本地扫描）、`RemoteBookActivity`（WebDAV 导入）、`FileManageActivity` / `HandleFileActivity`（SAF 文件/目录）、`WelcomeActivity`（启动页；`Launcher1..6` 是 `enabled=false` 的图标别名）、`AboutActivity`、`WebViewActivity`、`SourceLoginActivity`、`QrCodeActivity`、`AutoTaskActivity`。

## 3. 阅读器结构

### 3.1 顶层组成

布局 `res/layout/activity_book_read.xml`：`ReadView`（正文）+ `ReadMenu`（顶部/底部菜单）+ `SearchMenu`（全文搜索）+ 导航栏占位 + `read_aloud_float_bar_container`（朗读浮条）。

`ReadBookActivity` 实现 `ReadView.CallBack`、`ReadMenu.CallBack`、`ReadBook.CallBack`、`ChangeBookSourceDialog.CallBack` 等十余个接口，是阅读功能的实际控制器。

### 3.2 分页与翻页

- `ReadView : FrameLayout` 固定持有三个 `PageView`：`prevPage / curPage / nextPage`（`ui/book/read/page/ReadView.kt:131,145-147`）。
- 翻页模式（`constant/PageAnim.kt`）：`0` 覆盖、`1` 滑动、`2` 仿真、`3` 滚动、`4` 无动画；对应 `*PageDelegate`（`Cover/Slide/Simulation/Scroll/NoAnimPageDelegate`），它们继承公共基类 `PageDelegate`，横向三种再经 `HorizontalPageDelegate`；`ReadView.upPageAnim()` 替换 delegate。
- 分页：`TextPageFactory`（`provider/TextPageFactory.kt:10`）负责当前/前后页与章节边界；`ChapterProvider`（`:79`）负责章节测量、分页、样式（`upLayout()`、`upStyle()`）；底层 `TextChapterLayout`、`TextMeasure`、`LineColumnLayout`、`ZhLayout`。
- 手势：`ReadView.onTouchEvent`；九宫格点击区域由 `AppConfig.pageTouchClick` 生成；另有长按选区、下拉书签、双指替换预览。
- 无障碍：当前页正文写入 `contentDescription`（`ReadView.kt:967-969`、`PageView.kt:399-404`）。

### 3.3 章节切换

- 翻到边界：`TextPageFactory.moveToNext/moveToPrev` → `ReadBook.moveToNextChapter/moveToPrevChapter`（`model/ReadBook.kt:936,1015`）。
- 目录跳转：`TocActivity` 返回 `TocActivityResult` → `ReadBookViewModel.openChapter(...)` → `ReadBook`。
- 内容刷新：`ReadBook` 回调 `callBack?.upContent()` → Activity → `ReadView.upContent()`。
- 进度竞态：`ReadPositionVersion` + `isReadPositionVersionCurrent()`，防止异步加载的旧内容覆盖新进度。

### 3.4 菜单与设置

- `ReadMenu`：标题栏、底部菜单、亮度、章节切换、目录、搜索、替换、夜间、自动翻页、朗读。
- `SearchMenu`：全书搜索；`TextActionMenu`：选中文本操作（复制/搜索/替换/高亮/朗读）。
- 阅读设置对话框（均在 `ui/book/read/config/`）：`ReadStyleDialog`（字体/字号/行距/翻页）、`BgTextConfigDialog`（背景/文字/下划线）、`PaddingConfigDialog`、`TipConfigDialog`、`ClickActionConfigDialog`、`MoreConfigDialog`、`ReaderMenuConfigDialog`、`TextSelectMenuConfigDialog`、`ReadAloudDialog`。
- 听书：朗读与音频播放的完整链路见 [listening.md](listening.md)。

## 4. 书架与书籍管理

- 分组：`BookGroup` 负 ID 内置分组（全部/本地/音频/本地未分组/根），`books.group` 位掩码；`BaseBookshelfFragment.initBookGroupData()` 观察 `bookGroupDao.show`。
- 两种样式：`BookshelfFragment2`（`BookshelfGroupItem` + `BooksAdapterGrid/List`，横滑切组）、`BookshelfFragment1`（Tab + ViewPager + `style1/books/BooksFragment`）。
- 分组管理：菜单 `menu_group_manage` → `GroupManageDialog`；长按 Tab → `GroupEditDialog`。
- 换源入口三处：详情页 `tvChangeSource`、阅读菜单 `menu_change_source`、书架批量管理 `BookshelfManageActivity`；章节级换源 `ChangeChapterSourceDialog`。
- 缓存/下载入口：阅读菜单 `menu_download` → `showBookDownloadDialog()` → `CacheBook.start()`；书架菜单 → `CacheActivity`。
- 导入/导出：书架菜单导出/导入 `bookshelf` JSON；`ImportBookActivity` 本地扫描；`RemoteBookActivity` WebDAV；`ConfigActivity → BackupConfigFragment` 负责备份。

## 5. 配置体系

| 配置 | 存储 | 说明 |
|---|---|---|
| `AppConfig` | 默认 SharedPreferences | `object` 门面；**大量属性在对象初始化时读入普通 `var`**，只有部分 key 在 `onSharedPreferenceChanged()` 里同步 |
| `LocalConfig` | 独立 `"local"` SharedPreferences | 本机私有配置 |
| `ReadBookConfig` | `filesDir/readConfig.json` + `shareReadConfig.json` | 阅读样式单例（字体/字号/行距/背景/翻页/页眉页脚） |
| `ThemeConfig` / `ThemeStore` | SharedPreferences + 内存 | 主题应用、日夜切换、E-ink |

设置界面：`ConfigActivity` 按 `ConfigTag` 加载对应 `PreferenceFragment`，通过 `addPreferencesFromResource(R.xml.pref_*)` 绑定；偏好变化直接写 SharedPreferences，`AppConfig` 再刷新缓存字段。

> 新增设置项的硬要求：写入 `PreferKey`、加 `pref_*` XML、**并在 `AppConfig.onSharedPreferenceChanged()` 补同步分支**，否则运行期读到旧值。

## 6. UI 与数据的交互模式

项目没有 Repository，ViewModel 直接用 DAO / 静态 `WebBook` / 全局 `ReadBook`：

1. **Room Flow + 生命周期收集**：`BookshelfFragment2.initBooksData()` 组合 `bookDao.flowByGroup()`、`flowBookshelfBooks()` 与分组 `MutableStateFlow`，经 `flowWithLifecycleAndDatabaseChangeFirst` + `conflate()` 更新 Adapter；分组本身用 LiveData。
2. **LiveData + WebBook**：`BookInfoViewModel` 暴露 `bookData`/`chapterListData`/`loadingData`，内部查 DAO 并调用 `WebBook.getBookInfo()`/`getChapterList()`，Activity `observe` 刷新。
3. **全局 ReadBook + CallBack**：`ReadBookActivity` 在 `onActivityCreated` 里 `ReadBook.register(this)`、`onResume` 里 `ReadBook.callBack = this`；`ReadBookViewModel` 调用 `ReadBook.resetData/upData/loadContent`，`ReadBook` 通过回调驱动 UI。

## 7. 主题、字体、E-ink

- 主题切换：`ThemeConfig.applyDayNight()` → 写 `ThemeStore` + `AppCompatDelegate.setDefaultNightMode` → `EventBus.RECREATE`；`MainActivity`/`ConfigActivity` 监听后 `recreate()`。**`ReadBookActivity` 不监听 RECREATE**，靠 `EventBus.UP_CONFIG` 局部刷新。
- 应用级字体缩放：`AppContextWrapper` 读取 `PreferKey.fontScale`（0.8–1.6），在 `attachBaseContext` 生效。
- 阅读字体：`FontSelectDialog` 写入 `ReadBookConfig.textFont`，`ChapterProvider.getTypeface()` 加载。
- E-ink：`AppConfig.isEInkMode`（themeMode=="3"）→ `Theme.EInk` 白底黑字；底栏改用 `bg_eink_border_*`；对话框关闭遮罩/动画。

## 8. 阅读器维护注意点

1. **`EventBus.UP_CONFIG` 是隐式协议**：整数码表（0 系统栏、1 背景、2 样式、3 透明度、4 触摸阈值、5 重载正文、6 重绘、8 章节样式、9/11 重绘、10 重新排版、12 点击区域、13 翻页动画），见 `ReadBookActivity.observeLiveBus()`。新增阅读设置时必须选对码或补码。
   - **码 10 目前没有发送方**（预留），别指望它触发重排版。
   - 码 5 在分发前会先调用 `readView.updateScrollReadPosition(preserveText = true)`，滚动阅读下有特殊的位置保持逻辑。
2. **状态在全局 `ReadBook` 单例而非 ViewModel**：改阅读功能要同时检查 `ReadBook.CallBack`、`ReadView`、`ReadMenu`、`ReadBookConfig` 四处。
3. **`ReadBookActivity` 3093 行**：新增功能优先放 `ReadView`/`ReadMenu`/独立 Dialog 或 `ReadBook`，不要再往 Activity 里堆。
4. **进度竞态**：任何异步更新正文的路径都要过 `ReadPositionVersion` 校验。
5. **多窗口不隐藏系统栏**：`BaseActivity` 在多窗口下跳过沉浸式处理。
6. **预测性返回**：仅 `AppConfig.disablePredictiveBack` 为真且 API 33+ 时接管。
