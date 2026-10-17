# Boots the dev client for one edition, waits for the mod's "loaded" line, then kills the client,
# the Gradle wrapper and the daemon so nothing is left running. Usage:
#   powershell -File boot-test.ps1 [-Edition public|private] [-TimeoutSeconds 150]
param(
    [string]$Edition = "private",
    [int]$TimeoutSeconds = 150,
    # What to wait for in the log before shutting the client down. The default is the mod's own
    # startup line; pass something later (e.g. a self-test marker) to keep the client alive longer.
    [string]$Pattern = "Shiny Hunter.* loaded"
)
$ErrorActionPreference = "Continue"
$env:JAVA_HOME = "C:\Program Files\Java\jdk-25.0.2"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$log = Join-Path $env:TEMP "shinyhunter-boot-$Edition.log"
if (Test-Path $log) { Remove-Item $log -Force }

$args = @("runClient", "-q", "-Pedition=$Edition")
$proc = Start-Process -FilePath (Join-Path $root "gradlew.bat") -ArgumentList $args -WorkingDirectory $root `
    -RedirectStandardOutput $log -RedirectStandardError (Join-Path $env:TEMP "shinyhunter-boot-$Edition.err") -PassThru -WindowStyle Hidden

$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
$result = "TIMEOUT: no 'loaded' line within $TimeoutSeconds s"
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Seconds 3
    if (Test-Path $log) {
        $hit = Select-String -Path $log -Pattern "$Pattern|Exception in|Mixin apply failed|ERROR" -ErrorAction SilentlyContinue | Select-Object -First 3
        if ($hit) {
            $result = ($hit | ForEach-Object { $_.Line }) -join "`n"
            if ($result -match $Pattern) { break }
        }
    }
    if ($proc.HasExited) { $result = "gradle exited early (code $($proc.ExitCode))"; break }
}

# Kill the whole tree: the dev client, the wrapper, then the daemon.
Get-CimInstance Win32_Process | Where-Object {
    $_.Name -match '^java(w)?\.exe$' -and $_.CommandLine -and (
        $_.CommandLine -match 'devlaunchinjector' -or $_.CommandLine -match 'gradle-wrapper\.jar" runClient')
} | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
if (-not $proc.HasExited) { Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue }
& (Join-Path $root "gradlew.bat") --stop -q | Out-Null

$left = @(Get-CimInstance Win32_Process | Where-Object { $_.Name -eq 'java.exe' }).Count
Write-Output $result
Write-Output "java.exe processes left: $left"
