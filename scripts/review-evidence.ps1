$ErrorActionPreference = "Stop"
$workspacePath = Split-Path -Parent $PSScriptRoot
Push-Location $workspacePath
function Assert-True($Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
}
function Read-Json([string]$Path) {
    return Get-Content -LiteralPath $Path -Raw -Encoding utf8 | ConvertFrom-Json -Depth 80
}
function Read-Trace([string]$Path) {
    return @(Get-Content -LiteralPath $Path -Encoding utf8 | Where-Object { $_.Trim() } | ForEach-Object { $_ | ConvertFrom-Json -Depth 80 })
}
function Next-Action($Events, $Sequence) {
    return $Events | Where-Object { $_.type -eq "MODEL_ACTION" -and $_.sequence -gt $Sequence } | Select-Object -First 1
}
try {
    $manifest = Read-Json "examples/live/manifest.json"
    $extended = Read-Json "examples/live/extended/summary.json"
    $main = Read-Json "examples/live/main/summary.json"
    $packaged = Read-Json "examples/packaged/result.json"
    $tests = Read-Json "examples/reports/test-results.json"
    $database = Read-Json "examples/reports/database-review.json"
    $idea = Read-Json "examples/reports/idea-inspection.json"
    $jarHash = (Get-FileHash "target/content-planner-agent-0.1.0.jar" -Algorithm SHA256).Hash
    Assert-True ($manifest.validationStatus -eq "PASSED" -and $extended.validationStatus -eq "PASSED" -and $main.validationStatus -eq "PASSED" -and $packaged.result -eq "PASSED") "验证材料未全部通过"
    Assert-True ($jarHash -eq $manifest.jarSha256 -and $jarHash -eq $extended.jarSha256 -and $jarHash -eq $packaged.jarSha256) "JAR 与运行材料哈希不一致"
    Assert-True ($tests.tests -gt 0 -and $tests.failures -eq 0 -and $tests.errors -eq 0 -and $tests.skipped -eq 0) "测试摘要未通过"
    Assert-True ($database.physicalForeignKeys -eq 0 -and $database.selectsPerGet -eq 2 -and $database.selectsPerReplay -eq 2 -and $database.updatesPerReplay -eq 1) "数据库关联或查询次数验证不符"
    foreach ($plan in $database.explain) {
        Assert-True ($plan.key -in @("PRIMARY", "uk_saved_request") -and $plan.estimatedRows -le 1) "实际 MP SQL 未命中预期索引"
    }
    Assert-True ($idea.complete -and $idea.errors -eq 0 -and $idea.warnings -eq 0 -and $idea.build.isSuccess) "IDEA 检查未完成或未通过"
    foreach ($source in $idea.sourceSha256.PSObject.Properties) {
        Assert-True ((Get-FileHash -LiteralPath $source.Name -Algorithm SHA256).Hash -eq $source.Value) "IDEA 检查后生产源文件已变化"
    }
    $responses = [Collections.Generic.List[object]]::new()
    $totalTokens = 0
    $modelResponses = 0
    $traceHashes = [ordered]@{}
    foreach ($file in Get-ChildItem @("examples/live/main", "examples/live/extended") -Recurse -Filter "trace.jsonl") {
        $events = @(Read-Trace $file.FullName)
        for ($index = 0; $index -lt $events.Count; $index++) {
            Assert-True ($events[$index].sequence -eq $index + 1) "Trace 序号不连续"
        }
        foreach ($event in @($events | Where-Object type -EQ "MODEL_ACTION")) {
            $metadata = $event.detail.metadata
            Assert-True ($metadata.adapter -eq "spring-ai" -and $metadata.provider -eq "deepseek" -and $metadata.liveTransport -and -not $metadata.localEndpoint) "存在非 Spring AI 官方传输的模型动作"
            Assert-True ($metadata.providerEndpoint -eq "https://api.deepseek.com/chat/completions" -and $metadata.responseId -and $metadata.returnedModel) "供应商响应标识不完整"
            $modelResponses++
            $totalTokens += $metadata.usage.total_tokens
        }
        $traceHashes[[IO.Path]::GetRelativePath($workspacePath, $file.FullName).Replace('\','/')] = (Get-FileHash $file.FullName -Algorithm SHA256).Hash
        $response = (Read-Json (Join-Path $file.DirectoryName "response.json")).data
        $sum = [decimal]0
        foreach ($step in $response.steps) { $sum += [decimal]$step.observation.chargedUnits }
        Assert-True ($sum -eq [decimal]$response.spentUnits) "费用合计与执行账本不一致"
        Assert-True ($sum -le [decimal]$response.budgetUnits) "费用超过预算"
        if ($response.saved) {
            Assert-True ($sum -eq [decimal]$response.savedPlan.totalCostUnits) "保存费用与账本不一致"
            Assert-True (([DateTimeOffset]$response.estimatedFinishTime) -le ([DateTimeOffset]$response.deadlineTime)) "保存方案超过截止时间"
            Assert-True ($response.message.Contains("费用 $sum")) "结果说明未反映实际费用"
        }
        $responses.Add($response)
    }
    $budgetEvents = @(Read-Trace "examples/live/main/budget-replan/trace.jsonl")
    $rejected = $budgetEvents | Where-Object { $_.type -eq "TOOL_OBSERVATION" -and $_.detail.tool -eq "ESTIMATE_PLAN" -and -not $_.detail.result.data.quote.feasible } | Select-Object -First 1
    $adjustment = Next-Action $budgetEvents $rejected.sequence
    Assert-True ($rejected -and $adjustment.detail.action.tool -eq "ESTIMATE_PLAN" -and $adjustment.detail.action.arguments.planMode -ne $rejected.detail.arguments.planMode) "没有观察到不可行报价后的模型改选"
    $failureEvents = @(Read-Trace "examples/live/main/tool-failure/trace.jsonl")
    $failure = $failureEvents | Where-Object { $_.type -eq "TOOL_OBSERVATION" -and $_.detail.tool -eq "GENERATE_SCRIPT" -and -not $_.detail.result.success } | Select-Object -First 1
    $retry = Next-Action $failureEvents $failure.sequence
    Assert-True ($failure -and $retry.detail.action.tool -eq "GENERATE_SCRIPT" -and $retry.detail.action.arguments.planId -eq $failure.detail.arguments.planId) "没有观察到工具失败后的模型重试"
    $normal = (Read-Json "examples/live/main/normal/response.json").data
    $duplicate = (Read-Json "examples/live/main/normal/response-duplicate.json").data
    Assert-True ($duplicate.replayed -and $duplicate.totalModelSteps -eq $normal.totalModelSteps -and $duplicate.spentUnits -eq $normal.spentUnits -and $duplicate.savedPlan.savedPlanId -eq $normal.savedPlan.savedPlanId) "重复提交改变执行或保存结果"
    $before = (Read-Json "examples/live/extended/save-resume/response-before-resume.json").data
    $after = (Read-Json "examples/live/extended/save-resume/response.json").data
    Assert-True (-not $before.saved -and $before.status -eq "INCOMPLETE" -and $before.spentUnits -eq 11 -and $after.saved -and $after.status -eq "COMPLETED" -and $after.spentUnits -eq 12) "保存回滚或续跑费用不正确"
    Assert-True (@($after.steps | Where-Object tool -EQ "SEARCH_ASSETS").Count -eq 1 -and @($after.steps | Where-Object tool -EQ "GENERATE_SCRIPT").Count -eq 1) "续跑重复执行成功步骤"
    $prices = (Read-Json "examples/live/extended/price-reversal/response.json").data
    Assert-True ($prices.savedPlan.plan.planMode -eq "STANDARD" -and $prices.spentUnits -eq 8) "价格反转后的方案不符合新费用"
    foreach ($name in @("missing-budget", "missing-deadline")) {
        $missing = (Read-Json "examples/live/main/$name/response.json").data
        Assert-True ($missing.status -eq "NEEDS_INPUT" -and $missing.totalModelSteps -eq 0 -and $missing.spentUnits -eq 0) "缺输入时发生模型调用或费用"
    }
    $review = [ordered]@{
        recordTime = [DateTimeOffset]::Now.ToString("o")
        result = "PASSED"
        source = "assertions over actual response files, trace events and current JAR"
        jarSha256 = $jarHash
        tests = $tests.tests
        realScenarios = $main.cases.Count + 2
        modelResponses = $modelResponses
        providerReportedTotalTokens = $totalTokens
        budgetObservationSequence = $rejected.sequence
        budgetNextActionSequence = $adjustment.sequence
        failureObservationSequence = $failure.sequence
        failureNextActionSequence = $retry.sequence
        checks = @("springAiOfficialTransport", "ledgerReconciliation", "savedConstraints", "budgetChangesAction", "toolFailureChangesAction", "duplicateDoesNotExecute", "saveRollbackAndResume", "priceChangeChangesPlan", "missingInputsDoNotExecute", "logicalRelations", "indexedQueries", "constantReadQueryCounts", "ideaInspectionMatchesSource", "currentJarMatchesArtifacts")
        traceSha256 = $traceHashes
    }
    [IO.File]::WriteAllText((Join-Path $workspacePath "examples/live/review.json"), ($review | ConvertTo-Json -Depth 20), [Text.UTF8Encoding]::new($false))
    [pscustomobject]$review | Select-Object result,tests,realScenarios,modelResponses,providerReportedTotalTokens | ConvertTo-Json
} finally { Pop-Location }
