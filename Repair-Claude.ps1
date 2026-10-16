$ErrorActionPreference = 'Stop'

$npmCommand = Get-Command npm.cmd -ErrorAction Stop
$npmPrefix = (& $npmCommand.Source config get prefix | Select-Object -Last 1).Trim()
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($npmPrefix)) {
    throw 'Could not read the npm global prefix. Check that Node.js/npm are installed.'
}
$launcher = Join-Path $npmPrefix 'claude.cmd'

$userPath = [Environment]::GetEnvironmentVariable('Path', 'User')
$userPathParts = @($userPath -split ';' | Where-Object { $_ })
if (-not ($userPathParts | Where-Object { $_.TrimEnd('\') -ieq $npmPrefix.TrimEnd('\') })) {
    $updatedUserPath = (@($userPathParts) + $npmPrefix) -join ';'
    [Environment]::SetEnvironmentVariable('Path', $updatedUserPath, 'User')
}
if (-not (($env:Path -split ';') | Where-Object { $_.TrimEnd('\') -ieq $npmPrefix.TrimEnd('\') })) {
    $env:Path = "$npmPrefix;$env:Path"
}

$healthy = $false
if (Test-Path -LiteralPath $launcher) {
    try {
        & $launcher --version *> $null
        $healthy = ($LASTEXITCODE -eq 0)
    }
    catch {
        $healthy = $false
    }
}

if (-not $healthy) {
    Write-Host 'Claude Code is missing or damaged; reinstalling the latest npm package...'
    & $npmCommand.Source install --global '@anthropic-ai/claude-code@latest' --force --prefer-online --ignore-scripts=false --registry='https://registry.npmjs.org/'
    if ($LASTEXITCODE -ne 0) {
        throw 'Claude Code reinstall failed. Check npm registry/network access, then run this script again.'
    }
}

if (-not (Test-Path -LiteralPath $launcher)) {
    throw "npm did not create the expected launcher: $launcher"
}

$version = & $launcher --version
if ($LASTEXITCODE -ne 0) {
    throw 'Claude Code still fails after reinstall. Check the npm install output and Windows architecture.'
}
Write-Host "Claude Code is ready: $version"
