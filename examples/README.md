# 验证材料

以下为自动化测试、DeepSeek 调用、数据库查询和 IDEA 检查的记录。模型替身与 DeepSeek 调用分别存放在 offline 和 live 中。

手工验收可打开 [requests.http](requests.http)，每轮使用新的 run 值；检查重复提交时保持原值。

| 材料 | 内容 |
| --- | --- |
| [test-results.json](reports/test-results.json) | 72 项测试，0 失败、0 错误、0 跳过 |
| [build-summary.txt](reports/build-summary.txt) | 构建命令、计数和成功状态 |
| [offline/summary.json](offline/summary.json) | 11 组 HTTP 用例，模型为显式离线替身 |
| [live/README.md](live/README.md) | 9 项真实 DeepSeek 场景、34 次响应 |
| [进程恢复结果](packaged/result.json) | 当前 JAR、MySQL、强制结束应用及恢复 |
| [进程恢复 Trace](packaged/trace.jsonl) | 重启前后执行事件 |
| [database-facts.txt](reports/database-facts.txt) | MySQL 版本、表引擎、排序规则和时间列 |
| [database-review.json](reports/database-review.json) | 逻辑关联、索引、实际 MP SQL 的 EXPLAIN 和查询次数 |
| [idea-inspection.json](reports/idea-inspection.json) | IDEA MCP Java 编译及 47 个生产文件的检查，包含源文件哈希 |

离线目录包含 normal、budget-replan、missing-budget、missing-deadline、tool-failure、estimate-failure、impossible-budget、duplicate、save-resume、expired-deadline、no-assets。每组至少包含输入、实际输出及 Trace；重复与续跑保留同一用例必要的前后结果。

离线模型只验证流程，不证明模型决策效果；本地协议服务的来源标记同样不能作为真实 DeepSeek 证据。真实调用的模型 ID、响应 ID、供应商端点和用量在 live Trace 中保留。

复现步骤见 [评估文档](../docs/03-evaluation.md)，重新运行会更新对应材料。
