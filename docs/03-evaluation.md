# 评估结果

验证结果：72 项自动化测试，0 失败、0 错误、0 跳过；11 组离线 HTTP 用例；9 项真实场景和 34 次 DeepSeek 响应；独立 JAR 强制结束后恢复通过。供应商返回的 token 合计为 63,823，与业务预算单位分别计算。当前 JAR SHA-256 为 `840386F0133D192332C23019DD15895994A24923763965EF574352B1A42B5AE8`。

## 复现命令

```powershell
mvn -B clean verify '-DrecordExamples=true'
./scripts/export-test-results.ps1
# 已配置 DeepSeek、应用 MySQL 已启动时：
./scripts/verify-release.ps1
```

普通测试全部使用隔离的真实 MySQL，模型为离线替身或本地协议服务。真实验收脚本启动当前 JAR，调用官方 DeepSeek，包含不同工具观察后的动作变化，随后验证独立进程恢复。

## 验证材料

| 材料 | 内容 |
| --- | --- |
| [测试结果](../examples/reports/test-results.json) | 实际 Surefire XML 中的用例和计数，排除环境属性 |
| [构建摘要](../examples/reports/build-summary.txt) | 当前构建命令、测试计数及完成状态 |
| [离线 HTTP 汇总](../examples/offline/summary.json) | 实际输入、响应、Trace，明确标记模型替身 |
| [真实主路径](../examples/live/main/summary.json) | 正常、超预算、工具失败、无可行方案、重复和缺输入 |
| [真实扩展路径](../examples/live/extended/summary.json) | 保存事务失败、显式续跑和费用反转 |
| [真实构建清单](../examples/live/manifest.json) | 实际运行 JAR 的 SHA-256 和技术栈 |
| [逐事件核对](../examples/live/review.json) | 动作因果、账目、保存约束、重复提交、续跑和哈希 |
| [JAR 恢复结果](../examples/packaged/result.json) | MySQL、实际进程强制结束和重启、续跑及幂等 |
| [JAR 恢复 Trace](../examples/packaged/trace.jsonl) | 保留前后执行事件与实际工具结果 |
| [数据库事实](../examples/reports/database-facts.txt) | 客户端只读核对版本、InnoDB、排序规则和 DATETIME 时间列 |
| [数据库查询审查](../examples/reports/database-review.json) | 无物理外键、实际 MP SQL 的 EXPLAIN、索引命中及查询次数 |
| [IDEA 检查](../examples/reports/idea-inspection.json) | 当前生产源文件的 warning 级检查、Java 编译结果和源文件哈希 |

## 第 2 题必验场景

| 场景 | 实际结果 | 记录 |
| --- | --- | --- |
| 正常完成 | COMPLETED，保存标准方案，费用 12 | [输出](../examples/live/main/normal/response.json) |
| 缺预算 | NEEDS_INPUT，模型调用及费用均为 0 | [输出](../examples/live/main/missing-budget/response.json) |
| 缺截止时间 | NEEDS_INPUT，明确要求补充 deadlineTime，费用 0 | [输出](../examples/live/main/missing-deadline/response.json) |
| 工具失败 | 脚本生成失败后，模型重试；最终保存，费用 8 | [Trace](../examples/live/main/tool-failure/trace.jsonl) |
| 预算超限 | 预算 10，标准报价 12 不可行；模型改选精简方案，总费用 8 | [Trace](../examples/live/main/budget-replan/trace.jsonl) |
| 重复执行 | 保存 ID、模型步骤和费用均未增加 | [重复提交结果](../examples/live/main/normal/response-duplicate.json) |
| 失败续跑（加分项） | 保存失败后费用 11、未保存；续跑后费用 12、已保存，检索和脚本未重做 | [失败结果](../examples/live/extended/save-resume/response-before-resume.json)、[续跑结果](../examples/live/extended/save-resume/response.json) |

工具故障通过验收参数注入，模型收到失败观察后选择后续动作。

## 其他检查

| 要求 | 预期与实际核对入口 |
| --- | --- |
| 无可行方案 | offline/impossible-budget 与 live/main/impossible-budget：未完成、未保存、返回缺口 |
| 询价失败 | offline/estimate-failure：失败后重试询价，再按报价调整方案 |
| 价格改变 | CatalogVariationTest 和 live/extended/price-reversal：动作与新费用一致 |
| 资料不足 | offline/no-assets：请求补充，不用无关素材凑方案 |
| 截止时间 | offline/expired-deadline 和服务测试：过期停止、预计超时拒绝保存 |
| 执行上限 | AgentRunnerBoundaryTest 与独立 JAR：返回 STEP_LIMIT，保存事实独立报告 |

独立用例目录包含 request.json、response.json 和 trace.jsonl；重复提交复用正常用例的输入，另存 response-duplicate.json。真实模型记录另含响应 ID、模型名和 token 用量。Trace 仅记录动作的简短理由。

两条动态路径已逐事件核对：预算用例的第 7 个事件返回不可行报价，第 9 个事件是模型改选；工具失败用例的第 13 个事件返回脚本工具错误，第 15 个事件是模型重试。价格反转后，模型选择 STANDARD，总费用为 8。

## 错误与一致性验证

本地协议测试覆盖缺密钥、非法密钥字符、HTTP 错误、超时、截断、非法动作和未知字段，确认错误进入明确未完成或可修正观察。读取响应的超时可能被框架包装成解析异常，适配器遍历原因链识别超时，协议测试核对返回 MODEL_TIMEOUT。

整轮测试会注入错误的模型结束说明，验证业务 API 金额与保存状态始终来自账本及数据库。模型声称完成而没有保存时被拒绝。保存 INSERT 后、COMMIT 前注入异常，方案和成功账目一起回滚，失败观察单独保留。MySQL 测试核对中文、emoji、大小写 ID、UTC 时间列及应用重启后的记录。

服务分层由 ServiceArchitectureTest 验证：四个 Service 均为接口，实现类位于 service.impl 并标注 @Service；实体服务使用 IService/ServiceImpl 配对；Controller、AgentRunner 与服务实现通过构造器注入接口。IDEA MCP 的 build_project 编译成功，47 个生产 Java 文件在 warning 级检查中无错误、无警告。模型协议另验证空 choices 返回明确的 INVALID_MODEL_RESPONSE，而不是执行空动作。

DirectoryStructureTest 验证 src/main/java 和 src/test/java 下所有 Java 文件的 package 与目录对应，核心类型位于规定的职责包。测试按 unit、integration、acceptance、support 归类，报告导出和评审脚本从 examples/reports 读取材料，进程恢复材料位于 examples/packaged。

DatabaseQueryTest 验证逻辑关联：缺少父任务时不能插入孤立方案，方案与请求归属不符时在写库前拒绝。两张表不含物理外键。实际 MP SQL 的 EXPLAIN 显示，任务 SELECT 使用 PRIMARY / const，任务 UPDATE 使用 PRIMARY / range，方案 SELECT 使用 uk_saved_request / const，估算行数均为 1。分别额外增加 1 和 100 项素材时，GET 为 2 次 SELECT，重复提交为 2 次 SELECT 加 1 次 UPDATE，Trace 为 1 次 SELECT；查询次数没有随集合规模增长。

## 验证范围与限制

- DeepSeek 记录用于核对本批场景中的决策过程，不作为长期成功率统计。
- 业务工具使用题目允许的合成资料和本地脚本模板，费用、耗时按工具规则计算。
- 服务按单实例验证；每次模型调用有超时、每轮有步数上限，整项任务未另设总运行时限。
