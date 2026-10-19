# 2026-10-02 可靠性交付独立复验

结论：**本轮可靠性交付在已定义的本地验收范围内通过。** Windows 独立执行的 21 项 D/E/B 检查、9 组修复边界、38 项受影响 Java 测试和 90 项 JavaScript 测试全部通过；Linux 为交付证据复核，未在本轮重跑。RD-F01～RD-F03 已关闭，历史容器差异经使用方确认属于其安排的清理，本轮前后资源集合一致。

本记录复核 [9 月 30 日独立验收](reliability-delivery-independent-acceptance-20260930.md)提出的 RD-F01～RD-F03，以及 [10 月 2 日开发补修](reliability-delivery-fixes-acceptance-20261002.md)新增的启动登记、页面并发读取和辅助数据库连接修正。原始失败及开发记录保留，本记录区分独立实测、开发证据复核和使用方确认的资源变更。

## 验收对象与完整性

- 冻结程序 SHA-256：`d94c36f2a4a06544164b035f1ae2bbd395944b7c921db00c8301294feedf575f`。
- 独立复制后用于全部程序启动与浏览器/恢复检查；未用重新生成的包替换测试对象。
- Java 测试执行前后分别核对 JAR 与 `target/classes`：425 个应用类与资源一致，差异均为 0。
- 逐项复核开发交付的 418 个源码摘要和 55 份公开证据摘要，均吻合。
- 对比上一轮冻结验收包与本轮冻结包，8 个已有数据库迁移字节一致。当前 `git diff --check` 通过。
- 独立证据根为 `.local/reliability-independent-recheck-20261002/`；模型与嵌入只使用明确的本地协议夹具。

## 三项原缺陷的闭合证据

| 问题 | 实际复验 | 结论 |
| --- | --- | --- |
| RD-F01 大索引完整性 | 真实 ES 的 0 条与 10,000 条备份均为 COMPLETE；10,000 条恢复到全新目标后计数与摘要一致；修改尾部文档能改变摘要；10,001 条返回 BACKUP_INDEX_SIZE_LIMIT，备份 FAILED，失败备份恢复被拒绝 | 通过；保留每索引 10,000 条上限 |
| RD-F02 健康检查误放行 | 真实 ES 正常响应通过；HTTP 200 空对象、缺字段、未知/空状态、空根、red、请求超时标记、非法 JSON、503 和实际超时均为 FAIL；文本/JSON/退出码一致；全部 20 次负向请求仅访问健康 GET，存储摘要前后不变 | 通过；未执行模型调用 |
| RD-F03 PORT 配置被覆盖 | 7 组实际 CLI 验证覆盖环境变量、配置文件覆盖环境值、显式端口覆盖、默认值、显式/环境随机端口、非法值、端口占用；核对真实监听、READY 回执与运行记录，结束后进程退出 | 通过；Spring 配置解析保持优先级 |

以上共 9 组边界全部通过，原始证据为 `boundaries/public/boundaries-report.json`。容量上限内的完整备份、上限恢复及超限失败材料均保留；边界源与恢复目标的专属容器/卷已清理。

## 独立执行与证据复核范围

| 范围 | 本次独立结果 | 原始证据 |
| --- | --- | --- |
| Java 受影响回归 | 38/38，通过；0 失败、0 错误、0 跳过 | `java-targeted.log`、`java-targeted-summary.json` |
| JavaScript 全量 | 90/90，通过；0 跳过 | `javascript.log` |
| D01 真实 JVM 崩溃 | ACCEPTED/APPLIED/VERIFIED 三阶段退出与恢复不重放通过 | 本次 `RemediationPersistenceTest` XML 与 Java 日志 |
| D02～D06 | 5/5 通过，启动登记写盘失败时确认真实子 JVM 已退出、失败状态已落盘、维护锁已释放 | `delivery/runtime/public/runtime-report.json` |
| E01～E08 | 8/8 一次通过；真实 Chromium、Spring HTTP 后端及专属存储；抽查 390px 修复结果截图 | `delivery/browser/public/browser-report.json` 与截图 |
| B01～B07 | 7/7 通过；完整备份恢复、非法备份/目标拒绝、实际 SQL 中断和 ES 超时、恢复业务读取及私密边界 | `delivery/recovery/public/recovery-report.json` |
| RD-F01～RD-F03 | 9/9 边界通过 | `boundaries/public/boundaries-report.json` |

