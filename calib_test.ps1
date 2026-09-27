# UltraBAR CB01 Target-B thermal calibration:
# free-running + locked 1320/1200/1008MHz, 4 stages x 10 min, 4-core 100% CPU only
$ErrorActionPreference = "Continue"
$base = "http://192.168.31.6:8091"
$stamp = Get-Date -Format "yyyyMMdd_HHmm"
$outDir = "d:\TraeCN Project\Thermal-analysis\calib_$stamp"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$log = Join-Path $outDir "run.log"
$dataFile = Join-Path $outDir "samples.jsonl"
$summaryFile = Join-Path $outDir "summary.jsonl"

function Log($m) {
    $line = "[{0}] {1}" -f (Get-Date -Format "HH:mm:ss"), $m
    Add-Content -Path $log -Value $line -Encoding ASCII
    Write-Host $line
}
function ApiPost($path, $body) {
    try {
        if ($body) { return Invoke-RestMethod "$base$path" -Method Post -Body $body -ContentType "application/x-www-form-urlencoded" -TimeoutSec 15 }
        else { return Invoke-RestMethod "$base$path" -Method Post -TimeoutSec 15 }
    } catch { Log ("API FAIL " + $path + " : " + $_); return $null }
}
function GetState() {
    try { return Invoke-RestMethod "$base/api/state" -TimeoutSec 8 }
    catch { return $null }
}
function Cleanup() {
    Log "CLEANUP: stop + unlock"
    ApiPost "/api/stop" $null | Out-Null
    ApiPost "/api/root/unlock" $null | Out-Null
}

$PHASE_SECONDS = 600
$SAMPLE_EVERY  = 2
$COOL_TARGET   = 60.0
$COOL_TIMEOUT  = 600

$stages = @(
    @{ name = "A_free"; freq = -1;      label = "free-running" },
    @{ name = "B_1320"; freq = 1320000; label = "locked-1320MHz" },
    @{ name = "C_1200"; freq = 1200000; label = "locked-1200MHz" },
    @{ name = "D_1008"; freq = 1008000; label = "locked-1008MHz" }
)

