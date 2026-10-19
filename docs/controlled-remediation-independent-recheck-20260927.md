# 受控修复中心 V1 独立验收记录

验收日期：2026-09-27。依据 [V1 设计](plans/controlled-remediation-design.md) 和 [开发任务清单](plans/controlled-remediation-tasks.md)，核对实现、重新运行现有测试，并补充独立浏览器与真实 HTTP 负例。

**结论：暂不通过完整 V1 验收。** 正常修复闭环和现有自动化回归通过，但确认三项未完成的行为：输入校验失败导致页面无法恢复、响应正文停滞未受执行预算约束、示例环境变更未形成持久化审计。既有 [开发方隔离验收记录](controlled-remediation-acceptance-20260927.md) 保留为历史证据，其“全部完成”结论不覆盖本次发现的负例。

## 1. 本轮范围与环境

- 未修改生产代码、数据库迁移或已有测试，未提交、推送或部署日常工作台。
- 重新执行 JDK 21 下的完整 Maven verify 和全部 JavaScript 测试，构建串行。
- 实际依赖为本机隔离 MySQL 23306、MinIO 29000、Elasticsearch 29200；核对了容器、监听与正在运行的 Java 进程。
- 保留开发方 19191 实例及原有数据库。本轮另建 19193 实例，数据库 `so_eval_1ef70620f58514997b137aaf`，Excel 数据域 `so_xl_1ef70620f58514997b137aaf`，存储前缀 `so-eval-1ef70620f58514997b137aaf`。
- 本轮实例 PID 53404，使用 `scripts/start.mjs` 创建运行 JAR 副本；实际应用启动身份和资源所有权保存在本地证据目录。
- 对话未配置、向量禁用，评测执行器、飞书与知识后台任务关闭；没有真实模型调用。
- 完整回归重新打包后的 JAR SHA256 与开发方验收包一致：`1d053960c0799603bf2ebb4dc76366cf84cb2539a5539ed26b981c6461463f80`。本轮浏览器使用同一内容的副本。

## 2. 已通过的检查

| 检查 | 本轮实际结果 |
| --- | --- |
| Java 完整 verify | 143 项，138 通过，0 失败，0 错误，5 项既有可选 ONNX 跳过；BUILD SUCCESS，约 8 分 19 秒 |
| 修复持久化用例 | 19/19 通过，包含现有真实 HTTP、并发、事务失败、容量和三个 JVM 中断窗口 |
| SupportOps HTTP 集成 | 27/27 通过，包含权限、旧入口与 OpenAPI/MCP 回归 |
| V8→V9 迁移 | 本轮全量回归中的迁移用例通过 |
| JavaScript | 80/80 通过，无跳过；其中现有修复测试主要覆盖 pending 存储和请求身份 |
| 新实例浏览器正常闭环 | 创建、批准、执行、结果及审计可用；模型未配置时仍可完成 |
| 浏览器成功证据 | 提案 `28936e2c-7715-4436-a57a-38acca0be798`；配置 revision 1→2，应用 APPLIED，验证 PASSED，HTTP 200、accepted=true |
| 独立 SQL 核对 | 一提案、一执行，CREATED / APPROVE / ACCEPTED / FINISHED 四条事件；报告可读取原证据 |
| 工作区检查 | `git diff --check` 通过；已有业务改动保留 |

成功执行 operationId 为 `cf2c9705-314f-444d-9b6c-02a694c81533`，验证请求为 `809ab118-3251-4602-bebb-d3dd2cf76ba1`。两者在页面和导出报告中一致。

这些通过结果证明对应已执行路径，不能抵消下列新增负例。未把开发方所有历史浏览器截图当成本轮重新执行；本轮浏览器重点复跑正常闭环及输入失败恢复。

## 3. 发现一：输入校验失败后，账号修复操作被永久待确认请求阻塞

优先级：**P1**。涉及 CR-06 / CR-07，补充 R13 / R19 的失败恢复验收。

定位：

- `src/main/resources/static/remediation.js:126`：提交前未拒绝纯空白名称/理由，直接冻结请求。
- `src/main/resources/static/remediation.js:77`：POST 错误统一转入按请求编号核查。
- `src/main/java/io/supportops/remediation/controller/RemediationInput.java:28`：Bean Validation 在调用服务、保存请求记录之前拒绝正文。

复现步骤：

1. 在迁移故障现场打开“新建修复提案”。
2. 名称填写正常文字，理由填写三个空格，点击“创建待审批提案”。原生 required 校验允许非空的空格字符串。
3. 后端返回 400 `INVALID_INPUT`；随后查询固定请求返回 404 `REMEDIATION_REQUEST_NOT_FOUND`。
4. 页面永久显示“原请求待确认”。将理由改成正常文字、点击原样重发、刷新后重新新建，均无法解除阻塞；创建按钮被禁用，原请求正文仍是空格。

实际请求 ID：`ca6a67ba-891d-4b73-a886-5ce89be282a5`。独立 SQL 核对该 requestId 的 `remediation_requests` 行数为 0，提案/执行仍分别只有前一成功案例的一条，没有创建非法业务记录。

原因是“确定的提交前校验拒绝”和“写入结果不明”共用同一恢复路径；当前账号只允许一个 pending，而这条请求永远无法产生可供 confirm 的持久化受理或拒绝结果。修正输入不会改变已冻结正文，普通用户操作无法恢复。

修复要求：

