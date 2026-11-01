param([string]$JarPath = "target/content-planner-agent-0.1.0.jar", [string]$OutputPath = "examples/packaged/result.json")
$ErrorActionPreference = "Stop"
$workspacePath = Split-Path -Parent $PSScriptRoot
$jarFile = (Resolve-Path -LiteralPath $JarPath).Path
$javaExecutable = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME "bin/java.exe" } else { (Get-Command java).Source }
if (-not (Test-Path -LiteralPath $javaExecutable)) { throw "Java 不存在：$javaExecutable" }
$runDirectory = Join-Path $workspacePath (".runtime/packaged-smoke-" + [Guid]::NewGuid().ToString("N"))
[IO.Directory]::CreateDirectory($runDirectory) | Out-Null
$portProbe = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
$portProbe.Start()
$port = $portProbe.LocalEndpoint.Port
$portProbe.Stop()
$baseUrl = "http://127.0.0.1:$port"
function Start-TestApplication([string]$LogPrefix) {
    return Start-Process -FilePath $javaExecutable -ArgumentList @("-jar", "`"$jarFile`"", "--server.port=$port", "--agent.max-steps=4", "--agent.simulation-enabled=false", "--agent.model-mode=mock") -WorkingDirectory $workspacePath -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $runDirectory "$LogPrefix.stdout.log") -RedirectStandardError (Join-Path $runDirectory "$LogPrefix.stderr.log")
}

function Wait-TestApplication($ApplicationProcess) {
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
        if ($ApplicationProcess.HasExited) { throw "JAR 启动失败，请查看 $runDirectory 下日志" }
        try {
            $health = Invoke-RestMethod "$baseUrl/api/health" -TimeoutSec 2
            if ($health.data.status -eq "UP" -and $health.data.modelMode -eq "mock") { return $health }
        } catch { Start-Sleep -Milliseconds 300 }
    }
    throw "等待 JAR 启动超时：$baseUrl"
}

function Stop-TestApplication($ApplicationProcess) {
    if ($null -ne $ApplicationProcess -and -not $ApplicationProcess.HasExited) {
        Stop-Process -InputObject $ApplicationProcess -Force
        $ApplicationProcess.WaitForExit(10000) | Out-Null
    }
}

$firstProcess = $null
$secondProcess = $null
try {
    $firstProcess = Start-TestApplication "first"
    $health = Wait-TestApplication $firstProcess
    $requestId = "packaged-" + [Guid]::NewGuid().ToString("N")
    $inputBody = [ordered]@{requestId=$requestId; topic="字幕活动"; budgetUnits=20; deadlineTime="2030-01-01T12:00:00Z"}
    $bytes = [Text.Encoding]::UTF8.GetBytes(($inputBody | ConvertTo-Json))
    $first = Invoke-RestMethod -Method Post -Uri "$baseUrl/api/plans" -ContentType "application/json; charset=utf-8" -Body $bytes -TimeoutSec 30
    [IO.File]::WriteAllText((Join-Path $runDirectory 'before-restart.json'), ($first | ConvertTo-Json -Depth 70), [Text.UTF8Encoding]::new($false))
    if ($first.data.status -ne "INCOMPLETE" -or $first.data.reasonCode -ne "STEP_LIMIT" -or -not $first.data.saved -or $first.data.totalModelSteps -ne 4) { throw ".env 步数限制或真实保存状态不符合预期" }
    Stop-TestApplication $firstProcess
    $secondProcess = Start-TestApplication "second"
    Wait-TestApplication $secondProcess | Out-Null
    $recovered = Invoke-RestMethod "$baseUrl/api/plans/$requestId" -TimeoutSec 10
    [IO.File]::WriteAllText((Join-Path $runDirectory 'after-restart.json'), ($recovered | ConvertTo-Json -Depth 70), [Text.UTF8Encoding]::new($false))
    if (-not $recovered.data.saved -or $recovered.data.spentUnits -ne 12) { throw "JAR 进程重启后未恢复持久化状态" }
    $resumed = Invoke-RestMethod -Method Post "$baseUrl/api/plans/$requestId/resume" -TimeoutSec 30
    if ($resumed.data.status -ne "COMPLETED" -or $resumed.data.spentUnits -ne 12 -or $resumed.data.steps.Count -ne $first.data.steps.Count) { throw "恢复时重复执行、重复计费或未合法完成" }
    $duplicate = Invoke-RestMethod -Method Post -Uri "$baseUrl/api/plans" -ContentType "application/json; charset=utf-8" -Body $bytes -TimeoutSec 30
    if (-not $duplicate.data.replayed -or $duplicate.data.totalModelSteps -ne $resumed.data.totalModelSteps) { throw "JAR 重复请求未幂等重放" }
    $trace = Invoke-WebRequest "$baseUrl/api/plans/$requestId/trace" -TimeoutSec 10
    $traceText = if ($trace.Content -is [byte[]]) { [Text.Encoding]::UTF8.GetString($trace.Content) } else { [string]$trace.Content }
    if ($traceText -notmatch '先检索主题相关素材') { throw "Trace 中文编码或历史恢复不正确" }
    $artifact = [ordered]@{recordTime=[DateTimeOffset]::Now.ToString("o"); source="packaged executable JAR, two separate Java processes, MySQL and MyBatis-Plus"; modelMode="mock"; realModelDecisionVerified=$false; jarSha256=(Get-FileHash -LiteralPath $jarFile -Algorithm SHA256).Hash; maxSteps=4; request=$inputBody; health=$health; beforeRestart=$first; recovered=$recovered; resumed=$resumed; duplicate=$duplicate; result="PASSED"}
    $outputFile = [IO.Path]::GetFullPath($OutputPath)
    [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($outputFile)) | Out-Null
    [IO.File]::WriteAllText($outputFile, ($artifact | ConvertTo-Json -Depth 70) + [Environment]::NewLine, [Text.UTF8Encoding]::new($false))
    [IO.File]::WriteAllText((Join-Path ([IO.Path]::GetDirectoryName($outputFile)) "trace.jsonl"), $traceText, [Text.UTF8Encoding]::new($false))
    Write-Output "PACKAGED SMOKE PASSED：独立 JAR、MySQL、HTTP、进程重启、续跑和幂等已验证"
} finally {
    Stop-TestApplication $firstProcess
    Stop-TestApplication $secondProcess
}
