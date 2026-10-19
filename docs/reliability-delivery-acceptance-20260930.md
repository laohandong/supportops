# 2026-09-30 可靠性交付记录

本轮完成 RD-01～RD-07。Windows 与实际 Linux 环境使用同一最终运行 JAR 完成 D01～D06、E01～E08、B01～B07，新增必测项没有跳过。结果属于开发验证，独立验收尚未执行。范围与契约见[设计](plans/reliability-delivery-design.md)，操作方法见[运行与恢复手册](reliability-delivery.md)。

## 程序与实际环境

最终程序为 `target/supportops-0.1.0-SNAPSHOT.jar`，SHA-256：

```text
153001ea40ab98dfc408222e91bf517371dce59509ce93b38c1fffe76f960c6a
```

| 项目 | Windows | Linux |
| --- | --- | --- |
| 系统 | Windows 原生进程与 Docker Desktop 存储 | Ubuntu 24.04.3 LTS，x86_64，WSL2 Linux 6.18.33.2 |
| JDK | Microsoft OpenJDK 21.0.11 | OpenJDK 21.0.12.1 |
| Node | 22.16.0 | 22.16.0 |
| 浏览器 | Playwright 1.63.0 / Chromium 153.0，build 1243 | 相同锁定版本与浏览器构建 |
| Maven Wrapper 完整验证 | 149 项：144 通过，0 失败/错误，5 项既有 ONNX 可选跳过 | 相同计数；原生 Linux 编译、测试与打包实际成功 |
| JavaScript 完整回归 | 82/82，0 跳过 | 82/82，0 跳过 |
| 六个来源场景 | 6/6 SOURCE_CHECKED，EXECUTION_FINISHED，资源 CLEANED | 相同结果，显式指向本轮随机测试端口 |
| D/E/B 必测矩阵 | 21/21 PASS | 21/21 PASS |

Linux 原生构建 ZIP 摘要为 `ffd0e104d67f422debc0b5789ae95f528421a6acfaa7d44b2cb937a7e351e031`；打包字节与 Windows ZIP 不同。最终运行身份、浏览器和恢复矩阵统一验证上面的 `153001…c6a` 程序副本。两套 `target/` 分属宿主与 Linux 私有运行卷，各自的 Maven 构建均串行。

## RD 交付

| 任务 | 状态 | 实际修改与验证 |
| --- | --- | --- |
| RD-01 | COMPLETE | 子 JVM 从当前 JDK 选择 `java`/`java.exe`，保留参数数组、超时、输出和清理；两平台真实 ACCEPTED/APPLIED/VERIFIED 退出 23，恢复零重放 |
| RD-02 | COMPLETE | JAR 副本、SHA、PID/开始时间、实际端口与原子就绪回执；只读 doctor/status；管理员运行 API 与页面信息；旧记录、权限与错误边界 |
| RD-03 | COMPLETE | 专属 UUID/目录/端口/数据库/卷、真实 Chromium 与后端存储、一次性合成账号、本地模型协议夹具、受理后丢响应及独立 SQL 核对 |
| RD-04 | COMPLETE | E01～E08；修复评测刷新加载及迟到响应竞争、Windows classpath 处理、SSE 断开后的异常响应；保持固定请求、原 SSE 和后台权限语义 |
| RD-05 | COMPLETE | 停写预检与维护锁、两库逻辑导出、对象字节/元数据/标签、官方 ES 快照、私有配置和评测材料；校验后全新目标恢复、PARTIAL 故障与业务核对 |
| RD-06 | COMPLETE | CI 接入 Node/JDK/Chromium 固定版本、完整 Maven/JS、六来源、S/X 和 D/E/B、公开产物白名单及精确清理；实际 Linux 命令链通过，远端 Actions 未执行 |
| RD-07 | COMPLETE | 运行、数据库、API、验证范围及文档导航；机器可读证据索引、失败记录、恢复入口与资源清单 |

## 21 项必测结果

每一项的原始报告相对位置见[机器可读索引](reliability-delivery-evidence-index-20260930.json)。生成报告和截图位于忽略目录 `.local/reliability-20260929/public-delivery/`，其中 `evidence-index.md` 提供可直接打开的链接。私有配置、SQL、运行身份和原始日志不进入公开证据或源码附件。

