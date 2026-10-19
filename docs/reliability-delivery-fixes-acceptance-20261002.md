# 可靠性交付补修与开发复验（2026-10-02）

结论：RD-F01～RD-F03 已修复；最终程序在 Windows 与实际 Linux 上的 21 项必测场景均通过，9 项新增边界均通过。RD-01～RD-07 实现、功能复验和证据交付已完成，**整体交付存在一项未解决的资源保留差异，不能标为无保留通过**：原有 31 个容器中 3 个历史 Testcontainers 已缺失，删除请求发起者未确认；原有 34 个卷全部保留。本记录属于开发复验，**独立复验尚未执行**；不改写 [2026-09-30 独立验收暂不通过的原始结论](reliability-delivery-independent-acceptance-20260930.md)。

## 最终产物与环境

- 最终 JAR：`target/supportops-0.1.0-SNAPSHOT.jar`；受限冻结副本：`.local/reliability-fixes-20261002/final/application-final.jar`。
- SHA-256：`d94c36f2a4a06544164b035f1ae2bbd395944b7c921db00c8301294feedf575f`。Windows/Linux 浏览器、来源、运行与恢复矩阵均使用此冻结包。Linux 另执行本地完整编译与打包，其原生包摘要见证据索引，未把 ZIP 时间差异当作同一个二进制。
- JAR 内 425 个应用类/资源与最终 `target/classes` 逐项摘要相同，差异为 0。原有 8 个迁移文件摘要未变，未重写已应用迁移。
- Windows 原生 Microsoft OpenJDK 21.0.11、Node 22.16.0；Linux 为 Ubuntu 24.04.3、WSL2 Linux x86_64、OpenJDK 21.0.12.1、Node 22.16.0。均使用仓库 Maven Wrapper；各构建目录内部串行。
- 全量 Java verify 后的最后一次打包仅删除静态入口一处 CR 字节，已逐项证明全部 Java 类与其他资源字节不变；两平台重新打包日志和字节差异证明保留。最终 90 项 JS、21 项运行矩阵与 9 项边界均在最终目录重新执行。
- Chromium 153（Playwright 1.63.0/build 1243）连接正式 Spring 后端与实际 MySQL 8.4、MinIO、Elasticsearch 8.19.4。模型与嵌入仅为明确的本地协议夹具。

## 修复与交付范围

| 项目 | 结果与依据 |
| --- | --- |
| RD-F01 索引完整性 | 查询要求准确总数与 `eq`，拒绝部分/超时分片结果。保留每索引 10,000 条容量上限；实际 0 条完整备份、10,000 条完整备份与新目标恢复通过，10,001 拒绝且备份 FAILED、维护锁保留。尾部文档变化影响摘要。 |
| RD-F02 ES 健康检查 | 必须是有效集群结构与明确 green/yellow；实际 ES 正常响应通过，空对象/缺字段/空值/未知状态/非法 JSON/red/非 200/超时均拒绝。文本、JSON 和退出码一致，存储摘要不变。 |
| RD-F03 配置优先级 | 未显式指定端口时复用 Spring 配置解析；保留 PORT、SERVER_PORT、Spring JSON、外部配置。显式端口仍优先。实际环境/文件/显式/默认/0/非法/占用路径核对监听端口、回执与登记。 |
| 并发页面渲染 | 修复受控修复页面的迟到列表/审计/场景历史追加；只有最新读取可渲染。3 项时序单测和两平台实际 E05 回归通过。固定请求与服务端权限保持原契约。 |
| 启动登记失败 | 身份快照顺序原子保存；登记失败结束本次子 JVM，确认退出且失败状态落盘才解锁。D02 通过单次写盘故障注入配合真实 JVM/OS 探针验证。Windows 另有真实共享锁重试和有界失败实测。 |
| 辅助数据库连接 | 等待服务端真实断开，不能把 mysql2 提前返回的 QUIT 入队回调当作已关闭。3 项回归验证等待/超时/错误；B01 另用真实闲置连接证明严格拒绝条件不变，关闭后连接确实消失。 |
| RD-01 | 子 JVM 跨平台与隔离数据库参数修复；两平台 D01 实际三阶段退出与零重放通过。 |
| RD-02 | 实际运行身份、READY 回执、只读 doctor/status、管理员运行 API；D02～D06/E01 通过。 |
| RD-03 | 真实 Chromium 自动回归 E01～E08；退出 helper 等待真实注销导航，保留全部账号隔离断言。 |
| RD-04 | 页面、固定请求恢复、账号切换与故障回归；E01～E08 通过，两种视口保留实际截图。 |
| RD-05 | 停写证明、完整备份与新 UUID/端口/卷恢复、故障和维护锁、旧审批失效/UNKNOWN 不重放；B01～B07 通过。 |
| RD-06 | Ubuntu CI 命令链已配置；本地 Windows/Linux 实跑通过。远端 Actions 未执行，未提交/推送。 |
| RD-07 | 运行手册、容量/配置/失败边界、逐项结果、公开证据索引与受限恢复入口齐备。 |

