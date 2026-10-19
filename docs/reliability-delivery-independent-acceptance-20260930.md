# 2026-09-30 可靠性交付独立验收

结论：**暂不通过，需修复 RD-F01～RD-F03 后复验。** 既有自动化流程通过，但新增边界检查复现了索引完整性统计、环境健康判定和启动配置兼容问题。三项均为 P2；优先修复 RD-F01，避免将部分文档摘要作为完整索引核对结果。

验收依据为[范围与技术契约](plans/reliability-delivery-design.md)和[任务及验收矩阵](plans/reliability-delivery-tasks.md)。本记录独立于[开发交付记录](reliability-delivery-acceptance-20260930.md)，保留原始开发证据，不覆盖其历史结论。本次只新增验收文档和忽略目录内的测试证据，没有修改业务源码。

## 验收对象与环境

- 当前工作区包含评测中心、受控修复及本轮交付的未提交文件；以实际文件和交付包为对象，不以 Git HEAD 代替当前实现。
- 验收前复制最终 JAR，后续启动、浏览器和恢复演练均使用这个冻结副本；SHA-256 为 `153001ea40ab98dfc408222e91bf517371dce59509ce93b38c1fffe76f960c6a`，与开发交付记录一致。
- JAR 内 423 个应用类和资源与本次编译后的 `target/classes` 逐项对比，差异为零。Maven 重新打包后的 ZIP 不替换已冻结的验收副本。
- 本次实际运行 Windows、JDK 21、Node 22.16.0、真实 Chromium、Spring 后端及专属 MySQL/MinIO/Elasticsearch；模型与嵌入仅使用回环协议夹具。
- 新证据统一位于 `.local/reliability-independent-20260930/`，机器可读总览为 `independent-summary.json`。既有工作台及开发交付保留的验收实例不作为本次测试写入目标。

## 必须修复的问题

### RD-F01：超过 10,000 条的 ES 索引被当作完整统计

- **位置**：[storage.mjs](../scripts/reliability/storage.mjs)，`elasticSnapshot` 第 80～83 行。
- **原因**：查询仅使用 `size: 10000`，未要求准确总数；代码只检查 `hits.total.value > 10000`。实际 ES 在超过默认精确计数阈值时返回 `{value:10000, relation:"gte"}`，因此超限检查未生效。
- **独立复现**：在专属 ES 新建合成索引并写入 11,001 条；实际计数为 11,001，`elasticSnapshot` 正常返回且只记录 10,000 条。原生查询总数字段为 `10000/gte`。该探针索引已在结束时删除。
- **影响**：备份清单和恢复比较中的条数、文档摘要只覆盖截取结果，且绕过了代码声明的超限拒绝逻辑。本次没有证明官方快照丢失数据，不能将此问题描述为已发生数据丢失。
- **修复要求**：保留当前容量上限时，准确计算总数并明确处理 `relation`，超限以稳定错误拒绝，不能产生 `COMPLETE` 结果；若实现分页支持，则必须遍历全部文档并形成稳定的全量摘要，不以抽样代替完整性核对。
- **复验条件**：真实 ES 覆盖 0、10,000、10,001 条。保留上限的方案应分别证明边界内成功、超限明确拒绝；分页方案还需证明尾部文档的新增、删除和修改能够影响全量计数或摘要。备份和恢复比较均使用一致语义。
- **证据**：`es-boundary-report.json`、`probe-es-boundaries.mjs`。

### RD-F02：doctor 将普通 HTTP 200 JSON 当作健康 ES