| 编号 | 核对内容 | Windows | Linux |
| --- | --- | --- | --- |
| D01 | 三阶段真实子 JVM 退出、恢复不重执行 | PASS | PASS |
| D02 | 正常 READY、早期失败、主动及异常退出、实际副本摘要 | PASS | PASS |
| D03 | PID 开始时间不匹配、旧格式/未知、截断记录、端口占用不换端口 | PASS | PASS |
| D04 | 缺 JDK/JAR、错误配置、连接拒绝/超时、有界且脱敏的文本/JSON | PASS | PASS |
| D05 | 缺模型密钥仍可检查、UI 配置优先、Docker NOT_APPLICABLE、零模型请求 | PASS | PASS |
| D06 | doctor 前后全部行/结构/对象/映射/别名一致；运行 API 401/403/管理员读取 | PASS | PASS |
| E01 | 空库初始化、登录退出、USER 页面与服务端边界 | PASS | PASS |
| E02 | SSE 断线、游标续读及刷新恢复原 runId，无新增受理或重复用量 | PASS | PASS |
| E03 | A 预检复制 B、刷新及迟到响应不能授予 B 资格 | PASS | PASS |
| E04 | 真实评测受理后丢响应，冻结请求恢复且只有一个受理 | PASS | PASS |
| E05 | 真实提案批准执行、业务回执、结果和历史；1440/390px | PASS | PASS |
| E06 | 修复丢响应、刷新、临时 404 与旧非法 pending；独立 SQL 一次副作用 | PASS | PASS |
| E07 | 同一浏览器 A/B/A 切换，pending 不跨账号或产生替代写入 | PASS | PASS |
| E08 | 双页面配置冲突、同名场景重置后旧审批失效，无验证 POST | PASS | PASS |
| B01 | 活动应用/后台/评测或未知子进程拒绝备份，不停止未知进程 | PASS | PASS |
| B02 | 完整备份和全新目标恢复，所有存储与配置/材料核对 | PASS | PASS |
| B03 | 缺文件、摘要、PARTIAL、版本、快照与材料路径在目标写入前拒绝 | PASS | PASS |
| B04 | 非空/源目录/路径逃逸/链接/身份与卷复用拒绝，源内容不变 | PASS | PASS |
| B05 | 真实 SQL 中断、导入后写失败、ES 暂停超时；锁与 PARTIAL 保留，拒绝盲目续写 | PASS | PASS |
| B06 | 恢复业务读取、旧审批 STALE、UNKNOWN 不重放、历史资源不重绑定 | PASS | PASS |
| B07 | Windows SID 独占 ACL / POSIX 0700、0600；公开产物秘密扫描与归档约束 | PASS | PASS |

补充 S01/S02 在两个平台实际创建同项目但未登记的合成容器/卷，清理均先拒绝，原登记资源保持；故障夹具只按独立 UUID 归属回收。X01～X03 验证固定合成文件摘要、缺失状态与错误摘要拒绝，没有加载模型或读取恢复记录里的历史路径。

## 恢复证据

完整备份为 COMPLETE，全新目标为 RESTORED_STOPPED。实际集合为 36 张主库表、1 张 Excel 表、3 个原件、2 个 ES 索引及别名、2 份评测归档；四个外置模型/tokenizer 依赖记录明确标为未打包。逻辑行、表结构、列元数据、对象内容/元数据/标签、全部索引文档/映射/别名均核对，源集合与备份清单摘要保持不变。

恢复后使用真实页面、API、下载和数据库核对：原 ADMIN/USER、完成和归档问答、事件/用量、项目记忆、两个版本文档、Excel 两行、评测样本/审阅、修复结果和审计。关键词与本地嵌入夹具的混合检索均保留 2.0 版本过滤。旧 APPROVED 审批在首次启动变为 STALE，版本加一并追加一条 INSTANCE_CHANGED；允许差异单独记录，其他表逐行一致。UNKNOWN 保持原值与原执行，不自动重放。