$summaries = @()
try {
    # 超温保护设 110℃：不干扰 70/90℃ 内核降频观测，又远离 135℃ critical
    ApiPost "/api/protect" "enabled=1&threshold=110" | Out-Null
    ApiPost "/api/trip/clear" $null | Out-Null
    Log "protection threshold set to 110C"
    foreach ($stg in $stages) {
        Log ("=== STAGE " + $stg.name + " (" + $stg.label + ") cooling to CPU<" + $COOL_TARGET + "C ===")
        $coolWaited = 0
        while ($coolWaited -lt $COOL_TIMEOUT) {
            $s = GetState
            if ($s) {
                $cpuT = [double]$s.zones[0].temp
                if ($cpuT -le $COOL_TARGET) { Log ("cooled: CPU=" + $cpuT + "C waited " + $coolWaited + "s"); break }
                if ($coolWaited % 30 -eq 0) { Log ("cooling... CPU=" + $cpuT + "C waited " + $coolWaited + "s") }
            }
            Start-Sleep 5; $coolWaited += 5
        }
        if ($coolWaited -ge $COOL_TIMEOUT) { Log "cool timeout, continue at current temp" }

        if ($stg.freq -gt 0) {
            $r = ApiPost "/api/root/lock" ("freq=" + $stg.freq)
            Log ("lock " + $stg.freq + "kHz ok=" + $r.ok)
            if (-not $r.ok) { Log "lock failed, skip stage"; continue }
        } else {
            ApiPost "/api/root/unlock" $null | Out-Null
            Log "unlocked (free-running)"
        }
        Start-Sleep 2

        ApiPost "/api/config" "cpu=1&threads=4&load=100&gpu=0&gpuload=60&mem=0&memmb=256&io=0&iothreads=2" | Out-Null
        $t0 = Get-Date
        Log ("burn started, duration " + $PHASE_SECONDS + "s")
        $firstThrottle = -1
        $samples = New-Object System.Collections.ArrayList
        $elapsed = 0
        while ($elapsed -le $PHASE_SECONDS) {
            $s = GetState
            if ($s) {
                $coolMax = 0
                foreach ($c in $s.cooling) { if ([int]$c.state -gt $coolMax) { $coolMax = [int]$c.state } }
                $rec = [pscustomobject]@{
                    stage = $stg.name
                    t = [int]$elapsed
                    wall = (Get-Date -Format "HH:mm:ss")
                    cpu = [double]$s.zones[0].temp
                    gpu = [double]$s.zones[1].temp
                    ddr = [double]$s.zones[2].temp
                    bat = [double]$s.zones[3].temp
                    mhz = [int]$s.cpuFreqMhz
                    cool = [int]$coolMax
                    lock = [int64]$s.root.lockedKhz
                    on = [bool]$s.cfg.anyOn
                    trip = [string]$s.protect.trip
                    cpuUtil = [int]$s.cpuUtil
                    gpuUtil = [int]$s.gpuUtil
                    gpuMemMb = [int64]$s.gpuMemMb
                    memAvail = [int64]$s.memAvailMb
                }
                [void]$samples.Add($rec)
                ($rec | ConvertTo-Json -Compress) | Add-Content -Path $dataFile -Encoding ASCII
                if ($firstThrottle -lt 0 -and $coolMax -gt 0) {
                    $firstThrottle = $elapsed
                    Log ("  >> FIRST THROTTLE t=" + $elapsed + "s CPU=" + $rec.cpu + "C state=" + $coolMax)
                }
                if ($s.protect.trip) {
                    Log ("  !! PROTECTION TRIP t=" + $elapsed + "s CPU=" + $rec.cpu + "C - abort stage")
                    break
                }
            }
            if ($elapsed % 60 -eq 0 -and $elapsed -gt 0) {
                $last = $samples[$samples.Count - 1]
                Log ("  t=" + $elapsed + "s CPU=" + $last.cpu + " GPU=" + $last.gpu + " DDR=" + $last.ddr + " mhz=" + $last.mhz + " state=" + $last.cool)
            }
            Start-Sleep $SAMPLE_EVERY; $elapsed += $SAMPLE_EVERY
        }
        ApiPost "/api/stop" $null | Out-Null
        $t1 = Get-Date
        $dur = [int]($t1 - $t0).TotalSeconds
        Log ("burn stopped, actual " + $dur + "s, samples " + $samples.Count)

        if ($samples.Count -gt 0) {
            $tail = @($samples.GetRange($samples.Count - [math]::Min(60, $samples.Count), [math]::Min(60, $samples.Count)))
            $avg = ($tail | Measure-Object -Property cpu -Average).Average
            $avgGpu = ($tail | Measure-Object -Property gpu -Average).Average
            $avgDdr = ($tail | Measure-Object -Property ddr -Average).Average
            $avgMhz = ($tail | Measure-Object -Property mhz -Average).Average
            $maxCpu = ($samples | Measure-Object -Property cpu -Maximum).Maximum
            $maxGpu = ($samples | Measure-Object -Property gpu -Maximum).Maximum
            $maxDdr = ($samples | Measure-Object -Property ddr -Maximum).Maximum
            $minMhz = ($samples | Measure-Object -Property mhz -Minimum).Minimum
            $spanS = $tail[-1].t - $tail[0].t
            if ($spanS -le 0) { $spanS = 1 }
            $slope = [math]::Round(($tail[-1].cpu - $tail[0].cpu) / $spanS * 60, 3)
            $throtCount = @($samples | Where-Object { $_.cool -gt 0 }).Count
            $throtPct = [math]::Round($throtCount / $samples.Count * 100, 1)
            $sum = [pscustomobject]@{
                stage = $stg.name; label = $stg.label; freqKhz = $stg.freq
                durationS = $dur
                startCpu = $samples[0].cpu
                firstThrottleS = $firstThrottle
                throttledPct = $throtPct
                maxCpu = $maxCpu; maxGpu = $maxGpu; maxDdr = $maxDdr
                tailAvgCpu = [math]::Round($avg,1); tailAvgGpu = [math]::Round($avgGpu,1); tailAvgDdr = [math]::Round($avgDdr,1)
                tailSlopeCpuPerMin = $slope
                avgMhz = [math]::Round($avgMhz,0); minMhz = $minMhz
            }
            $summaries += $sum
            ($sum | ConvertTo-Json -Compress) | Add-Content -Path $summaryFile -Encoding ASCII
            Log ("SUMMARY firstThrottle=" + $firstThrottle + "s throtPct=" + $throtPct + "% maxCpu=" + $maxCpu + " tailAvg=" + [math]::Round($avg,1) + "C slope=" + $slope + "C/min avgMhz=" + [math]::Round($avgMhz,0) + " minMhz=" + $minMhz)
        }
        Start-Sleep 10
    }
} finally {
    Cleanup
    $summaries | ConvertTo-Json | Set-Content (Join-Path $outDir "summary_pretty.json") -Encoding ASCII
    Log ("ALL DONE. outdir=" + $outDir)
}
