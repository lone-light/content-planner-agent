param(
    [string]$JarPath = "target/content-planner-agent-0.1.0.jar",
    [string]$OutputDirectory = "examples/live"
)
$ErrorActionPreference = "Stop"
$workspacePath = Split-Path -Parent $PSScriptRoot
$jarFile = (Resolve-Path -LiteralPath $JarPath).Path
$javaExecutable = Join-Path $env:JAVA_HOME "bin/java.exe"
$runId = "extended-" + [Guid]::NewGuid().ToString("N").Substring(0, 10)
$outputPath = [IO.Path]::GetFullPath((Join-Path $OutputDirectory "extended"))
$runtimePath = Join-Path $workspacePath (".runtime/" + $runId)
[IO.Directory]::CreateDirectory($runtimePath) | Out-Null
$applicationProcess = $null
$baseUrl = $null
$status = "FAILED"
$stage = "startup"

function Write-Artifact([string]$Name, $Value) {
    $path = Join-Path $outputPath $Name
    [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($path)) | Out-Null
    [IO.File]::WriteAllText($path, ($Value | ConvertTo-Json -Depth 70) + [Environment]::NewLine, [Text.UTF8Encoding]::new($false))
}

function Start-Scenario([string]$Name, [string]$CatalogPath, [int]$MaxSteps = 12) {
    $probe = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
    $probe.Start()
    $port = $probe.LocalEndpoint.Port
    $probe.Stop()
    $script:baseUrl = "http://127.0.0.1:$port"
    $arguments = @(
        "-jar", ('"' + $jarFile + '"'), "--server.port=$port",
        "--agent.model-mode=live", "--agent.simulation-enabled=true", "--agent.max-steps=$MaxSteps",
        "--agent.deepseek.base-url=https://api.deepseek.com", "--agent.deepseek.model=deepseek-flash"
    )
    if ($CatalogPath) { $arguments += ('"--agent.catalog-path=' + ([Uri]$CatalogPath).AbsoluteUri + '"') }
    $script:applicationProcess = Start-Process -FilePath $javaExecutable -ArgumentList $arguments -WorkingDirectory $workspacePath -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $runtimePath "$Name.stdout.log") -RedirectStandardError (Join-Path $runtimePath "$Name.stderr.log")
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
        if ($applicationProcess.HasExited) { throw "扩展验收 JAR 启动失败：$Name" }
        try {
            $health = Invoke-RestMethod "$baseUrl/api/health" -TimeoutSec 2
            if ($health.data.modelMode -eq "live" -and $health.data.simulationEnabled) { return }
        } catch { Start-Sleep -Milliseconds 300 }
    }
    throw "扩展验收 JAR 未就绪：$Name"
}

function Stop-Scenario {
    if ($null -ne $applicationProcess -and -not $applicationProcess.HasExited) {
        Stop-Process -InputObject $applicationProcess -Force
        $applicationProcess.WaitForExit(10000) | Out-Null
    }
}

function Record-Trace([string]$Name, [string]$RequestId) {
    $response = Invoke-WebRequest "$baseUrl/api/plans/$RequestId/trace" -TimeoutSec 30
    $text = if ($response.Content -is [byte[]]) { [Text.Encoding]::UTF8.GetString($response.Content) } else { [string]$response.Content }
    [IO.File]::WriteAllText((Join-Path $outputPath $Name), $text, [Text.UTF8Encoding]::new($false))
    $events = @($text -split '\r?\n' | Where-Object { $_.Trim() } | ForEach-Object { $_ | ConvertFrom-Json -Depth 70 })
    $actions = @($events | Where-Object { $_.type -eq "MODEL_ACTION" })
    if ($actions.Count -eq 0) { throw "扩展场景没有取得模型动作；已保留 Trace，请查看模型错误" }
    foreach ($action in $actions) {
        if ($action.detail.metadata.localEndpoint -or $action.detail.metadata.provider -ne "deepseek" -or -not $action.detail.metadata.liveTransport) { throw "扩展场景不是 DeepSeek 真实传输" }
    }
}

