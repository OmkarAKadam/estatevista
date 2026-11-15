<#
.SYNOPSIS
    Provisions an isolated, throwaway PostgreSQL cluster for the backend
    integration tests and optionally runs the complete Maven verification.

.DESCRIPTION
    Testcontainers is not used because Docker is not available in the current
    development environment. This script keeps the external PostgreSQL test
    strategy but makes it repeatable: it initialises a dedicated cluster under
    backend/.test-postgres (never the installed PostgreSQL service), creates the
    estatevista_test database, runs `mvn -B clean verify` with the required
    TEST_DB_* variables, and stops the cluster afterwards.

    The cluster uses trust authentication and listens on localhost only. It is
    disposable and must never contain development or production data.

.PARAMETER Action
    verify  Start the cluster, run the full Maven verification, stop the cluster.
    start   Start the cluster and create the test database, then leave it running.
    stop    Stop the running cluster.
    status  Report whether the cluster is running and the test database exists.

.PARAMETER Port
    Port for the isolated cluster. Default 55433 avoids the installed service
    on 5432.

.PARAMETER PostgresBin
    PostgreSQL bin directory. When omitted, the newest installation under
    C:\Program Files\PostgreSQL is used.

.PARAMETER ClusterDir
    Cluster data directory. Defaults to backend/.test-postgres. The directory is
    git-ignored and deliberately outside target/ so `mvn clean` cannot delete a
    running cluster.

.PARAMETER Database
    Test database name. Defaults to estatevista_test.

.PARAMETER MavenCommand
    Maven executable. Defaults to mvn.

.EXAMPLE
    ./scripts/postgres-test-cluster.ps1 -Action verify

.EXAMPLE
    ./scripts/postgres-test-cluster.ps1 -Action start
    $env:TEST_DB_URL = "jdbc:postgresql://localhost:55433/estatevista_test"
    $env:TEST_DB_USERNAME = "postgres"
    $env:TEST_DB_PASSWORD = ""
    mvn -B clean verify
    ./scripts/postgres-test-cluster.ps1 -Action stop
#>
[CmdletBinding()]
param(
    [ValidateSet('verify', 'start', 'stop', 'status')]
    [string]$Action = 'verify',

    [int]$Port = 55433,

    [string]$PostgresBin,

    [string]$ClusterDir,

    [string]$Database = 'estatevista_test',

    [string]$MavenCommand = 'mvn'
)

$ErrorActionPreference = 'Stop'

function Resolve-PostgresBin {
    param([string]$Requested)

    if ($Requested) {
        if (-not (Test-Path (Join-Path $Requested 'pg_ctl.exe'))) {
            throw "PostgreSQL bin directory does not contain pg_ctl.exe: $Requested"
        }
        return (Resolve-Path $Requested).Path
    }

    $installRoot = 'C:\Program Files\PostgreSQL'
    if (Test-Path $installRoot) {
        $candidate = Get-ChildItem $installRoot -Directory |
            Sort-Object { [int]($_.Name -replace '\D', '') } -Descending |
            ForEach-Object { Join-Path $_.FullName 'bin' } |
            Where-Object { Test-Path (Join-Path $_ 'pg_ctl.exe') } |
            Select-Object -First 1
        if ($candidate) {
            return $candidate
        }
    }

    $onPath = Get-Command pg_ctl.exe -ErrorAction SilentlyContinue
    if ($onPath) {
        return (Split-Path $onPath.Source -Parent)
    }

    throw 'PostgreSQL bin directory not found. Install PostgreSQL 18 or pass -PostgresBin.'
}

function Invoke-Pg {
    param(
        [string]$Tool,
        [string[]]$Arguments
    )

    & (Join-Path $script:Bin $Tool) @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "$Tool failed with exit code $LASTEXITCODE. Arguments: $($Arguments -join ' ')"
    }
}

function Test-ClusterRunning {
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'SilentlyContinue'
    try {
        & (Join-Path $script:Bin 'pg_ctl.exe') -D $script:ClusterDir status *> $null
        return ($LASTEXITCODE -eq 0)
    }
    finally {
        $ErrorActionPreference = $previous
    }
}

