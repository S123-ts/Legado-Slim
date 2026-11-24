#Requires -Version 5.1
<#
书源在线更新：发布包生成工具

用途：把扫描器的书源产物过滤成可内置/可在线下发的包，并生成清单。

过滤规则（与 AGENTS.md 书源口径一致）：
  1. 只保留 bookSourceType = 0（内置书源硬约束）
  2. 剔除 DefaultData.kt 里 removedDefaultBookSourceUrls 登记的下架源（正则提取，避免两处维护漂移）
  3. 按 bookSourceUrl 去重，保留最后一条

用法：
  .\publish-booksource.ps1                              # 只生成到 build\booksource-update
  .\publish-booksource.ps1 -SyncAssets                  # 同时把过滤后的包写回 app 内置 assets
  .\publish-booksource.ps1 -SyncAssets -BumpVersion     # 写回 assets 并把 LocalConfig.bookSourceVersion 提升 1
  .\publish-booksource.ps1 -UpdateLogFile xxx.md        # 指定清单里的 updateLog 来源（默认 app 的 updateLog.md）

产物（build\booksource-update）：
  bookSources.json   过滤后的书源包
  booksource.json    清单：version / count / sha256 / updateLog / fileName / createdAt

注意：
  - 不推送、不上传；发布到内网/GitHub 由 intranet-update 的两个脚本负责
  - -SyncAssets 会覆盖内置 assets，执行前请确认工作区干净
#>
[CmdletBinding()]
param(
    [string]$Source = "",
    [string]$RepoRoot = "",
    [string]$UpdateLogFile = "",
    [int]$Version = 0,
    [switch]$SyncAssets,
    [switch]$BumpVersion
)

$ErrorActionPreference = "Stop"

function Write-Step([string]$message) {
    Write-Host ("[{0}] {1}" -f (Get-Date -Format "HH:mm:ss"), $message)
}

# ---------- 路径 ----------
if ([string]::IsNullOrWhiteSpace($RepoRoot)) {
    $RepoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..\..")).Path
}
if (-not (Test-Path -LiteralPath (Join-Path $RepoRoot "app\build.gradle"))) {
    throw "没有在 $RepoRoot 找到 app\build.gradle，请用 -RepoRoot 指定仓库根目录"
}
$RepoRoot = (Resolve-Path -LiteralPath $RepoRoot).Path

$assetsFile = Join-Path $RepoRoot "app\src\main\assets\defaultData\bookSources.json"
if ([string]::IsNullOrWhiteSpace($Source)) {
    $scannerSource = "D:\Programs\legado-booksource-scanner\docs\legado-booksource\书源-可用-46条.json"
    if (Test-Path -LiteralPath $scannerSource) {
        $Source = $scannerSource
    } else {
        # 扫描器程序已从磁盘移除，回退到内置清单作为发布源，
        # 保证 push-github.ps1 -IncludeBookSource 不再因默认源缺失而中断
        $Source = $assetsFile
    }
}
if (-not (Test-Path -LiteralPath $Source)) {
    throw "找不到书源产物：$Source，请用 -Source 指定"
}

$defaultDataFile = Join-Path $RepoRoot "app\src\main\java\io\legado\app\help\DefaultData.kt"
$localConfigFile = Join-Path $RepoRoot "app\src\main\java\io\legado\app\help\config\LocalConfig.kt"
$distDir = Join-Path $RepoRoot "build\booksource-update"
New-Item -ItemType Directory -Force -Path $distDir | Out-Null

# ---------- 下架名单：从 DefaultData.kt 提取，作为唯一真源 ----------
if (-not (Test-Path -LiteralPath $defaultDataFile)) {
    throw "找不到 $defaultDataFile"
}
$defaultSrc = [System.IO.File]::ReadAllText($defaultDataFile)
$blockMatch = [regex]::Match($defaultSrc, '(?s)removedDefaultBookSourceUrls\s*=\s*setOf\((.*?)\n\s*\)')
if (-not $blockMatch.Success) {
    throw "没能从 DefaultData.kt 提取 removedDefaultBookSourceUrls，请检查格式"
}
$removed = @{}
foreach ($m in [regex]::Matches($blockMatch.Groups[1].Value, '"([^"]+)"')) {
    $removed[$m.Groups[1].Value] = $true
}
Write-Step ("下架名单 {0} 条（来自 DefaultData.kt）" -f $removed.Count)

