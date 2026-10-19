# 评测集成与旧入口兼容

SupportOps 提供诊断、知识、人工处理结果及管理员确认后的脱敏来源导出。案例、模型档案、实验、执行器、审阅与报告由独立 AgentEval 项目维护，其源码和运行数据不纳入本仓库。

跨项目协作采用版本化来源 JSON 和固定被测程序包，不依赖相邻源码、共享数据库、工作台会话或模型设置。接口和打包命令见[评测集成](../docs/evaluation-split.md)，来源字段与请求恢复见[API](../docs/api.md)。

旧 `/api/evaluations/**` 经鉴权后返回 `410 EVALUATION_MOVED`，不转发 POST。旧 `evaluation/run.mjs` 与 `scripts/evaluate.mjs` 以退出码 2 停止，不创建实验或读取工作台密钥。

已有数据库中的旧评测表按原迁移保留，已应用 Flyway 文件不改写。旧数据写保护与备份格式见[数据库维护](../docs/database.md)，历史路径见[兼容清单](../docs/evaluation-split-ownership.md)。
