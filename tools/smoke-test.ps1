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
# 重要：用 cmd 的 "1> file 2>&1" 讓「子行程自己」寫入輸出檔。
#
# 若改用 [Process] 的 OutputDataReceived / ErrorDataReceived 事件回呼，
# 這些回呼會在多個執行緒集區執行緒上並行附加同一個檔案，導致 console.log
# 的行序與實際輸出順序不同（實際遇過診斷摘要的某一行被搬到區塊最後面，
# 造成誤判為程式碼順序錯誤）。直接由 cmd 重導可保證只有一個寫入者，
# 行序與實際一致。
#
# 要交叉檢查真正的日誌順序時，請看 logs/latest.log（log4j 檔案輸出，順序一定正確）。
$batFile = Join-Path $RunDir 'start-server.bat'
$batContent = @(
    '@echo off',
    'rem OrigCore smoke-test: let cmd redirect stdout and stderr to one file (single writer).',
    "java -Dterminal.jline=false -Dterminal.ansi=false -Xms512M -Xmx${MaxHeapMB}M -jar `"$Jar`" --nogui 1> `"$logFile`" 2>&1",
    'exit /b %ERRORLEVEL%'
) -join "`r`n"
Write-TextNoBom $batFile $batContent

$psi = New-Object System.Diagnostics.ProcessStartInfo
$psi.FileName = $batFile
$psi.WorkingDirectory = $RunDir
$psi.UseShellExecute = $false
# 只用 stdin 送 stop；stdout/stderr 已由 cmd 重導到檔案，不需要另外接管。
$psi.RedirectStandardInput = $true
$psi.RedirectStandardOutput = $false
$psi.RedirectStandardError = $false

Write-Host "啟動: java -Xms512M -Xmx${MaxHeapMB}M -jar `"$Jar`" --nogui"
Write-Host "工作目錄: $RunDir"

$proc = [System.Diagnostics.Process]::Start($psi)

Write-Host "等待 ${StartupWaitSeconds}s 讓伺服器啟動..."
Start-Sleep -Seconds $StartupWaitSeconds

$stoppedCleanly = $false
if (-not $NoStop) {
    if (-not $proc.HasExited) {
        Write-Host "送出 stop 指令..."
        $bytes = [System.Text.Encoding]::ASCII.GetBytes("stop`r`n")
        $proc.StandardInput.BaseStream.Write($bytes, 0, $bytes.Length)
        $proc.StandardInput.BaseStream.Flush()
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

# 等待檔案控點關閉、且 cmd 已把緩衝內容刷新到磁碟。
Start-Sleep -Seconds 2

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
