#Requires -Version 5.1
<#
内网日志接收服务

用法（在电脑上运行，手机和电脑在同一个局域网）：
    .\serve-logs.ps1
    .\serve-logs.ps1 -Port 8766 -LogDir D:\legado-logs

脚本会：
1. 在 0.0.0.0:<Port> 收 POST，body 就是 zip 原始字节，文件名取 X-File-Name 头
2. 落盘到 -LogDir（默认脚本目录下的 logs），重名自动加序号
3. 返回 {"url":"...","file":"...","size":...}，手机端会把这个 url 显示出来
4. 浏览器打开 http://<地址>:<Port>/ 能看到收到的日志列表

只给局域网自用，没做鉴权，别把这个端口映射到公网。
#>
[CmdletBinding()]
param(
    [int]$Port = 8766,
    [string]$LogDir = ""
)

$ErrorActionPreference = "Stop"

$Script:MaxBytes = 64MB

if ([string]::IsNullOrWhiteSpace($LogDir)) {
    $LogDir = Join-Path $PSScriptRoot "logs"
}
New-Item -ItemType Directory -Force -Path $LogDir | Out-Null
$Script:LogDir = (Resolve-Path -LiteralPath $LogDir).Path

function Write-Step([string]$message) {
    Write-Host ("[{0}] {1}" -f (Get-Date -Format "HH:mm:ss"), $message)
}

# 逐字节读到行尾。不能直接用 StreamReader：它会把后面的请求体一起读进缓冲区
function Read-RawLine($stream) {
    $bytes = New-Object System.Collections.Generic.List[byte]
    $one = New-Object byte[] 1
    while ($true) {
        $read = $stream.Read($one, 0, 1)
        if ($read -le 0) {
            if ($bytes.Count -eq 0) { return $null }
            break
        }
        if ($one[0] -eq 10) { break }
        if ($one[0] -ne 13) { [void]$bytes.Add($one[0]) }
    }
    return [System.Text.Encoding]::ASCII.GetString($bytes.ToArray())
}

function Get-TargetPath([string]$rawName) {
    $name = ""
    if (-not [string]::IsNullOrWhiteSpace($rawName)) {
        $name = [System.Uri]::UnescapeDataString($rawName.Trim())
        $name = [System.IO.Path]::GetFileName($name)
        $name = $name -replace "[^A-Za-z0-9._-]", "_"
    }
    # 清洗后没有字母数字（例如纯中文名被剥成一片下划线）就用上传时间当名字
    $stem = $name.ToLower() -replace "\.zip$", ""
    if ([string]::IsNullOrWhiteSpace($stem) -or -not ($stem -match "[a-z0-9]")) {
        $name = "upload-{0}.zip" -f (Get-Date -Format "yyyyMMdd-HHmmss")
    }
    if (-not $name.ToLower().EndsWith(".zip")) { $name = "$name.zip" }
    $target = Join-Path $Script:LogDir $name
    if (Test-Path -LiteralPath $target) {
        $base = [System.IO.Path]::GetFileNameWithoutExtension($name)
        $ext = [System.IO.Path]::GetExtension($name)
        $index = 1
        while (Test-Path -LiteralPath $target) {
            $target = Join-Path $Script:LogDir ("{0}-{1}{2}" -f $base, $index, $ext)
            $index++
        }
    }
    return $target
}

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
    throw "端口 $Port 无法监听（可能被占用），试试 -Port 18766"
}

$addresses = Get-LanAddresses
Write-Host ""
Write-Host "内网日志接收服务已启动，app 里「关于 → 上传日志」填下面任意一个地址："
foreach ($address in $addresses) {
    Write-Host ("  http://{0}:{1}/logs" -f $address, $Port) -ForegroundColor Green
}
Write-Host ""
Write-Host ("  收件目录: {0}" -f $Script:LogDir)
Write-Host ("  列表页面: http://<地址>:{0}/" -f $Port)
Write-Host "  按 Ctrl+C 停止"
Write-Host ""

