param([string]$BaseUrl = "http://127.0.0.1:8081", [string]$OutputDirectory = "examples/live")
$ErrorActionPreference = "Stop"
$BaseUrl = $BaseUrl.TrimEnd('/')
$health = Invoke-RestMethod "$BaseUrl/api/health" -TimeoutSec 10
if ($health.data.modelMode -ne "live") { throw "服务不是 live 模式；不能将 Mock 结果作为真实验证" }
if (-not $health.data.simulationEnabled) { throw "请启用 AGENT_SIMULATION_ENABLED 后重启，以验证工具失败路径" }
$runPrefix = "live-" + [DateTimeOffset]::UtcNow.ToString("yyyyMMddHHmmss") + "-" + [Guid]::NewGuid().ToString("N").Substring(0, 6)
$OutputDirectory = Join-Path $OutputDirectory "main"
$deadline = [DateTimeOffset]::UtcNow.AddHours(2).ToString("o")
$results = [Collections.Generic.List[object]]::new()
$validationStatus = "FAILED"
$currentCase = "initialization"

function Write-JsonFile([string]$Directory, [string]$Name, $Value) {
    [IO.Directory]::CreateDirectory($Directory) | Out-Null
    [IO.File]::WriteAllText((Join-Path $Directory $Name), ($Value | ConvertTo-Json -Depth 70) + [Environment]::NewLine, [Text.UTF8Encoding]::new($false))
}

function Invoke-RecordedPlan([string]$Name, [int]$Budget, $Simulation = $null) {
    $script:currentCase = $Name
    $directory = Join-Path $OutputDirectory $Name
    $inputBody = [ordered]@{requestId="$runPrefix-$Name"; topic="字幕活动"; budgetUnits=$Budget; deadlineTime=$deadline; preferredMode="STANDARD"}
    if ($null -ne $Simulation) { $inputBody.simulate = $Simulation }
    Write-JsonFile $directory "request.json" $inputBody
    $bytes = [Text.Encoding]::UTF8.GetBytes(($inputBody | ConvertTo-Json -Depth 10))
    $response = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/plans" -ContentType "application/json; charset=utf-8" -Body $bytes -TimeoutSec 900
    Write-JsonFile $directory "response.json" $response
    $trace = Invoke-WebRequest "$BaseUrl/api/plans/$($inputBody.requestId)/trace" -TimeoutSec 30
    $traceText = if ($trace.Content -is [byte[]]) { [Text.Encoding]::UTF8.GetString($trace.Content) } else { [string]$trace.Content }
    [IO.File]::WriteAllText((Join-Path $directory "trace.jsonl"), $traceText, [Text.UTF8Encoding]::new($false))
    if ($response.data.modelMode -ne "live") { throw "$Name 返回了非 live 结果" }
    $events = @($traceText -split '\r?\n' | Where-Object { $_.Trim() } | ForEach-Object { $_ | ConvertFrom-Json -Depth 70 })
    $actions = @($events | Where-Object { $_.type -eq "MODEL_ACTION" })
    if ($actions.Count -eq 0) { throw "$Name 未取得真实传输的模型动作；请查看实际错误，不能虚报成功" }
    foreach ($action in $actions) {
        $metadata = $action.detail.metadata
        if ($metadata.provider -ne "deepseek" -or -not $metadata.liveTransport -or $metadata.localEndpoint -or [string]::IsNullOrWhiteSpace($metadata.returnedModel) -or [string]::IsNullOrWhiteSpace($metadata.responseId)) { throw "$Name 出现离线/本地协议替身动作或缺失模型响应标识，不能作为真实模型证据" }
    }
    $results.Add([ordered]@{case=$Name; requestId=$inputBody.requestId; status=$response.data.status; spentUnits=$response.data.spentUnits; saved=$response.data.saved; actionCount=$actions.Count})
    return $response.data
}

