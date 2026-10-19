# 受控修复中心 V1 隔离验收记录

历史记录说明：本文保存首次验收的版本、操作及证据。随后 [独立复验](controlled-remediation-independent-recheck-20260927.md) 在该包发现输入恢复、正文超时和场景审计三项缺口，因此本文的“完成”仅代表当时所列检查，不能单独证明完整 V1 验收。三项补修、完整重跑与当前交付结论见 [补修与隔离复验](controlled-remediation-fixes-acceptance-20260927.md)；19191 是保留的旧版本，补修版本使用 19194。

本记录覆盖 2026-09-26 至 2026-09-27 的实现与隔离验证。范围仅为内置合成 OrderBridge 的固定配置迁移、人工审批、执行后验证、审计和未知结果恢复。实际配置变换、回环 HTTP、真实 MySQL 和真实子 JVM 中断均已执行；不代表企业订单落库、真实企业连接器或生产系统修复验收。本次没有调用真实模型服务。

## 交付与环境

固定动作是 `ORDERBRIDGE_MIGRATE_SYNC_PATH`，目标为 `BUILTIN_ORDERBRIDGE`；动作、验证、提案结构和审批策略版本均为 1。提案有效 30 分钟，批准执行窗口 10 分钟且不晚于提案期限。同一管理员可以提案和批准，属于单管理员显式审批。

- 使用 JDK 21 和仓库 Maven Wrapper，构建串行执行。
- 存储依赖为经现场核对的隔离 MySQL `23306`、MinIO `29000`、Elasticsearch `29200`。自动化测试每组创建随机 `so_it_*` / `so_xl_*` 数据域，按原隔离夹具清理。
- 浏览器使用独立数据库 `so_cr_20260926_f3e817`、Excel 数据域 `so_cr_xl_20260926_f3e817`、存储命名空间 `so-cr-20260926-f3e817`，应用端口 `19191`、测试代理 `19192`。
- 应用运行目录是 `.local/controlled-remediation-work/runtime`，运行最终 JAR 副本，未替换日常工作台。
- 最终包 SHA256：`1D053960C0799603BF2EBB4DC76366CF84CB2539A5539ED26B981C6461463F80`。浏览器副本的 PID、启动时间和摘要保存在本地 `runtime.json`；进程身份随恢复启动重新记录。
- 浏览器实例对话模型未配置、无模型密钥，向量服务关闭；评测执行器、飞书及知识后台任务关闭。已有模型协议测试只使用本地 HTTP 夹具。

V9 仅新增五张修复表。没有改写 B3、V4–V8，没有清空原数据库，也没有提交、推送或发布。既有评测中心与任务开始时的未提交文件保留；共享文件只接入修复入口或同步兼容断言。

## CR 任务交付

| 任务 | 实现与实际验证 |
| --- | --- |
| CR-01 | 固定契约、明确 DTO/VO、状态与版本、规范化摘要、时效及能力边界已实现；模型/MCP 无写入口。 |
| CR-02 | 五表增量迁移、MyBatis-Plus、唯一约束、外键、短事务、条件更新、游标与分页已实现；真实 V8→V9 与旧数据保护测试通过。 |
| CR-03 | 实例与单调修订、实际字段预检查、确定性变换、原应用/验证回执及容量登记已实现；真实 410→200 和 HTTP 失败负例通过。 |
| CR-04 | 不可变快照、审批绑定、当前角色重查、驳回、撤销、过期与失效已实现；竞争、配置改回和实例更换测试通过。 |
| CR-05 | 一次受理、一执行、固定请求、共享诊断准入、UNKNOWN 核查及人工结束已实现；事务故障和三个真实 JVM 中断窗口通过。 |
| CR-06 | 14 个 GET/POST 操作、管理员权限、严格字段白名单、旧入口审批收口与中文 OpenAPI 已实现；真实 HTTP 权限和新旧入口重放通过。 |
| CR-07 | 修复中心列表、详情、差异、审批、执行、核查、审计、导出、诊断/示例入口和账号隔离恢复已实现；主要浏览器流程、390px 与键盘通过。 |
| CR-08 | 完整 Java、JavaScript、真实数据库、下游、进程和浏览器验收已完成；最终包连续四次读失败并刷新后的自动核查补验通过。 |
| CR-09 | 使用说明、API、架构、权限、迁移、部署、产品/演示边界与本记录已更新；规划清单按最终证据全部勾选。 |