- 冻结前完整校验名称、理由及 DTO 约束，明确拒绝 trim 后为空的值。
- 为已经冻结但在业务受理前被确定拒绝的请求定义可恢复协议；不能只添加前端 trim 后忽略已有卡住记录。
- 保留原规则：不能依据普通 404、网络异常或读取失败随意清除可能已执行的请求。
- 增加真实页面和接口测试：空白名称/理由、明确校验拒绝、刷新后恢复；同时复跑真正的执行响应丢失用例，证明没有放开重复副作用。

## 4. 发现二：HTTP 正文停滞可以突破冻结的执行预算

优先级：**P2**。涉及 CR-03 / CR-05 的超时、互斥与证据状态。

定位：`src/main/java/io/supportops/demo/service/impl/DemoEnvironment.java:220` 至 `:227`。当前通过 `HttpRequest.timeout(3s)` 配合 `http.send(...BodyHandlers.ofString())` 读取完整正文。动作目录与冻结提案声明 budgetSeconds=10，但没有覆盖正文读取的端到端截止控制。

独立复现使用正式 DemoEnvironment，在测试专用回环下游先发送 HTTP 200 响应头和一个正文字符，随后阻塞余下正文；没有改变产品接口或连接真实服务。

两次观察一致：

```json
{
  "httpRequestTimeoutSeconds": 3,
  "declaredBudgetSeconds": 10,
  "stillRunningAfterTwelveSeconds": true,
  "elapsedMillis": 12008,
  "applicationStatus": "APPLIED",
  "verificationStatus": "PASSED"
}
```

12 秒时验证仍未结束。测试主动释放剩余正文后，正式适配器仍记录 PASSED，未执行声明的总预算。验证方法持有示例环境监视器，服务编排还持有人工操作准入；正文持续不结束时会持续阻塞现场读取和后续诊断/人工操作。

修复要求：

- 让真正的截止时间覆盖响应体消费及执行阶段，不能只依赖请求头阶段的超时；接收过程同时施加字节上限。
- 到期后关闭/取消本次读取并释放资源，保留 APPLIED 和验证 UNKNOWN；不自动补发业务 POST。
- 迟到正文不得把已超时结论覆盖成成功；核查只处理原操作证据。
- 补充“立即返回头、正文延迟/停止”“大正文”“超时后迟到完成”用例，检查预算、资源退出、下一任务准入及只发送一次 POST。

本次实证是超过预算 12 秒仍未结束，不把它扩写成已运行无限时长的测试。

## 5. 发现三：场景重置没有持久化审计

优先级：**P2**。涉及 CR-02 / CR-06；对应设计第 3.4 节及第 8.2 节的环境操作来源、目标和操作者记录。

定位：`src/main/java/io/supportops/demo/service/impl/DemoEnvironment.java:119` 至 `:126`。场景操作只增加内存 revision、覆盖 `changeSource` 并清空最近日志，没有写入追加式审计。`V9__controlled_remediation.sql:42` 又要求事件 plan_id 非空，现有表结构不能表达没有提案的场景重置事件。

实测：在本轮隔离实例连续切换 healthy 和 migration，环境 revision 从 3 变为 5；独立 SQL 查询 `remediation_events` 仍为 4 条，全部属于先前的成功修复。只能读取最后一次 `changeSource`，无法回查两次操作的时间、前后状态和完整顺序；重启还会丢失这项内存来源。

旧审批仍会因为 revision 改变而被阻止，本问题不是已发现审批绕过，而是方案要求的环境审计缺失。当前状态字段不能替代持久化操作历史。

修复要求：

- 经服务边界持久化场景构造/重置的操作者、目标/实例、前后 revision、时间和结果，允许没有关联提案。
- 多次重置、改回原值及重启后仍能回看完整顺序；保持该入口属于管理员合成场景操作的边界。
- 已有 V9 已被应用，使用下一号增量迁移（实施时核对），不得修改 V9 或清空已有修复/评测数据。
- 增加独立 SQL 与重启读取测试，不以 snapshot.changeSource 非空代替审计完成。

## 6. 修复后复验门槛

| 编号 | 必须补齐的结果 |
| --- | --- |
| CR-F01 | 空白/无效表单不产生无法结束的 pending；历史卡住记录可安全恢复；真实未知执行仍保持原请求防重 |
| CR-F02 | 正文停滞受截止时间约束，超时资源释放、APPLIED/UNKNOWN 事实正确，迟到响应不改写终态且无第二次 POST |
| CR-F03 | 无提案的场景操作形成持久化审计，多次变化和重启后完整可读，增量升级保留 V9 数据 |

三个问题修复后，针对补充负例复验，并按改动风险重跑修复模块、HTTP/权限、JavaScript 和迁移/全量构建。全部通过后再更新完整 V1 验收结论。当前不宜直接沿用“R01～R20 均通过，因此无剩余问题”的表述。

## 7. 本地证据

本轮证据目录为 `.local/controlled-remediation-independent-20260927/`，不纳入源码发布：

- `verify.log`：完整 Java 回归与打包结果。
- `../controlled-remediation-independent-js-20260927.log`：80 项 JavaScript 结果。
- `workspace.json`：本轮隔离资源身份；`application.log` / `application.err.log`：独立实例运行日志。
- `success-report.json`、`browser-success.png`：正常闭环的报告和浏览器截图。
- `browser-invalid-request-stuck.png`、`http-recheck.json`：空白输入 400、查询 404、请求行数为零、刷新后仍禁用，以及环境审计行数核对。
- `http-recheck.mjs`：针对本轮独立实例的 HTTP/SQL 复现程序。
- `TimeoutProbe.java`、`timeout-probe-confirmed.log`、`timeout-compile.log`：正式适配器的真实回环 HTTP 正文停滞复现与干净重编译结果。

保留本轮隔离资源与失败证据供修复复验；未更改 19191 开发方实例或日常工作台。