# ---------- 读取并过滤 ----------
$sources = [System.IO.File]::ReadAllText($Source) | ConvertFrom-Json
Write-Step ("源产物 {0} 条：{1}" -f @($sources).Count, (Split-Path -Leaf $Source))

$kept = @()
$dropType = 0
$dropRemoved = 0
$seen = @{}
foreach ($s in $sources) {
    $type = [string]$s.bookSourceType
    if ($type -ne "0") { $dropType++; continue }
    $url = [string]$s.bookSourceUrl
    if ($removed.ContainsKey($url)) { $dropRemoved++; continue }
    if ($seen.ContainsKey($url)) { continue }
    $seen[$url] = $true
    $kept += $s
}
Write-Step ("过滤后 {0} 条（剔除 type!=0 共 {1}，下架源 {2}）" -f $kept.Count, $dropType, $dropRemoved)
if ($kept.Count -eq 0) { throw "过滤后没有书源，终止" }

# ---------- 内置书源编号 ----------
# 编号映射是「稳定标识」，同一 URL 跨版本必须保持同一个编号，
# 这样客户端才能按编号判断新增/更新/下架，即使站点 URL 变了也能认出来。
$idMapFile = Join-Path $PSScriptRoot "builtin-ids.json"
$idMap = @{}
if (Test-Path -LiteralPath $idMapFile) {
    $saved = [System.IO.File]::ReadAllText($idMapFile) | ConvertFrom-Json
    foreach ($prop in $saved.PSObject.Properties) {
        $idMap[[string]$prop.Name] = [int]$prop.Value
    }
} else {
    # 首次运行：用当前内置 assets 的 URL 顺序作为初始编号（1..N），保证与现有安装平滑衔接
    if (Test-Path -LiteralPath $assetsFile) {
        $assetsSources = [System.IO.File]::ReadAllText($assetsFile) | ConvertFrom-Json
        $i = 1
        foreach ($s in $assetsSources) {
            $url = [string]$s.bookSourceUrl
            if (-not $idMap.ContainsKey($url)) { $idMap[$url] = $i }
            $i++
        }
    }
}
$maxId = 0
foreach ($v in $idMap.Values) { if ($v -gt $maxId) { $maxId = $v } }

$assigned = 0
$reused = 0
foreach ($s in $kept) {
    $url = [string]$s.bookSourceUrl
    if ($idMap.ContainsKey($url)) {
        $reused++
    } else {
        $maxId++
        $idMap[$url] = $maxId
        $assigned++
    }
    # 把编号写回书源对象；BookSource 实体无此字段，GSON 解析时会忽略，不影响现有导入
    $s | Add-Member -NotePropertyName "builtinId" -NotePropertyValue ([int]$idMap[$url]) -Force
}
Write-Step ("内置编号：沿用 {0}，新分配 {1}，最大编号 {2}" -f $reused, $assigned, $maxId)

# 写回映射文件（URL -> 编号），保留已下架 URL 的编号以免复用
$idMapJson = ($idMap | ConvertTo-Json -Depth 3)
[System.IO.File]::WriteAllText($idMapFile, $idMapJson, (New-Object System.Text.UTF8Encoding($false)))

# ---------- 输出包 ----------
# 紧凑 JSON，与 assets 现有格式风格一致（单行数组）。
# PowerShell 的 ConvertTo-Json 只有 1 条时会输出单个对象而非数组，
# 客户端按 JSON 数组解析，这里统一包成数组，避免单源包解析失败。
$json = ($kept | ConvertTo-Json -Depth 20 -Compress)
if (-not $json.TrimStart().StartsWith("[")) {
    $json = "[" + $json + "]"
}
$packagePath = Join-Path $distDir "bookSources.json"
[System.IO.File]::WriteAllText($packagePath, $json, (New-Object System.Text.UTF8Encoding($false)))

