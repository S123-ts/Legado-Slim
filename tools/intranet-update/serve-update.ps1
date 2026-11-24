#Requires -Version 5.1
<#
内网更新推送服务

用法（在同一局域网内、装着构建产物的电脑上运行）：
    .\serve-update.ps1
    .\serve-update.ps1 -Port 8765 -Apk D:\build\2026111601.apk
    .\serve-update.ps1 -VersionCode 38503 -VersionName 2026111601
    .\serve-update.ps1 -GenerateOnly        # 只生成清单和改名后的 apk，不起服务（给发布到 GitHub 用）

脚本会：
1. 找到（或使用指定的）release apk
2. 版本号优先取 apk 同目录的 output-metadata.json，其次 -VersionCode 参数，最后用 aapt2 读 apk
3. 生成 update.json 与 sha256
4. 在 0.0.0.0:<Port> 上提供 update.json 与 apk 下载
5. 每次 app 请求清单时重新检查有没有新出的包，有就自动换成新清单，不用重启脚本

注意：apk 是延迟到请求时才重新检查的，所以服务可以先开着，出包后直接从 app 点检测。
#>
[CmdletBinding()]
param(
    [string]$Apk = "",
    [int]$Port = 8765,
    [long]$VersionCode = 0,
    [string]$VersionName = "",
    [string]$UpdateLogFile = "",
    [string]$RepoRoot = "",
    [switch]$GenerateOnly
)

$ErrorActionPreference = "Stop"

$Script:DistDir = ""
$Script:ApkName = ""
$Script:JsonPath = ""
$Script:VersionName = ""
$Script:VersionCode = 0L
$Script:Size = 0L
$Script:Sha256 = ""
$Script:UpdateLog = ""
$Script:Stamp = ""
$Script:FixedApk = $Apk
$Script:FixedVersionName = $VersionName
$Script:FixedVersionCode = $VersionCode

function Write-Step([string]$message) {
    Write-Host ("[{0}] {1}" -f (Get-Date -Format "HH:mm:ss"), $message)
}

# ---------- 基础路径 ----------

if ([string]::IsNullOrWhiteSpace($RepoRoot)) {
    $RepoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..\..")).Path
}
if (-not (Test-Path -LiteralPath (Join-Path $RepoRoot "app\build.gradle"))) {
    throw "没有在 $RepoRoot 找到 app\build.gradle，请用 -RepoRoot 指定仓库根目录"
}
$RepoRoot = (Resolve-Path -LiteralPath $RepoRoot).Path
$Script:RepoRoot = $RepoRoot
# 书源在线更新的产物目录，与 apk 更新共用本服务
$Script:BookSourceDistDir = Join-Path $RepoRoot "build\booksource-update"

# ---------- 版本号 ----------

