param(
    [switch]$Rebuild,
    [string]$HostName = 'localhost',
    [int]$Port = 3306,
    [string]$Database = 'mediflow',
    [string]$User = 'mediflow',
    [string]$Password = 'mediflow',
    [string]$QdrantUrl = 'http://localhost:6333',
    [string]$Index = 'qdrant',
    [string]$Mvn = 'mvn'
)

# Controlled indexing command for hospital material (M3). The management interface arrives in M4.
#
# Runs as a batch job: it embeds the chunks that still need a vector, writes them to the vector store
# and exits, without starting a web server.
#
# -Rebuild throws the whole index away first and rebuilds it from MySQL. That is what a change of
# embedding model requires, because a collection is created for one model and one vector size, and
# vectors from two models are not comparable.

$ErrorActionPreference = 'Stop'
$workspaceRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if (-not (Get-Command $Mvn -ErrorAction SilentlyContinue)) {
    throw "Maven not found on PATH: $Mvn"
}
if ($Index -notin @('qdrant', 'memory', 'none')) {
    throw "Unknown index implementation: $Index (expected qdrant, memory or none)"
}
if ($Index -eq 'memory') {
    throw 'The memory index forgets everything when this command exits, so indexing into it is pointless.'
}
if ($Index -eq 'none') {
    throw 'The none index refuses writes by design; it exists so an unconfigured deployment says "cannot look it up".'
}
$QdrantUrl = $QdrantUrl.TrimEnd('/')

# Same case-insensitive duplicate-proxy defect the MySQL script has to work around: child processes
# fail to start when both HTTP_PROXY and http_proxy are present.
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

$settings = @{
    'MEDIFLOW_DB_URL' = "jdbc:mysql://${HostName}:${Port}/${Database}?useUnicode=true&characterEncoding=utf8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true"
    'MEDIFLOW_DB_USERNAME' = $User
    'MEDIFLOW_DB_PASSWORD' = $Password
    'MEDIFLOW_KNOWLEDGE_INDEX' = $Index
    'MEDIFLOW_QDRANT_URL' = $QdrantUrl
}
$savedSettings = @{}
foreach ($name in $settings.Keys) {
    $savedSettings[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
    [Environment]::SetEnvironmentVariable($name, $settings[$name], 'Process')
}

try {
    Write-Host "Checking $QdrantUrl ..."
    try {
        $probe = Invoke-WebRequest -Uri "$QdrantUrl/collections" -TimeoutSec 5 -UseBasicParsing
    } catch {
        throw ("Qdrant did not answer at $QdrantUrl. Start it first, for example:" + [Environment]::NewLine +
            "  docker compose up -d qdrant" + [Environment]::NewLine +
            "Original error: " + $_.Exception.Message)
    }
    if ($probe.StatusCode -ne 200) {
        throw "Qdrant answered with HTTP $($probe.StatusCode); expected 200."
    }

    $arguments = '--mediflow.knowledge.index-run=true --server.port=0'
    if ($Rebuild) { $arguments = '--mediflow.knowledge.index-rebuild=true ' + $arguments }

    Push-Location $workspaceRoot
    try {
        & $Mvn -B 'spring-boot:run' '-Dspring-boot.run.profiles=mysql' `
            "-Dspring-boot.run.arguments=$arguments"
        $exitCode = $LASTEXITCODE
    } finally { Pop-Location }
    if ($exitCode -ne 0) {
        throw "Knowledge indexing failed with exit code $exitCode. See the log above for the failing chunk."
    }
} finally {
    foreach ($name in $savedSettings.Keys) {
        [Environment]::SetEnvironmentVariable($name, $savedSettings[$name], 'Process')
    }
    foreach ($proxyName in $savedProxies.Keys) {
        [Environment]::SetEnvironmentVariable($proxyName, $savedProxies[$proxyName], 'Process')
    }
}

$mode = if ($Rebuild) { 'rebuilt from scratch' } else { 'updated' }
Write-Host "The knowledge index in '$Database' has been $mode."
Write-Host 'Only chunks that still need a vector are embedded, so re-running without -Rebuild is nearly a no-op.'
Write-Host 'MySQL stays the source of truth: the index can be dropped and rebuilt from it at any time.'
