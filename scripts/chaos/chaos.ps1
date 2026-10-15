# Chaos tests of the local stack (Phase 11, plan 11.2; report docs/reports/hardening-chaos-report.md).
#   $env:DEMO_USER_PASSWORD = "<the stack's demo password>"
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts/chaos/chaos.ps1 -ApiPort 8181 [-Only S1,S4] [-Seconds 40]
#
# LOCAL STACKS ONLY: it kills the API process, stops the redis and postgres containers of the compose project named in
# .env and locks the vo_outbox role. Each scenario runs a concurrent writer (writer.mjs: idempotent commands that each
# record one event, retried with the same Idempotency-Key) and a resilient WebSocket probe (ws-probe.mjs), injects one
# fault at t = 10 s, undoes it after the hold time, and then checks the invariants with SQL:
#   I1 every operation was acknowledged eventually (retries with the same key)
#   I2 exactly one command row per acknowledged key; the command ids match the acks
#   I3 exactly one event per key (none lost, none duplicated); the event ids match the acks
#   I4 per-aggregate event sequence has no gap and no duplicate
#   I5 the outbox drains by itself: every event published, none DEAD
#   I6 retrying 10 keys after recovery returns the original command ids and creates no new event
#   I7 the WebSocket probe never received an event twice on one connection nor an event that is not in the store
# plus what was observed while the fault was active (health status, whether commands still succeeded, recovery time).
# The harness restarts a killed API itself, standing in for a process supervisor. Results: chaos-results.json.
# Keep this file ASCII-only (Windows PowerShell 5.1).
param(
    [int]$ApiPort = 8181,
    [string[]]$Only = @(),
    [int]$Seconds = 40,
    [string]$Out = ''
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib.ps1')
Import-DotEnv
if ([string]::IsNullOrEmpty($env:DEMO_USER_PASSWORD)) { throw 'DEMO_USER_PASSWORD is not set' }
$Only = @($Only | ForEach-Object { $_ -split ',' } | Where-Object { $_ })
$proxyPort = $ApiPort + 100
$api = "http://localhost:$ApiPort"
$work = Join-Path $script:Root '.mcd/chaos'
New-Item -ItemType Directory -Path $work -Force | Out-Null
if ($Out -eq '') { $Out = Join-Path $work 'chaos-results.json' }
$project = Get-Project
$pg = "$project-postgres-1"
$redis = "$project-redis-1"
$results = New-Object System.Collections.Generic.List[object]

function Get-Http([string]$url, [string]$token = '') {
    try {
        $h = @{}
        if ($token) { $h.Authorization = "Bearer $token" }
        $r = Invoke-WebRequest -UseBasicParsing -TimeoutSec 3 -Uri $url -Headers $h
        return [int]$r.StatusCode
    } catch {
        if ($_.Exception.Response) { return [int]$_.Exception.Response.StatusCode }
        return 0
    }
}

function Get-OutboxAgeMax {
    try {
        $m = Invoke-WebRequest -UseBasicParsing -TimeoutSec 3 -Uri "$api/actuator/prometheus"
        $line = ($m.Content -split "`n") | Where-Object { $_ -like 'vo_outbox_oldest_age_seconds*' } | Select-Object -First 1
        if ($line) { return [double](($line -split ' ')[-1]) }
    } catch { }
    return -1
}

function Start-Node([string]$script, [string[]]$nodeArgs) {
    $log = Join-Path $work ([IO.Path]::GetFileNameWithoutExtension($script) + '.out.log')
    $quoted = @((Join-Path $PSScriptRoot $script)) + $nodeArgs | ForEach-Object { if ($_ -match '\s') { '"' + $_ + '"' } else { $_ } }
    return Start-Process -FilePath 'node' -ArgumentList $quoted -WindowStyle Hidden -PassThru -RedirectStandardOutput $log -RedirectStandardError ($log + '.err')
}

function Start-Proxy { return Start-Node 'tcp-proxy.mjs' @('--listen', "$proxyPort", '--target', "$ApiPort") }

function Wait-Postgres {
    for ($i = 0; $i -lt 90; $i++) {
        & docker exec $pg pg_isready -h 127.0.0.1 -p 5432 *> $null
        if ($LASTEXITCODE -eq 0) { return }
        Start-Sleep -Seconds 1
    }
    throw 'postgres did not come back'
}

function Get-OpenTask {
    $t = Get-Token $ApiPort
    $site = '018f0000-0000-7000-8000-00000000a021'
    $r = Invoke-RestMethod -Uri "$api/api/tasks?site_id=$site&limit=100" -Headers @{ Authorization = "Bearer $t" }
    $open = $r.items | Where-Object { $_.status -notin @('COMPLETED', 'FAILED', 'CANCELLED') } | Select-Object -First 1
    if (-not $open) { throw 'no open task to attach issues to' }
    return $open.id
}

function Run-Scenario([string]$id, [string]$title, [string]$fault, [int]$holdS, [scriptblock]$inject, [scriptblock]$recover) {
    if ($Only.Count -gt 0 -and $Only -notcontains $id) { return }
    Say "=== $id $title"
    $run = ('{0}{1}' -f $id.ToLower(), (Get-Date -Format 'HHmmss'))
    $marksFile = Join-Path $work "$run.marks.jsonl"
    $probeFile = Join-Path $work "$run.probe.jsonl"
    $task = Get-OpenTask
    $proxy = Start-Proxy
    Start-Sleep -Seconds 1
    $probe = Start-Node 'ws-probe.mjs' @('--api', "http://localhost:$proxyPort", '--out', $probeFile, '--seconds', '600')
    Start-Sleep -Seconds 3
    $healthFile = Join-Path $work "$run.health.jsonl"
    $healthProbe = Start-Node 'health-probe.mjs' @('--api', $api, '--out', $healthFile, '--seconds', '600')
    $writer = Start-Node 'writer.mjs' @('--api', $api, '--task', $task, '--run', $run, '--seconds', "$Seconds", '--out', $marksFile)
    $t0 = Get-Date
    $injected = $null
    $recovered = $null
        $injectStart = $null
    $recoverStart = $null
    $loginDuring = $null
    while (-not $writer.HasExited) {
        $el = ((Get-Date) - $t0).TotalSeconds
        if (-not $injected -and $el -ge 10) {
            $injectStart = Get-Date
            & $inject
            $injected = Get-Date
            Say "fault injected: $fault"
        }
        if ($injected -and -not $recovered -and ((Get-Date) - $injected).TotalSeconds -ge $holdS) {
            $recoverStart = Get-Date
            & $recover
            $recovered = Get-Date
            Say "fault undone after $holdS s"
        }
        if ($injected -and -not $recovered -and $null -eq $loginDuring -and ((Get-Date) - $injected).TotalSeconds -ge 3) {
            try {
                $b = @{ tenant = 'marutiflex-demo'; email = 'exec@demo.invalid'; password = $env:DEMO_USER_PASSWORD } | ConvertTo-Json
                $null = Invoke-RestMethod -Method Post -Uri "$api/api/auth/login" -ContentType 'application/json' -Body $b -TimeoutSec 5
                $loginDuring = 'login succeeded'
            } catch { $loginDuring = "login failed: $($_.Exception.Message)" }
        }
        Start-Sleep -Milliseconds 300
    }
    if ($injected -and -not $recovered) { $recoverStart = Get-Date; & $recover; $recovered = Get-Date }
    # Let the API be ready, then wait for the outbox to drain by itself.
    $readyAfterS = Wait-Api $ApiPort 180
    $drainStart = Get-Date
    $drained = $false
    for ($i = 0; $i -lt 90 -and -not $drained; $i++) {
        $pending = [int](Invoke-Psql "select count(*) from evt.outbox where status <> 'PUBLISHED'")
        if ($pending -eq 0) { $drained = $true } else { Start-Sleep -Seconds 1 }
    }
    $drainS = [math]::Round(((Get-Date) - $drainStart).TotalSeconds, 1)
    Start-Sleep -Seconds 2
    Stop-Process -Id $probe.Id -Force -ErrorAction SilentlyContinue
    Stop-Process -Id $healthProbe.Id -Force -ErrorAction SilentlyContinue
    Stop-Process -Id $proxy.Id -Force -ErrorAction SilentlyContinue

    # ---- health as observed by the independent probe (fault window = from injection start to 20 s after recovery) ----
    $hs = @(Get-Content $healthFile | ForEach-Object { $_ | ConvertFrom-Json })
    $injMs = 0; $recMs0 = 0
    if ($injectStart) { $injMs = ([DateTimeOffset]$injectStart).ToUnixTimeMilliseconds() }
    if ($recoverStart) { $recMs0 = ([DateTimeOffset]$recoverStart).ToUnixTimeMilliseconds() }
    $inFault = @($hs | Where-Object { $injMs -gt 0 -and $_.t -ge $injMs -and $_.t -le ($recMs0 + 20000) })
    $maxOutboxAge = 0.0
    foreach ($h in $inFault) { if ($null -ne $h.outbox_age_s -and $h.outbox_age_s -gt $maxOutboxAge) { $maxOutboxAge = [double]$h.outbox_age_s } }
    $firstUpAfter = $null
    if ($recMs0 -gt 0) {
        $up = $hs | Where-Object { $_.t -ge $recMs0 -and $_.readiness -eq 200 } | Select-Object -First 1
        if ($up) { $firstUpAfter = [math]::Round(($up.t - $recMs0) / 1000.0, 1) }
    }
    $readinessSeen = @($inFault | Group-Object readiness | Sort-Object Name | ForEach-Object { "$($_.Name) x$($_.Count)" })
    $healthSeen = @($inFault | Group-Object health | Sort-Object Name | ForEach-Object { "$($_.Name) x$($_.Count)" })

    # ---- invariants ----
    $marks = Get-Content $marksFile | ForEach-Object { $_ | ConvertFrom-Json }
    $ops = @($marks | Where-Object { $null -ne $_.key })
    $acked = @($ops | Where-Object { $_.ack_at -and $_.ack_status -eq 200 })
    $gaveUp = @($ops | Where-Object { $_.gave_up })
    $retried = @($acked | Where-Object { $_.attempts -gt 1 })
    $ackedCmdIds = ($acked | ForEach-Object { $_.command_id } | Sort-Object { $_ } -CaseSensitive)
    $ackedEventIds = ($acked | ForEach-Object { $_.event_id } | Sort-Object { $_ } -CaseSensitive)
    function Md5Of([string[]]$items) {
        $bytes = [Text.Encoding]::UTF8.GetBytes(($items -join ','))
        return (([Security.Cryptography.MD5]::Create().ComputeHash($bytes) | ForEach-Object { $_.ToString('x2') }) -join '')
    }
    $like = "chaos-$run-%"
    $cmdRows = [int](Invoke-Psql "select count(*) from cmd.commands where idempotency_key like '$like'")
    $cmdDigest = Invoke-Psql "select coalesce(md5(string_agg(command_id::text, ',' order by command_id)), '') from cmd.commands where idempotency_key like '$like'"
    $evRows = [int](Invoke-Psql "select count(*) from evt.events where event_type = 'TASK_ISSUE_REPORTED' and payload->>'description' like '$like'")
    $evDistinct = [int](Invoke-Psql "select count(distinct event_id) from evt.events where event_type = 'TASK_ISSUE_REPORTED' and payload->>'description' like '$like'")
    $evDup = [int](Invoke-Psql "select count(*) from (select payload->>'description' d from evt.events where event_type = 'TASK_ISSUE_REPORTED' and payload->>'description' like '$like' group by 1 having count(*) <> 1) x")
    $evDigest = Invoke-Psql "select coalesce(md5(string_agg(event_id::text, ',' order by event_id)), '') from evt.events where event_type = 'TASK_ISSUE_REPORTED' and payload->>'description' like '$like'"
    $seq = (Invoke-Psql "select count(*), coalesce(min(sequence),0), coalesce(max(sequence),0), count(distinct sequence) from evt.events where aggregate_id = '$task'") -split '\|'
    $seqGaps = ([int64]$seq[2] - [int64]$seq[1] + 1) - [int64]$seq[0]
    $seqDup = [int64]$seq[0] - [int64]$seq[3]
    $ob = (Invoke-Psql "select count(*) filter (where o.status = 'PUBLISHED'), count(*) filter (where o.status = 'PENDING'), count(*) filter (where o.status = 'DEAD'), count(*) from evt.outbox o join evt.events e on e.event_id = o.event_id where e.event_type = 'TASK_ISSUE_REPORTED' and e.payload->>'description' like '$like'") -split '\|'

    # I6: retry sample keys after recovery.
    $tok = Get-Token $ApiPort
    $retryMismatch = 0
    $evBeforeRetry = [int](Invoke-Psql "select count(*) from evt.events where payload->>'description' like '$like'")
    foreach ($m in ($acked | Select-Object -First 10)) {
        $b = @{ issue_type = 'OTHER'; description = $m.key } | ConvertTo-Json
        $r = Invoke-RestMethod -Method Post -Uri "$api/api/tasks/$task/issues" -Body $b -ContentType 'application/json' -Headers @{ Authorization = "Bearer $tok"; 'Idempotency-Key' = $m.key }
        if ($r.command_id -ne $m.command_id) { $retryMismatch++ }
    }
    $evAfterRetry = [int](Invoke-Psql "select count(*) from evt.events where payload->>'description' like '$like'")

    # I7: WebSocket probe.
    $dbEvents = @{}
    foreach ($e in (Invoke-Psql "select event_id from evt.events where event_type = 'TASK_ISSUE_REPORTED' and payload->>'description' like '$like'")) { $dbEvents[$e.Trim()] = $true }
    $pl = @(Get-Content $probeFile | ForEach-Object { $_ | ConvertFrom-Json })
    $pEvents = @($pl | Where-Object { $_.type -eq 'event' -and $_.event_type -eq 'TASK_ISSUE_REPORTED' })
    $pDelivered = @($pEvents | ForEach-Object { $_.event_id } | Sort-Object -Unique)
    $pDupConn = @($pEvents | Where-Object { $_.duplicate_on_connection }).Count
    $pPhantom = @($pDelivered | Where-Object { -not $dbEvents.ContainsKey($_) -and $_ -match '^[0-9a-f-]{36}$' }).Count
    $pEventsCommitted = @($pDelivered | Where-Object { $dbEvents.ContainsKey($_) }).Count
    $pOpens = @($pl | Where-Object { $_.type -eq 'open' }).Count
    $pCloses = @($pl | Where-Object { $_.type -eq 'close' })
    $pReady = @($pl | Where-Object { $_.type -eq 'ready' })
    $reconnectS = $null
    if ($injMs -gt 0 -and $pCloses.Count -gt 0) {
        $firstClose = ($pCloses | Where-Object { $_.t -ge $injMs } | Select-Object -First 1)
        if ($firstClose) {
            $re = $pReady | Where-Object { $_.t -gt $firstClose.t } | Select-Object -First 1
            if ($re) { $reconnectS = [math]::Round(($re.t - $firstClose.t) / 1000.0, 1) }
        }
    }
    $retryAttemptsMax = 0
    if ($acked.Count -gt 0) { $retryAttemptsMax = ($acked | Measure-Object -Property attempts -Maximum).Maximum }
    $firstErrors = @($ops | Where-Object { $_.first_error } | Group-Object first_error | ForEach-Object { "$($_.Name) x$($_.Count)" })
    $afterRecAck = $null
    if ($recoverStart) {
        $recMs = ([DateTimeOffset]$recoverStart).ToUnixTimeMilliseconds()
        $first = ($acked | Where-Object { $_.ack_at -gt $recMs } | Sort-Object ack_at | Select-Object -First 1)
        if ($first) { $afterRecAck = [math]::Round(($first.ack_at - $recMs) / 1000.0, 1) }
    }
    $okDuring = @($acked | Where-Object { $_.attempts -eq 1 -and $injected -and $recovered -and $_.ack_at -ge $injMs -and $_.ack_at -le $recMs0 }).Count

    $inv = [ordered]@{
        I1_all_acknowledged        = ($gaveUp.Count -eq 0 -and $acked.Count -eq $ops.Count)
        I2_one_command_per_key     = ($cmdRows -eq $acked.Count -and $cmdDigest -eq (Md5Of $ackedCmdIds))
        I3_one_event_per_key       = ($evRows -eq $acked.Count -and $evDistinct -eq $acked.Count -and $evDup -eq 0 -and $evDigest -eq (Md5Of $ackedEventIds))
        I4_sequence_continuous     = ($seqGaps -eq 0 -and $seqDup -eq 0)
        I5_outbox_drained_no_dead  = ($drained -and [int]$ob[1] -eq 0 -and [int]$ob[2] -eq 0 -and [int]$ob[0] -eq $acked.Count)
        I6_retry_idempotent        = ($retryMismatch -eq 0 -and $evAfterRetry -eq $evBeforeRetry)
        I7_ws_no_dup_no_phantom    = ($pDupConn -eq 0 -and $pPhantom -eq 0)
    }
    $all = -not ($inv.Values -contains $false)
    $row = [ordered]@{
        scenario = $id; title = $title; fault = $fault; hold_s = $holdS; run = $run
        operations = $ops.Count; acknowledged = $acked.Count; gave_up = $gaveUp.Count
        acked_after_retry = $retried.Count; max_attempts_for_one_op = $retryAttemptsMax
        first_attempt_errors = $firstErrors
        succeeded_first_try_during_fault = $okDuring
        command_rows = $cmdRows; event_rows = $evRows; event_duplicate_keys = $evDup
        task_sequence_gaps = $seqGaps; task_sequence_duplicates = $seqDup
        outbox_published = [int]$ob[0]; outbox_pending = [int]$ob[1]; outbox_dead = [int]$ob[2]; outbox_drain_wait_s = $drainS
        max_outbox_oldest_age_s_during_fault = [math]::Round($maxOutboxAge, 1)
        retry_sample = 10; retry_mismatches = $retryMismatch
        ws_events_delivered = $pDelivered.Count; ws_delivered_committed = $pEventsCommitted; ws_missed_during_fault = ($acked.Count - $pEventsCommitted)
        ws_duplicates_on_one_connection = $pDupConn; ws_phantom_events = $pPhantom
        ws_connections_opened = $pOpens; ws_closes = $pCloses.Count; ws_reconnect_after_first_close_s = $reconnectS
        health_readiness_seen = $readinessSeen
        health_overall_seen = $healthSeen
        login_during_fault = $loginDuring
        readiness_up_after_recovery_s = $firstUpAfter
        first_ack_after_recovery_s = $afterRecAck
        api_ready_wait_s = $readyAfterS
        invariants = $inv
        passed = $all
    }
    $results.Add([pscustomobject]$row)
    Say ("{0} -> {1}  (ops {2}, acked {3}, retried {4}, events {5}, outbox drain {6} s)" -f $id, $(if ($all) { 'ALL INVARIANTS HOLD' } else { 'INVARIANT VIOLATED' }), $ops.Count, $acked.Count, $retried.Count, $evRows, $drainS)
}

# ---- scenarios ------------------------------------------------------------------------------------------------------
Run-Scenario 'S0' 'baseline, no fault' 'none' 1 { } { }
Run-Scenario 'S1' 'API killed (taskkill /F) mid-write, restarted by the harness' 'kill -9 the API process' 8 `
    { Stop-Api $ApiPort } { Start-Api $ApiPort | Out-Null }
Run-Scenario 'S2' 'Redis stopped mid-write' 'docker stop redis' 10 `
    { & docker stop $redis | Out-Null } { & docker start $redis | Out-Null }
Run-Scenario 'S3' 'Postgres stopped mid-write' 'docker stop postgres' 10 `
    { & docker stop $pg | Out-Null } { & docker start $pg | Out-Null; Wait-Postgres }
Run-Scenario 'S4' 'Outbox publisher cut off (vo_outbox login revoked, sessions terminated)' 'ALTER ROLE vo_outbox NOLOGIN + terminate its sessions' 15 `
    { Invoke-Psql "alter role vo_outbox nologin" | Out-Null; Invoke-Psql "select pg_terminate_backend(pid) from pg_stat_activity where usename = 'vo_outbox'" | Out-Null } `
    { Invoke-Psql "alter role vo_outbox login" | Out-Null }
Run-Scenario 'S5' 'WebSocket path dropped (proxy killed, every socket cut)' 'kill the TCP path of /ws/live' 8 `
    { Get-CimInstance Win32_Process -Filter "Name='node.exe'" | Where-Object { $_.CommandLine -match 'tcp-proxy' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force } } `
    { $script:proxyProc = Start-Proxy }

$results | ConvertTo-Json -Depth 6 | Set-Content -Encoding utf8 $Out
Say "results: $Out"
$failed = @($results | Where-Object { -not $_.passed })
if ($failed.Count -gt 0) { Say ("FAILED: " + (($failed | ForEach-Object { $_.scenario }) -join ', ')); exit 1 }
Say 'all scenarios held their invariants'
