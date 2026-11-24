# Legado（自用精简版）

Android 小说阅读器 Legado 的个人精简 fork，只维护两条链路：**看书**（阅读页、正文、缓存、排版）和**听书**（系统 TTS / 在线 TTS / 音频书）。

## 与上游的差异

> 本 fork 自 2026-10-04 起不再与上游并流，也不打算合并回来；上游代码仅作参考查阅。下面是与上游基线的差异清单。

- 移除内置 Web 服务：`service/WebService`、4 个 controller、`modules/web`、`assets/web` 前端及对应菜单/字符串
- 移除词典规则：`dictRules` 表与相关代码
- 移除 WebDAV 启动时自动同步
- 移除漫画、视频、弹幕、MCP、Firebase 等非看书链路模块
- 数据库随精简调整，当前 `@Database(version = 113)`
- 版本号取构建时刻：`versionName = 3.yyMMddHH`；`versionCode` 为 2020-01-01 起的秒数（每秒唯一、单调递增，不取小时），与 git 提交计数解耦；产物名带 versionCode，同一小时多次出包也不会重名

## 构建

JDK 17、Gradle 8.14.5、compileSdk/targetSdk 36、minSdk 23。常用命令与出包签名参数见 `doc/开发指南.md`（`doc/` 不入库）。

出包用 `tools\build-release.ps1`：不带参数 = 正式包；`-Fast` 跳过 lintVital（内测用，省约 1m45）；`-Fast -Profile` 额外打印耗时前 5 的任务；`-Task compileAppDebugKotlin` 是日常改代码时的快速编译（实测约 51s）。

## 更新

- 入口：设置 → 我的 → 检查更新
- 双通道：内网（电脑跑 `tools/intranet-update/serve-update.ps1`，默认端口 8765，手机填电脑地址）或 GitHub 发行
- 出包后把 `update.json` 与 apk 放在同一目录，客户端按 `versionCode` 比对

## 说明

个人自用项目，不接受外部贡献。上游版权归 Legado 原作者，许可证见 `LICENSE.md`。
