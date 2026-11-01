# 真实 DeepSeek 验收

验收使用 Spring AI 调用官方 DeepSeek，数据保存在 MySQL，业务工具使用合成资料。2026-10-30 记录了 9 项场景、34 次模型响应，token 合计 63,823。token 用量与工具的预算单位分开统计。

## 场景与文件

| 场景 | 结果 | 材料 |
| --- | --- | --- |
| 正常完成 | STANDARD，费用 12，已保存 | [输入](main/normal/request.json)、[输出](main/normal/response.json)、[Trace](main/normal/trace.jsonl) |
| 预算调整 | 标准报价不可行，改选 LEAN，费用 8 | [输入](main/budget-replan/request.json)、[输出](main/budget-replan/response.json)、[Trace](main/budget-replan/trace.jsonl) |
| 工具失败 | 生成失败后模型重试，费用 8 | [输入](main/tool-failure/request.json)、[输出](main/tool-failure/response.json)、[Trace](main/tool-failure/trace.jsonl) |
| 无可行方案 | INCOMPLETE，费用 1，预算缺口 3，未保存 | [输入](main/impossible-budget/request.json)、[输出](main/impossible-budget/response.json)、[Trace](main/impossible-budget/trace.jsonl) |
| 重复提交 | 相同保存 ID，无新增模型步骤或费用 | [输出](main/normal/response-duplicate.json)、[Trace](main/normal/trace-after-duplicate.jsonl) |
| 缺预算 | NEEDS_INPUT，零模型调用和费用 | [输入](main/missing-budget/request.json)、[输出](main/missing-budget/response.json)、[Trace](main/missing-budget/trace.jsonl) |
| 缺截止时间 | NEEDS_INPUT，零模型调用和费用 | [输入](main/missing-deadline/request.json)、[输出](main/missing-deadline/response.json)、[Trace](main/missing-deadline/trace.jsonl) |
| 保存失败续跑 | 事务回滚后未保存，续跑费用 11 → 12，保留成功步骤 | [输入](extended/save-resume/request.json)、[失败输出](extended/save-resume/response-before-resume.json)、[失败 Trace](extended/save-resume/trace-before-resume.jsonl)、[续跑输出](extended/save-resume/response.json)、[完整 Trace](extended/save-resume/trace.jsonl) |
| 价格反转 | 读取新费用后选择 STANDARD，总费用 8 | [费用表](extended/price-reversal/catalog-after.json)、[输入](extended/price-reversal/request.json)、[输出](extended/price-reversal/response.json)、[Trace](extended/price-reversal/trace.jsonl) |

[主验收摘要](main/summary.json)、[扩展摘要](extended/summary.json)、[构建清单](manifest.json)、[逐事件核对](review.json)分别保存。

预算 Trace 的第 7 个事件是不可行报价，第 9 个事件是模型改选；工具失败 Trace 的第 13 个事件是失败观察，第 15 个事件是模型重试。每个动作保留实际参数和简短理由，成功保存按数据库事实确认。

运行 `./scripts/verify-release.ps1` 可重建该批材料。`review-evidence.ps1` 根据实际文件核对费用、约束、动作因果和 JAR SHA-256，不生成预设模型决策日志。

这批记录每类场景各运行一次，尚未统计多次运行的完成率。
