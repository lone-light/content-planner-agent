# MySQL 配置与持久化

正常运行和持久化测试均使用 MySQL 8.4.7。应用通过 MyBatis-Plus 服务访问数据库。

## 启动应用数据库

1. 复制 .env.example 为 .env，填写 MYSQL_PASSWORD 和 MYSQL_ROOT_PASSWORD。
2. 执行 `docker compose up -d --wait mysql`。
3. 执行 `mvn spring-boot:run`。应用幂等创建两张表，不自动改写已有表结构。

Compose 仅绑定 127.0.0.1:3307，数据库为 content_planner，默认应用账号 planner。修改 MYSQL_PORT 时同步修改 AGENT_DB_URL。使用已有 MySQL 时自行创建数据库和专用应用账号，配置 URL、用户名与密码，无需 Docker Compose 或 root 密码。

初始化需要 CREATE 及读写权限。表结构见 [schema.sql](../../src/main/resources/db/mysql/schema.sql)。远程连接按环境配置 TLS；本项目 Compose 用于本地运行。

命名卷保留数据，`docker compose stop mysql` 停止但不删除卷。MYSQL_* 初始化环境变量只作用于新数据库，修改 .env 不会自动修改已有用户密码。

## 表和服务

| 表 | Entity | MP Service | 约束 |
| --- | --- | --- | --- |
| agent_task | AgentTaskEntity | AgentTaskService / AgentTaskServiceImpl | id 主键，model_mode/request_id 联合唯一 |
| saved_plan | SavedPlanEntity | SavedPlanService / SavedPlanServiceImpl | id 主键，model_mode/request_id 联合唯一，逻辑关联任务 |

任务主键为 modelMode:requestId。保存记录 id 为保存工具生成的 savedPlanId，plan_id 记录候选方案 ID。LONGTEXT 保存状态和方案 JSON，InnoDB 提供原子提交和回滚，utf8mb4_0900_bin 保持大小写 ID 独立。

SQL 时间列 create_time、update_time、save_time 均为 DATETIME(6)，按 UTC 写入。API 使用带时区的 ISO 8601。没有 Mapper XML，也没有业务服务手写 DML。

## 逻辑关联

数据库没有物理外键。保存入口校验方案的 requestId 和 modelMode 与当前任务一致；在同一事务中先按任务主键 UPDATE，确认命中一条父记录并持有行锁，再读取或插入方案。缺少父记录时拒绝保存；方案插入失败或故障注入会回滚任务更新和方案记录。使用数据库约束保证主键和请求唯一性。

当前不提供任务删除接口。逻辑关联由应用事务维护；绕过服务直接改数据库时，操作者必须维护关联完整性。以后增加删除接口时，应在同一事务中处理关联方案。

## 索引和查询次数

| 操作 | 条件 | 实际使用索引 |
| --- | --- | --- |
| 读取任务、恢复检查点 | id 精确匹配 | agent_task.PRIMARY |
| 更新检查点、验证父任务 | id 精确匹配 | agent_task.PRIMARY |
| 读取已保存方案 | model_mode 和 request_id 同时精确匹配 | saved_plan.uk_saved_request |
| 防止同请求重复记录 | model_mode/request_id 联合唯一 | 两表各自的请求唯一索引 |

不对索引列套函数，不省略模型模式，不单独查询 request_id。没有按时间、plan_id 或 JSON 内容过滤的接口，因此不为这些列盲目加索引；联合索引已覆盖其 model_mode 左前缀。

DatabaseQueryTest 捕获实际 MP SQL，在已有记录上执行 EXPLAIN：SELECT 为 const，UPDATE 为 range，估算行数均为 1。测试核对主键和请求联合唯一索引，确认不存在物理外键；实际数据库的全部索引也单独核对。详见 [实际结构和查询计划](../../examples/reports/database-review.json)。

现有接口查询单个任务；集合元素在加载后的 JSON 状态内处理，未使用逐项数据库读取。测试将关联素材项分别增加 1 和 100，GET 固定为 2 次 SELECT，重复提交固定为 2 次 SELECT 加 1 次 UPDATE，Trace 固定为 1 次 SELECT。重复提交的完成校验与结果组装共用同一次保存记录读取，不设置跨请求缓存。

Agent 每次决策的检查点写入用于故障恢复，次数随实际执行步骤增加，不在遍历关联集合时逐项发起 SELECT。

## 测试隔离

`mvn -B verify` 通过 Testcontainers 创建本测试 JVM 独享的 MySQL，随机宿主机端口和密码，使用相同建表脚本。不会连接 .env 中的应用数据库。Docker 不可用时测试明确失败，不跳过数据库验证。首次运行可能下载 MySQL 和资源回收器镜像。

测试覆盖真实数据库产品、中文及 emoji、唯一约束、事务回滚、失败续跑和应用上下文重启。`scripts/verify-packaged.ps1` 则连接已配置的应用数据库，使用唯一请求 ID 验证独立 Java 进程被强制结束后的恢复。
