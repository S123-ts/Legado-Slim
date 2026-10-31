# 改动范围：只围绕「听书」和「看书」

自本条规则起，本仓库的所有新需求、优化、修 bug **默认只落在「听书」与「看书」两条链路上**；其它模块不在范围内，除非明确点名或它阻塞了这两条链路。

## 一、看书（文本阅读）

范围内（含其实体、状态、配置、界面）：

| 层面 | 关键落点 |
|---|---|
| 阅读界面 | `ui/book/read/ReadBookActivity`、`ReadView`、`PageView`、`ReadMenu`、`TextActionMenu`、`SearchMenu`、`page/`（翻页委托）、`provider/`（分页与排版） |
| 阅读状态 | `model/ReadBook`、`ReadBookViewModel`、阅读进度/章节切换/换源后的正文衔接 |
| 排版与显示 | `ReadBookConfig`、`ReadStyleDialog` 等阅读设置、字体、背景、页眉页脚、行距/字号、夜间与 E-ink 的**阅读页**表现 |
| 正文获取与缓存 | `WebBook.getContent`、`BookContent`、`BookHelp`（正文读写/缓存）、`CacheBook` + `CacheBookService`（离线缓存供阅读）、正文版本栅栏 |
| 正文呈现相关 | 替换净化在阅读页的生效、高亮/书签在阅读页的展示、正文图片、目录跳转与章节边界 |
| 书源 | **仅在影响正文取回/解析时**才动（如正文规则、正文分页、编码问题）；不改书源管理与编辑界面 |

不含：搜索页、书架管理、换源弹窗本身（换源后正文衔接除外）、漫画阅读 `ReadMangaActivity`、RSS 阅读。

## 二、听书（TTS 与音频）

范围内：

| 层面 | 关键落点 |
|---|---|
| 系统 TTS | `service/TTSReadAloudService`、`help/TTS`、系统 TTS 引擎选择与参数 |
| 在线 TTS | `service/HttpReadAloudService`、`HttpTTS` 配置、音频流缓存 |
| 朗读控制 | `service/BaseReadAloudService`（分页取文、音频焦点、通知按钮、定时/按章停止）、`model/ReadAloud`、`ReadAloudDialog`、`ReadAloudControls` 浮条 |
| 朗读与正文的衔接 | 朗读取文与分页、朗读位置跟随阅读进度、暂停/恢复/跳章、朗读时的正文刷新 |
| 音频书 | `service/AudioPlayService`、`model/AudioPlay`、`AudioPlayActivity`、media3 ExoPlayer 播放/进度/倍速/定时 |
| 音频缓存 | `service/AudioCacheService`、`help/audio/*`（不含视频） |

不含：视频播放（本 fork 已移除）、弹幕、MCP、Firebase、PDF 书签目录。

## 三、跨范围改动的处理

1. 若缺陷根因在共享层（`help/http`、`AppConfig`、数据库迁移、`base/` 基类），且**阻塞**了阅读或朗读：可以改，但必须
   - 在回复里说明「为什么必须动共享层」；
   - 取最小改动面，不顺手重构；
   - 单独成一次提交，不与阅读/听书功能改动混在一起。
2. 纯 UI 打磨、设置项整理、文档补充：只做与阅读页/朗读相关的部分。
3. 用户明确点名的模块优先于本条规则。

## 四、验收口径

- 看书：至少验证「打开书 → 翻页/跳章 → 换章后正文正确 → 退出重进进度正确」。
- 听书：至少验证「开始朗读 → 跨章续读 → 暂停/恢复 → 通知栏控制 → 定时停止」；音频书另验「播放/切章/倍速/进度记忆」。
- 改动若同时触及缓存或正文保存，必须覆盖「缓存已存在」「缓存失效重下」两种状态。

## 五、与其它规则的关系

- 本仓库是**自用**版：不为通用场景加功能。不在上述两条链路内的能力（RSS、漫画、自动任务、内置 Web 服务/对外 API、词典、局域网备份与直链上传、WebDAV 等）**默认不再维护，可按批次评估删除**；但删表/删依赖属于独立批次，不与功能改动混在一次提交。
- 用户已明确的取舍（2026-10-25）：
  - **书源相关、替换净化相关的必须留**——因此代码编辑器（`ui/code/`，替换/书源规则编辑在用）、键盘辅助、二维码（书源/规则扫码导入在用）**不删**。
  - **漫画之类的不要**——已删 `ui/book/manga/`、`model/ReadManga`、`MangaMenu`、`LegadoDataUrlLoader`、`okHttpClientManga` 与 14 个漫画偏好；`BookType.image` / `BookSourceType.image` 常量与 `ImageProvider`（正文图片共用）**必须保留**。
  - 自动任务的入口是详情页/书架的「定时更新目录」，属看书链路 → **保留**（待用户另行推翻时再删）。
- 删除时的纪律：不轻易升数据库版本（表与 DAO 可留作数据兼容，`Backup/Restore` 读写保留）；每批改完跑 `./gradlew :app:compileAppDebugKotlin --offline` 作为证据。
- 本条只约束**功能范围**，不改变 `git-push-scope.md` 的提交/推送铁律（本地 commit 可做，push 每次单独点名）。
- 本仓库是精简版：范围内的功能缺失（如听书引擎被删）按「缺失不等于 bug」处理，先确认再补。
