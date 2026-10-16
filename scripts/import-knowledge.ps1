param(
    [string]$SourceDir = 'knowledge',
    [string]$HostName = 'localhost',
    [int]$Port = 3306,
    [string]$Database = 'mediflow',
    [string]$User = 'mediflow',
    [string]$Password = 'mediflow',
    [string]$Mvn = 'mvn'
)

# Controlled import command for hospital material (M3). The management interface arrives in M4.
# Runs the importer as a batch job: it writes documents and chunks to MySQL and exits, without
# starting a web server and without touching any embedding model or vector store.

$ErrorActionPreference = 'Stop'
$workspaceRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$sourcePath = [System.IO.Path]::GetFullPath((Join-Path $workspaceRoot $SourceDir))
if (-not (Test-Path -LiteralPath $sourcePath -PathType Container)) {
    throw "Material directory not found: $sourcePath"
}
if (-not (Get-Command $Mvn -ErrorAction SilentlyContinue)) {
    throw "Maven not found on PATH: $Mvn"
}

$settings = @{
    'MEDIFLOW_DB_URL' = "jdbc:mysql://${HostName}:${Port}/${Database}?useUnicode=true&characterEncoding=utf8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true"
    'MEDIFLOW_DB_USERNAME' = $User
    'MEDIFLOW_DB_PASSWORD' = $Password
    'MEDIFLOW_KNOWLEDGE_SOURCE_DIR' = $sourcePath
}
$saved = @{}
foreach ($name in $settings.Keys) {
    $saved[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
    [Environment]::SetEnvironmentVariable($name, $settings[$name], 'Process')
}

try {
    Push-Location $workspaceRoot
    try {
        # --server.port=0 keeps this batch run from colliding with a backend that is already serving.
        & $Mvn -B 'spring-boot:run' '-Dspring-boot.run.profiles=mysql' `
            '-Dspring-boot.run.arguments=--mediflow.knowledge.import=true --server.port=0'
        $exitCode = $LASTEXITCODE
    } finally { Pop-Location }
    if ($exitCode -ne 0) {
        throw "Knowledge import failed with exit code $exitCode. See the log above for the per-file reason."
    }
} finally {
    foreach ($name in $saved.Keys) {
        [Environment]::SetEnvironmentVariable($name, $saved[$name], 'Process')
    }
}

Write-Host "Material under '$SourcePath' has been imported into '$Database'."
Write-Host 'Unchanged files are skipped, so re-running this command is a no-op.'
Write-Host 'Chunks are stored as PENDING; indexing happens in the next step (M3 block 2).'
