# Crash-recovery proof (PLAN test #6 / NFR2, NFR5).
# Starts the packaged jar, drives concurrent transfers, hard-kills the JVM (-Force,
# i.e. SIGKILL-equivalent) mid-flight, restarts, then asserts the ledger invariant
# survived: SUM(entries)=0 and not a single half-posted or unbalanced transaction.
$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent
. "$root\set-env.ps1"
$psql = "C:\Program Files\PostgreSQL\17\bin\psql.exe"
$env:PGPASSWORD = $env:LEDGER_DB_PASSWORD
$jar = Join-Path $root "target\ledger-engine-0.1.0.jar"
if (-not (Test-Path $jar)) { throw "jar not found — run: mvn -DskipTests package" }
$base = "http://127.0.0.1:$($env:LEDGER_PORT)"
$apiHdr = @{ "X-Api-Key" = $env:LEDGER_API_KEY }
$jsonHdr = @{ "X-Api-Key" = $env:LEDGER_API_KEY; "Content-Type" = "application/json" }

function Invoke-Psql([string]$sql) {
    & $psql -U $env:LEDGER_DB_USER -h 127.0.0.1 -d ledger_engine -tAc $sql
}
function Stop-OnPort {
    $pid8080 = (Get-NetTCPConnection -LocalPort $env:LEDGER_PORT -State Listen -ErrorAction SilentlyContinue).OwningProcess
    if ($pid8080) { Stop-Process -Id $pid8080 -Force -ErrorAction SilentlyContinue }
}
function Start-App {
    Start-Process java -ArgumentList "-jar", $jar -PassThru `
        -RedirectStandardOutput "$env:TEMP\crash-app-$(Get-Random).log" `
        -RedirectStandardError "$env:TEMP\crash-app-$(Get-Random).err"
}
function Wait-Health {
    for ($i = 0; $i -lt 60; $i++) {
        try { if ((Invoke-RestMethod "$base/health" -TimeoutSec 2).status -eq "UP") { return } } catch {}
        Start-Sleep -Milliseconds 500
    }
    throw "app did not become healthy"
}

Stop-OnPort
Invoke-Psql "TRUNCATE outbox, entries, transactions, accounts RESTART IDENTITY CASCADE;" | Out-Null

Write-Host "starting app (run 1)..."
$app = Start-App
Wait-Health

$alice = (Invoke-RestMethod -Method Post -Uri "$base/accounts" -Headers $jsonHdr -Body '{"name":"Alice","currency":"INR"}').id
$bob = (Invoke-RestMethod -Method Post -Uri "$base/accounts" -Headers $jsonHdr -Body '{"name":"Bob","currency":"INR"}').id
# Fund Alice generously via one balanced statement so funds never run out mid-burst.
$deposit = @"
WITH e AS (INSERT INTO accounts(name,currency) VALUES('ext','INR') RETURNING id),
     t AS (INSERT INTO transactions(type,status) VALUES('TRANSFER','POSTED') RETURNING id)
INSERT INTO entries(transaction_id,account_id,amount_minor,direction,currency)
SELECT t.id, e.id, -100000000, 'DEBIT', 'INR' FROM e, t
UNION ALL
SELECT t.id, $alice, 100000000, 'CREDIT', 'INR' FROM e, t;
"@
Invoke-Psql $deposit | Out-Null
$funded = Invoke-Psql "SELECT COALESCE(SUM(amount_minor),0) FROM entries WHERE account_id=$alice;"
Write-Host "Alice funded balance: $funded"

Write-Host "driving concurrent transfers, then hard-killing the JVM mid-flight..."
$load = Start-Job -ScriptBlock {
    param($base, $hdr, $from, $to)
    $body = "{""from"":$from,""to"":$to,""amountMinor"":1,""currency"":""INR""}"
    for ($i = 0; $i -lt 5000; $i++) {
        try { Invoke-RestMethod -Method Post -Uri "$base/transfers" -Headers $hdr -Body $body -TimeoutSec 5 | Out-Null } catch {}
    }
} -ArgumentList $base, $jsonHdr, $alice, $bob

Start-Sleep -Milliseconds 1500
Stop-Process -Id $app.Id -Force   # crash mid-batch
Stop-Job $load -ErrorAction SilentlyContinue; Remove-Job $load -Force -ErrorAction SilentlyContinue

$committed = Invoke-Psql "SELECT COUNT(*) FROM entries WHERE account_id=$bob;"
Write-Host "transfers committed before crash: $committed"
if ([int]$committed -lt 50) {
    throw "FAIL: only $committed transfers committed — load didn't meaningfully exercise the engine before the kill"
}

Write-Host "restarting app (run 2)..."
$app2 = Start-App
Wait-Health

$entriesSum = Invoke-Psql "SELECT COALESCE(SUM(amount_minor),0) FROM entries;"
$broken = Invoke-Psql "SELECT COUNT(*) FROM (SELECT transaction_id FROM entries GROUP BY transaction_id HAVING SUM(amount_minor)<>0 OR COUNT(*)<2) t;"
$recon = Invoke-RestMethod -Uri "$base/admin/reconcile" -Headers $apiHdr
Stop-Process -Id $app2.Id -Force -ErrorAction SilentlyContinue

Write-Host "----------------------------------------"
Write-Host "entriesSum (must be 0):        $entriesSum"
Write-Host "half/unbalanced tx (must be 0): $broken"
Write-Host "reconcile invariantOk:         $($recon.invariantOk)"
Write-Host "----------------------------------------"
if ("$entriesSum" -ne "0") { throw "FAIL: ledger drift, sum=$entriesSum" }
if ("$broken" -ne "0") { throw "FAIL: $broken half-posted/unbalanced transactions survived the crash" }
if (-not $recon.invariantOk) { throw "FAIL: reconcile reports drift" }
Write-Host "CRASH-RECOVERY PASS: invariant held across a hard kill — no half-transfer." -ForegroundColor Green
