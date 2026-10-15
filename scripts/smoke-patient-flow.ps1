param([string]$BaseUrl = 'http://localhost:8080')

# Manual smoke check against the configured model; this can incur model API usage.
# Only synthetic scenarios are sent. No appointments are created by this script.
$ErrorActionPreference = 'Stop'
$today = [TimeZoneInfo]::ConvertTimeBySystemTimeZoneId([DateTime]::UtcNow, 'China Standard Time').Date
$tomorrow = $today.AddDays(1)
$dayAfter = $today.AddDays(2)

function New-DemoTask {
    $created = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/v1/tasks" `
        -ContentType 'application/json' -Body '{"title":"患者导诊回归验收"}'
    return $created.taskId
}

function Send-DemoMessage([string]$TaskId, [string]$Message) {
    $body = [System.Text.Encoding]::UTF8.GetBytes((@{ message = $Message } | ConvertTo-Json -Compress))
    $result = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/v1/tasks/$TaskId/messages" `
        -ContentType 'application/json; charset=utf-8' -Body $body
    if ($result.message -match 'yyyy-MM-dd|yyyy-mm-dd|MORNING|AFTERNOON|EVENING|模型决策失败') {
        throw "Patient-facing response contains internal terminology: $($result.message)"
    }
    [PSCustomObject]@{ user = $Message; status = $result.status; reply = $result.message } |
        ConvertTo-Json -Compress | Write-Host
    return $result
}

$symptomTask = New-DemoTask
$question = Send-DemoMessage $symptomTask '我最近老是咳嗽，已经一周了'
if ($question.status -ne 'NEED_MORE_INFO' -or $question.message -notmatch '呼吸') {
    throw 'Expected symptom-based department guidance before querying slots.'
}
$plan = Send-DemoMessage $symptomTask "可以，$($tomorrow.Month)月$($tomorrow.Day)号上午"
if ($plan.status -ne 'AWAITING_CONFIRMATION' -or $plan.task.activePlan.draft.appointmentDate -ne $tomorrow.ToString('yyyy-MM-dd')) {
    throw 'Expected natural-language date to produce a plan awaiting explicit confirmation.'
}

$alternativeTask = New-DemoTask
$alternative = Send-DemoMessage $alternativeTask "帮我预约呼吸内科$($dayAfter.Month)月$($dayAfter.Day)号晚上，如果没有号请给我最近的替代时间"
if ($alternative.status -ne 'AWAITING_CONFIRMATION' -or $alternative.task.lastToolResult.data.exactMatch -ne $false) {
    throw 'Expected same-department alternatives when the requested evening has no slots.'
}

$earliestTask = New-DemoTask
$earliest = Send-DemoMessage $earliestTask '帮我最近时间预约一个呼吸内科的号'
if ($earliest.status -ne 'AWAITING_CONFIRMATION') {
    throw 'Expected earliest-slot query without demanding a specific date or period.'
}

Write-Host 'PASS: symptom guidance, Chinese dates, alternative times, earliest-slot request; all plans await confirmation.'
