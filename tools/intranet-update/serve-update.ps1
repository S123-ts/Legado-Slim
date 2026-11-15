#Requires -Version 5.1
<#
内网更新推送服务

用法（在同一局域网内、装着构建产物的电脑上运行）：
    .\serve-update.ps1
    .\serve-update.ps1 -Port 8765 -Apk D:\build\legado_3.26111510.apk
    .\serve-update.ps1 -VersionCode 38512 -VersionName 3.26111510
    .\serve-update.ps1 -GenerateOnly        # 只生成清单和改名后的 apk，不起服务

脚本会：
1. 找到（或使用指定的）release apk
2. 从 build.gradle + git 推导 versionCode（与出包构建一致）
3. 生成 update.json 与 sha256
4. 在 0.0.0.0:<Port> 上提供 update.json 与 apk 下载
5. 打印本机局域网地址，填到 app 的「关于 → 内网更新」里即可
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

# ---------- 版本号 ----------

function Get-VersionCodeFromGradle([string]$repo) {
    $gradle = Join-Path $repo "app\build.gradle"
    $text = [System.IO.File]::ReadAllText($gradle)
    $baseCommit = [regex]::Match($text, "versionCodeBaseCommit\s*=\s*'([0-9a-fA-F]+)'").Groups[1].Value
    $baseCodeText = [regex]::Match($text, "versionCodeAtBase\s*=\s*([0-9]+)").Groups[1].Value
    $floorText = [regex]::Match($text, "versionCodeFloor\s*=\s*([0-9]+)").Groups[1].Value
    if (-not $baseCommit -or -not $baseCodeText -or -not $floorText) {
        Write-Step "build.gradle 里没找到 versionCode 基准常量，改用 -VersionCode 参数"
        return [long]0
    }
    $count = 0
    try {
        $raw = & git -C $repo rev-list "$baseCommit..HEAD" --count --no-merges 2>$null
        if ($LASTEXITCODE -eq 0 -and $raw) {
            $count = [int]($raw.ToString().Trim() -split "`n" | Select-Object -First 1)
        }
    } catch {
        $count = 0
    }
    $code = [long]$baseCodeText + $count
    $floor = [long]$floorText
    if ($code -lt $floor) { $code = $floor }
    return $code
}

function Get-VersionNameFromApk([string]$path) {
    $name = [System.IO.Path]::GetFileName($path)
    $match = [regex]::Match($name, "3\.(\d{6,})")
    if ($match.Success) { return "3." + $match.Groups[1].Value }
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

# ---------- 解析 apk ----------

if ([string]::IsNullOrWhiteSpace($Apk)) {
    $Apk = Find-LatestApk $RepoRoot
    if ([string]::IsNullOrWhiteSpace($Apk)) {
        throw "没找到 release apk（app\build\outputs\apk），请先出包，或用 -Apk 指定文件"
    }
}
$Apk = (Resolve-Path -LiteralPath $Apk).Path

$metadata = Get-OutputMetadata $Apk
if ($VersionCode -le 0 -and $metadata.versionCode -gt 0) {
    $VersionCode = $metadata.versionCode
}
if ([string]::IsNullOrWhiteSpace($VersionName) -and -not [string]::IsNullOrWhiteSpace($metadata.versionName)) {
    $VersionName = $metadata.versionName
}

if ([string]::IsNullOrWhiteSpace($VersionName)) {
    $VersionName = Get-VersionNameFromApk $Apk
}
if ([string]::IsNullOrWhiteSpace($VersionName)) {
    throw "无法解析版本名，请用 -VersionName 指定，例如 -VersionName 3.26111510"
}
$VersionName = $VersionName.Trim()

if ($VersionCode -le 0) {
    $VersionCode = Get-VersionCodeFromGradle $RepoRoot
}
if ($VersionCode -le 0) {
    throw "versionCode 无法确定，请用 -VersionCode 指定（见 build.gradle 的算法）"
}

Write-Step "apk       : $Apk"
Write-Step "版本      : $VersionName (versionCode $VersionCode)"

# ---------- 发布目录与清单 ----------

$Script:DistDir = Join-Path $RepoRoot "build\intranet-update"
New-Item -ItemType Directory -Force -Path $Script:DistDir | Out-Null

$Script:ApkName = "legado_$VersionName.apk"
$apkTarget = Join-Path $Script:DistDir $Script:ApkName
Copy-Item -LiteralPath $Apk -Destination $apkTarget -Force

$size = (Get-Item -LiteralPath $apkTarget).Length
$sha256 = (Get-FileHash -LiteralPath $apkTarget -Algorithm SHA256).Hash.ToLower()

if ([string]::IsNullOrWhiteSpace($UpdateLogFile)) {
    $UpdateLogFile = Join-Path $RepoRoot "app\src\main\assets\updateLog.md"
}
$updateLog = ""
if (Test-Path -LiteralPath $UpdateLogFile) {
    $updateLog = [System.IO.File]::ReadAllText($UpdateLogFile).Trim()
} else {
    Write-Step "没有找到更新日志 $UpdateLogFile，清单里留空"
}

$manifest = [ordered]@{
    versionName = $VersionName
    versionCode = $VersionCode
    apkName     = $Script:ApkName
    apkUrl      = ""
    size        = $size
    sha256      = $sha256
    updateLog   = $updateLog
    createdAt   = [long]([DateTime]::UtcNow - [DateTime]::new(1970, 1, 1, 0, 0, 0, [DateTimeKind]::Utc)).TotalMilliseconds
}
$json = $manifest | ConvertTo-Json -Depth 5
$Script:JsonPath = Join-Path $Script:DistDir "update.json"
[System.IO.File]::WriteAllText($Script:JsonPath, $json, (New-Object System.Text.UTF8Encoding($false)))

Write-Step "体积      : $size bytes"
Write-Step "sha256    : $sha256"
Write-Step "清单      : $Script:JsonPath"

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
    try {
        $file.CopyTo($stream)
    } finally {
        $file.Dispose()
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

            switch ($path) {
                "/update.json" {
                    Send-File $stream $Script:JsonPath "application/json; charset=utf-8"
                    Write-Step "GET update.json"
                }
                "/index.html" {
                    $html = @"
<!doctype html><html lang="zh"><meta charset="utf-8">
<title>legado 内网更新</title>
<body style="font-family:sans-serif;padding:24px">
<h2>legado 内网更新</h2>
<p>当前推送版本：$VersionName（versionCode $VersionCode）</p>
<p><a href="/$($Script:ApkName)">$($Script:ApkName)</a> · $size bytes</p>
<p>sha256: <code>$sha256</code></p>
<pre style="white-space:pre-wrap">$updateLog</pre>
</body></html>
"@
                    Send-Text $stream "200 OK" "text/html; charset=utf-8" $html
                }
                default {
                    $fileName = $path.TrimStart("/")
                    if ($fileName -eq $Script:ApkName) {
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
