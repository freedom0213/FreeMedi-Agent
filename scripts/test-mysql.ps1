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

# Windows environment variable names are case-insensitive, but the process environment can still
# hold both HTTP_PROXY and http_proxy. Start-Process then fails with "an item with the same key has
# already been added" before it can launch the child, which is how a developer machine ends up
# unable to run this script at all. Drop the lower-case spellings for this run and restore them
# afterwards; the isolated server and Maven both talk to 127.0.0.1 and need no proxy.
$proxyNames = @('http_proxy', 'https_proxy', 'all_proxy', 'no_proxy')
$savedProxies = @{}
foreach ($proxyName in $proxyNames) {
    $proxyValue = [Environment]::GetEnvironmentVariable($proxyName, 'Process')
    if ($null -ne $proxyValue) {
        $savedProxies[$proxyName] = $proxyValue
        [Environment]::SetEnvironmentVariable($proxyName, $null, 'Process')
    }
}
if ($savedProxies.Count -gt 0) {
    Write-Host ("Normalised $($savedProxies.Count) lower-case proxy variable(s) for this run.")
}

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
        $reportDirectory = Join-Path $workspaceRoot 'target\surefire-reports'
        $runStarted = [DateTime]::UtcNow
        # The Qdrant adapter's tests need a vector store, not MySQL. They are excluded so this run
        # reports the offline suite exactly, with no skipped class mixed in; scripts/test-qdrant.ps1
        # runs them against a real server.
        & mvn -q "-Dmediflow.test.db.url=$testUrl" "-Dmediflow.test.db.username=root" `
            "-Dmediflow.test.db.password=" '-Dtest=!com.mediflow.agent.knowledge.QdrantKnowledgeIndexTest' test
        if ($LASTEXITCODE -ne 0) { throw 'MySQL integration tests failed.' }

        # Surefire leaves reports from earlier runs behind. A stale one would let a check below pass for
        # a class that never executed, so reports are selected by write time. Deleting them is avoided:
        # a machine whose recycle bin is unavailable refuses to delete at all.
        $freshReports = @(Get-ChildItem -LiteralPath $reportDirectory -Filter 'TEST-*.xml' -File |
            Where-Object { $_.LastWriteTimeUtc -ge $runStarted.AddSeconds(-2) })
        if ($freshReports.Count -lt 1) {
            throw 'No test report was produced by this run.'
        }
        $totalTests = 0
        $totalSkipped = 0
        foreach ($freshReport in $freshReports) {
            [xml]$parsed = Get-Content -LiteralPath $freshReport.FullName
            $totalTests += [int]$parsed.testsuite.tests
            $totalSkipped += [int]$parsed.testsuite.skipped
            if ([int]$parsed.testsuite.tests -gt 0 -and [int]$parsed.testsuite.skipped -gt 0) {
                $name = $freshReport.Name -replace '^TEST-|\.xml$', ''
                throw "$name reported $($parsed.testsuite.skipped) skipped test(s); this run is supposed to execute everything it selects."
            }
        }

        $integrationClasses = @(
            'com.mediflow.agent.agent.persistence.MySqlTaskStateStoreTest',
            'com.mediflow.agent.knowledge.MySqlKnowledgeImportTest',
            'com.mediflow.agent.knowledge.MySqlKnowledgeSearchTest'
        )
        foreach ($className in $integrationClasses) {
            $reportPath = Join-Path $reportDirectory "TEST-$className.xml"
            if (-not (Test-Path -LiteralPath $reportPath)) {
                throw "MySQL integration test report was not generated for $className."
            }
            $reportFile = Get-Item -LiteralPath $reportPath
            if ($reportFile.LastWriteTimeUtc -lt $runStarted.AddSeconds(-2)) {
                throw "The report for $className is left over from an earlier run; the tests did not execute."
            }
            [xml]$report = Get-Content -LiteralPath $reportPath
            if ([int]$report.testsuite.tests -lt 1 -or [int]$report.testsuite.skipped -gt 0) {
                throw "MySQL integration tests in $className did not execute; refusing a false-positive test run."
            }
        }
        Write-Host ("Executed $totalTests test(s) in $($freshReports.Count) class(es) with $totalSkipped skipped; " +
            'the MySQL integration classes all ran against the isolated server.')
        $succeeded = $true
    } finally { Pop-Location }
} finally {
    [Environment]::SetEnvironmentVariable('MEDIFLOW_TEST_DB_URL', $previousUrl, 'Process')
    [Environment]::SetEnvironmentVariable('MEDIFLOW_TEST_DB_USERNAME', $previousUser, 'Process')
    [Environment]::SetEnvironmentVariable('MEDIFLOW_TEST_DB_PASSWORD', $previousPassword, 'Process')
    foreach ($proxyName in $savedProxies.Keys) {
        [Environment]::SetEnvironmentVariable($proxyName, $savedProxies[$proxyName], 'Process')
    }
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

# Every throw above aborts the script, so reaching this point means the suite passed. Exit explicitly:
# the last native command is the server shutdown, whose exit code would otherwise become the script's.
exit 0
