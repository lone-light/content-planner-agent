# Java 工程约定

项目采用单 Maven 模块，按以下职责分包。格式由 Spotless 检查，目录和依赖关系由架构测试检查。

## 服务接口与实现

```text
service/
  PlanService.java
  AgentTaskService.java
  SavedPlanService.java
  TaskPersistenceService.java
  impl/
    PlanServiceImpl.java
    AgentTaskServiceImpl.java
    SavedPlanServiceImpl.java
    TaskPersistenceServiceImpl.java
  support/
    PlanResultMessage.java
    TaskKey.java
```

四个 Service 文件只声明服务契约，实现位于 impl 并标注 @Service。实现方法使用 @Override。Controller、AgentRunner 和服务之间通过构造器注入服务接口，依赖字段为 final；业务调用方不依赖具体 ServiceImpl。support 放置业务辅助类：PlanResultMessage 组装结果说明，TaskKey 生成任务主键，两者均不是 Spring 服务。

实体持久化接口继承 MP 的 IService<Entity>，实现继承 ServiceImpl<Mapper, Entity> 并实现对应接口；PlanService 和 TaskPersistenceService 负责编排业务与事务，不强行绑定实体 CRUD。常规查询和更新在 MP 服务实现中完成，Mapper 只继承 BaseMapper，无 Mapper XML。

持久化读取结果为 model.agent 下的不可变 StoredTask；公共异常放在 common.exception，服务接口不暴露实现类的嵌套类型。

## 目录职责与阅读顺序

生产代码根包为 com.lonelight.contentplanner，采用单 Maven 模块：

| 包 | 职责 |
| --- | --- |
| controller | HTTP 入口及输入输出 |
| service / service.impl | 服务契约、业务编排、MP 服务实现和短事务 |
| service.support | 业务结果说明及任务主键辅助类 |
| agent.runtime | 有界决策循环、动作验证、观察和 Trace |
| agent.client | 模型接入契约、请求上下文、Spring AI 和离线适配 |
| tool | 本地业务工具、资料规则加载和费用耗时计算 |
| mapper | BaseMapper 接口 |
| model.dto / model.vo | HTTP 请求与响应 |
| model.entity | 数据库表映射 |
| model.agent | 执行动作、状态、事件及持久化任务快照 |
| model.plan | 候选方案、报价、脚本和已保存方案 |
| model.tool | 工具执行结果和尝试记录 |
| model.enums | 业务枚举 |
| common.exception / common.json / common.web | 公共异常、JSON 编码、HTTP 结果与异常处理 |
| config | 类型化配置和 Bean 装配 |

建议阅读顺序：PlanController → PlanService → PlanServiceImpl → AgentRunner → ModelClient / ToolExecutor → TaskPersistenceServiceImpl → 两个 MP 服务实现及 Mapper。

测试源码统一位于 src/test/java：unit 放无 Spring/数据库依赖的约束测试，integration 放模型协议、服务和 MySQL 联调，acceptance 放完整 HTTP 验收，support 放共享 MySQL 测试设施。根目录 tests/README.md 提供考核评审入口。

docs 根目录保留考核规定文档，reference 放接口、数据库和 Java 参考说明。examples/offline 与 live 存放实际场景，packaged 存放进程恢复记录，reports 集中放测试、构建、数据库及 IDEA 报告。合成资料唯一入口为 data，打包时复制进资源路径；src/main/resources 放应用配置与建表脚本。

## 分层与代码约定

- Controller 负责请求校验及 HTTP 输出，服务实现负责业务编排与事务。
- DTO、VO 和执行结果使用 record；配置、执行状态和 Entity 按职责使用可变类，密钥字段排除 toString。
- 时间字段以 Time 结尾，数据库列以 _time 结尾，持久化时间为 UTC。
- 金额使用 BigDecimal；完成状态来自业务约束与真实保存结果。
- 异常统一处理，业务响应不包含模型原始错误正文、数据库细节或密钥。
- 网络模型调用不占用数据库事务；逻辑关联、唯一性和回滚边界见数据库说明。
- Java 使用 Spotless / Google Java Format AOSP 格式，提交前运行格式检查和相关测试。

## 可执行验证

ServiceArchitectureTest 不依赖数据库，验证接口与 impl 配对、MP 类型关系、构造器及接口依赖；DirectoryStructureTest 验证 Java 包声明与源码路径一致、核心类型按职责归属。完整功能回归使用真实隔离 MySQL，并核对 HTTP、模型协议、预算、逻辑关联和查询次数。

IDEA MCP 用于语义重命名、编译和文件检查。[检查记录](../../examples/reports/idea-inspection.json)包含文件、源文件 SHA-256、编译结果及问题列表；检查级别为 warning。
