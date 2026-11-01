# 可执行测试

本目录是考核测试评审入口。测试源码按 Maven 标准统一放在 src/test/java/com/lonelight/contentplanner 下，运行 `mvn -B verify`。需要 JDK 21、Maven 和正在运行的 Docker。support/MySqlIntegrationSupport 为测试 JVM 创建隔离 MySQL，不访问应用数据库。

| 子包 | 内容 |
| --- | --- |
| unit | 无 Spring/数据库依赖的架构和目录约束测试 |
| integration | 服务、模型协议、SQL 和 MySQL 恢复测试 |
| acceptance | 完整 HTTP 输入、输出和 Trace 验收 |
| support | 共享 MySQL 测试设施 |

| 测试 | 重点 |
| --- | --- |
| PlanServiceTest | 正常、缺输入、预算、并发重复提交、续跑和截止时间 |
| AgentRunnerBoundaryTest | 越权完成、非法动作、步数上限、方案状态隔离 |
| CatalogVariationTest | 价格反转、小数金额、资料版本及模型模式隔离 |
| DeepSeekProtocolTest | Spring AI 协议、鉴权、截断、非法返回、超时和保密 |
| DeepSeekLoopIntegrationTest | HTTP → Spring AI → 本地协议服务 → 工具观察 → 下一次决策 |
| MySqlPersistenceTest | MySQL 产品、中文及 emoji、大小写 ID、幂等和回滚恢复 |
| DatabaseQueryTest | 零物理外键、逻辑关联防孤儿、归属校验、实际 MP SQL 的 EXPLAIN、集合增长时查询次数固定 |
| ServiceArchitectureTest | Service 接口与 impl 配对、MP 契约与实现、构造器及接口依赖 |
| DirectoryStructureTest | Java 包声明与源码路径、核心类型的职责包归属 |
| PersistenceRestartTest | 关闭并重开应用后的剩余步骤恢复 |
| AcceptanceEvidenceTest | 真实 Tomcat 的输入、输出及 JSONL Trace |

测试模型为规则替身或本地协议服务，不属于真实 DeepSeek 效果证明。协议服务的模型名和 localEndpoint 标记明确区分本地来源。

报告写入 target/surefire-reports；交付摘要由 scripts/export-test-results.ps1 导出，不复制报告中的环境属性。`-DrecordExamples=true` 将实际 HTTP 材料写入 examples/offline，普通测试写入 target/acceptance-evidence。

真实模型验收通过 scripts/verify-live.ps1 和 scripts/verify-live-extended.ps1 执行，独立 JAR 恢复通过 scripts/verify-packaged.ps1 执行。详见评估文档和 examples。
