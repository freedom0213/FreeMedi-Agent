param(
    [string]$MySqlBin,
    [int]$Port = 3306,
    [string]$Database = 'mediflow',
    [string]$User = 'mediflow',
    [string]$Password = 'mediflow',
    [int]$Days = 14
)

# Re-runnable demo data seeder. See database/seed-demo-slots.sql for why Flyway V4/V5 are not
# enough on their own. Safe to run before every demo or recording session.

$ErrorActionPreference = 'Stop'
$workspaceRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if ($Days -lt 1 -or $Days -gt 30) {
    throw 'Choose between 1 and 30 days.'
}
if (-not $MySqlBin) {
    $MySqlBin = Split-Path (Get-Command mysql.exe -ErrorAction Stop).Source
}
$MySqlBin = [System.IO.Path]::GetFullPath($MySqlBin)
$clientExe = Join-Path $MySqlBin 'mysql.exe'
if (-not (Test-Path -LiteralPath $clientExe -PathType Leaf)) {
    throw "MySQL client not found: $clientExe"
}
$sqlFile = Join-Path $workspaceRoot 'database\seed-demo-slots.sql'
if (-not (Test-Path -LiteralPath $sqlFile -PathType Leaf)) {
    throw "Seed script not found: $sqlFile"
}

# MYSQL_PWD keeps the password out of the command line, which would otherwise be visible to
# any process on this machine. It is restored afterwards.
$previousPassword = [Environment]::GetEnvironmentVariable('MYSQL_PWD', 'Process')
try {
    [Environment]::SetEnvironmentVariable('MYSQL_PWD', $Password, 'Process')
    # The seed file is ASCII only, so piping it through the console cannot corrupt it.
    $batch = "SET @days = $Days;`n" + (Get-Content -Raw -Encoding UTF8 -LiteralPath $sqlFile)
    $batch | & $clientExe --no-defaults --host=127.0.0.1 "--port=$Port" "--user=$User" `
        "--database=$Database" --default-character-set=utf8mb4 --batch --table
    if ($LASTEXITCODE -ne 0) {
        throw 'Seeding failed. Check the connection settings and that migrations V1-V5 have been applied.'
    }
} finally {
    [Environment]::SetEnvironmentVariable('MYSQL_PWD', $previousPassword, 'Process')
}

Write-Host "Demo slots for the next $Days day(s) are ready in '$Database'."
Write-Host 'Re-run this script whenever the demo window has passed; existing rows are never overwritten.'
