param(
    [Parameter(Mandatory)]
    [ValidateSet('Status', 'Archive')]
    [string]$Action,
    [Parameter(Mandatory)]
    [string]$Database,
    [string]$DocNo = '',
    [string]$HostName = 'localhost',
    [int]$Port = 3306,
    [string]$User = 'mediflow',
    [string]$Password = 'mediflow',
    [string]$Mvn = 'mvn'
)

$ErrorActionPreference = 'Stop'
if ($Action -eq 'Archive' -and [string]::IsNullOrWhiteSpace($DocNo)) {
    throw 'Archive requires -DocNo.'
}
$settings = @{
    'MEDIFLOW_DB_URL' = "jdbc:mysql://${HostName}:${Port}/${Database}?useUnicode=true&characterEncoding=utf8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true"
    'MEDIFLOW_DB_USERNAME' = $User
    'MEDIFLOW_DB_PASSWORD' = $Password
    'MEDIFLOW_KNOWLEDGE_INDEX' = 'none'
    'MEDIFLOW_EMBEDDING_PROVIDER' = 'local'
    'MEDIFLOW_KNOWLEDGE_MAINTENANCE_RUN' = 'true'
    'MEDIFLOW_KNOWLEDGE_MAINTENANCE' = $Action.ToLowerInvariant()
    'MEDIFLOW_KNOWLEDGE_MAINTENANCE_DOC_NO' = $DocNo
}
$savedSettings = @{}
foreach ($name in $settings.Keys) {
    $savedSettings[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
    [Environment]::SetEnvironmentVariable($name, $settings[$name], 'Process')
}

Push-Location (Join-Path $PSScriptRoot '..')
try {
    & $Mvn -B 'spring-boot:run' '-Dspring-boot.run.profiles=mysql' '-Dspring-boot.run.arguments=--server.port=0 --spring.flyway.enabled=false'
    if ($LASTEXITCODE) {
        throw "Knowledge maintenance failed with exit code $LASTEXITCODE."
    }
} finally {
    Pop-Location
    foreach ($name in $savedSettings.Keys) {
        [Environment]::SetEnvironmentVariable($name, $savedSettings[$name], 'Process')
    }
}