function Start-Cluster {
    if (Test-ClusterRunning) {
        Write-Host "PostgreSQL test cluster already running on port $Port."
        return
    }

    New-Item -ItemType Directory -Force -Path $script:ClusterDir | Out-Null
    $versionFile = Join-Path $script:ClusterDir 'PG_VERSION'

    if (-not (Test-Path $versionFile)) {
        Write-Host "Initialising throwaway PostgreSQL cluster at $script:ClusterDir ..."
        Invoke-Pg 'initdb.exe' @('-D', $script:ClusterDir, '-U', 'postgres', '-A', 'trust', '-E', 'UTF8', '--no-locale')
        Add-Content -Path (Join-Path $script:ClusterDir 'postgresql.conf') -Value @(
            '',
            '# EstateVista test cluster overrides',
            "port = $Port",
            "listen_addresses = 'localhost'",
            'max_connections = 50'
        )
    }

    $logFile = Join-Path $script:ClusterDir '..\test-postgres.log'
    Write-Host "Starting PostgreSQL test cluster on localhost:$Port ..."
    Invoke-Pg 'pg_ctl.exe' @('-D', $script:ClusterDir, '-l', $logFile, '-w', 'start')

    $ready = Join-Path $script:Bin 'pg_isready.exe'
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'SilentlyContinue'
    try {
        for ($attempt = 1; $attempt -le 20; $attempt++) {
            & $ready -h localhost -p $Port -U postgres *> $null
            if ($LASTEXITCODE -eq 0) {
                break
            }
            Start-Sleep -Milliseconds 250
        }
        if ($LASTEXITCODE -ne 0) {
            throw "PostgreSQL test cluster did not become ready on localhost:$Port. See $logFile."
        }

        $exists = & (Join-Path $script:Bin 'psql.exe') -h localhost -p $Port -U postgres -d postgres -tAc `
            "select 1 from pg_database where datname = '$Database'"
        if ($LASTEXITCODE -ne 0) {
            throw "Could not query the test cluster on localhost:$Port."
        }
    }
    finally {
        $ErrorActionPreference = $previous
    }
    if (($exists | Out-String).Trim() -ne '1') {
        Write-Host "Creating database $Database ..."
        Invoke-Pg 'createdb.exe' @('-h', 'localhost', '-p', "$Port", '-U', 'postgres', $Database)
    }
}

function Stop-Cluster {
    if (-not (Test-ClusterRunning)) {
        Write-Host 'PostgreSQL test cluster is not running.'
        return
    }
    Write-Host 'Stopping PostgreSQL test cluster ...'
    Invoke-Pg 'pg_ctl.exe' @('-D', $script:ClusterDir, '-m', 'fast', '-w', 'stop')
}

$script:Bin = Resolve-PostgresBin -Requested $PostgresBin
$script:ClusterDir = if ($ClusterDir) {
    [System.IO.Path]::GetFullPath($ClusterDir)
} else {
    [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\.test-postgres'))
}

$connectionUrl = "jdbc:postgresql://localhost:$Port/$Database"

switch ($Action) {
    'start' {
        Start-Cluster
        Write-Host ''
        Write-Host "Test cluster ready: $connectionUrl"
        Write-Host "  TEST_DB_URL=$connectionUrl"
        Write-Host '  TEST_DB_USERNAME=postgres'
        Write-Host '  TEST_DB_PASSWORD='
    }
    'stop' {
        Stop-Cluster
    }
    'status' {
        if (Test-ClusterRunning) {
            Write-Host "PostgreSQL test cluster is running on localhost:$Port."
        } else {
            Write-Host 'PostgreSQL test cluster is not running.'
        }
    }
    'verify' {
        $exitCode = 1
        try {
            Start-Cluster
            $env:TEST_DB_URL = $connectionUrl
            $env:TEST_DB_USERNAME = 'postgres'
            $env:TEST_DB_PASSWORD = ''

            Write-Host ''
            Write-Host "Running full verification against $connectionUrl ..."
            & $MavenCommand -B clean verify
            $exitCode = $LASTEXITCODE
        }
        finally {
            Stop-Cluster
        }
        if ($exitCode -ne 0) {
            throw "Maven verification failed with exit code $exitCode."
        }
        Write-Host ''
        Write-Host 'Full verification passed.'
    }
}