Java 选择 `ReadOnlyDoctorTest`、`RuntimeConfigurationTest`、`RemediationCrashProcessTest` 和 `RemediationPersistenceTest`，覆盖新增健康协议校验、配置优先级、隔离连接和受控修复持久化/崩溃行为。本轮没有重新执行全部 167 项 Java 测试；开发的两平台 167 项结果不冒充本轮独立重跑。

完整恢复核对包含 36 张主库表、1 张 Excel 表、3 个原件对象、2 个 ES 索引、2 份评测归档及 4 类外置依赖记录。行内容、表结构、对象摘要、映射和别名一致。实际恢复实例可用原账号登录、读取原件和 Excel、查看历史与审阅，关键词和夹具向量检索均保留版本过滤；普通用户读取运行信息为 403、跨账号读取为 404、旧审批执行为 409。只有预期的 1 个旧计划失效和 1 条实例变更审计，其他表不变，UNKNOWN 状态未重放，启动时无模型调用。

Linux 的运行、浏览器、恢复、9 组边界、六来源和 S01/S02 报告已读取，并核实程序摘要及对应证据文件摘要一致。本轮没有再次启动 Linux 验证环境，也没有重新执行六来源、S/X 专项、可选 ONNX 或远端 Actions。

## 历史容器差异的处置

独立对比 9 月 30 日基线和本轮开始清单，确认以下 3 个历史容器在本轮开始前已经缺失，原有 34 个数据卷全部仍在：

| 容器 | 原 ID 前缀 | 数据卷 |
| --- | --- | --- |
| amazing_heyrovsky | `5bfbc9915f16` | 原匿名卷仍在 |
| clever_ishizaka | `fddc6b2fc72e` | 原匿名卷仍在 |
| sleepy_vaughan | `9f41eeaac158` | 原匿名卷仍在 |

Docker 原日志中存在 2026-10-02 15:46:40～15:46:41（北京时间）的对应 DELETE 请求。使用方在本次复验中明确确认这三个容器是其安排的清理，因此该差异按 **EXPECTED_USER_ARRANGED_CLEANUP** 闭合，不再作为未授权资源变更阻塞。

这项确认不改变“原容器已删除”的事实，也不表示已通过技术日志定位具体发起进程；没有重建同名容器来替代旧身份，没有启动、删除或改写对应旧卷。核对与确认分别保存在 `historical-resource-check.json` 和 `resource-cleanup-confirmation.json`。原开发报告的 FAIL 保留为当时尚未获得使用方确认的历史记录。

## 本轮资源收尾与交付

本次新建的 6 套专属测试部署均已按完整归属清单回收容器和数据卷：2 套索引边界源/恢复目标、1 套基础测试存储、1 套浏览器/恢复源、2 套完整与中断恢复目标。原始完整备份、失败备份、维护锁证据、冻结包、运行记录、截图和日志继续保留。

本轮开始与结束的 Docker 集合均为 **31 个容器、38 个卷**，容器 ID 与卷名集合无增减；最终无活动 Java 进程。开发交付的 `65fddfb3-94d2-4a87-af40-e5d0ddc07a2a` 保留验收目标仍有 3 个健康存储容器，本轮未启动或替换该目标应用。对账见 `final-resource-inventory.json`、`base-cleanup.log`、边界及交付报告的 cleanup 字段。

原有 109 个 Git 状态路径保留，唯一新增路径为本文。没有修改业务代码、提交、推送、发布或替换既有工作台。机器可读结论与证据入口为 `.local/reliability-independent-recheck-20261002/independent-summary.json`。

## 验收边界

- 保留 V1 每索引最多 10,000 条、完整备份总量 2 GiB、专属单机 Compose、停写备份和全新目标恢复的适用范围。
- 真实模型、真实嵌入、飞书与远端 CI 未在本轮执行；协议夹具证明工程链路，不能证明服务商鉴权或模型效果。
- D03 的不可读身份分支为定向 UNKNOWN 注入；PID 开始时间不匹配用真实存活进程验证。D02 为单次记录写入故障注入，退出结果由真实 OS/JVM 探针确认。
- B05 覆盖真实 SQL 导出子进程终止、真实 SQL 导入后的失败回调、暂停专属 ES 容器导致的实际超时及恢复，不包含耗尽物理磁盘；B06 的 UNKNOWN 历史为合成数据准备，真实 JVM 崩溃由 D01 单独验证。
- 开发记录中的 Windows 偶发进程身份不可确认及原始文件替换停滞的宿主原因未独立定位。本轮没有放宽身份检查或自动重试失败浏览器用例来获得通过。
- 两项关联 moderate 依赖审计为开发交付记录的已知限制，本轮未重新联网审计；未将其计作新增功能失败。
