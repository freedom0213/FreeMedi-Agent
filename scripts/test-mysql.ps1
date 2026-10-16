param(
    [string]$MySqlBin,
    [int]$Port = 13316,
    [switch]$KeepData
)

$ErrorActionPreference = 'Stop'
$workspaceRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if (-not $MySqlBin) {
    $MySqlBin = Split-Path (Get-Command mysql.exe -ErrorAction Stop).Source
}
$MySqlBin = [System.IO.Path]::GetFullPath($MySqlBin)
$serverExe = Join-Path $MySqlBin 'mysqld.exe'
$clientExe = Join-Path $MySqlBin 'mysql.exe'
$adminExe = Join-Path $MySqlBin 'mysqladmin.exe'
foreach ($executable in @($serverExe, $clientExe, $adminExe)) {
    if (-not (Test-Path -LiteralPath $executable -PathType Leaf)) {
        throw "MySQL executable not found: $executable"
    }
}
if ($Port -lt 1024 -or $Port -gt 65535) {
    throw 'Choose a test port between 1024 and 65535.'
}
# Refuse an occupied port before creating a completely new, disposable data directory.
$probe = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, $Port)
try { $probe.Start() } finally { $probe.Stop() }
$runDirectory = [System.IO.Path]::GetFullPath((Join-Path $workspaceRoot (
    'target\mysql-test-' + [Guid]::NewGuid().ToString('N'))))
if (-not $runDirectory.StartsWith($workspaceRoot + '\', [StringComparison]::OrdinalIgnoreCase)) {
    throw 'The test directory must be inside the workspace.'
}
$dataDirectory = Join-Path $runDirectory 'data'
New-Item -ItemType Directory -Path $dataDirectory -Force | Out-Null
$baseDirectory = Split-Path $MySqlBin
$testServer = $null
$succeeded = $false
$previousUrl = [Environment]::GetEnvironmentVariable('MEDIFLOW_TEST_DB_URL', 'Process')
$previousUser = [Environment]::GetEnvironmentVariable('MEDIFLOW_TEST_DB_USERNAME', 'Process')
$previousPassword = [Environment]::GetEnvironmentVariable('MEDIFLOW_TEST_DB_PASSWORD', 'Process')

try {
    & $serverExe --no-defaults "--basedir=$baseDirectory" "--datadir=$dataDirectory" --initialize-insecure --console
    if ($LASTEXITCODE -ne 0) { throw 'Isolated MySQL initialization failed.' }
    $testServer = Start-Process -FilePath $serverExe -WindowStyle Hidden -PassThru -ArgumentList @(
        '--no-defaults', "--basedir=`"$baseDirectory`"", "--datadir=`"$dataDirectory`"",
        "--port=$Port", '--bind-address=127.0.0.1', '--mysqlx=OFF', '--default-time-zone=+00:00', '--console'
    ) -RedirectStandardOutput (Join-Path $runDirectory 'server.out.log') `
      -RedirectStandardError (Join-Path $runDirectory 'server.err.log')
    $deadline = [DateTime]::UtcNow.AddSeconds(45)
    $ready = $false
    while ([DateTime]::UtcNow -lt $deadline) {
        $testServer.Refresh()
        if ($testServer.HasExited) { throw "Test MySQL exited; see $runDirectory\server.err.log" }
        $pingExitCode = 1
        try {
            & $adminExe --no-defaults --host=127.0.0.1 "--port=$Port" --user=root --connect-timeout=1 ping 2>$null | Out-Null
            $pingExitCode = $LASTEXITCODE
        } catch {
            # Connection refused is expected while the isolated server is starting.
        }
        if ($pingExitCode -eq 0) { $ready = $true; break }
        Start-Sleep -Milliseconds 200
    }
    if (-not $ready) { throw 'Test MySQL did not become ready within 45 seconds.' }
    & $clientExe --no-defaults --host=127.0.0.1 "--port=$Port" --user=root --execute `
        'CREATE DATABASE mediflow_test CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;'
    if ($LASTEXITCODE -ne 0) { throw 'Could not create the isolated test schema.' }
    Push-Location $workspaceRoot
    try {
        $testUrl = "jdbc:mysql://127.0.0.1:$Port/mediflow_test?connectionTimeZone=UTC"
        & mvn -q "-Dmediflow.test.db.url=$testUrl" "-Dmediflow.test.db.username=root" `
            "-Dmediflow.test.db.password=" test
        if ($LASTEXITCODE -ne 0) { throw 'MySQL integration tests failed.' }
        $reportPath = Join-Path $workspaceRoot 'target\surefire-reports\TEST-com.mediflow.agent.agent.persistence.MySqlTaskStateStoreTest.xml'
        if (-not (Test-Path -LiteralPath $reportPath)) { throw 'MySQL integration test report was not generated.' }
        [xml]$report = Get-Content -LiteralPath $reportPath
        if ([int]$report.testsuite.tests -lt 1 -or [int]$report.testsuite.skipped -gt 0) {
            throw 'MySQL integration tests did not execute; refusing a false-positive test run.'
        }
        $succeeded = $true
    } finally { Pop-Location }
} finally {
    [Environment]::SetEnvironmentVariable('MEDIFLOW_TEST_DB_URL', $previousUrl, 'Process')
    [Environment]::SetEnvironmentVariable('MEDIFLOW_TEST_DB_USERNAME', $previousUser, 'Process')
    [Environment]::SetEnvironmentVariable('MEDIFLOW_TEST_DB_PASSWORD', $previousPassword, 'Process')
    if ($null -ne $testServer) {
        $testServer.Refresh()
        if (-not $testServer.HasExited) {
            & $adminExe --no-defaults --host=127.0.0.1 "--port=$Port" --user=root shutdown 2>$null | Out-Null
            if (-not $testServer.WaitForExit(10000)) {
                Stop-Process -Id $testServer.Id -ErrorAction SilentlyContinue
            }
        }
    }
    if ($succeeded -and -not $KeepData) {
        # Every run reserves about 180 MB, so a passing run cleans up after itself.
        # -KeepData, and any failure, still retain the directory for diagnosis.
        Remove-Item -LiteralPath $runDirectory -Recurse -Force -ErrorAction SilentlyContinue
        Write-Host 'Removed the disposable test data directory. Use -KeepData to retain it.'
    } else {
        Write-Host "Test data and diagnostics retained under $runDirectory (ignored by Git)."
    }
}
