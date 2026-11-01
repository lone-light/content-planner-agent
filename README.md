# 内容任务规划 Agent

小影校招第 2 题。输入主题、预算和截止时间后，Agent 检索素材、询价、生成脚本并保存活动方案。遇到超预算或工具失败时，根据返回结果调整动作，保留已完成步骤和费用记录。

**Java 21 · Spring Boot 3.5.9 · Spring AI 1.1.8 · MyBatis-Plus 3.5.18 · MySQL 8.4.7**。运行方式为 HTTP API。

验证结果：72 项测试通过，0 失败、0 错误、0 跳过；11 组离线 HTTP 用例；9 项 DeepSeek 验收场景、34 次响应；JAR 重启恢复与重复提交验证通过。详见 [验收记录](docs/03-evaluation.md)。

## 评审入口

- [需求与验收](docs/01-requirements.md)
- [架构与决策循环](docs/02-solution.md)、[接口说明](docs/reference/http-api.md)、[HTTP 请求](examples/requests.http)
- [目录职责、阅读顺序与 Java 约定](docs/reference/java-conventions.md)、[IDEA 检查](examples/reports/idea-inspection.json)
- [实际验证结果](docs/03-evaluation.md)、[输入、输出和 Trace](examples/README.md)
- [AI 使用记录](docs/04-ai-usage.md)、[复盘](docs/05-retrospective.md)、[数据库说明](docs/reference/mysql.md)

## 安装和运行

目录如下，包职责见 [Java 工程约定](docs/reference/java-conventions.md)。

```text
src/main/java/com/lonelight/contentplanner/
  controller/  service/{impl,support}/  mapper/
  agent/{runtime,client}/  tool/  config/
  model/{dto,vo,entity,agent,plan,tool,enums}/
  common/{exception,json,web}/
src/main/resources/       应用配置、MySQL 建表脚本
src/test/java/.../        unit、integration、acceptance、support
data/                    合成素材与费用规则
docs/                    考核文档；reference 为技术参考
examples/                offline、live、packaged、reports
scripts/                 验证与报告导出命令
tests/README.md           考核测试评审入口
```

需要 JDK 21、Maven 3.9+ 和 MySQL 8.4。测试需要正在运行的 Docker；Testcontainers 自动创建独立 MySQL，测试结束自动回收，不访问应用数据库。`mvn -version` 应显示 Java 21。

PowerShell 验收脚本面向 Windows / PowerShell 7，JAVA_HOME 应指向 JDK 21；Java 服务与 Maven 测试不依赖 Windows。

PowerShell：

```powershell
if (-not (Test-Path .env)) { Copy-Item .env.example .env }
# 在 .env 填写 MYSQL_PASSWORD、MYSQL_ROOT_PASSWORD、DEEPSEEK_API_KEY。
docker compose up -d --wait mysql
mvn -B verify
mvn spring-boot:run
```

Bash 复制配置使用 `test -f .env || cp .env.example .env`。使用已有 MySQL 时配置数据库 URL 和应用账号，无需运行 Compose。不要覆盖已有 `.env`，也不要提交密码。完整步骤见 [MySQL 说明](docs/reference/mysql.md)。

默认真实调用 DeepSeek，监听 `http://127.0.0.1:8081`。缺少模型密钥时任务返回 `MODEL_NOT_CONFIGURED`；数据库不可用时启动失败，均不会自动切换运行方式。

```powershell
Invoke-RestMethod http://127.0.0.1:8081/api/health
$body = @{
  requestId = "campaign-001"
  topic = "字幕活动"
  budgetUnits = 10
  deadlineTime = "2030-01-01T12:00:00Z"
  preferredMode = "STANDARD"
} | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri http://127.0.0.1:8081/api/plans -ContentType "application/json; charset=utf-8" -Body ([Text.Encoding]::UTF8.GetBytes($body))
Invoke-RestMethod http://127.0.0.1:8081/api/plans/campaign-001
Invoke-WebRequest http://127.0.0.1:8081/api/plans/campaign-001/trace -OutFile trace.jsonl
Invoke-RestMethod -Method Post http://127.0.0.1:8081/api/plans/campaign-001/resume
```

标准方案总费用 12，精简方案 8。模型需要询价后判断预算，而不能假定某模式必然更便宜。相同 ID 和输入重复提交返回已有结果；改输入使用新 ID。失败后显式调用 `/resume` 才继续执行。

打包运行：`java -jar target/content-planner-agent-0.1.0.jar`。Windows PATH 若指向其他 JDK，可使用 `& "$env:JAVA_HOME/bin/java.exe" -jar target/content-planner-agent-0.1.0.jar`。

## 配置

`.env` 使用普通 `KEY=value`，不加引号或 `export`；实际环境变量优先。配置示例见 [.env.example](.env.example)。

| 配置 | 含义 |
| --- | --- |
| `AGENT_DB_URL` | 默认连接本地 3307 端口的 `content_planner` |
| `MYSQL_USER` / `MYSQL_PASSWORD` | MySQL 应用账号及密码 |
| `MYSQL_ROOT_PASSWORD` / `MYSQL_PORT` | Compose 初始化密码和本地端口；修改端口须同步 URL |
| `AGENT_MODEL_MODE` | 默认 `live`；`mock` 是题目要求的显式离线模型入口 |
| `DEEPSEEK_API_KEY` / `DEEPSEEK_BASE_URL` | 密钥和服务地址，默认官方地址 |
| `DEEPSEEK_MODEL` / `DEEPSEEK_TIMEOUT_SECONDS` | 默认 `deepseek-flash` / 45 秒 |
| `AGENT_MAX_STEPS` | 单次运行最多 12 次模型决策，范围 1–50 |
| `AGENT_SIMULATION_ENABLED` | 默认 `false`；故障验收需显式设为 `true` |
| `AGENT_CATALOG_PATH` / `AGENT_ASSETS_PATH` | 默认读取 JAR 内合成费用表和素材，可配置文件 URI |

业务资料由 `data/` 维护。工具为题目允许的本地函数和合成数据，脚本使用可编辑模板，来源和工具性质在结果中明确标记。费用单位与耗时为业务规则，不是模型 API 的货币费用或实际制作工期。

## 验证

```powershell
mvn -B verify
# 修改 Java 代码后先格式化：
mvn spotless:apply
# 重新生成 HTTP 输入、实际输出和 Trace：
mvn -B verify '-DrecordExamples=true'
./scripts/export-test-results.ps1
# 应用 MySQL 已启动时验证独立 JAR 强制结束、恢复、幂等：
./scripts/verify-packaged.ps1
```

自动化测试使用 Testcontainers 创建的独立 MySQL；模型为离线替身或本地协议服务，不调用付费模型。测试覆盖服务、事务、并发重复提交、报价变化、Spring AI 协议、完整 HTTP 请求、恢复和输入校验。

无需模型服务时，显式运行 `mvn spring-boot:run '-Dspring-boot.run.arguments=--agent.model-mode=mock'`，仍使用 MySQL。离线结果只证明工程流程。

完整真实验收运行 `./scripts/verify-release.ps1`：它依次启动当前 JAR，执行主路径、保存失败续跑、价格反转和独立进程恢复，最后运行 `review-evidence.ps1` 核对账目、动作因果和构建哈希。需要已启动的应用 MySQL 和真实密钥，会产生模型服务调用。

单独验证时，可在启用故障注入的服务上运行 `verify-live.ps1`；`verify-live-extended.ps1` 自行启动应用。输出写入固定的 `examples/live/main` 和 `extended`，重新验收会更新当前材料；请求 ID 每次唯一。
