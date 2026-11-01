param([string]$ReportsDirectory = "target/surefire-reports", [string]$OutputPath = "examples/reports/test-results.json")
$ErrorActionPreference = "Stop"
$reports = @(Get-ChildItem -LiteralPath $ReportsDirectory -Filter "TEST-*.xml" -File)
if ($reports.Count -eq 0) { throw "没有发现 Surefire 实际结果" }
$suites = foreach ($report in $reports) {
    [xml]$document = Get-Content -LiteralPath $report.FullName -Raw -Encoding utf8
    $suite = $document.testsuite
    [pscustomobject][ordered]@{
        name = [string]$suite.name
        tests = [int]$suite.tests
        failures = [int]$suite.failures
        errors = [int]$suite.errors
        skipped = [int]$suite.skipped
        seconds = [double]::Parse([string]$suite.time, [Globalization.CultureInfo]::InvariantCulture)
        cases = @($suite.testcase | ForEach-Object { [string]$_.name })
    }
}
$result = [ordered]@{
    recordTime = [DateTimeOffset]::Now.ToString("o")
    source = "Maven test report XML; environment properties intentionally excluded"
    modelMode = "mock/local-protocol-fixtures"
    realModelDecisionVerified = $false
    tests = [int](($suites | Measure-Object -Property tests -Sum).Sum)
    failures = [int](($suites | Measure-Object -Property failures -Sum).Sum)
    errors = [int](($suites | Measure-Object -Property errors -Sum).Sum)
    skipped = [int](($suites | Measure-Object -Property skipped -Sum).Sum)
    suites = @($suites)
}
if ($result.tests -le 0) { throw "未读取到有效测试计数，拒绝导出空的成功摘要" }
$targetPath = [IO.Path]::GetFullPath($OutputPath)
[IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($targetPath)) | Out-Null
[IO.File]::WriteAllText($targetPath, ($result | ConvertTo-Json -Depth 12) + [Environment]::NewLine, [Text.UTF8Encoding]::new($false))
Write-Output "已导出 $($result.tests) 项实际测试结果：$targetPath"
if ($result.failures -gt 0 -or $result.errors -gt 0) { exit 1 }