Write-Output "扩展验收材料目录：$outputPath"
try {
    $stage = "save-recovery"
    Start-Scenario "recovery" "" 5
    $request = [ordered]@{
        requestId = "$runId-recovery"
        topic = "字幕活动"
        budgetUnits = 20
        deadlineTime = [DateTimeOffset]::UtcNow.AddHours(2).ToString("o")
        simulate = @{failTool="SAVE_PLAN"; failures=2; failAfterInsert=$true}
    }
    Write-Artifact "save-resume/request.json" $request
    $failed = Invoke-RestMethod -Method Post "$baseUrl/api/plans" -ContentType "application/json; charset=utf-8" -Body ([Text.Encoding]::UTF8.GetBytes(($request | ConvertTo-Json -Depth 10))) -TimeoutSec 900
    Write-Artifact "save-resume/response-before-resume.json" $failed
    Record-Trace "save-resume/trace-before-resume.jsonl" $request.requestId
    if ($failed.data.status -ne "INCOMPLETE" -or $failed.data.saved -or $failed.data.spentUnits -ne 11 -or @($failed.data.steps | Where-Object { $_.observation.code -eq "SAVE_FAILED" }).Count -eq 0) { throw "保存失败后状态、回滚或成功产物保留不符合预期" }
    $resumed = Invoke-RestMethod -Method Post "$baseUrl/api/plans/$($request.requestId)/resume" -TimeoutSec 900
    Write-Artifact "save-resume/response.json" $resumed
    Record-Trace "save-resume/trace.jsonl" $request.requestId
    if ($resumed.data.status -ne "COMPLETED" -or -not $resumed.data.saved -or $resumed.data.spentUnits -ne 12 -or @($resumed.data.steps | Where-Object { $_.tool -eq "GENERATE_SCRIPT" }).Count -ne 1 -or @($resumed.data.steps | Where-Object { $_.tool -eq "SEARCH_ASSETS" }).Count -ne 1) { throw "真实模型续跑未完成或重复执行成功步骤" }
    Stop-Scenario

    $stage = "price-reversal"
    $catalog = Get-Content (Join-Path $workspacePath "data/tool-catalog.json") -Raw -Encoding UTF8 | ConvertFrom-Json
    Write-Artifact "price-reversal/catalog-before.json" $catalog
    $catalog.version = "live-price-reversal-v1"
    $catalog.generate.STANDARD.costUnits = 6
    $catalog.generate.LEAN.costUnits = 10
    Write-Artifact "price-reversal/catalog-after.json" $catalog
    Start-Scenario "price-reversal" (Join-Path $outputPath "price-reversal/catalog-after.json")
    $changedRequest = [ordered]@{requestId="$runId-prices"; topic="字幕活动"; budgetUnits=10; deadlineTime=[DateTimeOffset]::UtcNow.AddHours(2).ToString("o"); preferredMode="STANDARD"}
    Write-Artifact "price-reversal/request.json" $changedRequest
    $changed = Invoke-RestMethod -Method Post "$baseUrl/api/plans" -ContentType "application/json; charset=utf-8" -Body ([Text.Encoding]::UTF8.GetBytes(($changedRequest | ConvertTo-Json))) -TimeoutSec 900
    Write-Artifact "price-reversal/response.json" $changed
    Record-Trace "price-reversal/trace.jsonl" $changedRequest.requestId
    if ($changed.data.status -ne "COMPLETED" -or $changed.data.savedPlan.plan.planMode -ne "STANDARD" -or $changed.data.spentUnits -ne 8) { throw "报价反转后模型未根据新价格选择可行方案" }
    $status = "PASSED"
    Write-Output "扩展真实验收通过：保存失败续跑和费用表反转。"
} finally {
    Stop-Scenario
    Write-Artifact "summary.json" ([ordered]@{
        recordTime = [DateTimeOffset]::Now.ToString("o")
        source = "actual DeepSeek calls through packaged JAR; synthetic business tools"
        validationStatus = $status
        failedStage = $(if ($status -eq "PASSED") { $null } else { $stage })
        semanticReviewRequired = $true
        maxSteps = @{saveResume=5; priceReversal=12}
        jarSha256 = (Get-FileHash $jarFile -Algorithm SHA256).Hash
    })
}