- **位置**：[ReadOnlyDoctor.java](../src/main/java/io/supportops/ops/ReadOnlyDoctor.java)，`elastic` 第 124～125 行。
- **原因**：仅判断 HTTP 为 200 且 `status` 不等于 `red`。缺失字段被转换为空字符串，因此不符合 ES 健康响应的内容也通过。
- **独立复现**：启动仅返回 HTTP 200 和 `{}` 的回环 HTTP 服务，将冻结 JAR 的 ES 检查目标指向该服务，实际返回 `status: PASS`、`code: CLUSTER_READABLE`。
- **影响**：地址指向错误服务或响应字段缺失时，维护者得到“依赖正常”的错误结论，后续实际索引访问仍可能失败。
- **修复要求**：校验健康响应的必要结构和合法状态，只接受明确支持的健康状态；缺失、空值、未知值和畸形 JSON 不得报告 `PASS`。保持有界超时、只读和脱敏约束，文本与 JSON 及退出码一致。
- **复验条件**：覆盖真实 ES 的正常响应，以及 HTTP 200 `{}`、缺失/未知状态、非法 JSON、`red`、非 200 和超时。检查全过程不创建索引、不迁移数据库、不调用模型。
- **证据**：`es-boundary-report.json` 的 `nonEsHttp200`；使用的 Java 实现来自冻结交付包。

### RD-F03：启动入口忽略原有 PORT 环境变量

- **位置**：[start.mjs](../scripts/start.mjs) 第 32 行；关联 [runtime.mjs](../scripts/reliability/runtime.mjs) 的 `--server.port` 参数及 [doctor.mjs](../scripts/doctor.mjs) 的端口预检。
- **原因**：未提供 `--port` 时固定选择 18080，并将它以高优先级命令参数传给 Spring，覆盖 [application.yml](../src/main/resources/application.yml) 中现有的 `${PORT:18080}`。旧启动器未强制注入这一参数。
- **独立复现**：探针确认并占用当时空闲的 18080；另外选择空闲端口 63717，设置 `PORT=63717`，不传 `--port`。实际启动命令以退出码 1 和 `PORT_IN_USE` 结束，而不是使用指定的环境端口。探针未启动 Spring 子进程，临时监听已关闭。
- **影响**：原来通过环境变量部署的实例会尝试错误端口；默认端口被占用时启动失败，空闲时则可能监听到非预期端口。与设计第 3.1 节要求的默认行为和配置优先级兼容不符。
- **修复要求**：显式 `--port` 可覆盖配置；未指定时保留原有配置解析行为，不以硬编码默认命令参数覆盖环境配置。doctor 的端口预检应与实际启动目标一致；记录真实监听端口。
- **复验条件**：仅设置 `PORT` 的启动及健康检查；显式 `--port` 覆盖环境值；默认行为；端口 0；非法值和目标端口被占用。核对实际监听端口、就绪回执和运行记录一致。
- **证据**：`port-compatibility-report.json`、`probe-port-compatibility.mjs`。

## 本次实际验证结果

| 范围 | 独立执行结果 | 证据与边界 |
| --- | --- | --- |
| JAR 与编译内容 | 摘要吻合；423 项一致、0 差异 | `artifact-alignment.json`；冻结副本 `application-under-review.jar` |
| Maven Wrapper 完整验证 | 149 项，144 通过、0 失败、0 错误、5 跳过；打包成功 | `maven-verify.log`、`java-summary.json`；5 项为既有可选 ONNX |
| JavaScript | 82/82 通过，0 跳过 | `javascript.log` |
| D01 | Windows 三阶段真实子 JVM 退出及恢复不重放通过 | Maven 中 `realJvmCrashAtThreeStagesNeverReexecutes`；ACCEPTED/APPLIED/VERIFIED 各退出 23 |
| D02～D06 | 5/5 通过 | `runtime/public/runtime-report.json`；运行子进程已按身份确认退出 |
| E01～E08 | 8/8 一次通过 | `browser/public/browser-report.json`；真实 Chromium、HTTP、后端和存储 |
| B01～B07 | 7/7 通过 | `recovery/public/recovery-report.json`；真实备份、全新恢复目标及业务读取 |
| 新增边界检查 | RD-F01～RD-F03 均复现缺陷 | 两份独立探针报告；现有矩阵通过不能覆盖这些失败条件 |
| 差异格式检查 | 未通过 | `diff-check.log`；本轮相关新增行的行尾问题另列为整理项 |

