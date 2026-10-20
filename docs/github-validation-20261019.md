# 2026-10-19 主线合并与 GitHub 验证

SupportOps 的 [PR #2](https://github.com/laohandong/supportops/pull/2) 已于 2026-10-19 合并到 `main`。合并提交为 `5c0956132536cc51e0f021d44b7acc0b0e5286b0`；该提交的 [Verify](https://github.com/laohandong/supportops/actions/runs/39248374704) 已完成且成功。2026-10-20 复核 GitHub API 时，主线仍指向该提交。逐作业、步骤及产物身份见 [公开证据索引](github-validation-evidence-20261019.json)。

## 提交与运行身份

| 阶段 | 提交 SHA | 事件与分支 | 结果 |
| --- | --- | --- | --- |
| 候选分支 | `afb6e520df55e0b03950c876016f7c93a9ea791c` | `push`，`prepare/supportops-20261019` | [Verify 39244176350](https://github.com/laohandong/supportops/actions/runs/39244176350)：成功 |
| PR #2 | `afb6e520df55e0b03950c876016f7c93a9ea791c` | `pull_request` | [Verify 39248215740](https://github.com/laohandong/supportops/actions/runs/39248215740)：成功 |
| 合并主线 | `5c0956132536cc51e0f021d44b7acc0b0e5286b0` | `push`，`main` | [Verify 39248374704](https://github.com/laohandong/supportops/actions/runs/39248374704)：成功 |

候选和 PR 通过与合并后主线通过分别核对。主线运行于北京时间 2026-10-19 19:57 开始，20:14 完成；`javascript`、`java` 两项作业均为 `completed / success`。

## 主线实际检查范围

| 作业或阶段 | 已通过的检查 |
| --- | --- |
| JavaScript | 锁定依赖安装、`npm audit --audit-level=high`、Node 测试 |
| Java 与真实存储 | JDK 21、Node 22.16.0、固定 Chromium，专属 MySQL/MinIO/Elasticsearch，完整 Maven `verify` 与打包 |
| 独立目标程序包 | SupportOps 固定目标 JAR、`target.json`、来源快照与程序产物归档 |
| 隔离与依赖 | 未登记资源拒绝、资源归属检查、外置模型依赖记录但不加载模型 |
| 运行与恢复 | 实际运行身份、真实 Chromium 操作、全新目标备份恢复矩阵 |
| 边界与清理 | 索引容量、健康响应、端口兼容、公开证据归档、停止并清理专属 CI 存储 |

具体步骤以该提交的 [Verify 工作流](https://github.com/laohandong/supportops/blob/5c0956132536cc51e0f021d44b7acc0b0e5286b0/.github/workflows/verify.yml) 和运行记录为准。评测平台实现、案例管理与实验执行由独立项目维护，不包含在此流程中。

## 产物与证据口径

| 产物 | 内容 | GitHub 产物 |
| --- | --- | --- |
| `supportops-evaluation-target` | 可独立消费的 SupportOps JAR、目标清单和来源快照 | [程序包 12184946198](https://github.com/laohandong/supportops/actions/runs/39248374704/artifacts/12184946198) |
| `reliability-public-evidence` | 脱敏 JSON、合成浏览器截图，含隔离、依赖、运行/恢复与边界结果 | [证据 12185457236](https://github.com/laohandong/supportops/actions/runs/39248374704/artifacts/12185457236) |

索引保存 GitHub API 报告的摘要、大小和到期时间；本次文档复核没有重新下载二进制产物核验摘要，也没有从远端原始 XML 提取逐用例数量。产物受 GitHub 保留期限与访问权限约束，不保证永久可下载。私有运行配置、数据库、Cookie、HAR、trace 和应用日志不属于上述公开运行证据。

2026-10-11 的 Java 171 项（166 通过、5 项 ONNX 条件跳过）与 JavaScript 79 项属于 [历史本地完整回归](supportops-validation-20261019.md)。该记录和[原证据索引](supportops-validation-evidence-20261019.json)保留原日期与提交准备快照，不能当作本次远端运行的测试数量。

## 适用限制

本工作流未读取本地模型密钥，也未下载可选 ONNX 权重。本地 HTTP 模型夹具验证工程协议，不证明真实服务商鉴权、模型质量或生产性能。旧真实模型记录仅适用于各自的程序与日期。

主线 Verify 的清理步骤成功，证明该次 CI 的专属资源流程通过；它不解决 2026-10-11 本地回归后 Docker 管理接口不可用、最终状态未能复核的历史缺口。当前工作台部署、业务数据切换、企业生产运行、多实例灾备及压力测试不属于本次结论。

结果仅对应表中 SHA。本记录之后的文档或代码更新，不自动继承该提交的远端验证结果。
