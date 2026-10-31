# 书源工作流

> 定位：从站点做一个可用书源，或从一批书源里筛出可用清单的通用流程与判定。
>
> 适用范围：Legado 文本 / 文件书源。不覆盖 RSS、漫画、音频源与 TTS 源。
>
> 实现细节：字段含义、语法全集、引擎与网络实现见 [../booksource.md](../booksource.md)；本文只讲怎么做、怎么判。

## 目录

- [0. 适用边界](#0-适用边界)
- [1. 四阶段链路](#1-四阶段链路)
- [2. 制作七步](#2-制作七步)
- [3. 站点类型模板](#3-站点类型模板)
- [4. 批量处理七阶段](#4-批量处理七阶段)
- [5. 判定口径](#5-判定口径)
- [6. 修复模式库](#6-修复模式库)
- [7. 规则语义要点](#7-规则语义要点)
- [8. 排错对照表](#8-排错对照表)
- [9. 工具与产出约定](#9-工具与产出约定)
- [附录 A 案例](#附录-a-案例)

## 0. 适用边界

- **覆盖**：搜索、详情、目录、正文四条链路的制作与核对；单源诊断；批量筛选；规则修复。
- **不覆盖**：RSS 源、漫画源、音频源、TTS 源；WebView 渲染与验证码的自动化；站点登录态的构造。
- **前置**：能访问目标站点；本地有 Node.js 18+ 与项目依赖；批量核对时准备 2 个以上真实书名作关键词。
- **原则**：先诊断再改规则；先小样本再全量；改源用补丁，不覆盖原文件。

## 1. 四阶段链路

一条书源是否可用，不能用「首页能打开」代替。必须走完：

```text
搜索 → 打开详情 → 取目录 → 读正文
```

| 阶段 | 输入 | 参与的规则 | 成功判据 | 常见失败 |
|---|---|---|---|---|
| 搜索 | 关键词 | `searchUrl` + `ruleSearch` | 返回书名与关键词相关 | 入口改版、编码、POST、302 |
| 详情 | 详情页 URL | `ruleBookInfo` | 书名非空，能定位目录 | UA 分流、JSON 壳 |
| 目录 | `tocUrl` 或详情页 | `ruleToc` | 章节数 > 0 | 目录在详情页、分页、选择器改版 |
| 正文 | 章节 URL | `ruleContent` | 去标签后 ≥100 字 | 正文分页、令牌接口、加密、`baseUrl` 错 |

**只有四步都通过才算整链可用。** 搜索通过但目录为空，只能算「搜索可用」，不能推荐导入。

## 2. 制作七步

### 2.1 第一步：分析站点

先拿真实响应，不要凭页面印象写选择器。用单源诊断脚本或手动 `curl`：

```bash
SKILL=/path/to/legado-booksource-search-audit
node "$SKILL/scripts/probe-site.js" --url=https://site.com --kw=测试书名
# 已有书源：按 URL 或索引诊断
node "$SKILL/scripts/probe-site.js" --src=sources.json --idx=0 --kw=测试书名
```

诊断输出应包含：

1. 首页是否可达、状态码、重定向后的最终 URL；
2. 搜索表单的 `action`、`method`、关键词字段名、隐藏字段；
3. 候选搜索入口的响应：状态、标题、是否含关键词、书籍链接聚类；
4. 详情页的 `og:novel:*`、标题、目录容器、目录入口。

**判据**：能拿到至少 1 本真实书的详情 URL，以及 1 个返回相关结果的搜索入口。拿不到就换 UA、换入口或归「受限」，不要硬猜。

### 2.2 第二步：写搜索规则

顺序固定：

1. 入口优先用首页表单的 `action`，其次试常见路径；
2. 参数名逐个试：`key` / `word` / `searchkey` / `keyword` / `q` / `wd`；
3. 确认 GET 还是 POST；POST 写出 `body` 与隐藏字段；
4. GBK 站点在 URL 选项里写 `"charset":"gbk"`；
5. 结果容器用诊断里的父子链聚类确定；
6. 书名与详情链接必须都能取到。

**判据**：`bookList` 解析条数 > 0；前 30 条中书名非空数量 > 0；书名样本与关键词相关。

### 2.3 第三步：写详情规则

详情页补全书名、作者、封面、简介，并定位目录：

```json
{
  "ruleBookInfo": {
    "name": "h1.booktitle@text",
    "author": ".booktag a.red@text",
    "intro": ".bookintro@textNodes",
    "tocUrl": "#allchapter a@href"
  }
}
```

详情页只是壳、数据来自接口时，用 `init` 把页面内容替换成接口结果：

```json
{
  "ruleBookInfo": {
    "init": "@js:java.ajax('https://api.site.com/book/' + java.getString('$.id'))",
    "name": "$.data.title",
    "author": "$.data.author"
  }
}
```

**判据**：书名非空；`tocUrl` 能打开，或目录就在详情页；字段缺失不影响后续两阶段。

### 2.4 第四步：写目录规则

三种形态，按顺序判断：

| 形态 | 特征 | 规则写法 |
|---|---|---|
| 目录在详情页 | 详情页已列出全部章节 | `ruleToc.chapterList` 直接指向列表容器 |
| 目录在独立页 | 详情页只有最新章节与「查看全部」 | `ruleBookInfo.tocUrl` 取链接，`ruleToc` 解析目标页 |
| 目录分页 | 底部有下一页 | 追加 `ruleToc.nextTocUrl` |

```json
{
  "ruleBookInfo": { "tocUrl": "#allchapter a@href" },
  "ruleToc": {
    "chapterList": "#list-chapterAll@dd@a",
    "chapterName": "text",
    "chapterUrl": "href",
    "nextTocUrl": "//*[@id='next' and contains(text(),'下一页')]/@href"
  }
}
```

**判据**：章节数 > 0；章节名与链接成对，顺序正确；分页时累计章节能取全。

### 2.5 第五步：写正文规则

按复杂度从低到高：

| 情况 | 写法 |
|---|---|
| 单容器 | `"content": "#content@text"` |
| 多个段落节点 | `"content": "#content@textNodes"` |
| 正文分页 | 追加 `"nextContentUrl": "a.next@href"` |
| 章节页是空壳、正文由接口返回 | `@js:` 里先取令牌，再 `java.ajax(URL,{...})` |

**判据**：去标签后正文 ≥100 字；内容是正文而非简介、导航或版权声明；分页时能拼完整。

### 2.6 第六步：header、UA 与登录

同一 URL 对不同 UA 可能返回不同模板：桌面端有完整目录，移动端只有壳。

```json
"header": "{\"User-Agent\":\"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36\"}"
```

- `header` 是 **JSON 字符串**，不是对象；
- 源级 `header` 会合并进搜索、详情、目录、正文以及 JS 沙箱请求；
- 需要登录的源，先找游客可用的搜索/阅读入口；必须登录的归「不可用」，不要硬造规则。

**判据**：固定 UA 后，目标页结构稳定且四阶段可复现。

### 2.7 第七步：三级验证

```bash
node "$SKILL/scripts/verify-full.js" --src=one.json --kw=测试书名 --c=1 --gap=2000 --out=out/check.json
```

详情阶段在搜索拿到详情页 URL 后隐式完成，不单独输出布尔值；下面三个布尔值对应搜索、目录、正文。看三个布尔值与三个数值：

| 字段 | 含义 | 通过条件 |
|---|---|---|
| `search` | 搜索阶段 | `true`，且 `names` 与关键词相关 |
| `toc` | 目录阶段 | `true`，且 `chapters` > 0 |
| `content` | 正文阶段 | `true`，且 `contentLen` ≥ 100 |

**验收要求**：至少换第二个关键词再跑一遍；只过一个关键词不算稳定。

## 3. 站点类型模板

### 3.1 CMS 列表页

特征：结果卡片固定 class，搜索多为 `search.php` 类接口。

```json
{
  "searchUrl": "https://site.com/modules/article/search.php,{\"method\":\"POST\",\"body\":\"searchkey={{key}}&searchtype=all\"}",
  "ruleSearch": {
    "bookList": ".bookbox",
    "name": ".bookname a@text",
    "bookUrl": ".bookname a@href",
    "author": ".author.0@text##作者："
  }
}
```

### 3.2 搜索 302 直达详情页

特征：搜索请求最终 URL 是 `/books/123/`，页面只有一本书。此时 `ruleSearch` 不参与，命中后按详情页解析：

```json
{
  "bookUrlPattern": "https?://(www[.])?site[.]com/books/[0-9]+.*"
}
```

排障要点：先看最终 URL 是不是详情页；POST 后 302 不要强制重发 POST（会 411）。

### 3.3 移动端与桌面端结构不同

特征：同一 URL 在手机 UA 下没有目录或正文容器。固定桌面 UA 到源级 `header`，让四个阶段共用同一模板。

### 3.4 JSON 接口源

特征：搜索、详情返回 JSON，页面只是壳。

```json
{
  "searchUrl": "https://api.site.com/search?keyword={{key}}",
  "ruleSearch": {
    "bookList": "$.data.books",
    "name": "$.title",
    "author": "$.author",
    "bookUrl": "https://site.com/book/{{$.id}}"
  }
}
```

详情壳用 `ruleBookInfo.init` 替换页面内容，再按 JSONPath 取字段。

### 3.5 目录就在详情页

特征：「查看全部章节」直接展开，或详情页底部有完整列表。只需 `ruleToc.chapterList`，不要多写 `tocUrl`。

### 3.6 两步令牌正文

特征：章节页只有空容器，正文由脚本先取令牌、再请求接口：

```text
章节页 /books/{aid}/{cid}.html
  → /scripts/chapter.js.php?aid=...&cid=...&referrer=...
      返回 chapterToken / timestamp / nonce
  → /modules/article/ajax2.php?aid=...&cid=...&token=...&timestamp=...&nonce=...
      返回 JSON，正文在 data.content
```

规则用 `@js:` 复刻两步请求，关键是：

1. `baseUrl` 必须是**章节页 URL**；
2. `java.ajax` 的 `URL,{...}` 选项串要带上编码与请求头；
3. 令牌每次重新取，不缓存；
4. 接口返回 JSON 就直接取字段；返回密文归复杂 JS，交给 App 验证。

### 3.7 WebView 与复杂 JS

特征：`bookUrl` 带 `{"webView":true}`，或正文依赖动态签名、验证码、渲染后 DOM。

这类源在 App 内可能可用，但本地模拟器验不了。处理原则：**归「受限」保留原规则，不为了本地通过而改坏它。**

## 4. 批量处理七阶段

### 4.1 管线总览

```text
输入 JSON
  → 1. 结构校验（数组、必需字段）
  → 2. 首页探测（直连 / 代理 / UA）
  → 3. 搜索探测（关键词、结果聚类、关键词命中）
  → 4. 分类（可用 / 没这本 / 乱返回 / 不可达 / 需人工）
  → 5. 三级验证（搜索 → 目录 → 正文）
  → 6. 修复（候选补丁 + 双关键词复验）
  → 7. 删除与导出（乱返回、确认不可用删除；受限保留）
```

### 4.2 环境与限流

- Node.js 18+，`curl` 在 PATH；
- 依赖 `cheerio`、`iconv-lite`、`@xmldom/xmldom`、`xpath`，安装在项目根目录；
- 同一站点连续请求间隔 ≥2 秒；返回 000/403 先等 10 秒再重试；
- 需要代理时统一用 `--proxy http://127.0.0.1:<端口>`，不要把代理写死进书源规则。

### 4.3 第一阶段：搜索探测

```bash
node "$SKILL/scripts/audit-probe.js" --src=sources.json --kw=测试书名 --out=out/stage1.json --c=8
```

关注字段：

- `count`：解析出的条目数；
- `named`：前 30 条中书名非空的数量；
- `names`：书名样本；
- `hints.hasKeyword`：响应里是否含关键词。

`count > 0` 但 `named == 0`，通常是容器对了、书名选择器错了。

### 4.4 第二阶段：重试

```bash
node "$SKILL/scripts/audit-retry.js" --src=sources.json --in=out/stage1.json --out=out/stage2.json --kw2=第二个书名
```

策略顺序：预热首页拿 Cookie → 带 Referer → 代理重试 → 换关键词。**只有全部失败才判搜索失效。**

### 4.5 第三阶段：首页书名回搜

```bash
node "$SKILL/scripts/audit-home.js" --src=sources.json --stage1=out/stage1.json --stage2=out/stage2.json --out=out/home.json
```

从首页提取真实书名回搜。站内有书但搜不到，才是真的搜索规则失效；首页书名也搜不到，多半是入口或站点问题。

### 4.6 第四阶段：分类

```bash
node "$SKILL/scripts/audit-classify.js" --src=sources.json --stage1=out/stage1.json --stage2=out/stage2.json --home=out/home.json --outdir=out
```

按源分类：

| 分类 | 含义 | 后续 |
|---|---|---|
| A | 可用：搜索返回相关书名 | 进入三级验证 |
| B | 搜索失效：站内有书但搜不到 | 诊断 + 修搜索 |
| C | 站点不可达 | 归受限，换网络再试 |
| D | 需人工：反爬、复杂 JS、无书名 | 保留，App 内验证 |
| E | 无 `searchUrl`，只能发现页 | 不参与搜索核对 |

指定书名测试时另用 P1–P4：

| 分类 | 含义 |
|---|---|
| P1 | 搜到测试书 |
| P2 | 没搜到测试书，但基线书能搜到 |
| P3 | 返回无关默认列表 |
| P4 | 两种关键词都无结果 |

### 4.7 第五阶段：三级验证

```bash
node "$SKILL/scripts/verify-full.js" --src=out/patched-one.json --kw=测试书名 --c=1 --gap=2000 --out=out/fullcheck.json
```

判定见 [2.7](#27-第七步三级验证)。未通过时记录失败阶段与 `err`，不要只记「不可用」。

### 4.8 第六阶段：修复

1. `repair-diagnose.js` 看首页表单、原响应、结果页聚类；
2. `repair-verify.js --gen` 生成候选补丁；
3. 人工确认 `searchUrl` / `ruleSearch` / `ruleBookInfo` / `ruleToc` / `ruleContent`；
4. 写成 `patches.json`；
5. 应用补丁，双关键词复验；
6. 通过的写入新副本，**原文件不动**。

补丁必须按 `bookSourceUrl` 或 `bookSourceName` 定位，**不要按数组索引**——增删源会让索引错位。

### 4.9 第七阶段：删除与导出

| 类型 | 处理 |
|---|---|
| 乱返回 | 删除，留档 |
| 确认不可用（需登录、关站、域名被占、接口稳定 500） | 删除，留档 |
| 受限（403、验证码、超时、复杂 JS） | **保留**，App 内再试 |
| 未处理 | 保留，等后续修 |

导出建议至少三份：整链可用、搜索可用、最终全量；删除项单独留档，便于回滚。

## 5. 判定口径

### 5.1 什么算「搜到」

书名必须与关键词相关：

- 搜《凡人修仙传》，返回《凡人修仙传》《凡人修仙之仙界篇》→ 相关；
- 返回站点热榜、默认推荐、无关书 → 乱返回；
- 只命中关键词里的两个字，噪音很大 → 可保留但标注「宽泛匹配」。

### 5.2 可用、受限、不可用

| 结论 | 判据 | 不要混淆 |
|---|---|---|
| 可用 | 四阶段全通过，书名相关 | 只有搜索通过不等于可用 |
| 搜索可用 | 搜索通过，目录/正文未验或未通过 | 只能作备用 |
| 受限 | 403、验证码、超时、代理问题、复杂 JS | 不等于永久失效，**不能删** |
| 不可用 | 需登录、站点关闭搜索、域名被占、稳定 5xx | 要有站点侧证据 |
| 乱返回 | 请求成功但书名与关键词无关 | 不能计为可用 |

### 5.3 删除策略

只删「乱返回」和「有明确站点侧原因的不可用」；受限与未处理一律保留。删除前把被删条目写入 `deleted-*.json`，原始输入始终只读。

## 6. 修复模式库

### 6.1 按症状选修法

| 症状 | 先查 | 修法 |
|---|---|---|
| 域名打不开但站点还活着 | 301/302 的 Location、TLD 变体 | 更新 `bookSourceUrl` 与 `searchUrl` |
| 搜索命中唯一结果跳详情页 | 最终 URL 是不是详情页 | 加 `bookUrlPattern` |
| 电脑正常、手机模板不同 | 是否按 UA 分流 | 源级 `header` 固定桌面 UA |
| 搜索表单是 POST | `action`、隐藏字段 | `searchUrl` 写 `URL,{method,body,charset}` |
| 列表有书但 `bookList` 为 0 条 | 容器 class 是否改版 | 按父链聚类重写选择器 |
| 详情页只有最新章节 | 「查看全部章节」链接 | `ruleBookInfo.tocUrl` |
| 目录分页 | 「下一页」链接 | `ruleToc.nextTocUrl` |
| 详情页是 JSON 壳 | 实际数据接口 | `ruleBookInfo.init` |
| 正文分页 | 「下一页」链接 | `ruleContent.nextContentUrl` |
| 正文两步令牌接口 | 令牌脚本 + 数据接口 | `java.ajax(URL,{...})` + 正确 `baseUrl` |
| 搜索 411 Length Required | POST 后 302 丢 body | 直接请求最终 URL，不强制重发 POST |

### 6.2 选择器改版速查

| 页面结构 | 规则 |
|---|---|
| `a < h4.title < div.item` | `bookList: ".item"`、`name: "h4.title@text"`、`bookUrl: "h4.title@a@href"` |
| `a < span < dd < dl` | `bookList: "dl 的 class"`、`name: "span@text"`、`bookUrl: "a@href"` |
| 列表项自身是 `<a>` | `bookList: "a.item"`、`name: "@text"`、`bookUrl: "@href"` |
| 目录 `#list-chapterAll dd a` | `chapterList: "#list-chapterAll@dd@a"`、`chapterName: "text"`、`chapterUrl: "href"` |

### 6.3 判定顺序

1. 首页 / 域名是否可达；
2. 搜索最终 URL 是不是详情页；
3. 搜索响应是否含关键词、是否返回默认列表；
4. `count > 0` 但 `named == 0` → 只改 `name`；
5. 搜索通过但目录 0 章 → 查 `tocUrl`、目录容器、UA；
6. 目录通过但正文 0 字 → 查 `ruleContent`、分页、加密、`baseUrl`。

## 7. 规则语义要点

写规则时最容易踩的 10 条，完整语法见 [../booksource.md](../booksource.md) 第二部分：

1. `字段@js:` 是**先取值、再把值作为 `result` 传给 JS**，不是「用 JS 生成字段」；
2. 多元素规则会合并全部匹配结果（换行连接），不是只取第一个；
3. 单段 `text` / `href` / `textNodes` 取**当前元素自身**，`a` / `div` / `li` 才是子选择器；
4. 根级 XPath 可用于 `tocUrl`、`content`、`nextContentUrl`；
5. `ruleBookInfo.init` 会替换详情页内容，适合 JSON 接口壳；
6. `bookUrlPattern` 命中后按详情页解析，书名取 `ruleBookInfo.name`；
7. 源级 `header` 合并进所有请求，包括 JS 沙箱里的 `java.ajax`；
8. POST 遇到 302 时，有 body 才发 POST，避免 411；
9. `java.ajax` 支持 `URL,{"method":"POST","body":...,"headers":{...},"charset":"gbk"}` 选项串；
10. `baseUrl` 分阶段变化：搜索=最终 URL、详情=详情页、目录=目录页、正文=章节页。

## 8. 排错对照表

| 现象 | 原因 | 处理 |
|---|---|---|
| 正常源被判 0 结果 | 只看了第一条，第一条是广告 | 统计前 30 条的 `named` |
| 搜到一堆无关书就判可用 | 站点忽略关键词，返回默认列表 | 看 `names` 是否与关键词相关 |
| 403 被判不可达 | 把反爬当域名失效 | 归受限，换 UA / 代理再试 |
| `bookSourceUrl` 不是网址 | 域名写在 `jsLib` 里 | 归需人工，不做自动判定 |
| 把「阅读记录」当书名回搜 | 首页导航被当成书 | 过导航词黑名单 |
| 用 `[分类]书名` 回搜仍为 0 | 前缀影响匹配 | 去前缀再搜 |
| `named > 0` 就当修好 | 返回的是默认推荐 | 检查关键词命中 |
| 补丁应用后索引错位 | 按数组索引改源 | 按 `bookSourceUrl` 定位 |
| 正文 0 字 | `baseUrl` 错、`java.ajax` 选项没生效、正文加密 | 按 [3.6](#36-两步令牌正文) 排障 |
| 目录 0 章 | `tocUrl` 错、移动/桌面模板不同、选择器改版 | 先看详情页目录容器 |
| 搜索 411 | POST 302 丢 body | 直接请求最终域名 |

## 9. 工具与产出约定

### 9.1 工具

脚本在项目外的 Skill 里，运行前把 `$SKILL` 指向其 `scripts/` 目录；在项目根目录执行，以复用 `node_modules`。

| 工具 | 用途 | 关键参数 |
|---|---|---|
| `audit-probe.js` | 第一轮直搜 | `--src --kw --out --c --only` |
| `audit-retry.js` | 预热 / Referer / 代理 / 备用关键词 | `--src --in --out --kw2 --proxy` |
| `audit-home.js` | 首页书名回搜 | `--src --stage1 --stage2 --out` |
| `audit-classify.js` | A–E / P1–P4 分类与报告 | `--src --stage1 --stage2 --home --outdir` |
| `repair-diagnose.js` | 首页表单、原响应、结果页聚类 | `--src --idx` |
| `repair-verify.js` | 候选补丁生成、应用、双关键词复验 | `--src --patches --gen` |
| `probe-site.js` | 单源诊断 | `--src --idx` 或 `--url`，`--kw` |
| `verify-full.js` | 三级验证 | `--src --kw --c --gap --out --proxy` |

`verify-full.js` 的主要输出字段：`search` / `toc` / `content`、`chapters`、`contentLen`、`err`、`names`。

### 9.2 产出命名

| 文件 | 内容 |
|---|---|
| `usable-full.json` | 四阶段全通过 |
| `usable-search.json` | 搜索可用（含全通过） |
| `final-booksource.json` | 最终全量 |
| `deleted-*.json` | 删除项留档，按原因分文件 |
| `patches.json` | 补丁，按 `bookSourceUrl` 定位 |
| `status-*.csv` | 逐条状态，便于表格核对 |
| `*-REPORT.md` | 方法、结论、名单、局限 |

### 9.3 留档原则

1. 原始输入只读，所有修改写入新副本；
2. 删除项单独留档，保证可回滚；
3. 补丁与验证结果分开存，便于复算；
4. 报告写清测试关键词、时间、网络条件；站点会改版，结论有时效。

## 附录 A 案例

> 案例只作方法示例，不含真实站点名；记录于 2026-10-31，外部接口可能已失效，处理前重新诊断。

### A.1 搜索 302 直达详情页

- **现象**：搜索请求返回 200，但解析结果为空；网络面板里最终 URL 是 `/books/123/`。
- **诊断**：站点把唯一命中直接 302 到详情页，`ruleSearch.bookList` 自然取不到列表。
- **处理**：加 `bookUrlPattern` 覆盖详情页 URL，让 Legado 改走 `ruleBookInfo`；同时去掉 `-X POST` 强制重发，避免 411。
- **结果**：书名、目录、正文均由详情页链路取到。
- **可复用点**：搜索无列表先看最终 URL，不要先改选择器。

### A.2 目录在详情页

- **现象**：详情页能显示最新章节，`ruleToc` 却解析出 0 章。
- **诊断**：完整目录在同一页底部，容器是 `#list-chapterAll dd a`，原规则指向了另一个已失效的分页地址。
- **处理**：删除错误的 `tocUrl`，把 `chapterList` 改为 `#list-chapterAll@dd@a`，`chapterName` / `chapterUrl` 用 `text` / `href`。
- **结果**：章节完整，顺序正确。
- **可复用点**：详情页有目录时不要再走 `tocUrl`；删除失效分页规则。

### A.3 两步令牌正文

- **现象**：目录正常，正文页只有空容器，本地验证 0 字。
- **诊断**：正文由 `chapter.js.php` 取令牌、`ajax2.php` 返回 JSON；模拟器没带选项串，且 `baseUrl` 用了书源首页。
- **处理**：`@js:` 里用章节页 URL 作 `baseUrl`，`java.ajax` 带 `{"charset":"gbk","headers":{...}}`，每次重新取令牌，再解析 `data.content`。
- **结果**：正文可取，字数正常。
- **可复用点**：正文 0 字时先确认 `baseUrl` 是章节页；接口源检查选项串是否生效。

## 交叉引用

- 实现全解：[../booksource.md](../booksource.md)
- 改动范围与验收口径：[../../.qoder/rules/scope-read-listen.md](../../.qoder/rules/scope-read-listen.md)
- 新指南模板：[_template.md](_template.md)
