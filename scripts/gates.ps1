<#
.SYNOPSIS
  Runs the two correctness gates against a running instance. Windows equivalent
  of scripts/gate1-concurrent-capture.sh and gate2-exactly-once-settlement.sh.

.EXAMPLE
  .\scripts\gates.ps1 -BaseUrl https://your-app.example.com
  .\scripts\gates.ps1 -BaseUrl http://localhost:8080 -Gate 1 -Concurrency 20
  .\scripts\gates.ps1 -BaseUrl http://localhost:8080 -Gate 2 -Batch 25

.NOTES
  Uses System.Net.Http.HttpClient directly so the concurrent bursts are genuinely
  simultaneous, which Invoke-RestMethod in a loop would not be. Works on both
  Windows PowerShell 5.1 and PowerShell 7.
#>
[CmdletBinding()]
param(
  [string]$BaseUrl = "http://localhost:8080",
  [ValidateSet("1", "2", "both")] [string]$Gate = "both",
  [int]$Concurrency = 20,
  [int]$Batch = 25,
  [double]$FaultRate = 0.4
)

$ErrorActionPreference = "Stop"
$BaseUrl = $BaseUrl.TrimEnd('/')
$script:Failures = 0

Add-Type -AssemblyName System.Net.Http
$http = [System.Net.Http.HttpClient]::new()
$http.Timeout = [TimeSpan]::FromSeconds(120)

function Write-Pass($m) { Write-Host "  PASS  $m" -ForegroundColor Green }
function Write-Fail($m) { Write-Host "  FAIL  $m" -ForegroundColor Red; $script:Failures++ }
function Write-Head($m) { Write-Host ""; Write-Host $m -ForegroundColor Cyan }

function Assert-Equal($description, $expected, $actual) {
  if ("$expected" -eq "$actual") { Write-Pass "$description ($actual)" }
  else { Write-Fail "$description - expected $expected, got $actual" }
}

# Issues one request and returns @{ Status = <int>; Body = <parsed json or $null> }.
function Invoke-Api {
  param([string]$Method, [string]$Path, $Body, [string]$CorrelationId)

  $request = [System.Net.Http.HttpRequestMessage]::new(
    [System.Net.Http.HttpMethod]::new($Method), "$BaseUrl$Path")
  if ($null -ne $Body) {
    $json = if ($Body -is [string]) { $Body } else { $Body | ConvertTo-Json -Compress -Depth 6 }
    $request.Content = [System.Net.Http.StringContent]::new($json, [Text.Encoding]::UTF8, "application/json")
  }
  if ($CorrelationId) { $request.Headers.Add("X-Correlation-Id", $CorrelationId) }

  $response = $http.SendAsync($request).GetAwaiter().GetResult()
  $text = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
  $parsed = $null
  if ($text) { try { $parsed = $text | ConvertFrom-Json } catch { } }
  [pscustomobject]@{ Status = [int]$response.StatusCode; Body = $parsed; Raw = $text }
}

# Fires $Count requests that genuinely overlap: every task is started before any
# is awaited, so the race under test actually happens.
function Invoke-Concurrently {
  param([int]$Count, [scriptblock]$BuildRequest)

  $tasks = New-Object System.Collections.ArrayList
  for ($i = 1; $i -le $Count; $i++) {
    $request = & $BuildRequest $i
    [void]$tasks.Add($http.SendAsync($request))
  }
  [System.Threading.Tasks.Task]::WaitAll($tasks.ToArray())
  $tasks | ForEach-Object { [int]$_.Result.StatusCode }
}

function New-CaptureRequest {
  param([string]$PaymentId, [string]$Key, [string]$CorrelationId)
  $request = [System.Net.Http.HttpRequestMessage]::new(
    [System.Net.Http.HttpMethod]::Post, "$BaseUrl/payments/$PaymentId/capture")
  $request.Content = [System.Net.Http.StringContent]::new(
    (@{ idempotency_key = $Key } | ConvertTo-Json -Compress), [Text.Encoding]::UTF8, "application/json")
  $request.Headers.Add("X-Correlation-Id", $CorrelationId)
  $request
}

function New-Payment {
  param([long]$Amount = 125000)
  $response = Invoke-Api POST "/payments" @{
    amount = $Amount; currency = "INR"; idempotency_key = [guid]::NewGuid().ToString()
  }
  if ($response.Status -ne 201) { throw "authorize failed: $($response.Status) $($response.Raw)" }
  $response.Body.id
}

function Get-Stats { (Invoke-Api GET "/admin/stats").Body }

function Test-Ready {
  try { if ((Invoke-Api GET "/readyz").Status -eq 200) { return } } catch { }
  Write-Host "service is not ready at $BaseUrl" -ForegroundColor Red
  exit 1
}

