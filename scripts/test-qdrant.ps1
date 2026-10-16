param(
    [string]$QdrantUrl = 'http://localhost:6333',
    [string]$Mvn = 'mvn'
)

# Verifies the vector store adapter against a real Qdrant server.
#
# The default test suite deliberately runs offline, so the adapter's tests are skipped unless a
# server is named. This script names one — after checking it actually answers, because a skipped test
# and a passing test look the same in a summary, and a missing container must not be mistaken for a
# verified one.

$ErrorActionPreference = 'Stop'
$workspaceRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if (-not (Get-Command $Mvn -ErrorAction SilentlyContinue)) {
    throw "Maven not found on PATH: $Mvn"
}
$QdrantUrl = $QdrantUrl.TrimEnd('/')

# Windows environment variable names are case-insensitive while the process environment can still
# hold both HTTP_PROXY and http_proxy; on such a machine child processes fail to start. Drop the
# lower-case spellings for this run and restore them afterwards.
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
    Write-Host "Checking $QdrantUrl ..."
    try {
        $probe = Invoke-WebRequest -Uri "$QdrantUrl/collections" -TimeoutSec 5 -UseBasicParsing
    } catch {
        throw ("Qdrant did not answer at $QdrantUrl. Start it first, for example:" + [Environment]::NewLine +
            "  docker compose up -d qdrant" + [Environment]::NewLine +
            "or point this script elsewhere with -QdrantUrl. Original error: " + $_.Exception.Message)
    }
    if ($probe.StatusCode -ne 200) {
        throw "Qdrant answered with HTTP $($probe.StatusCode); expected 200."
    }
    $version = 'unknown'
    try {
        $root = Invoke-WebRequest -Uri $QdrantUrl -TimeoutSec 5 -UseBasicParsing
        $version = ($root.Content | ConvertFrom-Json).version
    } catch {
        # A version banner is nice to have; the collections endpoint already proved the server is up.
    }
    Write-Host "Qdrant $version is reachable."

    Push-Location $workspaceRoot
    try {
        $reportDirectory = Join-Path $workspaceRoot 'target\surefire-reports'
        $runStarted = [DateTime]::UtcNow
        & mvn -q "-Dtest=QdrantKnowledgeIndexTest" "-Dmediflow.qdrant.url=$QdrantUrl" test
        if ($LASTEXITCODE -ne 0) { throw 'Vector store adapter tests failed.' }

        # Reports are matched by write time rather than by deleting older ones: Surefire leaves them
        # behind, and a leftover would let this check pass for a class that never ran. Deleting is
        # avoided on purpose, since a machine whose recycle bin is unavailable refuses to delete at all.
        $className = 'com.mediflow.agent.knowledge.QdrantKnowledgeIndexTest'
        $reportPath = Join-Path $reportDirectory "TEST-$className.xml"
        if (-not (Test-Path -LiteralPath $reportPath)) {
            throw "Vector store test report was not generated for $className."
        }
        $reportFile = Get-Item -LiteralPath $reportPath
        if ($reportFile.LastWriteTimeUtc -lt $runStarted.AddSeconds(-2)) {
            throw "The report for $className is left over from an earlier run; the tests did not execute."
        }
        [xml]$report = Get-Content -LiteralPath $reportPath
        if ([int]$report.testsuite.tests -lt 1 -or [int]$report.testsuite.skipped -gt 0) {
            throw 'Vector store tests did not execute; refusing a false-positive test run.'
        }
        Write-Host "Vector store tests executed: $($report.testsuite.tests) passed, 0 skipped."
    } finally { Pop-Location }
} finally {
    foreach ($proxyName in $savedProxies.Keys) {
        [Environment]::SetEnvironmentVariable($proxyName, $savedProxies[$proxyName], 'Process')
    }
}

Write-Host 'The adapter was verified against a real Qdrant server.'
Write-Host 'Each test used a throwaway collection and dropped it afterwards, so the application index is untouched.'

# Any failure above throws, so reaching this point means the tests passed. Exit explicitly rather than
# letting the last native command's status stand in for the script's.
exit 0
