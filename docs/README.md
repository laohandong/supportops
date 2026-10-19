# 文档目录

SupportOps 的项目介绍、两条核心流程和快速启动命令见 [项目 README](../README.md)。本文按读者任务组织详细文档；所有命令默认在仓库根目录执行。

## 使用与部署

| 文档 | 内容 |
| --- | --- |
| [产品说明](product.md) | 使用角色、企业支持流程中的位置、当前能力与产品边界 |
| [本地部署与启动依赖](deployment.md) | JDK、Node、Docker、三项存储服务、首次启动、停止与故障排查 |
| [企业系统接入](enterprise-integration.md) | 项目、系统、环境、只读连接、应用授权及固定请求恢复 |
| [企业独立部署](enterprise-deployment.md) | Linux离线包、HTTPS与秘密挂载、单实例保护、停写备份及新部署恢复 |
| [可靠性运行与恢复](reliability-delivery.md) | 实际运行身份、只读 doctor/status、真实浏览器矩阵、停写备份与全新隔离恢复 |
| [模型服务配置](providers.md) | 界面配置、服务商选择、凭据隔离、配置优先级与参数兼容性 |
| [用户系统](users.md) | 首次管理员、账号角色、本人对话范围、归档与历史归属 |
| [评测集成](evaluation-split.md) | 人工脱敏来源导出、固定目标程序包、外部入口与旧接口兼容 |
| [诊断处理结果与来源导出](diagnosis-outcomes.md) | 人员回填、角色证据、不可变修订、人工脱敏导出和原请求恢复 |
| [受控修复中心](controlled-remediation.md) | 固定合成目标、人工审批、一次执行、独立验证及原请求恢复 |
| [飞书私聊诊断](feishu.md) | 自建应用、长连接配置、一次性账号绑定、消息去重和投递恢复 |

## 实现原理与接口

| 文档 | 内容 |
| --- | --- |
| [架构说明](architecture.md) | 模块分层、事务与任务生命周期、SSE、记忆和用量口径 |
| [知识存储与处理](knowledge.md) | 上传、原件、修订、分片、发布、任务补偿及 Excel 查询 |
| [本地重排序](reranking.md) | ONNX 适配、候选预算、分数含义、模型文件与失败边界 |
| [后端接口](api.md) | GET / POST 契约、Swagger、认证、异步状态和请求示例 |
| [标准诊断源协议](enterprise-source-protocol.md) | 四项只读能力、严格身份与网络规则、独立Java样例和OpenAPI |
| [界面设计](design.md) | 当前布局、视觉来源、交互状态、窄屏与可访问性约定 |

## 维护与验证