本次 Windows 原定 D/E/B 共 21 项通过，未删减断言或跳过新增必测项。恢复核对包含 36 张主库表、1 张 Excel 表、3 个 MinIO 对象、2 个 ES 索引和 2 份评测归档；表结构、行内容、对象摘要、映射和别名一致。

恢复后的实际浏览器/API 检查确认账号可登录、普通用户和跨账号访问受限、原件和 Excel 可读、关键词及夹具向量检索保留版本过滤、评测历史与审阅可读、UNKNOWN 执行不重放。旧审批变为 STALE，只有预期的 1 个计划变化和 1 条实例变化审计，其他表不变；旧审批执行被 409 拒绝。

验证方式保持明确：D03 不可读分支为定向 UNKNOWN 注入，PID 重用分支使用实际存活进程和不匹配开始时间；B05 包含实际 SQL 导出子进程终止、实际 SQL 导入后的失败回调，以及暂停本次 ES 容器造成的真实超时，未耗尽物理磁盘；B06 的 UNKNOWN 历史由合成数据准备，真实 JVM 崩溃由 D01 单独覆盖。

本次没有重新执行 Linux 全套、六来源场景、S/X 隔离专项或远端 Actions；开发记录的相应结果不能标成此次独立重跑。未加载可选 ONNX 权重，未调用真实模型、真实嵌入或飞书；协议夹具与业务恢复通过不代表模型质量或生产验收通过。

## 资源和工作区收尾

本次新建的基础测试存储、浏览器源存储、恢复目标和中断恢复目标共 4 套部署，均在核实身份及停写后清理了各自容器和数据卷。B05 故障维护锁、完整与失败备份、冻结 JAR、日志、截图及报告继续保留在本地测试目录，不删除原始失败证据。

最终 Docker 清单与验收开始时一致：**31 个原有容器、34 个原有数据卷完整保留，ID/名称集合无增减**。开发交付保留的 3 个运行中存储容器仍在，最终无活动 Java 进程。证据为 `final-resource-inventory.json`、`base-cleanup.log`、`browser-source-cleanup.log` 及恢复报告中的目标清理记录。此项核对资源身份和存在性，不表示独立校验过所有既有业务数据内容。

原有 101 个 Git 状态路径保留，唯一新增状态路径为本文；证据为 `git-status-comparison.json`。没有提交、推送、发布或替换既有工作台。

## 整改交接

1. 在保留全部现有未提交文件的前提下，依次修复 RD-F01、RD-F02、RD-F03；不扩展新功能，不替换既有工作台。
2. 将独立探针揭示的条件纳入稳定回归。修复可选择契约内的实现方式，不通过删除容量校验、放宽健康判断或改变原配置契约消除失败。
3. 使用真实隔离存储复验 ES 边界、备份清单及恢复比较；使用冻结的新 JAR 复验 doctor 和实际端口，继续完成受影响的 Java/JavaScript、D/E/B 检查。
4. 交付新的 JAR 摘要、失败与修复后证据、精确资源清理状态，以及 Windows/Linux 实际执行范围。未重跑的平台不得复用旧结果冒充新包验收。
5. 同时处理本轮改动涉及的行尾问题。当前 `git diff --check` 对 `scripts/start.mjs`、`static/app.js`、`static/index.html`、`SupportOpsIntegrationTest.java` 的部分新增行报告 trailing whitespace；不为此格式化无关文件。

原开发记录中声明的本地 Linux 结果与远端 CI 的区别、可选 ONNX 跳过、两项关联 moderate 依赖审计记录等边界继续保留，不因本次基础流程通过而改写成生产可用或真实模型效果已验证。
