<#
.SYNOPSIS
  OrigCore 伺服器煙霧測試 (smoke test)。

.DESCRIPTION
  啟動指定的伺服器 JAR，等待啟動完成、擷取完整主控台輸出，
  然後送出 stop 指令並等待正常關閉。

  這是「驗證 JAR 真的能跑」用的工具，不是完整功能測試。

.PARAMETER Jar
  要測試的伺服器 JAR 路徑。

.PARAMETER RunDir
  伺服器執行目錄（會自動建立，含 eula.txt / server.properties）。

.PARAMETER StartupWaitSeconds
  送出 stop 前的等待秒數（讓伺服器完成世界產生與啟動）。

.PARAMETER MaxHeapMB
  JVM 最大堆積大小 (MB)。

.PARAMETER ExitWaitSeconds
  送出 stop 後等待行程結束的秒數上限。

.PARAMETER NoStop
  只啟動不送 stop（由呼叫端自行結束）。預設會送 stop。
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Jar,
    [Parameter(Mandatory = $true)][string]$RunDir,
    [int]$StartupWaitSeconds = 120,
    [int]$MaxHeapMB = 2048,
    [int]$ExitWaitSeconds = 240,
    [switch]$NoStop
)

$ErrorActionPreference = 'Stop'

$Jar = (Resolve-Path $Jar).Path
if (-not (Test-Path -LiteralPath $RunDir)) {
    New-Item -ItemType Directory -Force -Path $RunDir | Out-Null
}
$RunDir = (Resolve-Path $RunDir).Path

# --- 準備最小可啟動環境 ------------------------------------------------
# 注意：Minecraft 讀 eula.txt / server.properties 時不會處理 BOM，
#       若寫入帶 BOM 的 UTF-8，第一個鍵會變成 "\uFEFFeula" 而失效。
#       因此一律使用「無 BOM」的 UTF-8 寫入。
$utf8NoBom = New-Object System.Text.UTF8Encoding $false

function Write-TextNoBom([string]$Path, [string]$Text) {
    [System.IO.File]::WriteAllText($Path, $Text, $utf8NoBom)
}

Write-TextNoBom (Join-Path $RunDir 'eula.txt') "eula=true`r`n"

$props = Join-Path $RunDir 'server.properties'
if (-not (Test-Path -LiteralPath $props)) {
    Write-TextNoBom $props (@(
        'level-name=world',
        'online-mode=false',
        'max-players=5',
        'view-distance=4',
        'simulation-distance=4',
        'spawn-protection=0',
        'sync-chunk-writes=false',
        'enable-jmx-monitoring=false',
        'level-type=minecraft\:flat'
    ) -join "`r`n")
}

$logFile = Join-Path $RunDir 'console.log'
if (Test-Path -LiteralPath $logFile) { Remove-Item -LiteralPath $logFile -Force }

# --- 啟動 --------------------------------------------------------------
$psi = New-Object System.Diagnostics.ProcessStartInfo
$psi.FileName = 'java'
$psi.Arguments = "-Xms512M -Xmx${MaxHeapMB}M -jar `"$Jar`" --nogui"
$psi.WorkingDirectory = $RunDir
$psi.UseShellExecute = $false
$psi.RedirectStandardInput = $true
$psi.RedirectStandardOutput = $true
$psi.RedirectStandardError = $true
$psi.StandardOutputEncoding = [System.Text.Encoding]::UTF8
$psi.StandardErrorEncoding = [System.Text.Encoding]::UTF8

Write-Host "啟動: java $($psi.Arguments)"
Write-Host "工作目錄: $RunDir"

$proc = [System.Diagnostics.Process]::Start($psi)

$sync = [hashtable]::Synchronized(@{})
$outAction = {
    if ($null -ne $EventArgs.Data) {
        try { [System.IO.File]::AppendAllText($Event.MessageData.LogFile, $EventArgs.Data + "`r`n") } catch { }
    }
}
$errAction = {
    if ($null -ne $EventArgs.Data) {
        try { [System.IO.File]::AppendAllText($Event.MessageData.LogFile, "[STDERR] " + $EventArgs.Data + "`r`n") } catch { }
    }
}
Register-ObjectEvent -InputObject $proc -EventName OutputDataReceived -Action $outAction -MessageData @{ LogFile = $logFile } | Out-Null
Register-ObjectEvent -InputObject $proc -EventName ErrorDataReceived  -Action $errAction -MessageData @{ LogFile = $logFile } | Out-Null
$proc.BeginOutputReadLine()
$proc.BeginErrorReadLine()

Write-Host "等待 ${StartupWaitSeconds}s 讓伺服器啟動..."
Start-Sleep -Seconds $StartupWaitSeconds

$stoppedCleanly = $false
if (-not $NoStop) {
    if (-not $proc.HasExited) {
        Write-Host "送出 stop 指令..."
        $proc.StandardInput.WriteLine('stop')
        $proc.StandardInput.Flush()
    }
    if ($proc.WaitForExit($ExitWaitSeconds * 1000)) {
        $stoppedCleanly = $true
        Write-Host "行程已結束，exit code = $($proc.ExitCode)"
    } else {
        Write-Warning "行程在 ${ExitWaitSeconds}s 內未結束，強制終止。"
        $proc.Kill()
        $proc.WaitForExit(30000) | Out-Null
    }
} else {
    Write-Host "（NoStop 模式，不送 stop）"
}

Start-Sleep -Seconds 2
Get-EventSubscriber | Unregister-Event -ErrorAction SilentlyContinue

Write-Host ""
Write-Host "===== 主控台輸出尾巴 ====="
if (Test-Path -LiteralPath $logFile) {
    Get-Content -LiteralPath $logFile -Tail 40
} else {
    Write-Warning "找不到輸出檔: $logFile"
}

[pscustomobject]@{
    JarPath        = $Jar
    RunDir         = $RunDir
    ConsoleLog     = $logFile
    StoppedCleanly = $stoppedCleanly
    ExitCode       = if ($proc.HasExited) { $proc.ExitCode } else { $null }
} | Format-List
