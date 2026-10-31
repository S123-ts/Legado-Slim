# Git 提交与推送范围（本项目规则，与全局规则同一条铁律）

## 归属对照（已核对）

| 远端 | 地址 | 归属 | 允许的操作 |
|---|---|---|---|
| `origin` | `https://github.com/S123-ts/legado.git` | 用户自己的 fork | 本地 commit、点名授权后 push |
| —（未配置） | `LegadoTeam/legado` | 原作者 / 上游仓库 | 只读：浏览、fetch、对比；**禁止任何写入** |

`S123-ts/legado` 是从 `LegadoTeam/legado` fork 而来（GitHub API 的 parent / source 均已确认为 `LegadoTeam/legado`）。

## 规则

- **提交目标只有两个**：本地仓库（`git commit`）和用户自己的远端仓库。
- **绝不向 `LegadoTeam/legado`（或任何上游 / 原作者仓库）写入**：不 push、不发 PR、不开 issue、不推分支。
- `gh pr create` 在 fork 仓库里默认把 base 指向上游，因此创建 PR 前必须先确认 head 与 base 两边归属，默认不动它。
- **不新增 `upstream` remote**。当前仓库只有 `origin` 一个远端，这是刻意状态，别为了"同步上游"就添加可写的 upstream；需要看上游更新时用只读方式（`git fetch <url>` 只取不推）。
- 推送前一律先 `git remote -v` 核对 URL 命名空间；不是 `S123-ts`（也不是用户点名的自有仓库）就停下确认。
- 一次 `git commit` 只涉及一个模块；提交前只 `add` 自己点名的路径。
- 本地 commit 可照常做，**push 每一次都要用户单独点名授权**。

## 本项目额外注意

本仓库是自用精简版（已移除 MCP、Firebase、PDF 书签目录、视频播放+弹幕等），删除类改动尤其依赖"改动落在一次提交里"这个前提，将来才能从 `origin/master` 逐文件捞回；不要在脏工作区上做大批删除。
