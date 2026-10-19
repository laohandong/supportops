# 受控修复中心 V1 补修与隔离复验

2026-09-27 已完成 CR-F01、CR-F02、CR-F03 三项补修，并重新执行完整 Java、JavaScript、真实隔离 MySQL、回环 HTTP 与受影响浏览器路径。CR-01～CR-09 的首版实现及下列 R01～R20 检查通过；当前结论仅适用于内置合成 OrderBridge，不代表真实企业订单落库、企业连接器或生产系统修复验收。本轮未调用真实模型服务。

[独立复验记录](controlled-remediation-independent-recheck-20260927.md) 中的三个问题在旧包上成立，其原始失败证据保留。[首次隔离验收](controlled-remediation-acceptance-20260927.md) 记录此前已执行的检查，不能单独证明这些负例通过；本记录给出补修后的结果。

## 补修结果

| 编号 | 改动与实际复验 |
| --- | --- |
| CR-F01 | 前端在冻结请求前校验名称、理由、固定动作及命令字段。服务端对有合法请求编号的字段拒绝保存账号、动作、目标、摘要与 `400/INVALID_INPUT` 回执；原编号换正文仍返回 409，不覆盖已受理请求。使用旧包实际生成空白理由卡住记录，升级同一隔离数据库后，GET 404 仍保留原 pending；显式原样重发获得持久化拒绝，随后查询原回执解除阻塞。最终前端空白名称/理由在本地拒绝，不产生 POST 或 pending。 |
| CR-F02 | 固定操作保留 10 秒总截止时间，HTTP 最多占用 3 秒；自定义异步正文订阅器在流式接收期间限制 64 KiB，并在截止时间取消订阅及请求。HTTP 等待不占用环境对象锁，超时释放共享操作准入。沿用独立复验的正文停滞探针，最终测得 3,017 ms，结果 `APPLIED/UNKNOWN`，12 秒后线程已结束；数据库用例证明迟到正文与后续核查不能升级成功，业务 POST 仍只有一次。 |
| CR-F03 | V10 新增独立场景审计表，场景服务在修改前短事务保存 STARTED，修改及读取证据后条件收尾为 APPLIED/UNKNOWN，记录操作者、固定目标、实例、场景、修订及前后事实。人工切换和同名重置共三次，形成三条持久化审计；冷重启后逐字段保持一致，页面仍可读取原实例历史。审计写入故障、完成保存故障、权限与非法场景均有真实 MySQL 负例。 |

场景修改前审计插入失败时，环境不修改；环境已改变但完成事实保存失败时，返回明确错误并保留 STARTED 意图，不声称已回滚或自动重做。场景历史不依赖提案外键，也不将历史成功作为当前执行的证据。

同步修复了静态资源版本号：普通刷新必须获取新校验与历史页面。最终包中的 `index.html`、`app.js`、`remediation.js` 与源码逐字节一致；旧浏览器标签普通刷新后已显示新的中文校验。本轮没有通过清空 localStorage、把 404 当拒绝、生成替代执行请求或删除有效断言来解除问题。

## CR-01～CR-09 交付

| 任务 | 状态 | 实现及最终验证 |
| --- | --- | --- |
| CR-01 契约与边界 | 完成 | 固定动作、目标、版本、摘要、状态与审批政策保持；补齐总预算与 HTTP 正文预算，模型/MCP 不取得写权限。 |
| CR-02 数据与迁移 | 完成 | V9 五张修复表与 V10 场景审计增量迁移；MyBatis-Plus、短事务、唯一约束、条件更新、分页游标；V8→V9→V10 旧数据保护通过。 |
| CR-03 示例适配器 | 完成 | 真实配置变换、原操作回执与一次业务 HTTP 验证；正文停滞、超限、失败及证据不足保持正确事实。 |
| CR-04 提案与审批 | 完成 | 不可变提案、人工审批绑定、角色重查、时效、驳回、撤销与环境失效；竞争和篡改负例通过。 |
| CR-05 执行与恢复 | 完成 | 固定请求幂等、共享准入、UNKNOWN 核查和人工结束；事务故障、三个真实 JVM 中断窗口、迟到结果与重启不重做通过。 |
| CR-06 API 与旧入口 | 完成 | 管理员 GET/POST 接口、严格字段及持久化拒绝；旧入口委托同一审批服务；最终包新旧入口返回同一执行，无审批与内容冲突均 409。 |
| CR-07 界面与恢复 | 完成 | 提案全流程、应用/验证分栏、报告、原请求恢复、输入预检查及跨重启场景审计页面；受影响浏览器路径复验通过。 |
| CR-08 隔离验收 | 完成 | 完整 Java/JS、独立 SQL、真实本地 HTTP、故障及浏览器证据；使用新数据库、19194/19195 和独立运行目录。 |
| CR-09 文档与交付 | 完成 | 使用、接口、迁移、部署、架构、规划状态和导航更新；保留历史失败，当前结论与首次验收分开。 |

## R01～R20 证据矩阵

下列自动化检查全部随本轮完整 Java/JS 回归重跑。数据库事实由测试专用独立 JDBC 查询核对。浏览器栏明确区分本轮补验和首次验收记录，本轮没有重复所有历史浏览器路径。