# =============================================================================
function Invoke-Gate1 {
  Write-Host ""
  Write-Host "Gate 1 - idempotent capture under concurrency" -ForegroundColor White
  Write-Host "target: $BaseUrl   concurrency: $Concurrency" -ForegroundColor DarkGray

  Write-Head "1. $Concurrency concurrent captures sharing ONE idempotency key"
  Write-Host "   (a client retrying a single logical request: all must succeed identically)"
  $paymentA = New-Payment
  $sharedKey = [guid]::NewGuid().ToString()
  $statuses = Invoke-Concurrently $Concurrency { param($i) New-CaptureRequest $paymentA $sharedKey "gate1-same-$i" }

  Assert-Equal "every retry returned 200" $Concurrency (@($statuses | Where-Object { $_ -eq 200 }).Count)
  Assert-Equal "zero 5xx responses" 0 (@($statuses | Where-Object { $_ -ge 500 }).Count)
  Assert-Equal "payment reached CAPTURED" "CAPTURED" (Invoke-Api GET "/payments/$paymentA").Body.state

  Write-Head "2. $Concurrency concurrent captures with DISTINCT idempotency keys"
  Write-Host "   (genuinely racing attempts: exactly one may win, the rest get 409)"
  $paymentB = New-Payment
  $statuses = Invoke-Concurrently $Concurrency {
    param($i) New-CaptureRequest $paymentB ([guid]::NewGuid().ToString()) "gate1-distinct-$i"
  }

  Assert-Equal "exactly one capture won" 1 (@($statuses | Where-Object { $_ -eq 200 }).Count)
  Assert-Equal "every loser got 409" ($Concurrency - 1) (@($statuses | Where-Object { $_ -eq 409 }).Count)
  Assert-Equal "zero 5xx responses" 0 (@($statuses | Where-Object { $_ -ge 500 }).Count)
  Assert-Equal "payment reached CAPTURED" "CAPTURED" (Invoke-Api GET "/payments/$paymentB").Body.state

  Write-Head "3. re-capturing an already-captured payment"
  $recapture = Invoke-Api POST "/payments/$paymentA/capture" @{ idempotency_key = [guid]::NewGuid().ToString() }
  Assert-Equal "rejected with 409" 409 $recapture.Status

  Write-Head "4. database invariants"
  $invariants = (Get-Stats).invariants
  Assert-Equal "no capture lost its settlement job" 0 $invariants.captured_without_outbox_row
  Assert-Equal "no payment settled twice" 0 $invariants.double_settled_payments
  Assert-Equal "safety invariants hold" $true $invariants.safety_holds
}

# =============================================================================
function Invoke-Gate2 {
  Write-Host ""
  Write-Host "Gate 2 - exactly-once settlement under a flaky downstream" -ForegroundColor White
  Write-Host "target: $BaseUrl   batch: $Batch   downstream fault rate: $FaultRate" -ForegroundColor DarkGray

  $keysBefore = (Get-Stats).downstream.distinct_settlement_keys

  Write-Head "1. pinning the downstream fault rate at $FaultRate"
  [void](Invoke-Api POST "/admin/mock-settlement/config" @{ failure_rate = $FaultRate })

  Write-Head "2. authorizing and capturing $Batch payments concurrently"
  $paymentIds = 1..$Batch | ForEach-Object { New-Payment (10000 + $_) }
  $statuses = Invoke-Concurrently $Batch {
    param($i) New-CaptureRequest $paymentIds[$i - 1] ([guid]::NewGuid().ToString()) "gate2-capture-$i"
  }
  Assert-Equal "payments captured" $Batch (@($statuses | Where-Object { $_ -eq 200 }).Count)

  Write-Head "3. draining - repeatedly, and two drainers at a time"
  Write-Host "   the downstream is failing ~$FaultRate of calls, so this takes several rounds" -ForegroundColor DarkGray
  $drained = $false
  for ($round = 1; $round -le 40; $round++) {
    # Two concurrent drain passes. If FOR UPDATE SKIP LOCKED were not doing its
    # job, this is where an item would get delivered twice at once.
    $drains = @(
      $http.PostAsync("$BaseUrl/admin/drain?passes=3", $null),
      $http.PostAsync("$BaseUrl/admin/drain?passes=3", $null)
    )
    [System.Threading.Tasks.Task]::WaitAll($drains)

    $invariants = (Get-Stats).invariants
    "   round {0,-2}  settled={1,-4} outstanding={2,-4}" -f $round, $invariants.settled_outbox_rows, $invariants.still_outstanding | Write-Host
    if ($invariants.still_outstanding -eq 0) { $drained = $true; break }
    Start-Sleep -Milliseconds 800
  }

  Write-Head "4. invariants"
  $final = Get-Stats
  $inv = $final.invariants

  Assert-Equal "no payment settled twice" 0 $inv.double_settled_payments
  Assert-Equal "no capture lost its settlement job" 0 $inv.captured_without_outbox_row
  Assert-Equal "nothing settled that was not captured" 0 $inv.settled_but_not_captured
  Assert-Equal "safety invariants hold" $true $inv.safety_holds
  Assert-Equal "outbox fully drained" $true $drained
  Assert-Equal "settlement keys == captured - dead-lettered" `
    ($inv.captured_payments - $inv.dead_lettered) $final.downstream.distinct_settlement_keys
  Assert-Equal "our batch's settlements landed" $Batch `
    ($final.downstream.distinct_settlement_keys - $keysBefore)

  if ($inv.dead_lettered -gt 0) {
    Write-Host "  NOTE  $($inv.dead_lettered) item(s) dead-lettered - terminal, visible at $BaseUrl/admin/dead-letters, never dropped" -ForegroundColor Yellow
  }

  Write-Head "5. evidence the retry path was exercised"
  Write-Host ("   captured={0}  settled={1}  dead-lettered={2}  settlement keys={3}  deliveries seen by provider={4}" -f `
      $inv.captured_payments, $inv.settled_outbox_rows, $inv.dead_lettered,
      $final.downstream.distinct_settlement_keys, $final.downstream.total_deliveries)
  Write-Host "   per-item attempt counts are on GET /payments/{id}; retries and dead-letters are in the logs" -ForegroundColor DarkGray
}

# =============================================================================
Test-Ready
if ($Gate -eq "1" -or $Gate -eq "both") { Invoke-Gate1 }
if ($Gate -eq "2" -or $Gate -eq "both") { Invoke-Gate2 }

Write-Host ""
if ($script:Failures -eq 0) {
  Write-Host "ALL GATES PASSED - captured exactly once, settled exactly once, nothing lost, zero 500s" -ForegroundColor Green
  exit 0
}
Write-Host "GATES FAILED - $($script:Failures) check(s) failed" -ForegroundColor Red
exit 1
