# AI 使用说明

这次开发使用了 Codex，代码编写、重构、命令执行、测试和文档整理由 Codex 完成。程序运行时通过 Spring AI 调用 DeepSeek，由模型根据工具结果选择下一步动作。

我确定使用 Java、Spring AI、MySQL 和 MyBatis-Plus。审查过程中，要求将 Service 拆成接口和实现类，调整包结构，优先使用 MP 服务完成数据库操作。数据库使用逻辑关联，并检查索引和 N+1 查询；时间字段统一以 Time 结尾。

我还要求按题目逐项检查功能，使用 IDEA MCP 检查代码，通过真实 DeepSeek 调用验证决策过程，并清理旧文件和重复说明。

个人投入约 6 小时。

验证包括 72 项自动化测试和 9 项真实 DeepSeek 场景。离线测试与真实模型调用分别记录，结果见 [测试报告](../examples/reports/test-results.json)、[IDEA 检查](../examples/reports/idea-inspection.json)和 [DeepSeek 运行记录](../examples/live/README.md)。
