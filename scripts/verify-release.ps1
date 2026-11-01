param([string]$JarPath = "target/content-planner-agent-0.1.0.jar")
$ErrorActionPreference = "Stop"
$workspacePath = Split-Path -Parent $PSScriptRoot
$jarFile = (Resolve-Path -LiteralPath $JarPath).Path
$javaExecutable = Join-Path $env:JAVA_HOME "bin/java.exe"
$runtimePath = Join-Path $workspacePath ".runtime/live"
[IO.Directory]::CreateDirectory($runtimePath) | Out-Null
$probe = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
$probe.Start()
$port = $probe.LocalEndpoint.Port
$probe.Stop()
$baseUrl = "http://127.0.0.1:$port"
$applicationProcess = $null
$status = "FAILED"
Push-Location $workspacePath
try {
    $applicationProcess = Start-Process -FilePath $javaExecutable -ArgumentList @(
        "-jar", ('"' + $jarFile + '"'), "--server.port=$port",
        "--agent.model-mode=live", "--agent.simulation-enabled=true", "--agent.max-steps=12"
    ) -WorkingDirectory $workspacePath -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $runtimePath "application.stdout.log") -RedirectStandardError (Join-Path $runtimePath "application.stderr.log")
    $ready = $false
    for ($attempt = 0; $attempt -lt 100; $attempt++) {
        if ($applicationProcess.HasExited) { throw "真实模型验收服务启动失败" }
        try {
            $health = Invoke-RestMethod "$baseUrl/api/health" -TimeoutSec 2
            if ($health.data.modelMode -eq "live" -and $health.data.simulationEnabled) { $ready = $true; break }
        } catch { Start-Sleep -Milliseconds 300 }
    }
    if (-not $ready) { throw "真实模型验收服务未就绪" }
    & "$PSScriptRoot/verify-live.ps1" -BaseUrl $baseUrl
    Stop-Process -InputObject $applicationProcess -Force
    $applicationProcess.WaitForExit(10000) | Out-Null
    & "$PSScriptRoot/verify-live-extended.ps1" -JarPath $jarFile
    & "$PSScriptRoot/verify-packaged.ps1" -JarPath $jarFile
    $status = "PASSED"
} finally {
    if ($null -ne $applicationProcess -and -not $applicationProcess.HasExited) {
        Stop-Process -InputObject $applicationProcess -Force
        $applicationProcess.WaitForExit(10000) | Out-Null
    }
    $manifest = [ordered]@{
        recordTime = [DateTimeOffset]::Now.ToString("o")
        validationStatus = $status
        jarSha256 = (Get-FileHash -LiteralPath $jarFile -Algorithm SHA256).Hash
        stack = "Spring AI 1.1.8 / MyBatis-Plus 3.5.18 / MySQL 8.4.7"
        source = "actual packaged application, official DeepSeek service, synthetic business tools"
    }
    $manifestPath = Join-Path $workspacePath "examples/live/manifest.json"
    [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($manifestPath)) | Out-Null
    [IO.File]::WriteAllText($manifestPath, ($manifest | ConvertTo-Json -Depth 12), [Text.UTF8Encoding]::new($false))
    Pop-Location
}
if ($status -eq "PASSED") { & "$PSScriptRoot/review-evidence.ps1" }
