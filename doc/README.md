# 项目文档

本目录分两层：**4 类实现文档**（全部从本仓库源码取证）与 **通用指南**（可复用的操作流程，见 [guides/](guides/README.md)）。与 App 内置帮助（`app/src/main/assets/web/help/md/*.md`）冲突时以源码为准。

## 取证基准

| 项 | 值 |
|---|---|
| 基准提交 | `234543e1f56afca29a7e5fa62c43648d777190ee`（2026-10-24，精简版首个提交） |
| 数据库版本 | 111（`data/AppDatabase.kt:80`） |
| JS 引擎 | HtmlUnit Core JS `5.3.0-legado.4`（`gradle/libs.versions.toml:59`），Rhino 系 |
| 本 fork 状态 | 已删除 MCP 服务 / Firebase / PDF 书签目录 / 视频播放+弹幕；2026-10-25 再删词典划词 / 局域网备份 / 漫画（书源与替换净化相关保留；数据库仍为 111 版，被删功能的表保留） |

> 行号会随后续改动漂移，引用时以「文件 + 符号名」为准。

## 文档大类

| 大类 | 文件 | 覆盖内容 |
|---|---|---|
| **程序架构与实现** | [architecture.md](architecture.md) | 技术栈、Gradle 模块、分层与依赖、`app` 各包职责、启动流程、并发模型、全局单例（第一部分）；Room 26 表 + 1 视图、DAO、迁移、缓存（第二部分）；11 个服务 + 1 抽象基类、备份/恢复/WebDAV、ReaderProvider 与 Web 服务、工具类（第三部分）；构建、签名、版本号、Cronet、依赖锁定（第四部分） |
| **看书** | [reading.md](reading.md) | ViewBinding 技术栈与基础类、页面清单、`ReadView` 三页缓存与 5 种翻页、分页器、书架两种样式、配置体系、主题/字体/E-ink、`UP_CONFIG` 事件码 |
| **听书** | [listening.md](listening.md) | TTS 与在线 TTS 两条链路、朗读服务与通知控制、朗读与正文衔接、音频书（ExoPlayer）、音频缓存、通知 ID、验收与排障 |
| **书源** | [booksource.md](booksource.md) | 书源数据结构（第一部分）、规则字符串语法（第二部分）、`@js:` 宿主接口（第三部分）、运行链路与实操（第四部分）、引擎与网络实现（第五部分） |

## 通用指南

| 指南 | 覆盖内容 |
|---|---|
| [guides/booksource-workflow.md](guides/booksource-workflow.md) | 书源制作七步、批量处理七阶段、判定口径、修复模式库、排错；新指南模板见 [guides/_template.md](guides/_template.md) |

## 阅读顺序建议

1. 上手改代码：architecture 第一部分 → 对应大类（reading / listening / booksource）
2. 改阅读/朗读相关：先看 [.qoder/rules/scope-read-listen.md](../.qoder/rules/scope-read-listen.md) 的改动范围与验收口径
3. 读懂一份书源 JSON：booksource 第一部分
4. 写/改规则：booksource 第二、三部分 + 第五部分
5. 制作或批量核对书源：先看 [guides/booksource-workflow.md](guides/booksource-workflow.md) 的流程与判定
6. 排查书源失效：[guides/booksource-workflow.md](guides/booksource-workflow.md) 的修复模式库 → booksource 第四、五部分
7. 出包：architecture 第四部分

## 三条最容易踩的前提

1. **JSON 键名逐字大小写敏感**：`BookSource` 全类没有 `@SerializedName`，GSON 也没设 `FieldNamingPolicy`（`utils/GsonExtensions.kt`），JSON 名 == Kotlin 字段名。写 `booksourceurl` 不会报错，只会被静默忽略。
2. **书源 JS 里 `java.xxx` 的可调集合不等于 `@JavascriptInterface` 标注的方法**——见 booksource 第三部分。
3. **本 fork 没有 `ruleCheck` 字段**，校验关键字来自 `ruleSearch.checkKeyWord`；也没有上游的 `RuleBookshelf` / `RuleStack` / `RuleSplit`。