## R01～R20 证据矩阵

自动化用例位于 `RemediationPersistenceTest`、`RemediationMigrationTest`、`SupportOpsIntegrationTest` 和 `src/test/js/remediation.test.mjs`。数据库事实使用独立 JDBC 查询核对，不能只通过相同 Mapper 读写互证。

| 编号 | 实际验证与结果 |
| --- | --- |
| R01 | 真实迁移前 HTTP 410；人工创建/批准后 APPLIED + PASSED，固定新请求 HTTP 200、accepted=true、路径和回显身份一致。数据库一执行、一次 ACCEPTED、一套完成审计；浏览器闭环和 JSON 下载通过。 |
| R02 | 预检查、创建、批准前后修订和日志条数不变，应用/验证计数为零；无模型调用。 |
| R03 | healthy、credentials、unavailable、insufficient 及未知 3.0 版本均不能创建固定迁移；无关配置保留。 |
| R04 | 实际 HTTP：匿名 401、USER 403、缺 POST 头 403；旧空请求 409 `REPAIR_APPROVAL_REQUIRED`；GET 执行 405；零写入。 |
| R05 | planHash、expectedVersion、正文审批人等伪造字段拒绝；审批后撤销 ADMIN 角色再执行拒绝，零应用。协议不接受任意目标/参数。 |
| R06 | 同名场景重置、健康后再改回同样配置仍失效；新实例启动将旧批准提案标记 STALE。旧版本请求与读取当前版本后的请求分别被版本/状态保护拒绝。浏览器漂移拒绝通过。 |
| R07 | 驳回、批准后撤销、提案期限与批准窗口过期均零应用；批准与驳回、撤销与执行的真实数据库竞争只有唯一胜者。浏览器驳回、撤销和过期通过。 |
| R08 | 同请求重放、不同请求并发、新旧入口交叉重放，均仅一执行、一次应用、一次验证。 |
| R09 | 同 requestId 换正文、命令或目标返回稳定冲突；原受理/拒绝记录保留，不能复活原意图。 |
| R10 | 使用真实 RunService 准入：活跃诊断禁止受理修复，修复在途禁止新诊断和场景操作；已存在的渠道请求仍可只读重放，内容冲突仍拒绝。 |
| R11 | 实际本地 HTTP 401、503、200/accepted=false 均保留 APPLIED + FAILED，执行为 VERIFICATION_FAILED；不自动回滚。 |
| R12 | 原回执丢失时，即使健康检查 200、独立历史同步成功、当前配置正确，核查仍 UNKNOWN；无第二次修复验证。 |
| R13 | 测试代理在真实完成后丢失执行响应，并连续四次返回原请求读取 503；浏览器刷新保留同一编号、原账号重登恢复，USER 不读取管理员恢复记录。最终包自动补验通过：约40.667秒后读取原受理结果，五次恢复调用全部GET，响应丢失后零POST，待确认面板自动关闭并恢复详情。 |
| R14 | 受理审计写入故障回滚提案/请求/执行，零外部写入；完成事务故障保留 RUNNING 和原操作，核查补录 SUCCEEDED，仅一次配置应用与一次业务 POST。 |
| R15 | 真实子 JVM 在 ACCEPTED、APPLIED、VERIFIED 三个窗口分别 `Runtime.halt` 退出。父实例确认 PID/启动身份后标记 UNKNOWN；不重新应用、不重新发送验证，缺原回执仍 UNKNOWN。 |
| R16 | 在途时核查/结束被拒绝；缺证据核查不重发。人工结束必须有理由，保存 CLOSED_UNRESOLVED 并保留 UNKNOWN；新提案关联原 execution，重新采集且仍待审批。数据库测试和浏览器流程通过。 |
| R17 | 已终态记录拒绝迟到核查覆盖；回执容量 64 满时受理前拒绝，不驱逐未决证据；70,000 字节真实 HTTP 响应保存 APPLIED + UNKNOWN 和超限错误，核查不再 POST，持久化回执小于 64 KiB。 |
| R18 | 真实 V8 数据带账号、诊断/事件、评测/组/样本/审阅升级 V9 后原字段保留；SQL 核对分页顺序、决策唯一约束、外键、事务回滚和全量字段中文说明。 |
| R19 | 浏览器完整闭环、驳回、撤销、过期、漂移、丢失响应、刷新与账号切换通过；390px 详情宽度与 scrollWidth 均为390，无详情横向溢出；Tab 到理由框，`<img ... onerror=...>` 作为文本，无 img 节点及弹窗。 |
| R20 | 报告读取已保存提案、决策、执行和审计；JSON 实际下载，中文 OpenAPI 对应90个实际业务操作；完整 Java/JS 回归通过，未改变 MCP 四个只读工具及原诊断/评测语义。 |