| 文档 | 内容 |
| --- | --- |
| [数据库维护](database.md) | 自动初始化、手动导入、已有 MySQL 接管与 H2 离线迁移 |
| [验证结果与范围](validation.md) | 最近构建、真实存储及 HTTP 检查结果，与尚未验证的范围 |
| [SupportOps 当前验证与交付范围](supportops-validation-20261019.md) | 单次完整 Java 回归、ONNX 跳过明细、源码隔离、证据摘要与未覆盖范围 |
| [2026-10-05 整体复审与补修](validation.md) | GitHub基线、具体补修、184项完整Java、29项案例与企业32/34矩阵、清理及未完成验证 |
| [2026-10-04 企业接入开发验证](enterprise-integration-acceptance-20261004.md) | 核心功能、34项覆盖与未执行子项、离线包、真实旧库升级和停写恢复证据 |
| [2026-10-02 处理结果与案例开发验收](diagnosis-outcome-cases-acceptance-20261002.md) | OC-01～OC-08、29项验收矩阵、失败复验、最终身份与独立验收入口 |
| [2026-10-02 可靠性交付补修与开发复验](reliability-delivery-fixes-acceptance-20261002.md) | RD-F01～RD-F03、两平台 21 项与 9 项边界、完整失败历史、最终包和隔离恢复入口 |
| [2026-09-30 可靠性交付独立验收](reliability-delivery-independent-acceptance-20260930.md) | 原始暂不通过结论及三个缺陷的证据与修复要求 |
| [2026-09-30 可靠性交付记录](reliability-delivery-acceptance-20260930.md) | RD-01～RD-07、Windows/Linux 的 21 项必测结果、失败复验、恢复证据与独立验收入口 |
| [2026-09-28 受控修复独立复验通过](controlled-remediation-independent-reacceptance-20260928.md) | CR-F01～CR-F03 关闭、完整回归、旧包原请求升级恢复、正文停滞探针及跨重启场景审计 |
| [2026-09-27 受控修复补修复验](controlled-remediation-fixes-acceptance-20260927.md) | CR-F01～CR-F03 修复、149 项 Java/82 项 JS、增量审计、最终隔离包及 CR/R 逐项结果 |
| [2026-09-27 受控修复独立复验](controlled-remediation-independent-recheck-20260927.md) | 补修前输入恢复、正文超时及环境审计三个缺口的原始发现与失败证据 |
| [2026-09-27 受控修复首次隔离验收](controlled-remediation-acceptance-20260927.md) | 首次 CR/R 检查、真实数据库与合成 HTTP、进程中断及浏览器历史证据 |
| [2026-09-26 真实服务与效果验收](real-provider-acceptance-20260926.md) | 对话与嵌入鉴权、六场景混合检索诊断、逐例审阅及注入覆盖缺口 |
| [2026-09-18 真实模型基线](model-evaluation-20260918.md) | 六场景三次重复、实际用量、逐例审阅发现与评测限制 |
| [恢复验证改进与复测](recovery-evaluation-20260918.md) | 恢复证据补查、修改前后各 18 次比较、回归与未覆盖范围 |
| [诊断证据一致性改进](evidence-evaluation-20260918.md) | 事实与推断区分、现场工具原文、真实评测结果及中断记录 |
| [评测集成与兼容](../evaluation/README.md) | 独立项目边界、版本化来源、固定程序包与旧入口行为 |
| [修改规范](../AGENTS.md) | 代码分层、中文注释、接口与数据访问规则、验证要求 |
| [版本路线图](../ROADMAP.md) | 已实现范围与后续工单、权限、自动修复、多 Agent 和技能市场 |

## 开发方案

| 文档 | 范围与状态 |
| --- | --- |
| [企业独立部署与系统接入 V1 设计](plans/enterprise-integration-design.md) | 本开发包实现契约；资源、标准只读连接器、应用 API、权限、迁移与企业交付 |
| [企业独立部署与系统接入 V1 任务和验收](plans/enterprise-integration-tasks.md) | 已实施核心范围与34项验收要求；实际覆盖和未执行项见开发验证记录 |
| [诊断处理结果与评测案例沉淀 V1 设计](plans/diagnosis-outcome-cases-design.md) | 本开发包范围契约；处理结果、来源证据、案例版本、受控复现与评测接入 |
| [诊断处理结果与评测案例沉淀 V1 任务和验收](plans/diagnosis-outcome-cases-tasks.md) | 本开发包任务与验收定义；实际结果见对应开发验收记录 |

## 示例与随附资源

- [排障演示](demo/README.md)：79 秒真实操作视频、中文字幕与场景说明。
- [示例资料清单](../examples/knowledge/manifest.json)：三份自建 OrderBridge 版本手册与迁移说明，供工作台导入。
- [评测集成入口](../evaluation/README.md)：SupportOps 提供的来源与程序包，不包含外部评测平台实现。
- [内置 Skills](../src/main/resources/skills)：排障与升级核查方法，不含脚本执行能力。
- [本地前端依赖](../src/main/resources/static/vendor/README.md)：Markdown 解析器、图表库、图标的来源与许可。

部署命令以部署文档为准，模型默认值和优先级以配置文档及源码为准，数据库操作集中在维护文档。运行生成的日志、报告、模型权重与本地配置不属于源代码附件。

- [历史文件归属与兼容清单](evaluation-split-ownership.md)：旧表、旧接口与 CLI 的保留行为。
- [SupportOps 验证证据索引](supportops-validation-evidence-20261019.json)：完整回归的逐文件摘要及交付检查口径。
