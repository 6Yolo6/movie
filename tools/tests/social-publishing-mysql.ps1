# Offline SQL regression only. No host ports, real credentials, persistent volumes or production services.
[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$docker = (Get-Command docker -ErrorAction Stop).Source
$repo = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
$publisher = Join-Path $repo 'social-publisher'
foreach ($file in @('publish-task.mjs', 'publish-task.mysql.integration.mjs', 'node_modules/mysql2/package.json')) {
    if (-not (Test-Path -LiteralPath (Join-Path $publisher $file))) { throw "Missing fixture dependency: $file" }
}
# Never pull images or use Compose; these must already be available locally.
foreach ($image in @('mysql:8.0', 'node:22-bookworm-slim')) {
    & $docker image inspect $image --format '{{.Id}}'
    if ($LASTEXITCODE -ne 0) { throw "Fixture image is not cached: $image" }
}
$suffix = [Guid]::NewGuid().ToString('N').Substring(0, 12)
$mysqlName = "gying-offline-publication-mysql-$suffix"
$runnerName = "gying-offline-publication-node-$suffix"
$label = 'com.gying.fixture=publication-mysql-offline'
try {
    $mysqlArgs = @('run', '--detach', '--rm', '--pull', 'never', '--name', $mysqlName,
        '--hostname', 'gying-publication-fixture', '--label', $label,
        '--network', 'none', '--read-only', '--user', '999:999', '--cap-drop', 'ALL',
        '--security-opt', 'no-new-privileges', '--memory', '768m', '--cpus', '1', '--pids-limit', '256',
        '--tmpfs', '/var/lib/mysql:rw,noexec,nosuid,size=512m,uid=999,gid=999',
        '--tmpfs', '/var/run/mysqld:rw,noexec,nosuid,size=16m,uid=999,gid=999',
        '--tmpfs', '/tmp:rw,noexec,nosuid,size=128m,mode=1777',
        '--env', 'MYSQL_ALLOW_EMPTY_PASSWORD=yes', '--env', 'MYSQL_INITDB_SKIP_TZINFO=1',
        '--env', 'MYSQL_DATABASE=gying_publication_fixture',
        'mysql:8.0', '--mysqlx=OFF', '--skip-log-bin', '--innodb-buffer-pool-size=64M')
    $id = & $docker @mysqlArgs
    if ($LASTEXITCODE -ne 0 -or $id -notmatch '^[0-9a-f]{64}$') { throw 'Could not create isolated MySQL fixture' }
    $ready = $false
    for ($attempt = 0; $attempt -lt 90; $attempt++) {
        $probe = & $docker exec $id mysqladmin --protocol=tcp --host=127.0.0.1 --user=root ping 2>&1
        if ($LASTEXITCODE -eq 0) { $ready = $true; break }
        Start-Sleep -Seconds 1
    }
    if (-not $ready) {
        Write-Output $probe
        throw 'Isolated MySQL did not become ready'
    }
    $nodeArgs = @('run', '--rm', '--pull', 'never', '--name', $runnerName, '--label', $label,
        '--network', "container:$id", '--read-only', '--user', '10001:10001', '--cap-drop', 'ALL',
        '--security-opt', 'no-new-privileges', '--memory', '256m', '--cpus', '1', '--pids-limit', '64',
        '--tmpfs', '/tmp:rw,noexec,nosuid,size=16m,mode=1777',
        '--env', 'PUBLICATION_MYSQL_FIXTURE=network-none-tmpfs', '--workdir', '/workspace',
        '--mount', "type=bind,source=$publisher/publish-task.mjs,target=/workspace/publish-task.mjs,readonly",
        '--mount', "type=bind,source=$publisher/publish-task.mysql.integration.mjs,target=/workspace/publish-task.mysql.integration.mjs,readonly",
        '--mount', "type=bind,source=$publisher/node_modules,target=/workspace/node_modules,readonly",
        '--entrypoint', 'node', 'node:22-bookworm-slim', 'publish-task.mysql.integration.mjs')
    & $docker @nodeArgs
    if ($LASTEXITCODE -ne 0) { throw 'Isolated MySQL publication regression failed' }
} finally {
    # Only stop exact names created by this invocation, also verified by the fixture label.
    # --rm removes these disposable containers; their databases exist only on tmpfs.
    foreach ($name in @($runnerName, $mysqlName)) {
        $owned = & $docker ps -aq --filter "name=^/$name$" --filter "label=$label"
        if ($LASTEXITCODE -eq 0 -and $owned -match '^[0-9a-f]{12,64}$') {
            & $docker stop --time 5 $owned | Out-Null
            if ($LASTEXITCODE -ne 0) { Write-Warning "Could not stop fixture $name" }
        }
    }
}