| 编号 | 本轮实际检查与结果 |
| --- | --- |
| R01 迁移闭环 | `previewApprovalExecutionAndReplayUseActualHttp` 通过；最终包浏览器新提案、批准、执行，保存 APPLIED/PASSED/SUCCEEDED、HTTP 200、accepted=true、原请求身份及四类提案审计。 |
| R02 预检查与创建 | 同用例确认预检查、提案和批准零应用、零验证 POST；浏览器实例模型无密钥，未调用真实模型服务。 |
| R03 不适用动作 | `unsuitableAndUnknownVersionNeverApply` 通过：健康、凭据、下游故障及未知版本不误套迁移；无关配置保持用例通过。 |
| R04 无审批与越权 | HTTP 集成确认匿名 401、USER/缺 POST 头 403、错误方法不写入；最终包旧入口空正文 409，执行数和审计数不变。 |
| R05 篡改绑定 | `bindingDriftAndRequestConflictsAreStable`、`revokedApproverCannotAuthorizeExecution` 与严格 HTTP 正文用例通过；客户端无法指定审批人、任意目标或动作参数。 |
| R06 环境漂移 | `restoredConfigurationAndRestartInvalidateOriginalApproval` 通过；同名重置、改回原配置及新实例均不能复活旧批准。场景审计另有三条真实记录与重启回读。 |
| R07 决策与时效 | `rejectionCancellationAndExpiryAreFinal`、`competingDecisionsAndCancellationHaveOneWinner` 通过；首次浏览器驳回、撤销、过期证据保留。 |
| R08 重复与并发 | `concurrentRequestsConsumeOnlyOneApproval` 及 HTTP 新旧入口交叉重放通过；最终包重放同一请求两入口均 200、同一 execution，SQL 仍三执行、十二条提案审计。 |
| R09 编号冲突 | 原编号不同正文、目标及动作冲突；新增持久化字段拒绝不能被改正文复活，也不能覆盖原受理记录。最终包改变原 expectedVersion 返回 409。 |
| R10 准入竞争 | `activeDiagnosisBlocksRepairBeforeAcceptance`、`actualAdmissionRejectsDiagnosisScenarioAndUnknownClosureWhileRepairIsInFlight` 通过；正文等待时只读可用，修改与新诊断被拒绝，超时后准入释放。 |
| R11 应用后验证失败 | `appliedVerificationFailuresStayApplied` 通过：实际 HTTP 401、503、accepted=false 保留 APPLIED/FAILED，无自动回滚。 |
| R12 证据不足 | `missingOriginalReceiptStaysUnknownAndManualClosurePreservesFacts` 通过：健康检查、旧请求成功及当前配置正确均不能替代原验证回执。 |
| R13 响应丢失与刷新 | 本轮代理在真实执行完成后丢弃响应，再返回四次 GET 503；浏览器刷新保留原编号，第五次 GET 自动确认，43,438 ms 内恢复；原 POST 后零新增 POST。账号隔离 JS 重跑通过。 |
| R14 事务故障 | `acceptanceTransactionFailureHasZeroExternalWrites`、`completionFaultReconcilesOriginalReceiptWithoutNewHttp` 通过；场景审计故障用例另证明修改前必须先有持久化意图。 |
| R15 中断与重启 | `realJvmCrashAtThreeStagesNeverReexecutes` 通过：受理、应用、验证三个窗口真实子 JVM 退出，无自动重跑。本轮浏览器实例冷重启，历史执行行及请求/提案数量逐字段保持。 |
| R16 UNKNOWN | 缺回执核查、在途核查/人工结束保护、理由约束、CLOSED_UNRESOLVED 保留 UNKNOWN 与新提案重新审批用例通过；首次明确标注的浏览器 UNKNOWN 展示证据保留。 |
| R17 迟到与资源限制 | 回执容量和 70,000 字节正文负例通过；新增 `responseBodyStallTimesOutAndLateSuccessCannotUpgradeUnknown` 与原独立停滞探针通过，超时不重发、不晚到变绿。 |
| R18 增量迁移 | `RemediationMigrationTest` 验证 V8→V9 和带五类历史修复数据的 V9→V10；账号、诊断、事件、评测、审阅与 V9 历史保持，外键/唯一/排序/回滚真实 MySQL 检查通过。旧包的七个既有 SQL 文件与最终包逐字节相同。 |
| R19 浏览器与输入安全 | 本轮实际空白输入预检查、旧 pending 安全恢复、普通刷新、成功详情、响应丢失与跨重启审计回读通过；JS 账号隔离/恢复重跑通过。390px、Tab 焦点与恶意文本浏览器证据来自首次验收，未冒充本轮重拍。 |
| R20 报告与回归 | 最终 JSON 报告范围为 SYNTHETIC_LOOPBACK_HTTP，执行与回执一致；运行包 OpenAPI 91 个业务操作，新审计 VO 15 字段；完整 Java/JS 通过，原诊断/评测/MCP 只读范围回归通过。 |

## 构建、原始失败与限制