浏览器 UNKNOWN 页面使用明确标注 `ISOLATED_BROWSER_FIXTURE` 的专用数据库负例，检查展示、只读核查、理由及人工结束；它不是进程中断证明。R15 的实际中断证据来自真实子 JVM。401/503/accepted=false 和正文超限使用测试专用 HTTP 响应器，未给生产 API 增加故障、地址或任意操作参数。

## 构建与故障修复记录

| 检查 | 实际结果 |
| --- | --- |
| 完整 `mvnw ... verify`，23:38:26结束 | 142项：137通过、0失败、0错误、5项既有可选 ONNX 跳过；BUILD SUCCESS。 |
| 新增 HTTP 超限负例后的定向 `-Dtest=RemediationPersistenceTest verify`，23:40:26结束 | 19/19通过、0跳过；最终源码前端恢复修复已打包。 |
| V8→V9迁移 | `RemediationMigrationTest` 1/1通过；账户和评测迁移回归亦通过。 |
| 实际 HTTP、权限、OpenAPI、MCP | `SupportOpsIntegrationTest` 27/27通过。 |
| JavaScript | 80/80通过、0跳过。 |
| 最终包自动恢复浏览器补验，2026-09-27 12:09–12:10 | 完成真实迁移后丢失响应，连续四次原请求读取503并刷新，随后自动确认原请求；一执行、一执行请求、一次受理/完成审计，零自动重发。 |
| 文档与工作区检查 | `git diff --check`通过，146个文档本地链接有效，开始时记录的45个已有改动路径全部保留；无提交、推送、发布或无关还原。 |

五项未执行的 ONNX 检查要求显式指定真实模型及 tokenizer 文件，属于既有可选重排序验证；不以历史通过结果声称本次已执行，也未跳过任何修复中心必测项。最后一次全量 Java 回归后新增的是一项 HTTP 超限测试；该项随全部19个修复持久化用例实际通过，不把全量回归数量写成143。

保留的原始失败及修复如下：

1. 初次完整回归139项有10项失败：评测使用旧 JAR，新增示例迁移说明与包内快照不一致；账户迁移数量仍停在旧版本。先同步打包文件，再更新新增V9的数量和中文说明要求，保留原数据保护断言；评测10/10与账户3/3复验通过。
2. 新重启用例预期状态冲突，但服务器先检查版本返回版本冲突。改为同时验证旧版本拒绝和读取当前版本后的状态拒绝，零应用断言保留；完整回归通过。
3. springdoc 将共享 record 字段引用折叠，丢失三个上下文说明。使用合法 `allOf` 维护引用与中文描述、严格 DTO 白名单；实际生成 OpenAPI 检查通过。
4. 前端恢复读取失败后的低频计时器需要继续安排只读核查；已修复，不生成替代 POST，JavaScript回归通过。最终包经过连续四次读取失败后自动续查通过，五次恢复调用均为原编号GET。
5. 自动补验的首个页面观察在第四次读取失败窗口内超时；保留原请求且未手动查询或重发。下一次既定低频读取于12:09:52获得原结果，页面随后自动显示保存的成功证据；不能把观察超时当作业务失败或据此新建执行。

## 中断后的环境恢复及最终补验