## 最终复验结果

| 范围 | Windows | Linux |
| --- | --- | --- |
| Maven 全量测试与打包 | 167 项：162 通过、0 失败/错误、5 可选 ONNX 跳过；BUILD SUCCESS | 同左；真实 Linux 编译与运行 |
| JavaScript | 90/90，0 跳过 | 90/90，0 跳过 |
| 六个来源场景 | 6/6 SOURCE_CHECKED、隔离资源 CLEANED | 同左 |
| 三项问题的新增边界 | 9/9 | 9/9 |
| 同项目未登记容器/卷保护 S01/S02 | 2/2 | 2/2 |
| 外置依赖记录与验证 | PASS；未加载模型 | PASS；未加载模型 |
| 实际 OpenAPI | 92 个 GET/POST 操作、RuntimeView 引用验证通过 | Maven 契约检查通过；精确操作数在 Windows 隔离入口核对 |

以下 21 项均有独立证据位置，详见 [机器可读索引](reliability-delivery-fixes-evidence-index-20261002.json)。浏览器用例零自动重试，结果不依赖删减断言或全量后端替身。

| 编号 | 验证内容 | Windows | Linux |
| --- | --- | --- | --- |
| D01 | Windows/Linux 三阶段真实子 JVM 退出 23，恢复零重放 | PASS | PASS |
| D02 | 真实 READY、正常停止、启动失败、意外退出及登记写盘失败后子 JVM 退出 | PASS | PASS |
| D03 | 实际存活 PID 的开始时间不匹配、旧记录、不可读分支、端口占用 | PASS | PASS |
| D04 | 缺 JDK/JAR、错误配置、连接拒绝和超时；文本/JSON/退出码一致 | PASS | PASS |
| D05 | 无模型密钥、UI 覆盖、Docker 不适用；模型请求数为零 | PASS | PASS |
| D06 | doctor 前后全量存储/资源摘要一致；权限由 E01 实际 HTTP 补证 | PASS | PASS |
| E01 | 初始化、登录、退出、USER/匿名/跨账号权限 | PASS | PASS |
| E02 | SSE 断线与刷新沿用 runId/游标，事件与用量不重复 | PASS | PASS |
| E03 | 复制草稿重新预检，刷新和迟到响应不继承启动资格 | PASS | PASS |
| E04 | 评测已受理丢响应，原请求恢复且仅一个受理 | PASS | PASS |
| E05 | 提案、审批、真实执行、分离的应用/验证回执及历史，两种视口 | PASS | PASS |
| E06 | 修复丢响应、刷新、暂时 404、非法历史 pending 保留固定意图 | PASS | PASS |
| E07 | 同一浏览器 A/B/A 切换，账号间 pending 不展示、不重放 | PASS | PASS |
| E08 | 配置版本冲突、审批后同名重置/重启令旧审批失效 | PASS | PASS |
| B01 | 活动/未知写入者阻止备份，不产生 COMPLETE | PASS | PASS |
| B02 | 完整备份、全新目标恢复及 B06 恢复业务读取 | PASS | PASS |
| B03 | 缺文件、摘要损坏、PARTIAL、快照失败和版本不支持拒绝 | PASS | PASS |
| B04 | 非空/源目标复用、共享卷、路径逃逸与链接拒绝 | PASS | PASS |
| B05 | 实际 SQL 中断/导入、故障回调、ES 暂停超时和维护锁 | PASS | PASS |
| B06 | 旧审批 STALE、UNKNOWN 不重放、历史材料/路径不重绑 | PASS | PASS |
| B07 | Windows ACL / Linux 0700、公开证据脱敏和路径边界 | PASS | PASS |

恢复报告逐项记录 36 张主库表、1 张 Excel 动态表、3 份对象、2 个索引、2 份评测归档和 4 类外置依赖；所有行、结构、对象、映射与别名均完整比较。恢复后以旧账号完成浏览器和 API 读取、原件下载、Excel 查询、版本过滤、评测审阅、归档历史核对。只有规定的旧审批 STALE 和 INSTANCE_CHANGED 审计发生变化，其他表保持一致；旧审批执行返回 409，UNKNOWN 未重放。