实际 `scripts/start.mjs --deployment=… --port=0` 已验证恢复账号登录、运行 SHA、3 个原件摘要和生成 OpenAPI：92 个 GET/POST 操作，12 个顶层声明分组、13 个实际使用分组。启动时对话、向量、飞书、评测消费者与 ONNX 精排关闭，检查后自有应用停止。

## 失败、未执行与限制

首次失败未被删除。Windows 首轮构建、Linux 首三轮 Maven、早期浏览器与恢复运行的原始日志保存在受限目录。已修复启动路径/进程差异、评测页面迟到响应、SSE 关闭竞争、停止后 SQL 表结构展示差异、恢复夹具端点兼容与 UI 等待/导航断言。Linux 全新检出的评测测试需要配套 JAR，CI 现在在同一次 Maven 调用先产生程序包，再完整测试与 verify；长时间中断导致预检过期的那次运行保留为失败，最终完整重跑成功。

早期 Windows 恢复报告曾因 B06 页面断言失败联动标记 B02 失败；原报告及后续 B06 独立复验均保留在 `public-delivery/windows-history/`。最终新建备份和目标的完整矩阵再次通过。Linux 早期来源检查曾连接错误测试端口，最终显式设置 TEST 地址并完成资源回收。MinIO SDK 的解析库跨主版本覆盖曾在 Linux 失败，现锁定兼容的 1.9.1，两平台重新安装及完整回归通过。

- D03 的不可读结果用定向 UNKNOWN 协议注入验证；PID_REUSED 用真实存活进程与不匹配开始时间验证。没有强制内核重新分配同一 PID，也没有修改宿主访问权限。
- B05 的导入写失败发生在实际 SQL 导入之后，使用受控故障回调；另有真实 SQL 子进程终止和真实 ES 暂停超时。没有耗尽物理磁盘。
- B06 的 UNKNOWN 历史行来自真实成功执行后的一次合成 SQL 故障准备，另有私有状态登记；真实崩溃边界由 D01 单独验证。
- 五项既有 ONNX 可选测试因未提供模型/tokenizer 文件而跳过。外置依赖只记录摘要和可用性，不打包、下载、加载或自动重绑定。
- npm 审计保留两项关联的 moderate 项，高危和严重为零。未通过跨主版本替换或降低审计阈值消除问题；工具不使用对象通知订阅。
- 真实模型、真实飞书、远端 Actions 和独立验收未执行。没有提交、推送、发布或替换当前工作台；这些工程证据不代表模型质量、生产部署、多实例灾备或压力验收。

## 独立运行与资源交接

本地交接清单位于 `.local/reliability-20260929/acceptance-handoff-private.json`，记录保留目标 UUID、完整路径、端口、启动参数及账号记录位置。密码与 Cookie 不出现在本文或公开索引。默认保留一套完整恢复存储，应用为停止状态。

在仓库根目录使用 JDK 21 与 Node 22：

```powershell
$env:JAVA_HOME = 'D:\dev\jdk21'
$env:PATH = "E:\code\agent\.cache\reliability-tools\node-v22.16.0-win-x64;$env:JAVA_HOME\bin;$env:PATH"
node scripts/start.mjs --deployment=.local/reliability-20260929/windows-delivery-final/recovery/acceptance-target/deployment.json --port=0
```

启动输出和新的 runtime/ready 文件记录实际端口。账号引用同目录交接记录指定的 `prepared-private.json`；仅使用本轮合成管理员与普通用户。结束时在这个前台启动器使用 Ctrl+C，随后按运行手册检查停止身份；不能操作其他工作台进程。

其余本轮部署、故障目标、Linux 基础依赖及运行器按清单回收，备份和原始失败材料保留。最终精确资源数量、保留 UUID、原有容器/卷存在性及迁移摘要检查见公开 `resource-report.json` 和私有清理记录。启动期间 Docker Desktop 曾受残留零字节 socket 阻塞，恢复保留了原 daemon 身份与全部原容器/卷；只隔离这次确认的旧 socket 目录，未删除配置或数据，隔离目录继续保留。

初始 72 个 Git 状态条目所指向的文件仍存在，B3 与 V4～V10 八份迁移字节摘要未变。未清理或覆盖旧评测与受控修复工作。环境曾被外部停止，原有容器当前是否运行不能由最初清单推断；本轮只核对其身份及数据卷仍保留。