# 找可用的 aapt2：优先 AAPT2 环境变量，其次 ANDROID_HOME / ANDROID_SDK_ROOT / local.properties 的 sdk.dir
function Resolve-Aapt2 {
    $candidates = @()
    if ($env:AAPT2) { $candidates += $env:AAPT2 }
    $sdk = $env:ANDROID_HOME
    if ([string]::IsNullOrWhiteSpace($sdk)) { $sdk = $env:ANDROID_SDK_ROOT }
    if ([string]::IsNullOrWhiteSpace($sdk)) {
        $lp = Join-Path $Script:RepoRoot "local.properties"
        if (Test-Path -LiteralPath $lp) {
            $line = Get-Content -LiteralPath $lp |
                Where-Object { $_ -match '^sdk\.dir=' } | Select-Object -First 1
            if ($line) { $sdk = $line.Substring(8).Trim().Replace('\:', ':').Replace('\\', '\') }
        }
    }
    if (-not [string]::IsNullOrWhiteSpace($sdk)) {
        $btRoot = Join-Path $sdk "build-tools"
        if (Test-Path -LiteralPath $btRoot) {
            $versions = Get-ChildItem -LiteralPath $btRoot -Directory -ErrorAction SilentlyContinue |
                Where-Object { $_.Name -match '^\d+(\.\d+)+$' } |
                Sort-Object { [version]$_.Name } -Descending
            foreach ($v in $versions) { $candidates += (Join-Path $v.FullName "aapt2.exe") }
        }
    }
    foreach ($c in $candidates) {
        if ($c -and (Test-Path -LiteralPath $c)) { return $c }
    }
    return ""
}

# 没有 output-metadata.json（例如传了外部 apk）时，从 apk 的 AndroidManifest 里读 versionCode。
# 不再解析 build.gradle：versionCode 已改为按时间生成，脚本里用正则复刻同一算法只会跟着漂。
function Get-VersionCodeFromApk([string]$apkPath) {
    $aapt2 = Resolve-Aapt2
    if ([string]::IsNullOrWhiteSpace($aapt2)) {
        Write-Step "找不到 aapt2，读不出 apk 的 versionCode；可设 AAPT2 环境变量，或直接传 -VersionCode"
        return [long]0
    }
    $badging = ""
    try {
        $badging = & $aapt2 dump badging $apkPath 2>$null | Select-Object -First 1
    } catch {
        $badging = ""
    }
    if ($badging -match "versionCode='([0-9]+)'") { return [long]$Matches[1] }
    Write-Step "aapt2 没能解析出 versionCode：$apkPath"
    return [long]0
}

function Get-VersionNameFromApk([string]$path) {
    $name = [System.IO.Path]::GetFileName($path)
    # 新规则 2026111601；兼容旧的 3.26111522
    if ($name -match "(20\d{6}[0-9]{0,2})") { return $Matches[1] }
    if ($name -match "(3\.\d{6,})") { return $Matches[1] }
    return ""
}

function Find-LatestApk([string]$repo) {
    $roots = @(
        (Join-Path $repo "app\build\outputs\apk"),
        (Join-Path $repo "build\outputs\apk")
    )
    $found = New-Object System.Collections.ArrayList
    foreach ($root in $roots) {
        if (Test-Path -LiteralPath $root) {
            Get-ChildItem -LiteralPath $root -Recurse -Filter "*.apk" -File |
                Where-Object { $_.Name -notlike "*debug*" } |
                ForEach-Object { [void]$found.Add($_) }
        }
    }
    if ($found.Count -eq 0) { return "" }
    $latest = $found | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    return $latest.FullName
}

# AGP 会在 apk 同目录写 output-metadata.json，版本号以它为准（产物名可能是 app-app-release.apk）
function Get-OutputMetadata([string]$apkPath) {
    $result = @{ versionName = ""; versionCode = [long]0 }
    $metaPath = Join-Path ([System.IO.Path]::GetDirectoryName($apkPath)) "output-metadata.json"
    if (-not (Test-Path -LiteralPath $metaPath)) { return $result }
    try {
        $meta = [System.IO.File]::ReadAllText($metaPath) | ConvertFrom-Json
        $element = $meta.elements | Select-Object -First 1
        if ($null -ne $element) {
            if ($element.versionName) { $result.versionName = [string]$element.versionName }
            if ($element.versionCode) { $result.versionCode = [long]$element.versionCode }
        }
    } catch {
        Write-Step "output-metadata.json 读取失败，改为推导版本号"
    }
    return $result
}

# ---------- 生成发布产物（可重复调用） ----------

function Resolve-ApkPath {
    if (-not [string]::IsNullOrWhiteSpace($Script:FixedApk)) {
        return (Resolve-Path -LiteralPath $Script:FixedApk).Path
    }
    $found = Find-LatestApk $Script:RepoRoot
    if ([string]::IsNullOrWhiteSpace($found)) { return "" }
    return (Resolve-Path -LiteralPath $found).Path
}

function Build-Release {
    param([switch]$Force)

    $apkPath = Resolve-ApkPath
    if ([string]::IsNullOrWhiteSpace($apkPath)) {
        throw "没找到 release apk（app\build\outputs\apk），请先出包，或用 -Apk 指定文件"
    }

    $item = Get-Item -LiteralPath $apkPath
    $stamp = "{0}|{1}" -f $item.LastWriteTimeUtc.Ticks, $item.Length
    if (-not $Force -and $stamp -eq $Script:Stamp -and (Test-Path -LiteralPath $Script:JsonPath)) {
        return $false
    }

    $metadata = Get-OutputMetadata $apkPath
    $versionCode = $Script:FixedVersionCode
    $versionName = $Script:FixedVersionName
    if ($versionCode -le 0 -and $metadata.versionCode -gt 0) {
        $versionCode = $metadata.versionCode
    }
    if ([string]::IsNullOrWhiteSpace($versionName) -and -not [string]::IsNullOrWhiteSpace($metadata.versionName)) {
        $versionName = $metadata.versionName
    }
    if ([string]::IsNullOrWhiteSpace($versionName)) {
        $versionName = Get-VersionNameFromApk $apkPath
    }
    if ([string]::IsNullOrWhiteSpace($versionName)) {
        throw "无法解析版本名，请用 -VersionName 指定，例如 -VersionName 2026111601"
    }
    $versionName = $versionName.Trim()
    if ($versionCode -le 0) {
        $versionCode = Get-VersionCodeFromApk $apkPath
    }
    if ($versionCode -le 0) {
        throw "versionCode 无法确定：请传 -VersionCode，或把 apk 同目录的 output-metadata.json 一起带上"
    }

    Write-Step "apk       : $apkPath"
    Write-Step "版本      : $versionName (versionCode $versionCode)"

    if ([string]::IsNullOrWhiteSpace($Script:DistDir)) {
        $Script:DistDir = Join-Path $Script:RepoRoot "build\intranet-update"
    }
    New-Item -ItemType Directory -Force -Path $Script:DistDir | Out-Null

    # 文件名带上 versionCode：同一小时内多次出包时 versionName 相同，只有 versionCode 不同；
    # 客户端按 apkName 落盘下载，同名会让第二次下载撞上第一个文件。
    $apkName = "legado_${versionName}_${versionCode}.apk"
    $apkTarget = Join-Path $Script:DistDir $apkName
    Copy-Item -LiteralPath $apkPath -Destination $apkTarget -Force

    $size = (Get-Item -LiteralPath $apkTarget).Length
    $sha = [System.Security.Cryptography.SHA256]::Create()
    $stream = [System.IO.File]::OpenRead($apkTarget)
    try {
        $hash = $sha.ComputeHash($stream)
    } finally {
        $stream.Dispose()
        $sha.Dispose()
    }
    $sha256 = ([System.BitConverter]::ToString($hash) -replace "-", "").ToLower()

    $logFile = $UpdateLogFile
    if ([string]::IsNullOrWhiteSpace($logFile)) {
        $logFile = Join-Path $Script:RepoRoot "app\src\main\assets\updateLog.md"
    }
    $updateLog = ""
    if (Test-Path -LiteralPath $logFile) {
        $updateLog = [System.IO.File]::ReadAllText($logFile).Trim()
    } else {
        Write-Step "没有找到更新日志 $logFile，清单里留空"
    }

    $manifest = [ordered]@{
        versionName = $versionName
        versionCode = $versionCode
        apkName     = $apkName
        apkUrl      = ""
        size        = $size
        sha256      = $sha256
        updateLog   = $updateLog
        createdAt   = [long]([DateTime]::UtcNow - [DateTime]::new(1970, 1, 1, 0, 0, 0, [DateTimeKind]::Utc)).TotalMilliseconds
    }
    $json = $manifest | ConvertTo-Json -Depth 5
    $jsonPath = Join-Path $Script:DistDir "update.json"
    [System.IO.File]::WriteAllText($jsonPath, $json, (New-Object System.Text.UTF8Encoding($false)))

    $Script:ApkName = $apkName
    $Script:JsonPath = $jsonPath
    $Script:VersionName = $versionName
    $Script:VersionCode = $versionCode
    $Script:Size = $size
    $Script:Sha256 = $sha256
    $Script:UpdateLog = $updateLog
    $Script:Stamp = $stamp

    Write-Step "体积      : $size bytes"
    Write-Step "sha256    : $sha256"
    Write-Step "清单      : $jsonPath"
    return $true
}

$null = Build-Release -Force

if ($GenerateOnly) {
    Write-Step "只生成产物，不启动服务"
    Write-Host ""
    Write-Host "产物目录：$Script:DistDir"
    Write-Host "  update.json      清单"
    Write-Host "  $($Script:ApkName)  包（已改名）"
    return
}

# ---------- HTTP 服务 ----------

function Send-Head($stream, [string]$status, [string]$contentType, [long]$length) {
    $head = "HTTP/1.1 $status`r`nContent-Type: $contentType`r`nContent-Length: $length`r`nCache-Control: no-store`r`nConnection: close`r`n`r`n"
    $headBytes = [System.Text.Encoding]::UTF8.GetBytes($head)
    $stream.Write($headBytes, 0, $headBytes.Length)
}

function Send-Text($stream, [string]$status, [string]$contentType, [string]$text) {
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($text)
    Send-Head $stream $status $contentType $bytes.Length
    $stream.Write($bytes, 0, $bytes.Length)
}

function Send-File($stream, [string]$path, [string]$contentType) {
    $length = (Get-Item -LiteralPath $path).Length
    Send-Head $stream "200 OK" $contentType $length
    $file = [System.IO.File]::OpenRead($path)
    $watch = [System.Diagnostics.Stopwatch]::StartNew()
    try {
        $file.CopyTo($stream)
    } finally {
        $file.Dispose()
        $watch.Stop()
        $mb = $length / 1MB
        $seconds = $watch.Elapsed.TotalSeconds
        $rate = if ($seconds -gt 0) { $mb / $seconds } else { 0 }
        # 这条日志用来区分「服务端发得慢」还是「手机/链路慢」
        Write-Step ("发送 {0} 共 {1:N1} MB，用时 {2:N2}s，{3:N1} MB/s" -f [System.IO.Path]::GetFileName($path), $mb, $seconds, $rate)
    }
}

function Get-LanAddresses() {
    $result = New-Object System.Collections.ArrayList
    try {
        $interfaces = [System.Net.NetworkInformation.NetworkInterface]::GetAllNetworkInterfaces()
        foreach ($nic in $interfaces) {
            if ($nic.OperationalStatus -ne "Up") { continue }
            if ($nic.NetworkInterfaceType -eq "Loopback") { continue }
            foreach ($unicast in $nic.GetIPProperties().UnicastAddresses) {
                $address = $unicast.Address
                if ($address.AddressFamily -ne "InterNetwork") { continue }
                $text = $address.ToString()
                if ($text -notlike "127.*" -and $text -notlike "169.254.*") {
                    [void]$result.Add($text)
                }
            }
        }
    } catch {
        # 拿不到网卡信息不影响服务
    }
    if ($result.Count -eq 0) { [void]$result.Add("127.0.0.1") }
    return $result
}

$listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Any, $Port)
try {
    $listener.Start()
} catch {
    throw "端口 $Port 无法监听（可能被占用或需要换端口），试试 -Port 18765"
}

$addresses = Get-LanAddresses
Write-Host ""
Write-Host "内网更新服务已启动，app 里填下面任意一个地址："
foreach ($address in $addresses) {
    Write-Host ("  http://{0}:{1}" -f $address, $Port) -ForegroundColor Green
}
Write-Host ""
Write-Host ("  清单: http://<地址>:{0}/update.json" -f $Port)
Write-Host ("  apk : http://<地址>:{0}/{1}" -f $Port, $Script:ApkName)
Write-Host "  出包后不用重启，app 下次检测会自动拿到新版本"
Write-Host "  按 Ctrl+C 停止"
Write-Host ""

try {
    while ($true) {
        $client = $listener.AcceptTcpClient()
        $stream = $client.GetStream()
        try {
            $stream.ReadTimeout = 5000
            $stream.WriteTimeout = 120000
            $reader = [System.IO.StreamReader]::new($stream, [System.Text.Encoding]::ASCII, $false, 1024, $true)
            $requestLine = $reader.ReadLine()
            if ([string]::IsNullOrWhiteSpace($requestLine)) { continue }
            # 读完请求头，避免客户端等待
            while ($true) {
                $line = $reader.ReadLine()
                if ([string]::IsNullOrWhiteSpace($line)) { break }
            }
            $parts = $requestLine.Split(" ")
            if ($parts.Length -lt 2) {
                Send-Text $stream "400 Bad Request" "text/plain; charset=utf-8" "bad request"
                continue
            }
            $rawPath = $parts[1]
            $path = ([System.Uri]::UnescapeDataString($rawPath)).Split("?")[0]
            if ($path -eq "/") { $path = "/index.html" }

            # 书源在线更新：清单与包由 publish-booksource.ps1 生成，与 apk 共用本服务
            if ($path -eq "/booksource.json" -and (Test-Path -LiteralPath (Join-Path $Script:BookSourceDistDir "booksource.json"))) {
                Send-File $stream (Join-Path $Script:BookSourceDistDir "booksource.json") "application/json; charset=utf-8"
                Write-Step "GET booksource.json"
                continue
            }
            if ($path -eq "/bookSources.json" -and (Test-Path -LiteralPath (Join-Path $Script:BookSourceDistDir "bookSources.json"))) {
                Send-File $stream (Join-Path $Script:BookSourceDistDir "bookSources.json") "application/json; charset=utf-8"
                Write-Step "GET bookSources.json"
                continue
            }

            switch ($path) {
                "/update.json" {
                    # 每次检测都看一眼有没有新包，避免必须重启服务
                    try {
                        $built = Build-Release
                        if ($built) { Write-Step "检测到新包，清单已更新为 $($Script:VersionName)" }
                    } catch {
                        Write-Step "刷新清单失败（继续用上一份）：$($_.Exception.Message)"
                    }
                    Send-File $stream $Script:JsonPath "application/json; charset=utf-8"
                    Write-Step "GET update.json -> $($Script:VersionName) ($($Script:VersionCode))"
                }
                "/index.html" {
                    $html = @"
<!doctype html><html lang="zh"><meta charset="utf-8">
<title>legado 内网更新</title>
<body style="font-family:sans-serif;padding:24px">
<h2>legado 内网更新</h2>
<p>当前推送版本：$($Script:VersionName)（versionCode $($Script:VersionCode)）</p>
<p><a href="/$($Script:ApkName)">$($Script:ApkName)</a> · $($Script:Size) bytes</p>
<p>sha256: <code>$($Script:Sha256)</code></p>
<pre style="white-space:pre-wrap">$($Script:UpdateLog)</pre>
</body></html>
"@
                    Send-Text $stream "200 OK" "text/html; charset=utf-8" $html
                }
                default {
                    $fileName = $path.TrimStart("/")
                    if ($fileName -eq $Script:ApkName -and (Test-Path -LiteralPath $Script:JsonPath)) {
                        Send-File $stream (Join-Path $Script:DistDir $Script:ApkName) "application/vnd.android.package-archive"
                        Write-Step "GET $fileName"
                    } else {
                        Send-Text $stream "404 Not Found" "text/plain; charset=utf-8" "not found"
                        Write-Step "GET $path -> 404"
                    }
                }
            }
        } catch {
            # 单个连接出错不影响服务
        } finally {
            try { $client.Close() } catch { }
        }
    }
} finally {
    $listener.Stop()
}
