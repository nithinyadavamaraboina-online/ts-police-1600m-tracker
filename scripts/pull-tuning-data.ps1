param(
    [Parameter(Mandatory = $false)]
    [string]$SyncCode = "",

    [Parameter(Mandatory = $false)]
    [string]$Since = "48h",

    [Parameter(Mandatory = $false)]
    [string]$InputFile = ""
)

$ErrorActionPreference = "Stop"
$RepoRoot = Split-Path -Parent $PSScriptRoot
$Channel = "tspolice-tune-9f4c2e7b18a6d305e419"
$DistDir = Join-Path $RepoRoot "dist"
$HistoryDir = Join-Path $DistDir "tuning-history"
New-Item -ItemType Directory -Force -Path $HistoryDir | Out-Null

function Get-HttpUtf8Text {
    param([string]$Url)
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    $resp = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 25
    if ($resp.Content -is [byte[]]) {
        return [System.Text.Encoding]::UTF8.GetString($resp.Content)
    }
    return [string]$resp.Content
}

$RawJson = $null
$ResolvedSource = ""
$ResolvedCode = "LOCAL"

if (-not [string]::IsNullOrWhiteSpace($InputFile)) {
    if (-not (Test-Path $InputFile)) {
        throw "InputFile not found: $InputFile"
    }
    $RawJson = Get-Content -Path $InputFile -Raw -Encoding UTF8
    $ResolvedSource = "Local File ($InputFile)"
} elseif (-not [string]::IsNullOrWhiteSpace($SyncCode)) {
    $CleanCode = $SyncCode.Trim()
    $FileUrl = "https://ntfy.sh/file/$CleanCode.json"
    Write-Host "Downloading tuning bundle by Sync Code '$CleanCode'..." -ForegroundColor Cyan
    $RawJson = Get-HttpUtf8Text -Url $FileUrl
    $ResolvedCode = $CleanCode
    $ResolvedSource = $FileUrl
} else {
    $PollUrl = "https://ntfy.sh/$Channel/json?poll=1&since=$Since"
    Write-Host "Checking cloud tuning relay for recent phone uploads (since $Since)..." -ForegroundColor Cyan
    $NdjsonText = ""
    try {
        $NdjsonText = Get-HttpUtf8Text -Url $PollUrl
    } catch {
        Write-Warning "Cloud poll error: $($_.Exception.Message)"
    }

    if (-not [string]::IsNullOrWhiteSpace($NdjsonText)) {
        $Messages = @()
        foreach ($line in ($NdjsonText -split "`r?`n")) {
            $trimmed = $line.Trim()
            if ([string]::IsNullOrWhiteSpace($trimmed)) { continue }
            try {
                $obj = $trimmed | ConvertFrom-Json
                if ($obj.event -eq "message") {
                    $Messages += $obj
                }
            } catch {
                # Ignore malformed line
            }
        }

        if ($Messages.Count -gt 0) {
            $Latest = $Messages | Sort-Object time | Select-Object -Last 1
            $ResolvedCode = [string]$Latest.id
            $AttUrl = ""
            if ($null -ne $Latest.attachment -and -not [string]::IsNullOrWhiteSpace($Latest.attachment.url)) {
                $AttUrl = [string]$Latest.attachment.url
            } elseif (-not [string]::IsNullOrWhiteSpace($ResolvedCode)) {
                $AttUrl = "https://ntfy.sh/file/$ResolvedCode.json"
            }

            if (-not [string]::IsNullOrWhiteSpace($AttUrl)) {
                try {
                    $RawJson = Get-HttpUtf8Text -Url $AttUrl
                    $ResolvedSource = "Cloud Relay (Code: $ResolvedCode)"
                } catch {
                    if (-not [string]::IsNullOrWhiteSpace($Latest.message) -and $Latest.message.Trim().StartsWith("{")) {
                        $RawJson = [string]$Latest.message
                        $ResolvedSource = "Cloud Inline Message (Code: $ResolvedCode)"
                    }
                }
            }
        }
    }

    # Optional USB ADB fallback if no cloud bundle is present yet
    if ([string]::IsNullOrWhiteSpace($RawJson)) {
        $AdbCmd = Get-Command adb -ErrorAction SilentlyContinue
        if ($null -ne $AdbCmd) {
            $AdbList = & adb devices 2>$null
            $OnlineDev = $AdbList | Where-Object { $_ -match "^\S+\s+device$" } | Select-Object -First 1
            if ($null -ne $OnlineDev) {
                $TmpPull = Join-Path $DistDir "adb-pulled-tuning.json"
                & adb pull "/sdcard/Android/data/com.tspolice.runtracker/files/ts_police_tuning_latest.json" $TmpPull 2>$null | Out-Null
                if (Test-Path $TmpPull) {
                    $RawJson = Get-Content -Path $TmpPull -Raw -Encoding UTF8
                    $ResolvedSource = "USB ADB Pull"
                    $ResolvedCode = "ADB"
                }
            }
        }
    }
}

