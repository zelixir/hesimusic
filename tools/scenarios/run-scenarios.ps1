param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$Rest
)
# One-command wrapper: powershell -NoProfile -File tools/scenarios/run-scenarios.ps1
$ErrorActionPreference = "Stop"
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }
try { $OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }

$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
Set-Location -LiteralPath $repoRoot

$bun = (Get-Command bun -ErrorAction SilentlyContinue).Source
if (-not $bun) {
    throw "bun not found: install Bun (https://bun.sh) or run 'bun run tools/scenarios/run-scenarios.ts' manually"
}

& $bun run (Join-Path $PSScriptRoot "run-scenarios.ts") @Rest
exit $LASTEXITCODE