try {
    while ($true) {
        $client = $listener.AcceptTcpClient()
        $stream = $client.GetStream()
        try {
            $stream.ReadTimeout = 15000
            $stream.WriteTimeout = 120000
            $requestLine = Read-RawLine $stream
            if ([string]::IsNullOrWhiteSpace($requestLine)) { continue }

            $headers = @{}
            while ($true) {
                $line = Read-RawLine $stream
                if ([string]::IsNullOrWhiteSpace($line)) { break }
                $index = $line.IndexOf(":")
                if ($index -gt 0) {
                    $key = $line.Substring(0, $index).Trim().ToLower()
                    $headers[$key] = $line.Substring($index + 1).Trim()
                }
            }

            $parts = $requestLine.Split(" ")
            $method = ""
            if ($parts.Length -ge 1) { $method = $parts[0].ToUpper() }
            $rawPath = "/"
            if ($parts.Length -ge 2) { $rawPath = $parts[1] }
            $path = ([System.Uri]::UnescapeDataString($rawPath)).Split("?")[0]

            $hostHeader = "127.0.0.1:$Port"
            if ($headers.ContainsKey("host")) { $hostHeader = $headers["host"] }
            $baseUrl = "http://$hostHeader"

            if ($method -eq "POST") {
                if ($headers.ContainsKey("transfer-encoding")) {
                    Send-Text $stream "411 Length Required" "text/plain; charset=utf-8" "需要 Content-Length，不支持 chunked"
                    continue
                }
                $length = [long]0
                if ($headers.ContainsKey("content-length")) {
                    $length = [long]$headers["content-length"]
                }
                if ($length -le 0) {
                    Send-Text $stream "400 Bad Request" "text/plain; charset=utf-8" "请求体为空"
                    continue
                }
                if ($length -gt $Script:MaxBytes) {
                    Send-Text $stream "413 Payload Too Large" "text/plain; charset=utf-8" ("超过上限 {0} 字节" -f $Script:MaxBytes)
                    continue
                }

                $target = Get-TargetPath $headers["x-file-name"]
                $written = [long]0
                $buffer = New-Object byte[] 8192
                $fileStream = [System.IO.File]::Create($target)
                try {
                    while ($written -lt $length) {
                        $need = [Math]::Min($buffer.Length, $length - $written)
                        $read = $stream.Read($buffer, 0, [int]$need)
                        if ($read -le 0) { break }
                        $fileStream.Write($buffer, 0, $read)
                        $written += $read
                    }
                } finally {
                    $fileStream.Dispose()
                }

                if ($written -lt $length) {
                    Remove-Item -LiteralPath $target -Force -ErrorAction SilentlyContinue
                    Send-Text $stream "500 Internal Server Error" "text/plain; charset=utf-8" ("请求体不完整 {0}/{1}" -f $written, $length)
                    Write-Step ("POST 失败，只收到 {0}/{1} 字节" -f $written, $length)
                    continue
                }

                $fileName = [System.IO.Path]::GetFileName($target)
                $json = '{{"url":"{0}/logs/{1}","file":"{1}","size":{2}}}' -f $baseUrl, $fileName, $written
                Send-Text $stream "200 OK" "application/json; charset=utf-8" $json
                Write-Step ("收到日志 {0}  {1} bytes" -f $fileName, $written)
                continue
            }

            if ($path -eq "/" -or $path -eq "/index.html") {
                $items = Get-ChildItem -LiteralPath $Script:LogDir -Filter "*.zip" -File |
                    Sort-Object LastWriteTime -Descending |
                    Select-Object -First 50
                $rows = ""
                foreach ($item in $items) {
                    $rows += ('<li><a href="/logs/{0}">{0}</a> · {1} bytes · {2}</li>' -f $item.Name, $item.Length, $item.LastWriteTime.ToString("yyyy-MM-dd HH:mm"))
                }
                $html = '<!doctype html><html lang="zh"><meta charset="utf-8"><title>legado 内网日志</title><body style="font-family:sans-serif;padding:24px"><h2>legado 内网日志</h2><p>收件目录：' + $Script:LogDir + '</p><ul>' + $rows + '</ul></body></html>'
                Send-Text $stream "200 OK" "text/html; charset=utf-8" $html
                continue
            }

            if ($path -like "/logs/*") {
                $fileName = [System.Uri]::UnescapeDataString($path.Substring(6))
                $fileName = [System.IO.Path]::GetFileName($fileName)
                $target = Join-Path $Script:LogDir $fileName
                if (Test-Path -LiteralPath $target) {
                    Send-File $stream $target "application/zip"
                    Write-Step "GET logs/$fileName"
                } else {
                    Send-Text $stream "404 Not Found" "text/plain; charset=utf-8" "not found"
                }
                continue
            }

            Send-Text $stream "404 Not Found" "text/plain; charset=utf-8" "not found"
        } catch {
            # 单个连接出错不影响服务
        } finally {
            try { $client.Close() } catch { }
        }
    }
} finally {
    $listener.Stop()
}
