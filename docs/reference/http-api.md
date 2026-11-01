# HTTP 接口说明

默认地址 http://127.0.0.1:8081。响应外层为 `{ "code": 0, "message": "success", "data": ... }`。错误响应沿用 code、message、data 字段；业务异常在 data.errorCode 中返回原因码，其他异常的 data 为 null。HTTP 200 表示请求已处理，任务是否完成应检查 data.status 和 data.saved。

## 路由

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| GET | /api/health | 服务状态、模型模式和故障注入开关；不调用模型 |
| POST | /api/plans | 创建任务；相同 ID 和输入重放 |
| GET | /api/plans/{requestId} | 查询已保存的任务状态及方案 |
| POST | /api/plans/{requestId}/resume | 显式续跑，已完成任务重放 |
| GET | /api/plans/{requestId}/trace | UTF-8 application/x-ndjson 事件 |

## 请求

```json
{
  "requestId": "campaign-001",
  "topic": "字幕活动",
  "budgetUnits": 10,
  "deadlineTime": "2030-01-01T12:00:00Z",
  "preferredMode": "STANDARD"
}
```

requestId 匹配 `[A-Za-z0-9][A-Za-z0-9_.-]{0,79}`。主题最多 500 字符；预算非负，最多 9 位整数和 2 位小数；截止时间必须包含时区。未知字段拒绝，金额和时区等价输入会先规范化再计算指纹。

缺主题、预算或截止时间返回 NEEDS_INPUT，并列出 missingFields；补齐输入应使用新 ID。preferredMode 允许 STANDARD、LEAN，默认 STANDARD。

故障验收可增加 `simulate: { "failTool": "SAVE_PLAN", "failures": 2, "failAfterInsert": true }`。仅显式开启 AGENT_SIMULATION_ENABLED 时可用；failAfterInsert 仅适用于 SAVE_PLAN；故障次数范围 0–5。默认关闭。

## 结果

| 字段 | 含义 |
| --- | --- |
| status / reasonCode / message | 任务状态、原因和基于账本及保存事实生成的说明 |
| missingFields | 待补充字段 |
| modelMode / modelIdentity | 本轮使用的模型模式及身份 |
| toolsProvenance / dataVersion | 合成工具性质及资料版本 |
| budgetUnits / spentUnits | 用户预算及累计实际业务费用 |
| remainingEstimatedUnits / projectedTotalUnits | 当前方案尚需费用、已发生加尚需费用 |
| deadlineTime / estimatedFinishTime | 截止时间及预计完成时间 |
| elapsedSimulatedMinutes / totalModelSteps | 合成耗时及累计模型决策次数 |
| taskPlan / candidates / quotes / steps | 带依赖与状态的任务计划、候选方案、询价快照和执行尝试 |
| saved / savedPlan | 数据库实际保存状态及方案 |
| replayed / resumed | 是否重放、是否显式续跑 |

保存方案包含 saveTime；Trace 包含 recordTime。所有对外时间使用带时区 ISO 8601。

COMPLETED 需通过保存、账目、预算、时间和素材引用校验。INCOMPLETE 可同时 saved=true，例如保存之后达到模型步数上限；这时仍未确认整个 Agent 完成，续跑可完成结束动作而不重做保存。RUNNING 可在进程异常退出后查询到，允许显式恢复。

生成动作需引用询价工具给出的 quoteId；每轮 currentAssessment 是按当前状态重新计算的费用与耗时，不签发询价凭证。模型简短 reason 保存在 Trace，不作为财务结论。

## 错误与重试

| HTTP | 含义 |
| --- | --- |
| 400 | 输入格式、字段或故障注入参数无效 |
| 404 | 当前模型命名空间下任务不存在 |
| 409 | 相同 ID 的输入、资料、模型指纹不同，或方案冲突 |
| 503 | 数据库不可用、检查点提交失败或状态不一致 |

模型服务失败通常返回 INCOMPLETE 和明确原因，不静默切换离线模式。请求中断或提交结果未知时先查询已有事实，再决定是否续跑。