## 证据入口

公开汇总根：`.local/reliability-fixes-20261002/final/public-delivery/`。公开索引不包含口令、Cookie、环境文件、SQL 或浏览器认证状态。原始配置、日志、故障材料和完整备份保存在仅当前 Windows SID 可访问的 `.local` 目录。

| 证据 | 相对公开汇总根的位置 |
| --- | --- |
| 两平台 D01、Java/JS 基线 | `windows/baseline-report.json`、`linux/baseline-report.json` |
| D02～D06 | 两平台 `runtime-report.json`，D02 含登记写盘故障的实际进程退出证明 |
| E01～E08 | 两平台 `browser/browser-report.json` |
| 宽/窄屏修复结果 | 两平台 `browser/E05-1440.png`、`browser/E05-390.png` |
| B01～B07 与业务读取 | 两平台 `recovery/recovery-report.json` |
| 恢复后的页面 | 两平台 `recovery/B02-restored-documents.png`、`B06-old-approval.png`、`B06-unknown.png` |
| 三个问题边界 | 两平台 `boundaries-report.json` |
| 真实 Windows 文件共享锁 | `windows/atomic-record-report.json` |
| 最终包内容一致性 | `artifact-alignment.json` |
| 资源保留与清理 | `resource-preservation.json` |

私有完整恢复证据：Windows `.local/reliability-fixes-20261002/final/windows-delivery-complete/recovery/complete-backup/`；Linux `.local/reliability-fixes-20261002/final/linux/captured/delivery/recovery/complete-backup/`。两边均保留源/恢复核对、允许的启动差异及故障中断证据。索引容量上限的完整备份分别在两平台 boundaries 的 `backup-limit/`，不是抽样副本。

## 原始失败与修正

1. 原独立验收的三个缺陷保留在 9 月 30 日记录；本轮没有发现官方 ES 快照丢数据的证据，修复的是完整性核对和错误放行。
2. 本轮初次 Windows 浏览器 E07 失败为测试导航与真实注销重定向竞争；Linux E05 发现实际页面并发读取重复追加。修复 helper 与页面后重新打包，两平台全部 8 项重跑通过。没有保留失败包作为最终交付包。
3. 第三次 Windows 端口边界曾留下 STARTING 初始记录、含真实 PID 的完整临时记录与 READY 回执，未完成登记。原始材料保留在 `.local/reliability-fixes-20261002/windows-boundaries-final/`。原始文件替换停滞的宿主根因未确认；随后补强登记失败收尾、顺序写入和短暂共享锁重试，以真实共享锁及真实子 JVM 故障注入分别验证，不能将推测写成已确认根因。
4. 早期 Maven 参数传递、误用 Node 24、子 JVM 未继承隔离端口、边界脚本参数/接口路径及 Docker 挂载返回次序的问题分别修正后复验。原始日志仍在 `.local/reliability-fixes-20261002/`；挂载比较仍逐字段核对，只规范化数组顺序。
5. Docker 中断后遗留零字节套接字导致启动失败。核对无活动引擎后只把指定瞬态目录移入保留目录，未删除 Docker 数据或重置设置。两个批次的隔离目录保留，具体路径见私有 transient* 记录。
6. 中间 Linux 恢复轮次 B01 被 UNKNOWN_DATABASE_WRITER 拒绝，其余 B02～B07 通过。驱动 end Promise 在发送 QUIT 前返回，辅助连接与下一次停写检查存在竞争；改为有界等待服务端断开，并加入真实闲置连接的拒绝/关闭后消失断言。原始报告及六项通过不替代最终完整重跑。
7. 最终摘要包的首次 Windows E04 评测因 EVALUATION_PROCESS_IDENTITY_UNCONFIRMED 失败，子 JVM 被按归属停止、资源清理，B01～B07 未执行。原始报告在 `.local/reliability-fixes-20261002/final/windows-delivery/`。事后 8 次只读 CIM 查询进程均正常结束，但旧目标已经退出，这不能证明故障时元数据可读取；原始宿主原因尚未确认，没有据此修改权限、放宽身份核对或延长断言。其他 Windows 验证 JVM 结束后，同包全新部署完整 D/E/B 复验的结果单列在 windows-delivery-complete。此前 Windows 中间轮次还存在一次准备阶段评测预检拒绝，原日志保留，不能将其解释为已确认的同一根因。