if ([string]::IsNullOrWhiteSpace($RawJson)) {
    Write-Host ""
    Write-Host "No fine-tuning telemetry bundle found yet (window: $Since)." -ForegroundColor Yellow
    Write-Host "To send data from your phone (v1.0.11+):"
    Write-Host "  1. Open TS Police 1.6K -> Today tab (or Coach & Lab tab)."
    Write-Host "  2. Tap 'Pull & Send Fine-Tuning Data (7-Day + Hourly Logs)'."
    Write-Host "  3. Re-run: .\scripts\pull-tuning-data.ps1"
    exit 0
}

$Utf8NoBom = New-Object System.Text.UTF8Encoding($false)
$LatestPath = Join-Path $DistDir "tuning-data-latest.json"
[System.IO.File]::WriteAllText($LatestPath, $RawJson, $Utf8NoBom)

$Stamp = (Get-Date).ToString("yyyyMMdd-HHmmss")
$ArchivePath = Join-Path $HistoryDir "tuning-$Stamp-$ResolvedCode.json"
[System.IO.File]::WriteAllText($ArchivePath, $RawJson, $Utf8NoBom)

$Data = $RawJson | ConvertFrom-Json
$Dev = $Data.deviceProfile
$Cfg = $Data.classifierConfig
$Diag = $Data.autoTuningDiagnostics
$TwoDay = $Data.twoDayComparison

Write-Host ""
Write-Host "======================================================================" -ForegroundColor Green
Write-Host " TS POLICE 1.6K - PULLED FINE-TUNING TELEMETRY REPORT" -ForegroundColor Green
Write-Host "======================================================================" -ForegroundColor Green
Write-Host "Source        : $ResolvedSource"
Write-Host "Saved Latest  : $LatestPath"
Write-Host "Generated At  : $($Data.generatedAtIso) ($($Data.localTimezone))"
Write-Host "Device        : $($Dev.manufacturer) $($Dev.model) (Android $($Dev.androidRelease), SDK $($Dev.sdkInt))"
Write-Host "App Build     : v$($Dev.appVersionName) (versionCode $($Dev.appVersionCode))"
Write-Host "Battery Guard : Unrestricted=$($Dev.batteryUnrestricted) | HealthStatus=$($Dev.healthSyncStatus)"

if ($null -ne $Dev.stepDetectorSensor) {
    $Sd = $Dev.stepDetectorSensor
    Write-Host "Step Detector : $($Sd.name) (FIFO max=$($Sd.fifoMaxEventCount), wakeUp=$($Sd.isWakeUpSensor))"
} else {
    Write-Host "Step Detector : NONE (Software Accel Fallback Only)" -ForegroundColor Yellow
}
if ($null -ne $Dev.accelerometerSensor) {
    $Ac = $Dev.accelerometerSensor
    Write-Host "Accelerometer : $($Ac.name) (FIFO max=$($Ac.fifoMaxEventCount), minDelayUs=$($Ac.minDelayUs))"
}

Write-Host ""
Write-Host "--- CLASSIFIER & STRIDE CONFIG ---" -ForegroundColor Cyan
Write-Host ("Height: {0} cm | Effective Stride Scale: {1}x (Raw Samsung: {2}x, Calibrated={3})" -f `
    $Cfg.runnerHeightCm, $Cfg.effectiveStrideScale, $Cfg.rawSamsungStrideScale, $Cfg.hasSamsungStrideCalibration)
Write-Host ("Run Threshold: {0} m/s | Target: {1}s | Audio: {2} | AllDay={3}" -f `
    $Cfg.runningThresholdMps, $Cfg.targetTimeSec, $Cfg.pocketAudioMode, $Cfg.allDayTrackingEnabled)