| 检查 | 结果 |
| --- | --- |
| 定向持久化与迁移 | 27 项通过，零失败：修复持久化 20、场景审计 3、账号迁移 3、修复迁移 1。 |
| 完整 Maven `verify`，13:34:31 结束 | 149 项：144 通过、0 失败、0 错误、5 项既有可选 ONNX 跳过；BUILD SUCCESS。 |
| JavaScript | 82/82 通过、0 跳过。 |
| 最终打包 | 静态资源版本刷新后重新打包成功；Java 源码未再改变，最终包 359 个 class 与已验证 `target/classes` 字节一致。 |
| 最终运行包 HTTP/SQL | 报告及 OpenAPI读取成功；新旧执行重放同一 execution；内容冲突、旧入口无审批拒绝；SQL 数量不增加。 |
| 重启持久化 | 三条场景历史、原两条执行及八条请求保持；重启不自动执行。重启后的第三条执行来自明确的新提案、新批准、新执行。 |
| 文档与差异 | 32 个 Markdown 文件的 217 个本地链接存在；`git diff --check` 通过。 |

第一次完整补修回归为 149 项、1 失败、5 跳过：`EvaluationMigrationTest` 仍预期四次迁移，V10 后实际为五次。更新数量与说明，保留所有旧数据断言后完整重跑通过。失败日志 `verify.log` 与通过日志 `verify-pass.log` 均保留。

五项 ONNX 检查要求提供真实模型及 tokenizer 文件，属于既有可选项，本轮未执行。没有跳过修复中心必测用例。本地 HTTP 故障响应器只验证协议和恢复行为，不提供真实服务商鉴权或模型效果结论。

本轮追加窄屏重拍尝试未形成有效新证据：浏览器视口设置后 DOM 仍报告 1280px，测试 iframe 被应用 `frame-ancestors 'none'` 拒绝。没有移除应用保护或将默认宽度截图标成 390px 通过；R19 的首次 390px 实测证据仍单独注明来源。

## 环境与证据

最终补修实例为 `http://127.0.0.1:19194/#remediation`，独立 MySQL 数据库 `so_eval_2bc83c4330a08273a220b467`、Excel 数据库 `so_xl_2bc83c4330a08273a220b467`，对象/索引前缀 `so-eval-2bc83c4330a08273a220b467`。依赖仍为已核对的隔离 MySQL 23306、MinIO 29000、Elasticsearch 29200；运行不读取已有模型凭据，向量、评测执行器、飞书与知识后台任务关闭。

最终 JAR SHA256 为 `25A893B84FFE8DEB8834E6C2EE7ABD4C053FF73AFA62DB6A983CD6C5E6783F31`，由标准启动脚本复制到 `.local/runtime/1790487355727-20a4e63b/application.jar`，PID 55040、父启动器 51308。运行包报告、OpenAPI 与静态资源已核对。19195 故障代理验收后按进程归属停止。

旧 19191、19193 应用仍分别为 PID 51672、53404，保持原包摘要 `1D053960C0799603BF2EBB4DC76366CF84CB2539A5539ED26B981C6461463F80`；其原数据库和运行副本未替换。因此这两个旧页面仍属于补修前版本，查看本次结果应使用 19194。已有评测中心与其他未提交改动保留，无提交、推送或发布。

最终提案 `610025e6-8671-4d08-8d8e-6ad823dd0f01`，执行 `f276a979-cd42-4417-8232-d4f02dad9c0b`，固定执行请求 `400b1f04-5d2c-40f9-9934-f5c3be4f6253`，原操作 `2a6ec706-ebfe-4e28-9582-02ea797946ec`，验证请求 `1c4c6ece-8086-4764-bee9-78e1230d6f53`。最终库共有三提案、三执行、十一请求、十二条提案审计、三条场景审计；其中两条输入拒绝并没有创建提案或执行。

原始证据保存在忽略目录 `.local/controlled-remediation-fixes-20260927/`，不纳入源码发布：

- 构建：`targeted.log`、`verify.log`、`verify-pass.log`、`javascript-final.log`、`package-final.log`、`package-evidence.json`。
- 输入恢复：`before-invalid-stuck.png`、`after-upgrade-still-pending.png`、`after-invalid-recovered.png`、`final-blank-input-preflight.png`、`sql-before.json`。
- 超时：沿用独立探针 `TimeoutProbe.java`，`timeout-probe-fixed.log`、真实 HTTP/数据库测试报告。
- 响应丢失：`browser-real-response-lost.png`、`browser-original-request-after-refresh.png`、`browser-proxy-final.jsonl`、`response-loss-summary.json`。
- 审计与重启：`browser-scene-audit.png`、`browser-scene-audit-after-restart.png`、`sql-before-restart.json`、`sql-restart.json`、`sql-after.json`。
- 最终交付：`final-package-success.png`、`report-final.json`、`openapi-final.json`、`final-facts.json`、`runtime-final.json`。

首版必需实现和上述隔离检查没有未完成项。真实企业连接器、双人复核、自动回滚、多节点执行和真实模型服务不属于本次范围，当前日常工作台未更新；这份记录不提供对应验收结论。
