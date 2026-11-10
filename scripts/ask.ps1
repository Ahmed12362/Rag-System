<#
.SYNOPSIS
  Ask the HRIS RAG assistant a question as one of the simulated users and print the answer, citations and trace.

.DESCRIPTION
  Logs in with HTTP Basic. The password is read from APP_DEMO_PASSWORD in the project's .env file (or the
  environment variable of the same name) and is never printed.
  Users: employee (E-1001, Engineering), qa (E-1002), manager (E-1000), hr (E-1003, HR_ADMIN, sees CONFIDENTIAL),
         finance (E-1004, Finance), contractor (CT-0001, PUBLIC documents only).

.EXAMPLE
  .\scripts\ask.ps1 "How many days in advance should I request annual leave?"
  .\scripts\ask.ps1 "Share the salary bands for team leads." -User hr
  .\scripts\ask.ps1 "ما هي سياسة الإجازة المرضية؟" -User finance
  .\scripts\ask.ps1 "How early must I request leave?" -Raw        # full JSON response
#>
param(
    [Parameter(Mandatory = $true, Position = 0)] [string] $Question,
    [ValidateSet("employee", "qa", "manager", "hr", "finance", "contractor")] [string] $User = "employee",
    [string] $Strategy = "NAIVE",
    [string] $BaseUrl = "http://localhost:8080",
    [switch] $Raw
)

[Console]::OutputEncoding = [Text.Encoding]::UTF8

$password = $env:APP_DEMO_PASSWORD
if (-not $password) {
    $envFile = Join-Path (Split-Path $PSScriptRoot -Parent) ".env"
    if (Test-Path $envFile) {
        $line = Get-Content $envFile | Where-Object { $_ -match '^\s*APP_DEMO_PASSWORD\s*=' } | Select-Object -First 1
        if ($line) { $password = ($line -split '=', 2)[1].Trim() }
    }
}
if (-not $password) { Write-Host "APP_DEMO_PASSWORD is not set (add it to .env)." -ForegroundColor Red; exit 1 }

$auth = "Basic " + [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("${User}:$password"))
$body = @{ question = $Question; strategy = $Strategy } | ConvertTo-Json -Compress
$bytes = [Text.Encoding]::UTF8.GetBytes($body)

try {
    $r = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/v1/chat" -ContentType "application/json; charset=utf-8" `
        -Headers @{ Authorization = $auth } -Body $bytes
}
catch {
    Write-Host "Request failed: $($_.Exception.Message)" -ForegroundColor Red
    if ($_.ErrorDetails.Message) { Write-Host $_.ErrorDetails.Message }
    exit 1
}

if ($Raw) { $r | ConvertTo-Json -Depth 8; exit 0 }

$color = switch ($r.status) { "ANSWERED" { "Green" } "ABSTAINED" { "Yellow" } default { "Red" } }
Write-Host ""
Write-Host "Q ($User): $Question" -ForegroundColor Cyan
Write-Host "[$($r.status)] strategy=$($r.strategy)" -ForegroundColor $color
Write-Host ""
Write-Host $r.answer
Write-Host ""
if ($r.citations.Count -gt 0) {
    Write-Host "Citations:" -ForegroundColor Cyan
    $r.citations | ForEach-Object { Write-Host ("  [{0}] {1} v{2} - {3}" -f $_.sourceId, $_.docKey, $_.version, $_.sectionPath) }
}
if ($r.retrieved.Count -gt 0) {
    Write-Host "Retrieved (top $($r.retrieved.Count)):" -ForegroundColor Cyan
    foreach ($c in $r.retrieved) {
        $check = $r.passageChecks | Where-Object { $_.chunkId -eq $c.chunkId } | Select-Object -First 1
        $mark = if ($check -and $check.outcome -ne "CLEAN") { "  <-- $($check.outcome) $($check.rules -join ',')" } else { "" }
        Write-Host ("  #{0} {1:N3}  {2} v{3} [{4}] - {5}{6}" -f $c.rank, $c.score, $c.docKey, $c.version, $c.accessLevel, $c.sectionPath, $mark)
    }
}
$gen = $r.modelCalls | Where-Object { $_.purpose -eq "GENERATE" }
Write-Host ("Timings: total={0} ms (embedding={1}, retrieval={2}, generation={3})" -f `
    $r.timings.totalMs, $r.timings.embeddingMs, $r.timings.retrievalMs, $r.timings.generationMs) -ForegroundColor DarkGray
Write-Host ("Calls: embeddings={0} (cache hits={1}), chat={2}" -f `
    $r.callCounts.embeddingCalls, $r.callCounts.embeddingCacheHits, $r.callCounts.chatCalls) -ForegroundColor DarkGray
if ($gen) { Write-Host ("Tokens: prompt={0} completion={1} thinking={2} finish={3}" -f `
    $gen.promptTokens, $gen.completionTokens, $gen.thinkingTokens, $gen.finishReason) -ForegroundColor DarkGray }
$flags = $r.guardrails | Where-Object { $_.outcome -notin @("PASS", "APPLIED") }
if ($flags) { Write-Host "Guardrails:" -ForegroundColor Yellow; $flags | ForEach-Object { Write-Host ("  {0}/{1}: {2} {3}" -f $_.layer, $_.rule, $_.outcome, $_.detail) } }