8. 第二次最终摘要包的 Windows D/E 全部通过，但 B06 没有继承仓库 Chromium 路径，实际浏览器未启动，B02 随业务验收失败判为 FAIL。原始记录保留在 .local/reliability-fixes-20261002/final/windows-delivery-retry/。显式设置整个验证进程的 PLAYWRIGHT_BROWSERS_PATH，并补齐安装与运行手册后，在 windows-delivery-complete 全新执行 D/E/B；程序包与全部断言保持相同。

## 独立验收入口与资源状态

已从最终 Windows 完整备份再恢复一个全新目标，实际启动、旧账号登录、运行身份、恢复安全配置和 OpenAPI 均核对通过，随后应用正常停止。存储保留供独立验收；目标 UUID：`65fddfb3-94d2-4a87-af40-e5d0ddc07a2a`。

在仓库根目录使用 JDK 21 和 Node 22：

```text
node scripts/start.mjs --deployment=.local/reliability-fixes-20261002/final/acceptance-target/deployment.json --port=0
```

启动输出给出实际回环地址。账号位于受限文件 `.local/reliability-fixes-20261002/final/acceptance-accounts-private.json`，不写入正式文档。停止后可以使用该次输出中的 runtime.json 执行 status；恢复目标默认禁用真实对话、向量、飞书、评测消费者、文档后台任务和 ONNX。

本轮只保留验收目标的 3 个容器和 4 个卷；其余本轮 Windows/Linux 测试部署及 Linux 验证容器/工作卷已按登记身份清理。原有 34 个数据卷全部保留，原有容器仅 28/31 仍在，总计 31 个容器、38 个卷。原有容器集合严格对账失败，该项保留为 FAIL，未改成通过。

缺失项均是 9 月 18 日创建、基线已退出的历史 Testcontainers：amazing_heyrovsky（Redis，5bfbc9915f16）、clever_ishizaka（MySQL，fddc6b2fc72e）、sleepy_vaughan（MySQL，9f41eeaac158）。Docker 日志记录 2026-10-02 15:46:40～15:46:41 +08:00 对三个完整 ID 的 DELETE 请求，客户端标识为 Docker-Client/29.7.2 (windows)，不能仅据此确认发起者。三个对应匿名卷均仍存在，未重建原容器，也未启动或删除这些卷。已记录的本轮清理入口仅针对登记 UUID 与精确 Linux runner ID；不能据此把未确认的删除来源归因给某个人或进程。完整 ID、卷名、请求时刻与调查限制见公开 resource-preservation.json；原日志摘录保存在 final/historical-container-removal-matches-private.txt。

替代处理为保留三个旧卷与日志，待核实请求来源和原容器配置后再决定是否重建；本轮不以新同名容器替代丢失身份。Docker 引擎保持运行以提供验收目标存储。冻结包、完整备份、历史失败、公开证据和本地测试依赖缓存保留；既有工作台未停止或替换，未修改其配置或业务数据，未提交、推送、发布。

## 未执行与限制

- 原有容器集合保留检查失败，3 个历史容器删除请求的发起者未确认；全部旧卷仍在。功能矩阵通过不能覆盖这项交付差异。
- 独立复验、远端 Actions、真实模型/嵌入/飞书服务验证未执行；本地协议夹具不代表服务商鉴权或诊断效果。
- Windows 曾在并行演练期间出现进程身份不可确认的安全拒绝；后续同包独立部署复验通过，宿主原始根因未确认。运行账号必须持续具备读取完整进程身份的能力，本记录不保证并行压力下不会再出现该拒绝。
- 每个平台 5 项既有可选 ONNX 用例未加载权重而跳过，未计入通过项。21 项必测与 9 项新增边界均无跳过。
- D03 不可读身份使用定向 UNKNOWN 注入；PID 重用用真实存活进程与不同开始时间验证。D02 写盘异常为单次写入函数故障注入并独立验证真实 JVM 退出；Windows 文件共享锁另有真实实测。
- B05 未耗尽物理磁盘，覆盖真实 SQL 中断/导入、故障回调与专属 ES 暂停超时；B06 UNKNOWN 历史为合成准备，真实崩溃由 D01 覆盖。
- npm 审计仍有 2 项关联 moderate，0 high/critical；没有跨主版本覆盖 MinIO 的解析依赖来消除审计提示。
- V1 每个 ES 索引上限 10,000 条、备份总量 2 GiB；仅专属单机 Compose、停写备份和新目标恢复，不支持共享存储、在线备份、原地覆盖或集群灾备。
