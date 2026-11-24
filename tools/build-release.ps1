#Requires -Version 5.1
<#
出包包装脚本：把「少等几分钟」和「看时间花在哪」固化成命令。

用法：
    .\tools\build-release.ps1                     # 正式包，lintVital 照跑（= .\gradlew.bat assembleRelease）
    .\tools\build-release.ps1 -Fast               # 内测包，跳过 lintVital（上次实测省约 1m45）
    .\tools\build-release.ps1 -Fast -Profile      # 同时出 profile 报告，并打印耗时前 5 的任务
    .\tools\build-release.ps1 -Task compileAppDebugKotlin     # 日常改代码只编 Kotlin（实测约 51s）

固定带 --parallel --build-cache（CI 一直是这么跑的，本机之前没开）。
不启用 configuration-cache：versionCode 在配置阶段取当前时间，缓存会把版本号冻住。
#>
[CmdletBinding()]
param(
    [switch]$Fast,
    [switch]$Profile,
    [string]$Task = ":app:assembleRelease",
    [string]$RepoRoot = ""
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($RepoRoot)) {
    $RepoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..")).Path
}
$gradlew = Join-Path $RepoRoot "gradlew.bat"
if (-not (Test-Path -LiteralPath $gradlew)) {
    throw "没有找到 $gradlew，请用 -RepoRoot 指定仓库根目录"
}

function Write-Step([string]$message) {
    Write-Host ("[{0}] {1}" -f (Get-Date -Format "HH:mm:ss"), $message)
}

# "1m 20.35s" / "350ms" / "2.5s" -> 秒
function ConvertTo-Seconds([string]$text) {
    $total = 0.0
    foreach ($m in [regex]::Matches($text, '(\d+(?:[.,]\d+)?)\s*(ms|s|m|h)')) {
        $v = [double]($m.Groups[1].Value.Replace(',', '.'))
        switch ($m.Groups[2].Value) {
            'ms' { $total += $v / 1000 }
            's' { $total += $v }
            'm' { $total += $v * 60 }
            'h' { $total += $v * 3600 }
        }
    }
    return $total
}

# profile html 是唯一的逐任务耗时来源，格式变了就只打印路径，不硬猜
function Show-ProfileTop([string]$repo) {
    $dir = Join-Path $repo "build\reports\profile"
    if (-not (Test-Path -LiteralPath $dir)) {
        Write-Host "没有找到 profile 报告目录：$dir"
        return
    }
    $report = Get-ChildItem -LiteralPath $dir -Filter "*.html" -File |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if ($null -eq $report) {
        Write-Host "profile 目录里还没有 html 报告"
        return
    }
    Write-Host ""
    Write-Step "profile 报告：$($report.FullName)"
    $tasks = @()
    try {
        $html = [System.IO.File]::ReadAllText($report.FullName)
        foreach ($row in [regex]::Matches($html, '(?s)<tr[^>]*>(.*?)</tr>')) {
            $cells = @([regex]::Matches($row.Groups[1].Value, '(?s)<td[^>]*>(.*?)</td>') |
                ForEach-Object { ($_.Groups[1].Value -replace '<[^>]+>', '').Trim() })
            if ($cells.Count -lt 2) { continue }
            $name = $cells[0]
            if ($name -notmatch ':') { continue }   # 只取 app:xxx 这类任务名
            $seconds = 0.0
            foreach ($c in $cells) { $seconds += (ConvertTo-Seconds $c) }
            if ($seconds -le 0) { continue }
            $tasks += [pscustomobject]@{ Task = $name; Seconds = $seconds }
        }
    } catch {
        $tasks = @()
    }
    if ($tasks.Count -eq 0) {
        Write-Host "（报告已生成，但没解析出任务耗时，直接打开上面那个 html 看）"
        return
    }
    Write-Host "耗时前 5 的任务："
    $tasks | Sort-Object Seconds -Descending | Select-Object -First 5 |
        ForEach-Object { Write-Host ("  {0,-52} {1,8:N1}s" -f $_.Task, $_.Seconds) }
}

function Show-Apk([string]$repo) {
    $metaPath = Join-Path $repo "app\build\outputs\apk\app\release\output-metadata.json"
    if (-not (Test-Path -LiteralPath $metaPath)) { return }
    try {
        $meta = [System.IO.File]::ReadAllText($metaPath) | ConvertFrom-Json
        $element = $meta.elements | Select-Object -First 1
        if ($null -eq $element) { return }
        $apk = Join-Path (Split-Path -Parent $metaPath) ([string]$element.outputFile)
        $sizeMb = if (Test-Path -LiteralPath $apk) { [math]::Round((Get-Item -LiteralPath $apk).Length / 1MB, 1) } else { 0 }
        Write-Host ""
        Write-Step ("产物：{0}  {1} (versionCode {2}, {3} MB)" -f $element.outputFile, $element.versionName, $element.versionCode, $sizeMb)
    } catch {
        # 产物信息只是顺手打印，读不到不影响构建结论
    }
}

$gradleArgs = @("--console=plain", "--parallel", "--build-cache", $Task)
if ($Fast) {
    $gradleArgs = @("-PfastRelease=true") + $gradleArgs
    Write-Step "Fast 模式：跳过 lintVital（仅用于内测）"
}
if ($Profile) {
    $gradleArgs += "--profile"
}
Write-Step ("gradlew {0}" -f ($gradleArgs -join " "))

$start = Get-Date
Push-Location $RepoRoot
try {
    & $gradlew @gradleArgs
    $exitCode = $LASTEXITCODE
} finally {
    Pop-Location
}
$seconds = ((Get-Date) - $start).TotalSeconds
$elapsed = "{0}m {1:D2}s" -f [int][math]::Floor($seconds / 60), [int][math]::Floor($seconds % 60)

if ($exitCode -ne 0) {
    Write-Host ""
    Write-Step "构建失败（exit $exitCode），总耗时 $elapsed；日志见 build 输出或上次的 *.log"
    exit $exitCode
}

Write-Step "构建成功，总耗时 $elapsed"

if ($Profile) { Show-ProfileTop $RepoRoot }
Show-Apk $RepoRoot

if (-not $Fast -and $Task -like "*ssembleRelease*") {
    Write-Host ""
    Write-Host "提示：内测包可以用 -Fast 跳过 lintVital，正式发版保持不带参数。"
}
