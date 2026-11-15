#Requires -Version 5.1
<#
把最新 release apk 发布到 GitHub Release，供 app 的「外网更新」通道拉取。

每次发布会在仓库里建一个 release（tag = 版本名），并上传两个资产：
    update.json
    legado_<versionName>.apk

app 端只要填仓库 owner/repo，就会去
    https://github.com/<owner>/<repo>/releases/latest/download/update.json
拉清单，再从同一个地址下载 apk。

用法：
    $env:GITHUB_TOKEN = "ghp_xxxx"      # 临时给本次会话
    .\push-github.ps1
    .\push-github.ps1 -Token ghp_xxxx
    .\push-github.ps1 -Repo S123-ts/Legado-Slim -Tag 3.26111522

Token 要求：
    classic PAT 勾选 repo；或 fine-grained PAT 给 Contents: Read and write

没有 token 时脚本只负责在本地产出 update.json 和 apk，并打印手动上传步骤。
#>
[CmdletBinding()]
param(
    [string]$Repo = "",
    [string]$Tag = "",
    [string]$Token = "",
    [string]$Apk = "",
    [string]$RepoRoot = "",
    [switch]$Draft
)

$ErrorActionPreference = "Stop"

function Write-Step([string]$message) {
    Write-Host ("[{0}] {1}" -f (Get-Date -Format "HH:mm:ss"), $message)
}

function Write-Fail([string]$message) {
    Write-Host $message -ForegroundColor Red
}

# ---------- 路径 ----------

if ([string]::IsNullOrWhiteSpace($RepoRoot)) {
    $RepoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..\..")).Path
}
if (-not (Test-Path -LiteralPath (Join-Path $RepoRoot "app\build.gradle"))) {
    throw "没有在 $RepoRoot 找到 app\build.gradle，请用 -RepoRoot 指定仓库根目录"
}
$RepoRoot = (Resolve-Path -LiteralPath $RepoRoot).Path

# ---------- 仓库 owner/repo ----------

function Resolve-Repo([string]$repo, [string]$root) {
    if (-not [string]::IsNullOrWhiteSpace($repo)) {
        $text = $repo.Trim()
        if ($text -match "github\.com[:/]([^/]+)/([^/]+?)(\.git)?$") {
            return "$($Matches[1])/$($Matches[2])"
        }
        if ($text -match "^([^/\s]+)/([^/\s]+)$") {
            return "$($Matches[1])/$($Matches[2])"
        }
        throw "认不出仓库名：$repo，请写成 owner/repo"
    }
    if (Test-Path -LiteralPath (Join-Path $root ".git")) {
        $url = (& git -C $root remote get-url origin 2>$null)
        if ($LASTEXITCODE -eq 0 -and $url) {
            $text = $url.ToString().Trim()
            if ($text -match "github\.com[:/]([^/]+)/([^/]+?)(\.git)?$") {
                return "$($Matches[1])/$($Matches[2])"
            }
        }
    }
    throw "没拿到仓库地址，请用 -Repo owner/repo 指定"
}

$Repo = Resolve-Repo $Repo $RepoRoot
Write-Step "仓库      : $Repo"

# ---------- 生成本地发布产物 ----------

$serveArgs = @{ GenerateOnly = $true; RepoRoot = $RepoRoot }
if (-not [string]::IsNullOrWhiteSpace($Apk)) {
    $serveArgs["Apk"] = $Apk
}
& (Join-Path $PSScriptRoot "serve-update.ps1") @serveArgs | Out-Null

$distDir = Join-Path $RepoRoot "build\intranet-update"
$jsonPath = Join-Path $distDir "update.json"
if (-not (Test-Path -LiteralPath $jsonPath)) {
    throw "没有生成 $jsonPath"
}
$manifest = [System.IO.File]::ReadAllText($jsonPath) | ConvertFrom-Json
$apkName = [string]$manifest.apkName
$apkPath = Join-Path $distDir $apkName
if (-not (Test-Path -LiteralPath $apkPath)) {
    throw "没有生成 $apkPath"
}
if ([string]::IsNullOrWhiteSpace($Tag)) {
    $Tag = [string]$manifest.versionName
}
Write-Step "tag       : $Tag (versionCode $($manifest.versionCode))"

# ---------- token ----------

if ([string]::IsNullOrWhiteSpace($Token)) {
    if (-not [string]::IsNullOrWhiteSpace($env:GITHUB_TOKEN)) {
        $Token = $env:GITHUB_TOKEN
    } elseif (-not [string]::IsNullOrWhiteSpace($env:GH_TOKEN)) {
        $Token = $env:GH_TOKEN
    }
}

if ([string]::IsNullOrWhiteSpace($Token)) {
    Write-Host ""
    Write-Host "没有可用的 GitHub token，本次只生成了本地文件：" -ForegroundColor Yellow
    Write-Host "  $jsonPath"
    Write-Host "  $apkPath"
    Write-Host ""
    Write-Host "两种继续方式：" -ForegroundColor Yellow
    Write-Host "  1) 给一个 token 后重跑："
    Write-Host '       $env:GITHUB_TOKEN = "ghp_xxxx"; .\push-github.ps1'
    Write-Host "     生成地址 https://github.com/settings/tokens（classic 勾 repo）"
    Write-Host "  2) 网页手动发布："
    Write-Host "     打开 https://github.com/$Repo/releases/new"
    Write-Host "     Tag 填 $Tag，标题随意，正文贴 update.json 里的 updateLog"
    Write-Host "     把这两个文件都拖进 assets：update.json、$apkName"
    Write-Host "     勾选 Set as the latest release 后发布"
    Write-Host ""
    Write-Host "发布后 app 里的 GitHub 通道填 $Repo 即可检测到 $($manifest.versionName)"
    exit 2
}

