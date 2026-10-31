# 听书：朗读与音频播放

> 取证基准：提交 `234543e1f`。行号会漂移，以「文件 + 符号名」为准。
> 本文是「听书」类改动的落点清单；阅读界面本身见 [reading.md](reading.md)。

## 1. 两条链路

```
系统/在线 TTS（听当前阅读的正文）
ReadMenu 朗读按钮 / ReadAloudDialog
  → model/ReadAloud.choose → BaseReadAloudService（抽象）
        ├─ TTSReadAloudService   系统 TTS 引擎
        └─ HttpReadAloudService  在线 TTS（HttpTTS 配置）
  ← 从 ReadBook 当前章取文（默认按段落，可切换按页） → 朗读 → 翻页/跨章续读

音频书（听音频文件）
AudioPlayActivity → model/AudioPlay → AudioPlayService（media3 ExoPlayer）
```

TTS 引擎选择（`ReadAloud.kt:25-41,61-70`）：引擎取值优先用**书级配置** `ReadBook.book?.getTtsEngine()`，没有才回落 `AppConfig.ttsEngine`；值可以是 `HttpTTS` 的 ID 字符串，也可以是 SelectItem JSON。`toLongOrNull()` 后能在 `httpTTSDao.get(id)` 命中 → `HttpReadAloudService`；取值为空或未命中 → `TTSReadAloudService`。启动统一走 `Context.startForegroundServiceCompat`。

## 2. 朗读服务

| 类 | 职责 | 关键点 |
|---|---|---|
| `service/BaseReadAloudService` | 朗读基类 | 从 `ReadBook` 当前章取文（**默认按段落**，勾选「按页朗读」后按页：`PreferKey.readAloudByPage`，默认 false）、请求音频焦点、构建通知按钮、定时/按章数停止；`startForegroundNotification()` 使用通知 ID **101** |
| `service/TTSReadAloudService` | 系统 TTS | 初始化系统引擎、逐段 `speak`；`onInit` 后开始朗读 |
| `service/HttpReadAloudService` | 在线 TTS | ExoPlayer + `SimpleCache`（128MB）缓存音频流；按 `HttpTTS` 配置的 URL/请求头/速率取音频 |

朗读控制 UI：

- `ReadMenu` 的朗读按钮 → `ReadBookActivity.onClickReadAloud()`。
- `ReadAloudDialog`：选择引擎、语速/音调、定时、按章停止。
- `ReadAloudControls`：阅读页浮条（播放/暂停、上一段/下一段、停止），朗读期间常驻。
- 通知栏：播放/暂停、上一章/下一章、停止；通知 ID 101。

## 3. 朗读与正文的衔接

- 取文来源是 `ReadBook` 当前章的 `TextChapter`：默认按**段落**切分（`getParagraphNum`），开启 `readAloudByPage` 后按**页**；另有 `readAloudFollowManualPage` 控制手动翻页后的跟随。朗读进度变化会驱动阅读页翻页/滚动跟随。
- 跨章：本章读完自动取下一章，但只在 `0..<ReadBook.simulatedChapterSize` 的模拟章节窗口内加载。未缓存时走 `loadSpeechChapterOnly()`：先 `BookHelp.getContent`，缺失则 `CacheBook.getOrCreate(...).downloadAwait(chapter)`——**不经过 `WebBook.getContentAwait`**（`BaseReadAloudService.kt:889-899`）。
- 暂停/恢复：保当前位置；退出阅读页但服务仍在前台时继续朗读（受系统后台限制影响）。
- 正文刷新（换源、重新加载）会重建分页，朗读位置需重新对齐——改这块时同时检查 `ReadBook` 回调与 `ReadAloud` 状态。

## 4. 音频书

| 类 | 职责 |
|---|---|
| `service/AudioPlayService` | ExoPlayer + MediaSession（`MediaSessionCompat`，来自 androidx.media，**不是 media3-session**）；音频焦点、锁屏控制、`PARTIAL_WAKE_LOCK`；通知 ID **102** |
| `model/AudioPlay` | 全局播放状态（当前书/章节、播放列表、倍速、定时停止）；`play()` 启动服务 |
| `ui/book/audio/AudioPlayActivity` | 播放界面：章节列表、进度、倍速、定时 |
| `service/AudioCacheService` | 把音频章节缓存到用户选择的 SAF 目录；通知 ID **109** |
| `help/audio/AudioCacheManager`、`AudioCachePolicy` | 缓存文件命名/去重/清理；**明确不支持 HLS 与多段音频** |

媒体依赖只有 `media3-exoplayer` + `media3-datasource-okhttp`（hls/ui/session 已注释）。自定义请求头通过 `ExoPlayerHelper` 拼接。

## 5. 相关数据与配置

| 数据 | 位置 | 说明 |
|---|---|---|
| 在线 TTS 配置 | 表 `httpTTS`（`HttpTTS` 实体） | `name`、`url`、`contentType`、`concurrentRate`、`loginUrl`、`header`、`jsLib`；`concurrentRate` 空值在打开库时被置 `'0'` |
| TTS 引擎选择 | `ReadBook.book?.getTtsEngine()` → 回落 `AppConfig.ttsEngine` | 值为 `HttpTTS` 的 ID（或 SelectItem JSON）；空或未命中 = 系统 TTS |
| 朗读语速/音调/定时 | `AppConfig` 与 `ReadAloudDialog` | 语速、音调、定时分钟数、按章停止 |
| 音频播放进度 | `books.durChapterIndex` / `durChapterPos`（`Book`） | 与文本阅读共用同一本书的进度字段（音频书按章节索引记录） |
| 音频缓存目录 | SAF 目录（用户选择） | 受 `AudioCachePolicy` 约束 |

## 6. 通知与前台服务约束

- 通知 ID 分配表在 `constant/NotificationId.kt`：朗读 **101**、音频播放 **102**、音频缓存 **109**，新增听书类服务必须避开已用 ID。
- 所有服务继承 `BaseService`：首次 `onStartCommand` 尝试前台化，被系统拒绝（`ForegroundServiceStartNotAllowedException`）会 `stopSelf` 并返回 `START_NOT_STICKY`——表现为「点了没反应」，先查通知权限与后台限制。
- `BaseService.onCreate` 会申请 `POST_NOTIFICATIONS`，SDK≥S 还申请忽略电池优化。

## 7. 验收与排障

改完听书相关代码，至少覆盖：

1. 开始朗读 → 连续跨章（验证模拟章节窗口边界）→ 暂停/恢复 → 通知栏控制 → 定时停止。
2. 按段落与按页两种取文模式各验一遍（`readAloudByPage` 开/关）。
3. 系统 TTS 与在线 TTS 各跑一遍，并验证书级引擎覆盖全局引擎。
4. 音频书：播放/切章/倍速/定时/退出重进进度正确。
5. 正文未缓存时开始朗读：应先经 `CacheBook.downloadAwait` 取正文再读，不出现重复朗读或跳段。
6. 音频缓存：SAF 目录选择、失败提示（HLS/多段被拒绝是设计行为）。

常见坑：

- **前台服务启动被拒**：通知权限关闭或厂商后台限制；服务静默停止不是逻辑 bug。
- **朗读不跟随翻页**：先确认 `ReadBook` 当前章节/分页是否正确，再查 `BaseReadAloudService` 的取文游标。
- **在线 TTS 无声**：检查 `httpTTS.concurrentRate`、请求头与 URL 模板；音频流缓存 128MB，清缓存可排除脏数据。
- **音频缓存失败**：HLS/多段音频不支持；SAF 权限失效需重新选择目录。