2026-09-27继续时，隔离应用和Docker引擎均已停止。Docker日志确认临时 `sailor-ingest.sock` 和 `docker-secrets-engine/engine.sock` 无法访问，导致引擎初始化中止。在明确授权范围备份两个临时目录后，引擎恢复，原三个 `supportops-ec-20260926` 存储容器使用原卷启动并达到healthy。没有删除或重建任何容器、镜像、卷或数据库；没有启动日常工作台或其他项目实例。

最终JAR以PID51672、启动时间 `2026-09-27T04:07:19.9912200Z` 在19191启动，副本摘要与最终包一致。Flyway读取既有V9，零新增迁移。原批准提案 `5fb581f8-ddcf-45b9-840b-47b0b601545f` 从APPROVED转STALE，没有执行行；原两个SUCCEEDED和一个CLOSED_UNRESOLVED执行保持原身份和应用/验证事实。

新提案 `2397a406-373f-4430-a24d-7def69ac6ac8` 重新采集并人工审批。12:09:11完成配置应用（修订1→2）和一次HTTP200/accepted=true验证后，测试代理丢弃执行响应。原请求 `7b75333d-32d5-480e-ae67-023108cdd55e` 刷新前后保持一致；12:09:11、12:09:22、12:09:32、12:09:42四次读取返回503，12:09:52自动读取原受理结果并恢复详情。没有点击“查询原请求”或“原样重发原请求”。代理记录只含一个执行POST、五次原请求GET，响应丢失后的写请求为零。

独立SQL核对该提案一执行、一条EXECUTE请求、一次ACCEPTED与一次FINISHED审计；总执行由原3条变4条，仅新增本次人工执行。执行 `c66539e8-2003-4c0e-bf42-b631f52362a4` 保存SUCCEEDED/APPLIED/PASSED，原操作 `3316c9d8-ec17-4415-83a0-a5bbeb8b65b7` 与验证请求 `2ca80dd2-e3ec-4d40-9e9b-9ba218a7ba31` 在丢失响应、请求查询、报告、下游最近日志和数据库中一致。报告保持 `SYNTHETIC_LOOPBACK_HTTP`。实际HTTP回读模型未配置且无密钥、向量禁用、诊断列表为空。

CR-01～CR-09与R01～R20的V1实现及隔离验收已完成，没有未完成的首版必测项。当前独立运行入口为 `http://127.0.0.1:19191/#remediation`，已核对PID与回环监听一致。补验结束后核对脚本路径并停止本任务的19192故障代理，保留19191应用；未启动或替换日常工作台。

## 证据位置与限制

原始构建日志、独立SQL核对、HTTP报告、JAR摘要、进程身份和浏览器PNG保存在忽略目录 `.local/controlled-remediation-work/`。主要文件是 `verify.log`、`verify-final.log`、`verify-pass.log`、`remediation-final.log`、`js-final.log`、`runtime.json`，以及 `browser-success.png`、`browser-narrow.png`、`browser-lost-response.png`、`browser-response-recovered.png`、`browser-user-boundary.png`、`browser-cancelled.png`、`browser-stale.png`、`browser-expired.png`、`browser-unknown.png`、`browser-closed-unknown.png`。这些本地证据不作为源代码或凭据附件发布。

最终补验证据为 `browser-final-auto-pending.png`、`browser-final-auto-recovered.png`、`browser-final-auto-observation.json`、`browser-proxy-final.jsonl`、`browser-sql-before-final.txt`、`browser-sql-final.txt`、`final-db-facts.json`、`report-final.json`、`request-final.json`、`demo-final.json`、`model-disabled-final.json` 和 `final-auto-result.json`。独立应用通过键盘打开最终详情，控制台无警告/错误；正常视口截图为 `browser-final-delivery.png` 和 `browser-final-result-viewport.png`。`dependency-ownership-final.json` 保存容器标签、回环端口及原卷身份，`docker-secrets-recovery.json` 保存两个可恢复临时目录备份的位置，`runtime-final.json` 保存应用身份、JAR摘要及代理已停止的最终监听状态。

使用说明见 [受控修复中心](controlled-remediation.md)，范围与契约见 [V1设计](plans/controlled-remediation-design.md)。V1没有双人复核、自动回滚、企业连接器、多节点目标所有权或模型执行授权；当前工作台未更新。本次结果仅说明已列合成环境和隔离路径的行为。
