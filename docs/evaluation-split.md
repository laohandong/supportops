# SupportOps 评测集成

SupportOps 负责诊断、知识、人工处理结果、项目记忆和企业接入。案例、模型档案、实验、执行器、审阅和报告属于独立 AgentEval 项目，不包含在本仓库中。SupportOps 不查询外部评测库，不启动评测 Worker，不依赖相邻项目的源码、账号或本地配置。

## 来源与程序交付

1. 在终态诊断中保存人工处理结果。管理员选择“导出脱敏评测来源”，填写问题和摘要，核对预览并确认下载。
2. 将来源 JSON 手动交给有权接收的独立评测项目。导出绑定当前结果修订与固定请求；重复查询和下载返回原包，后续修改不会覆盖历史导出。
3. 需要提供诊断程序时，在 SupportOps 根目录构建 JAR，再生成固定目标包：

```powershell
node scripts/package-evaluation-target.mjs --jar target/supportops-0.1.0-SNAPSHOT.jar --output C:/artifacts/supportops-target
```

交付完整目录，不能只复制 JAR。包包含 JAR、能力清单与公开源码观察摘要。JAR 摘要和源码摘要分别记录；构建对应关系未证明时标记为 UNKNOWN。包的接收、登记和实验执行以独立评测项目文档为准。

来源包只包含人工确认的有限字段，不自动复制原回答、附件、用户账号或配置秘密。内容摘要证明完整性，不证明来源可信或模型质量；实验和历史查看不要求当前工作台在线。

## SupportOps 接口

| 接口 | 用途 |
| --- | --- |
| POST /api/runs/{runId}/evaluation-exports | 检查当前处理结果修订，保存固定请求与不可变脱敏包 |
| GET /api/evaluation-exports/{id} | 按权限下载原包 |
| GET /api/evaluation-exports/requests/{requestId} | 查询原导出回执 |
| GET /api/evaluation-integration | 读取可选外部首页 |
| GET/POST /api/evaluations/** | 鉴权后返回 410 EVALUATION_MOVED |

写请求继续使用 SupportOps Cookie 与 `X-SupportOps-Request: 1`。旧 POST 不重定向或转发凭据；旧 CLI 以退出码 2 停止。可选 `SUPPORTOPS_EVALUATION_PLATFORM_URL` 仅接受 HTTPS 或回环 HTTP，默认不配置；该入口不共享登录状态。详细字段与错误见 [API](api.md)。

## 数据与恢复

SupportOps 保留 B3 至 V15 原迁移与旧 14 张评测表。V16 增加来源导出与迁移控制，V17 建立旧表写门禁，V18 补充注释，V19 绑定唯一迁移目标代次。移除页面和 Worker 不会删除旧数据。数据库升级与写保护见[数据库维护](database.md)。

当前备份格式为 `SUPPORTOPS_BACKUP_V2`，旧整体格式只通过显式 `restore-legacy` 工具处理。备份、停写和恢复要求见[可靠性运行与恢复](reliability-delivery.md)。外部评测库、密钥与执行材料由独立项目管理，不由本项目的新备份格式接管。

旧路径和接口见[兼容清单](evaluation-split-ownership.md)，验证范围与限制见 [SupportOps 验证记录](supportops-validation-20261019.md)。本地隔离验证不代表当前工作台已完成升级或业务切换。