$bytes = [System.IO.File]::ReadAllBytes($packagePath)
$sha = [System.Security.Cryptography.SHA256]::Create()
$hash = $sha.ComputeHash($bytes)
$sha256 = ([System.BitConverter]::ToString($hash) -replace "-", "").ToLower()
$sha.Dispose()
Write-Step ("包体积 {0} bytes，sha256 {1}" -f $bytes.Length, $sha256)

# ---------- 版本：默认从 assets 现有版本戳读取，也可 -Version 指定 ----------
if ($Version -le 0) {
    $lcSrc = [System.IO.File]::ReadAllText($localConfigFile)
    $vMatch = [regex]::Match($lcSrc, 'isLastVersion\((\d+),\s*"bookSourceVersion"\)')
    if (-not $vMatch.Success) { throw "没能从 LocalConfig.kt 读取 bookSourceVersion，请用 -Version 指定" }
    $Version = [int]$vMatch.Groups[1].Value
}
Write-Step ("清单版本 version = $Version")

# ---------- 更新日志 ----------
if ([string]::IsNullOrWhiteSpace($UpdateLogFile)) {
    $UpdateLogFile = Join-Path $RepoRoot "app\src\main\assets\updateLog.md"
}
$updateLog = ""
if (Test-Path -LiteralPath $UpdateLogFile) {
    $updateLog = [System.IO.File]::ReadAllText($UpdateLogFile).Trim()
}

$manifest = [ordered]@{
    version    = $Version
    count      = $kept.Count
    sha256     = $sha256
    fileName   = "bookSources.json"
    size       = $bytes.Length
    updateLog  = $updateLog
    createdAt  = [long]([DateTime]::UtcNow - [DateTime]::new(1970, 1, 1, 0, 0, 0, [DateTimeKind]::Utc)).TotalMilliseconds
}
$manifestPath = Join-Path $distDir "booksource.json"
[System.IO.File]::WriteAllText(
    $manifestPath,
    ($manifest | ConvertTo-Json -Depth 5),
    (New-Object System.Text.UTF8Encoding($false))
)
Write-Step "清单：$manifestPath"

# ---------- 可选：同步 assets + 提升版本戳 ----------
if ($SyncAssets) {
    $backupDir = Join-Path $RepoRoot "build\booksource-update\backup"
    New-Item -ItemType Directory -Force -Path $backupDir | Out-Null
    $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
    Copy-Item -LiteralPath $assetsFile -Destination (Join-Path $backupDir "bookSources-$stamp.json") -Force
    Write-Step "已备份原 assets 到 build\booksource-update\backup"

    [System.IO.File]::WriteAllText($assetsFile, $json, (New-Object System.Text.UTF8Encoding($false)))
    Write-Step "已写回 assets：$assetsFile"

    if ($BumpVersion) {
        if (-not (Test-Path -LiteralPath $localConfigFile)) {
            throw "找不到 $localConfigFile，无法提升版本戳"
        }
        $lc = [System.IO.File]::ReadAllText($localConfigFile)
        $newVersion = $Version + 1
        $updated = [regex]::Replace(
            $lc,
            'isLastVersion\((\d+),\s*"bookSourceVersion"\)',
            ('isLastVersion(' + $newVersion + ', "bookSourceVersion")')
        )
        if ($updated -eq $lc) { throw "版本戳替换失败，请检查 LocalConfig.kt" }
        [System.IO.File]::WriteAllText($localConfigFile, $updated, (New-Object System.Text.UTF8Encoding($false)))
        Write-Step "bookSourceVersion：$Version -> $newVersion（请同步更新注释）"
    }
}

Write-Host ""
Write-Host "完成。产物目录：$distDir"
if (-not $SyncAssets) {
    Write-Host "如需写回内置 assets，加 -SyncAssets；若同时升版本戳，再加 -BumpVersion。"
}