# ---------- 调用 GitHub API ----------

[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$apiBase = "https://api.github.com"
$headers = @{
    "Authorization"          = "Bearer $Token"
    "User-Agent"             = "legado-update-push"
    "Accept"                 = "application/vnd.github+json"
    "X-GitHub-Api-Version"   = "2022-11-28"
}

# 仓库可能改过名，以 API 返回的 full_name 为准
try {
    $repoInfo = Invoke-RestMethod -Method Get -Uri "$apiBase/repos/$Repo" -Headers $headers -TimeoutSec 60
} catch {
    Write-Fail "读取仓库失败：$($_.Exception.Message)"
    Write-Fail "确认 token 有效、有 repo（或 Contents: Read and write）权限，且仓库名正确"
    exit 1
}
$fullName = $repoInfo.full_name
if ($fullName -ne $Repo) {
    Write-Step "仓库已改名，实际为 $fullName"
}

# ---------- 建或复用 release ----------

$releaseUrl = "$apiBase/repos/$fullName/releases"
$existing = $null
try {
    $existing = Invoke-RestMethod -Method Get -Uri "$releaseUrl/tags/$Tag" -Headers $headers -TimeoutSec 60 -ErrorAction Stop
} catch {
    $existing = $null
}

if ($null -ne $existing) {
    Write-Step "release 已存在，覆盖资产"
    $release = $existing
    $patch = @{ name = $Tag; body = [string]$manifest.updateLog } | ConvertTo-Json
    try {
        Invoke-RestMethod -Method Patch -Uri "$releaseUrl/$($release.id)" -Headers $headers -Body $patch -ContentType "application/json" -TimeoutSec 60 | Out-Null
    } catch {
        Write-Step "更新 release 说明失败（不影响资产）：$($_.Exception.Message)"
    }
} else {
    $body = @{
        tag_name         = $Tag
        name             = $Tag
        body             = [string]$manifest.updateLog
        draft            = [bool]$Draft
        prerelease       = $false
        target_commitish = "master"
    } | ConvertTo-Json
    $release = Invoke-RestMethod -Method Post -Uri $releaseUrl -Headers $headers -Body $body -ContentType "application/json" -TimeoutSec 60
    Write-Step "release 已创建: $($release.html_url)"
}

$releaseId = $release.id

# ---------- 清掉同名旧资产 ----------

$assets = Invoke-RestMethod -Method Get -Uri "$releaseUrl/$releaseId/assets" -Headers $headers -TimeoutSec 60
foreach ($asset in $assets) {
    if ($asset.name -eq "update.json" -or $asset.name -eq $apkName) {
        Write-Step "删除旧资产 $($asset.name)"
        try {
            Invoke-RestMethod -Method Delete -Uri "$apiBase/repos/$fullName/releases/assets/$($asset.id)" -Headers $headers -TimeoutSec 60 | Out-Null
        } catch {
            Write-Fail "删除 $($asset.name) 失败：$($_.Exception.Message)"
        }
    }
}

# ---------- 上传 ----------

function Send-Asset([string]$name, [string]$path) {
    $size = (Get-Item -LiteralPath $path).Length
    Write-Step ("上传 {0} ({1:N1} MB)…" -f $name, ($size / 1MB))
    $url = "https://uploads.github.com/repos/$fullName/releases/$releaseId/assets?name=" + [uri]::EscapeDataString($name)
    $result = Invoke-RestMethod -Method Post -Uri $url -Headers $headers `
        -ContentType "application/octet-stream" -InFile $path -TimeoutSec 900
    Write-Step "已上传 $($result.name) ($($result.size) bytes)"
    return $result
}

$jsonAsset = Send-Asset "update.json" $jsonPath
$apkAsset = Send-Asset $apkName $apkPath

# ---------- 验证 ----------

if (-not $Draft) {
    try {
        $latest = Invoke-RestMethod -Method Get -Uri "$apiBase/repos/$fullName/releases/latest" -Headers $headers -TimeoutSec 60
        Write-Step "latest release = $($latest.tag_name)"
        if ($latest.tag_name -ne $Tag) {
            Write-Fail "注意：latest 当前指向 $($latest.tag_name)，不是 $Tag"
        }
    } catch {
        Write-Step "读取 latest 失败：$($_.Exception.Message)"
    }

    $checkUrl = "https://github.com/$fullName/releases/latest/download/update.json"
    try {
        $remote = Invoke-RestMethod -Method Get -Uri $checkUrl -TimeoutSec 60 -Headers @{ "User-Agent" = "legado-update-push" }
        if ([long]$remote.versionCode -eq [long]$manifest.versionCode) {
            Write-Step "公网清单可读，versionCode $($remote.versionCode) 与本地一致"
        } else {
            Write-Fail "公网清单 versionCode $($remote.versionCode) 与本地 $($manifest.versionCode) 不一致"
        }
    } catch {
        Write-Fail "公网清单读取失败：$($_.Exception.Message)（CDN 可能还没刷新，稍后重试）"
    }
}

Write-Host ""
Write-Host "发布完成" -ForegroundColor Green
Write-Host "  release : https://github.com/$fullName/releases/tag/$Tag"
Write-Host "  清单    : https://github.com/$fullName/releases/latest/download/update.json"
Write-Host "  包      : https://github.com/$fullName/releases/latest/download/$apkName"
Write-Host ""
Write-Host "app 里「关于 → 检查更新 → GitHub」填 $fullName 即可"
