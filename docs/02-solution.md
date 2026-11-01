# 实现方案

## 框架职责

| 组件 | 版本 | 职责 |
| --- | --- | --- |
| Java / Spring Boot | 21 / 3.5.9 | HTTP、输入校验、依赖注入、事务和配置 |
| Spring AI | 1.1.8 | ChatModel、Prompt、消息、模型选项、响应和 token 用量 |
| MyBatis-Plus | 3.5.18 | Entity 映射、BaseMapper 和 ServiceImpl 的 CRUD、Lambda 查询与更新 |
| MySQL | 8.4.7 | InnoDB 事务、唯一约束、持久化任务和方案 |
| Testcontainers | 2.0.5，测试范围 | 为测试 JVM 创建独立 MySQL 并回收 |

版本兼容依据 [Spring AI 1.1 的官方说明](https://github.com/spring-projects/spring-ai/tree/v1.1.8#spring-boot-version-compatibility)、[MP 安装说明](https://baomidou.com/getting-started/install/)和 [Testcontainers MySQL 模块](https://java.testcontainers.org/modules/databases/mysql/)。

## 调用链

```text
PlanController
  → PlanService 接口 → PlanServiceImpl：输入、幂等、进程内锁、结果组装
  → AgentRunner：有界模型决策循环、动作验证、观察和 Trace
      → SpringAiModelClient → Spring AI ChatModel → DeepSeek
      → ToolExecutor → 检索 / 询价 / 脚本 / 保存
      → TaskPersistenceService 接口 → TaskPersistenceServiceImpl：短事务协调
          → AgentTaskService / SavedPlanService 接口
              → AgentTaskServiceImpl / SavedPlanServiceImpl：MP Service CRUD
              → AgentTaskMapper / SavedPlanMapper：BaseMapper
                  → MySQL
```

服务接口位于 service，实现位于 service.impl；Controller、AgentRunner 和服务实现之间注入接口。两个实体服务接口继承 IService，对应实现继承 ServiceImpl。AgentTaskServiceImpl 用 getById 和 lambdaUpdate，SavedPlanServiceImpl 用 lambdaQuery，新增记录用 save；两个 Mapper 没有自定义方法或 XML。TaskPersistenceServiceImpl 将方案插入和状态检查点纳入同一短事务；模型网络调用期间不持有数据库事务。约定及检查见 [Java 工程约定](reference/java-conventions.md)。

Agent 执行循环位于 agent.runtime，模型适配位于 agent.client。model 按请求、响应、表映射、执行状态、方案、工具结果和枚举分包；common 按异常、JSON 和 HTTP 职责分包。完整目录职责及建议阅读顺序统一维护在 Java 工程约定中。

## 动态决策

AgentRunner 每轮向模型提供输入、已取得素材、候选方案、询价快照、当前费用评估、成功脚本、最近工具结果和剩余步数。模型输出 JSON 工具动作或 FINISH。合法动作才进入工具，工具观察重新提交给下一轮模型。

默认费用表中，标准方案总费用为 12。预算为 10 时，模型可以查询精简方案，确认总费用为 8 后再生成和保存。工具失败后模型可以重试、换方案或停止。切换方案由模型决定；价格反转用例中，修改费用表后模型改选了 STANDARD。

Spring AI 使用 OpenAiChatModel 连接 DeepSeek 兼容端点，仅调用 DeepSeek。设置 JSON 输出、2048 最大输出 token、关闭 thinking 和框架内部工具执行；框架传输重试上限为 1，业务重试由下一次模型动作决定。适配器只提取最终 content 和允许记录的元数据，错误提示不包含供应商原始响应或凭据。

## 费用、时间和完成条件

BigDecimal 汇总所有成功工具实际费用；换方案不清零。成功产物复用不重复收费，失败不收费但消耗业务规则中的耗时。当前评估会随完成步骤更新，询价快照保留原凭证；生成脚本仍需真实询价返回的 quoteId。

业务时间取实际当前时间与 startTime 加累计业务耗时中的较晚者。保存前再次核对预算、预计完成时间、素材和脚本依赖；不能满足时返回工具错误，由模型调整。API 的 message 从账本和数据库保存事实生成，不直接将模型理由作为财务或保存结论。

时间命名统一为 deadlineTime、startTime、estimatedFinishTime、saveTime、recordTime、createTime 和 updateTime。对外 OffsetDateTime 统一 UTC；SQL DATETIME(6) 由 UTC LocalDateTime 写入。

## 幂等、恢复和存储

任务 ID 为 modelMode 与 requestId 的组合，另有联合唯一约束；请求指纹包含规范化输入、资料版本和模型身份。相同 ID 输入不同返回 409。普通重复提交只重放；显式 resume 建立新的 runId，保留已完成工具、费用和失败次数。

数据库两表 agent_task、saved_plan，均使用 InnoDB、LONGTEXT 和 utf8mb4_0900_bin；请求 ID 大小写不同视为不同请求。两表通过 model_mode/request_id 逻辑关联，不建立物理外键。保存前验证方案所属任务和模式；同一事务先按主键更新父任务并持有行锁，父任务不存在则停止，随后插入方案，任何失败一起回滚。每次调用模型前写检查点，每次工具结果后提交状态。事务结果未知时返回 503，不推断保存成功。

任务读取与更新使用主键 id；方案读取同时约束 model_mode 和 request_id，使用 uk_saved_request 联合唯一索引。task 的联合唯一索引承担请求唯一性，不另建其左前缀单列索引。当前没有按时间或 plan_id 查询的接口，不添加无使用场景的索引。查询条件不对索引列做函数处理或类型转换。

结果组装只读取一次任务和一次保存记录；重复提交复用已读取的保存记录完成校验和输出。素材、方案候选、步骤和 Trace 均从已加载状态中处理，不在集合遍历中发起数据库查询。SQL 次数和实际 MP 查询的 EXPLAIN 由 DatabaseQueryTest 在真实 MySQL 上验证，见 [数据库评估](reference/mysql.md)。

服务按单实例运行，使用固定 64 个分片锁协调同一请求的提交与续跑，锁数量不随请求增长。
