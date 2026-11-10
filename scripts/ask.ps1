<#
.SYNOPSIS
  Ask the HRIS RAG assistant a question and print the answer, citations and trace in a readable form.

.EXAMPLE
  .\scripts\ask.ps1 "How many days in advance should I request annual leave?"
  .\scripts\ask.ps1 "ما هي سياسة الإجازة المرضية؟"
  .\scripts\ask.ps1 "How early must I request leave?" -Raw        # full JSON response
#>
param(
    [Parameter(Mandatory = $true, Position = 0)] [string] $Question,
    [string] $Strategy = "NAIVE",
    [string] $BaseUrl = "http://localhost:8080",
    [switch] $Raw
)

[Console]::OutputEncoding = [Text.Encoding]::UTF8
$body = @{ question = $Question; strategy = $Strategy } | ConvertTo-Json -Compress
$bytes = [Text.Encoding]::UTF8.GetBytes($body)

try {
    $r = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/v1/chat" -ContentType "application/json; charset=utf-8" -Body $bytes
}
catch {
    Write-Host "Request failed: $($_.Exception.Message)" -ForegroundColor Red
    if ($_.ErrorDetails.Message) { Write-Host $_.ErrorDetails.Message }
    exit 1
}

if ($Raw) { $r | ConvertTo-Json -Depth 8; exit 0 }

$color = switch ($r.status) { "ANSWERED" { "Green" } "ABSTAINED" { "Yellow" } default { "Red" } }
Write-Host ""
Write-Host "Q: $Question" -ForegroundColor Cyan
Write-Host "[$($r.status)] strategy=$($r.strategy)" -ForegroundColor $color
Write-Host ""
Write-Host $r.answer
Write-Host ""
if ($r.citations.Count -gt 0) {
    Write-Host "Citations:" -ForegroundColor Cyan
    $r.citations | ForEach-Object { Write-Host ("  [{0}] {1} v{2} - {3}" -f $_.sourceId, $_.docKey, $_.version, $_.sectionPath) }
}
Write-Host "Retrieved (top $($r.retrieved.Count)):" -ForegroundColor Cyan
$r.retrieved | ForEach-Object { Write-Host ("  #{0} {1:N3}  {2} v{3} - {4}" -f $_.rank, $_.score, $_.docKey, $_.version, $_.sectionPath) }
$gen = $r.modelCalls | Where-Object { $_.purpose -eq "GENERATE" }
$emb = $r.modelCalls | Where-Object { $_.purpose -eq "EMBED_QUERY" }
Write-Host ("Timings: total={0} ms (embedding={1}, retrieval={2}, generation={3})" -f `
    $r.timings.totalMs, $r.timings.embeddingMs, $r.timings.retrievalMs, $r.timings.generationMs) -ForegroundColor DarkGray
if ($gen) { Write-Host ("Tokens: prompt={0} completion={1} thinking={2} finish={3}" -f `
    $gen.promptTokens, $gen.completionTokens, $gen.thinkingTokens, $gen.finishReason) -ForegroundColor DarkGray }
if ($emb) { Write-Host ("Query embedding cached: {0}" -f $emb.cached) -ForegroundColor DarkGray }
$flags = $r.guardrails | Where-Object { $_.outcome -notin @("PASS", "APPLIED") }
if ($flags) { Write-Host "Guardrails:" -ForegroundColor Yellow; $flags | ForEach-Object { Write-Host ("  {0}/{1}: {2} {3}" -f $_.layer, $_.rule, $_.outcome, $_.detail) } }