function Show-DayPair {
    param([string]$Label, $Node)
    if ($null -eq $Node) { return }
    $t = $Node.tracker
    $s = $Node.samsung
    $c = $Node.comparison
    if ($null -ne $s) {
        $matchStr = if ($null -ne $c -and $null -ne $c.overallMatchPercent) { "$($c.overallMatchPercent)%" } else { "n/a" }
        $recScale = if ($null -ne $c) { "$($c.recommendedStrideScale)x" } else { "--" }
        Write-Host ("[{0}] Steps: Trk {1} vs Sam {2} | Dist: Trk {3}m vs Sam {4}m | Walk: {5}s/{6}s | JogRun: {7}s/{8}s | Match: {9} | RecScale: {10}" -f `
            $Label, $t.steps, $s.steps, $t.distanceMeters, $s.distanceMeters, `
            $t.walkingSec, $s.walkingSec, $t.jogRunCombinedSec, $s.jogRunSec, $matchStr, $recScale)
    } else {
        Write-Host ("[{0}] Steps: Trk {1} | Dist: Trk {2}m (No Samsung snapshot)" -f $Label, $t.steps, $t.distanceMeters)
    }
}

Write-Host ""
Write-Host "--- YESTERDAY vs TODAY vs 2-DAY SAMSUNG COMPARISON ---" -ForegroundColor Cyan
Show-DayPair -Label "YESTERDAY ($($Data.yesterdayIso))" -Node $TwoDay.yesterday
Show-DayPair -Label "TODAY     ($($Data.todayIso))"     -Node $TwoDay.today
Show-DayPair -Label "2-DAY COMBINED       "             -Node $TwoDay.twoDayCombined

Write-Host ""
Write-Host "--- AUTO-TUNING DIAGNOSTICS ---" -ForegroundColor Cyan
Write-Host ("Logged Hourly Steps: HW={0} | Accel-Fallback={1} | GPS={2}m | Stride={3}m | VehicleRejected={4}s" -f `
    $Diag.hourlyLoggedHwSteps, $Diag.hourlyLoggedFallbackSteps, $Diag.hourlyLoggedGpsMeters, `
    $Diag.hourlyLoggedStrideMeters, $Diag.hourlyVehicleIgnoredSec)
if ($null -ne $Diag.observedSamsungWalkStrideM) {
    Write-Host "Observed Samsung Pure-Walk Stride : $($Diag.observedSamsungWalkStrideM) m/step"
}
if ($null -ne $Diag.observedSamsungRunHourStrideM) {
    Write-Host "Observed Samsung Run-Hour Stride  : $($Diag.observedSamsungRunHourStrideM) m/step"
}
if ($null -ne $Diag.recommendations) {
    foreach ($rec in $Diag.recommendations) {
        Write-Host "  * $rec"
    }
}

if ($null -ne $Data.intradayHourlyComparison -and $Data.intradayHourlyComparison.Count -gt 0) {
    Write-Host ""
    Write-Host "--- 48H INTRADAY HOURLY BUCKETS (ACTIVE HOURS) ---" -ForegroundColor Cyan
    foreach ($h in ($Data.intradayHourlyComparison | Select-Object -Last 16)) {
        $tSteps = if ($null -ne $h.tracker) { "$($h.tracker.totalSteps) (hw:$($h.tracker.hwSteps)/sw:$($h.tracker.fallbackSteps))" } else { "0" }
        $sSteps = if ($null -ne $h.samsung) { "$($h.samsung.steps)" } else { "0" }
        $tDist = if ($null -ne $h.tracker) { "$($h.tracker.distanceMeters)m" } else { "0m" }
        $sDist = if ($null -ne $h.samsung) { "$($h.samsung.distanceMeters)m" } else { "0m" }
        Write-Host ("  {0} | Steps Trk {1} vs Sam {2} | Dist Trk {3} vs Sam {4}" -f $h.bucketKey, $tSteps, $sSteps, $tDist, $sDist)
    }
}

if ($null -ne $Data.recentStateTransitions -and $Data.recentStateTransitions.Count -gt 0) {
    Write-Host ""
    Write-Host ("--- LAST GAIT TRANSITIONS (showing latest 10 of {0}) ---" -f $Data.recentStateTransitions.Count) -ForegroundColor Cyan
    foreach ($tr in ($Data.recentStateTransitions | Select-Object -First 10)) {
        $spd = if ($null -ne $tr.gpsSpeedMps) { "$($tr.gpsSpeedMps)m/s" } else { "no-gps" }
        Write-Host ("  {0} | {1} -> {2} | cad={3}spm | rms={4} | gps={5}" -f `
            $tr.localTime, $tr.from, $tr.to, $tr.cadenceSpm, $tr.accelRms, $spd)
    }
}
Write-Host "======================================================================" -ForegroundColor Green