Write-Output "本次验收材料目录：$OutputDirectory"
try {
    $normal = Invoke-RecordedPlan "normal" 20
    if ($normal.status -ne "COMPLETED" -or -not $normal.saved -or [decimal]$normal.spentUnits -gt 20) { throw "正常路径未通过" }
    $budget = Invoke-RecordedPlan "budget-replan" 10
    if ($budget.status -ne "COMPLETED" -or -not $budget.saved -or [decimal]$budget.spentUnits -gt 10) { throw "预算调整路径未通过" }
    $rejectedQuotes = @($budget.quotes | Where-Object { -not $_.feasible })
    if ($rejectedQuotes.Count -eq 0) { throw "未观察到不可行报价后的调整，需要检查真实 Trace 并补充决策验证" }
    $failure = Invoke-RecordedPlan "tool-failure" 10 @{failTool="GENERATE_SCRIPT"; failures=1; failAfterInsert=$false}
    if (@($failure.steps | Where-Object { -not $_.observation.success }).Count -eq 0) { throw "未观察到工具失败" }
    if ($failure.status -notin @("COMPLETED", "INCOMPLETE")) { throw "工具失败后的终态不明确" }
    $impossible = Invoke-RecordedPlan "impossible-budget" 5
    if ($impossible.status -ne "INCOMPLETE" -or $impossible.saved -or [decimal]$impossible.spentUnits -gt 5 -or @($impossible.quotes | Where-Object { $_.budgetGapUnits -gt 0 }).Count -eq 0) { throw "无可行预算时未明确停止或未提供缺口" }
    $currentCase = "duplicate"
    $duplicateInput = Get-Content -LiteralPath (Join-Path $OutputDirectory "normal/request.json") -Raw -Encoding utf8
    $duplicate = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/plans" -ContentType "application/json; charset=utf-8" -Body ([Text.Encoding]::UTF8.GetBytes($duplicateInput)) -TimeoutSec 30
    Write-JsonFile (Join-Path $OutputDirectory "normal") "response-duplicate.json" $duplicate
    if (-not $duplicate.data.replayed -or $duplicate.data.totalModelSteps -ne $normal.totalModelSteps -or $duplicate.data.spentUnits -ne $normal.spentUnits -or $duplicate.data.savedPlan.savedPlanId -ne $normal.savedPlan.savedPlanId -or $duplicate.data.steps.Count -ne $normal.steps.Count) { throw "重复执行出现新增模型步骤、费用或保存记录" }
    $replayTrace = Invoke-WebRequest "$BaseUrl/api/plans/$($normal.requestId)/trace" -TimeoutSec 30
    $replayTraceText = if ($replayTrace.Content -is [byte[]]) { [Text.Encoding]::UTF8.GetString($replayTrace.Content) } else { [string]$replayTrace.Content }
    [IO.File]::WriteAllText((Join-Path (Join-Path $OutputDirectory "normal") "trace-after-duplicate.jsonl"), $replayTraceText, [Text.UTF8Encoding]::new($false))
    $results.Add([ordered]@{case="duplicate"; requestId=$normal.requestId; status=$duplicate.data.status; replayed=$duplicate.data.replayed; actionCount=0})
    foreach ($missingField in @("budgetUnits", "deadlineTime")) {
        $currentCase = if ($missingField -eq "budgetUnits") { "missing-budget" } else { "missing-deadline" }
        $missingDirectory = Join-Path $OutputDirectory $currentCase
        $missing = [ordered]@{requestId="$runPrefix-$currentCase"; topic="字幕活动"; budgetUnits=10; deadlineTime=$deadline}
        $missing.Remove($missingField)
        Write-JsonFile $missingDirectory "request.json" $missing
        $missingResponse = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/plans" -ContentType "application/json; charset=utf-8" -Body ([Text.Encoding]::UTF8.GetBytes(($missing | ConvertTo-Json))) -TimeoutSec 30
        Write-JsonFile $missingDirectory "response.json" $missingResponse
        if ($missingResponse.data.status -ne "NEEDS_INPUT" -or $missingResponse.data.totalModelSteps -ne 0 -or $missingResponse.data.spentUnits -ne 0 -or $missingField -notin $missingResponse.data.missingFields) { throw "$currentCase 路径未通过" }
        $missingTrace = Invoke-WebRequest "$BaseUrl/api/plans/$($missing.requestId)/trace" -TimeoutSec 10
        $missingTraceText = if ($missingTrace.Content -is [byte[]]) { [Text.Encoding]::UTF8.GetString($missingTrace.Content) } else { [string]$missingTrace.Content }
        [IO.File]::WriteAllText((Join-Path $missingDirectory "trace.jsonl"), $missingTraceText, [Text.UTF8Encoding]::new($false))
        $results.Add([ordered]@{case=$currentCase; requestId=$missing.requestId; status=$missingResponse.data.status; spentUnits=0; saved=$false; actionCount=0})
    }
    $validationStatus = "PASSED"
    Write-Output "真实传输验收完成；请继续逐步核对 Trace、引用和费用，并完成报价变化对照与交卷信息。"
} finally {
    Write-JsonFile $OutputDirectory "summary.json" ([ordered]@{
        recordTime = [DateTimeOffset]::Now.ToString("o")
        modelMode = "live"
        source = "actual HTTP requests; review trace for semantic decision quality"
        validationStatus = $validationStatus
        failedStage = $(if ($validationStatus -eq "PASSED") { $null } else { $currentCase })
        semanticReviewRequired = $true
        cases = @($results)
    })
}
