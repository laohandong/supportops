# 运行身份、只读检查与停写恢复

SupportOps 使用 JDK 21、Node.js 22 和 Maven Wrapper 进行可靠性交付验证。浏览器依赖由 `package-lock.json` 固定；SupportOps 从独立 JAR 副本运行，构建目录可以继续串行构建。日常工作台与演练必须使用不同的数据库、端口、配置文件和运行目录。

测试工具固定 MinIO SDK 8.0.7 与其兼容的 `stream-json` 1.9.1，不能将解析库直接跨主版本覆盖，否则 Linux 的大小写敏感导入会失败。2026-10-19 的 npm 审计报告两项中等级别关联项，来源为解析过滤器的嵌套输入复杂度问题；高危与严重项为零。可靠性工具只连接已核对的专属本地存储，不启用对象通知订阅；这项依赖限制仍需在升级 SDK 时重新审计，不以跨主版本覆盖制造无漏洞结果。

## 运行身份与只读诊断

`node scripts/start.mjs` 保留原入口。可显式传入 `--config`、`--jar`、`--workdir`、`--runtime-root`、`--settings`、`--providers`、`--port` 与 `--deployment`。显式 `--port` 覆盖应用配置；未传时使用 Spring 的正式配置解析，包括 `PORT`、`SERVER_PORT`、`SPRING_APPLICATION_JSON` 和工作目录下的外部配置，未配置时为 18080。`--config` 文件可包含 `PORT`，文件值按原入口规则覆盖继承的同名环境值。doctor 的端口预检使用同一解析器。目标端口占用时失败，不自动换端口；明确配置端口为 0（例如 `--port=0` 或 `PORT=0`）才允许系统分配端口，回执和运行记录保存实际端口。非法端口返回输入错误且不回显原值。

每次启动创建私有目录，复制程序并生成 `runtime.json`。启动器记录实际 Java 路径、PID、操作系统进程开始时间、JAR SHA-256 和运行 UUID；应用在 `ApplicationReadyEvent` 后生成配套 `ready.json`，记录实际端口与就绪时间。构建修订或源码状态无法确定时显示 `unknown`，不会以文件时间代替。管理员可以通过 `GET /api/ops/runtime` 和工作台运行信息查看脱敏身份，匿名返回 401，普通用户返回 403。

身份快照按产生顺序原子写入；Windows 替换文件遇到短暂共享锁时仅有界重试同一完整临时文件。子 JVM 已创建但身份登记失败时，启动器结束本次创建的子进程并保存 `RUNTIME_REGISTRATION_FAILED`。只有确认进程退出且失败记录保存成功才释放启动维护锁；停止或写盘结果未知时保留现场，不以删除旧记录解决问题。

```text
node scripts/status.mjs --runtime=<本次 runtime.json 的绝对路径> --json
node scripts/doctor.mjs --config=<明确配置> --jar=<明确 JAR> --workdir=<隔离目录> --settings=<设置文件> --providers=none --port=0 --json
```

`status` 同时检查操作系统身份、JAR 摘要、就绪回执与监听端口。PID 被复用不会认作旧程序；旧格式、进程不可读或回执不匹配保留未知状态。`doctor` 使用正式配置解析器进行有限只读探测，不创建 Spring 容器，不迁移、建库、建桶、建索引，不调用模型鉴权或模型列表。没有模型密钥属于可选能力警告；读取 UI 覆盖配置仍遵循界面优先级。只有明确传入 Compose 部署清单时才检查 Docker，否则为 `NOT_APPLICABLE`。

ES 探针要求 HTTP 200、合法的集群名、明确的 `green` 或 `yellow` 状态、未超时及有效的节点和分片计数。空对象、缺字段、空值、未知状态、畸形 JSON、`red`、非 200 和超时均返回 `ES_UNAVAILABLE`，不会据此声明集群可读。

退出码：0 表示所执行的检查通过，允许可选警告；1 表示已知失败；2 表示输入或配置格式错误；3 表示身份或探针结果未知。状态检查通过不表示模型鉴权或诊断效果通过。

## 专属依赖与回归入口

以下命令创建全新随机部署身份、回环端口及专属卷；目录或资源名称碰撞立即拒绝。`--fixed-test-ports` 只用于已核对的测试环境，其端口为 23306、29000、29200，不能用于替换正在运行的服务。

安装浏览器前，在同一个终端设置浏览器目录，并保留该环境变量直到浏览器及恢复矩阵全部结束。PowerShell 使用 `$env:PLAYWRIGHT_BROWSERS_PATH = Join-Path (Get-Location) '.cache/reliability-browsers'`；Linux 使用 `export PLAYWRIGHT_BROWSERS_PATH="$PWD/.cache/reliability-browsers"`。恢复验收也会直接启动 Chromium，不能只给浏览器测试的子进程设置此变量。

```text
npm ci
npx playwright install --with-deps chromium
node scripts/isolate.mjs --create --directory=<不存在的私有部署目录>
node scripts/isolate.mjs --deployment=<deployment.json>
node scripts/test-runtime.mjs --deployment=<deployment.json> --directory=<新的结果目录> --jar=<最终 JAR>
node scripts/test-e2e.mjs --directory=<新的结果目录> --jar=<最终 JAR> --keep
node scripts/test-recovery.mjs --source=<浏览器结果目录> --directory=<新的恢复结果目录> --jar=<同一 JAR> --keep
```

浏览器直接连接正式后端、MySQL、MinIO 和 ES。外部模型由回环 HTTP 协议夹具代替；响应丢失发生在真实后端受理和独立 SQL 核对之后。测试默认串行、零自动重试，保留首次失败证据。`--keep` 保留自有存储供验收，应用与夹具按真实身份退出。未传 `--keep` 时，只清理已登记且仍匹配的自有资源；未知状态保留并报告。

`test-delivery.mjs --deployment=<测试部署清单> --directory=<新目录> --jar=<最终 JAR>` 串行运行身份、浏览器和恢复矩阵。Java 与 JavaScript 基线另外执行，不以这条命令代替。SupportOps 执行本项目完整 `verify`；六场景来源与评测持久化由独立评测项目验证。

```text
sh ./mvnw -s .mvn/settings.xml verify
node --test src/test/js/*.test.mjs
node scripts/test-isolation.mjs --deployment=<专属依赖清单> --directory=<新的结果目录>
node scripts/test-dependencies.mjs --directory=<新的结果目录>
node scripts/test-reliability-boundaries.mjs --directory=<新的结果目录> --jar=<同一最终 JAR>
```

Windows 将 `sh ./mvnw` 换为 `./mvnw.cmd`。完整 `verify` 负责验证与打包，后续运行器使用明确指定的最终 JAR。共享 `target/` 的 Maven 调用始终串行。

来源检查使用AgentEval独立配置，SupportOps运维不导入评测目录。

Maven 的隔离地址使用 JVM 系统属性 `-Dsupportops.test.mysql=jdbc:mysql://127.0.0.1:<端口>/`、`-Dsupportops.test.minio=http://127.0.0.1:<端口>` 和 `-Dsupportops.test.es=http://127.0.0.1:<端口>`；设置同名测试环境变量不能代替这些属性。PowerShell 中将每个 `-D` 参数作为独立的引号字符串传入。边界运行器另外创建自己的专属存储，实际启动 JVM，核验健康响应并进行容量边界完整备份恢复；结束时只清理其登记的容器与卷，失败证据保留。

## V2 停写备份

当前备份工具生成 `SUPPORTOPS_BACKUP_V2`，接受通过 `isolate.mjs` 创建的独占 Compose 部署。它不猜测日常工作台的资源归属，不备份共享数据库服务器、共享桶或共享 ES 集群。原 `infrastructure/compose.yml` 不变；仅显式的可靠性部署叠加 `reliability-snapshots.compose.yml`，为 ES 增加专属快照仓库卷。

操作者先停止自有应用写入。工具再次核对全部已登记应用的 PID/开始时间、任务队列、保留旧评测表静止状态、修复处理器、飞书待投递与数据库连接。活动、不可读或归属不清均拒绝，不自行停止未知进程。备份和应用启动共用独占维护锁，锁不会因年龄自动消失。

辅助 MySQL 连接发出退出命令后还会等待服务端断开事件；驱动的命令入队回调不作为连接已关闭的证明。关闭超时或失败明确报错，真正仍存活的闲置连接也会阻止备份，不因其当前没有执行 SQL 而忽略。

默认命令只生成计划；实际操作必须再次指定同一计划和明确部署 UUID。

```text
node scripts/backup.mjs --deployment=<deployment.json> --plan=<私有计划路径>
node scripts/backup.mjs --execute --plan=<同一计划> --deployment-id=<计划中的 UUID> --output=<不存在的备份目录>
```

完整备份包括主库和 Excel 库逻辑导出、所有原件字节与元数据/标签、官方 ES 完整快照及别名、配套 JAR、部署与环境/提供商/UI 配置；旧评测表保留于 SQL 备份，不打包独立评测项目的运行目录和秘密。外置 ONNX 模型与 tokenizer 只记录源路径、存在或缺失状态、大小和 SHA-256，明确标为未打包；记录在私有 `configuration/external-dependencies.json`，导出前后再次比对，既不加载模型也不下载权重。它不会复制 ES 节点数据目录。当前 MinIO 范围为未启用版本或保留锁的单桶；无法证明这一条件即拒绝。空桶不存在的状态保持不变，不通过检查创建桶。

清单先标记 `WRITING`，逐项核对文件集合、大小、SHA-256、两库所有行与表结构、原件、索引映射、别名及全部文档后才原子标记 `COMPLETE`。官方逻辑导出会展开继承字符集；核对仅消除这项展示差异，同时独立比较完整列元数据、排序规则、默认值、索引、约束与自增计数。最大文件集合为 50,000，备份总量上限 2 GiB；工具超时和失败会保留可核查状态及维护锁。

当前每个 ES 索引最多支持 10,000 条文档。完整性查询使用准确总数，要求 `hits.total.relation=eq`、返回文档数与总数相等且分片查询未失败、未超时；下界或部分结果不进入完整摘要。超过上限返回 `BACKUP_INDEX_SIZE_LIMIT`，备份标记 `FAILED` 并保留维护锁，不能恢复为完整包。备份前后和恢复目标使用相同的全量核对语义。大于此上限的部署需要先扩展并验证分页摘要支持，不能跳过容量检查。

## 全新目标恢复

只使用操作者信任的私有本地备份；摘要检测损坏，不提供签名或加密远程存储。恢复先验证权限、版本、所有文件摘要、规模、材料路径与镜像版本，再创建任何目标资源。拒绝非空目录、源目录或身份复用、路径逃逸、符号链接及硬链接，不提供 force 或 skip-check。

```text
node scripts/restore.mjs --backup=<可信私有备份目录> --target=<不存在的新目录> --plan=<私有恢复计划>
node scripts/restore.mjs --execute --plan=<同一计划> --target-id=<计划中的 UUID>
node scripts/start.mjs --deployment=<恢复目标的 deployment.json> --port=0
```

恢复分配新端口、容器与卷，保持应用停止，先写 `PARTIAL` 清单，再导入主库/Excel、原件和 ES 快照。全部内容核对成功后才标记 `RESTORED_STOPPED`。部分目标禁止盲目追加和启动；修复后使用另一个全新目标恢复，保留失败证据。

原配置在目标 `archived-configuration/` 封存，活动配置只指向新依赖。启动入口自动禁用飞书、评测消费者、文档后台任务、对话、向量外发和 ONNX 精排，不启动外部评测执行器。外置模型记录仍为历史，不重新绑定源路径；较早的 V1 备份没有该记录时明确保留未知依赖，不能宣称权重已恢复。旧评测路径只作历史，不赋予资源操作权；恢复不会清理源资源、搬运 Cookie/localStorage 或重绑定旧处理器。旧审批因实例变化转为 STALE，追加 INSTANCE_CHANGED 审计；UNKNOWN 不重放。允许的启动差异逐行记录，其他业务表保持完整一致。

恢复验收另行核对旧账号与权限、归档问答、用量/事件、项目记忆、原件下载、Excel、版本过滤、保留的历史评测表和修复历史。向量核对只显式启动本轮合成回环夹具，保持原兼容端点；夹具结束后再次关闭向量能力。真实模型鉴权、质量、企业生产部署与多实例灾备不属于这套证据。

## 证据、CI 与清理

私有结果使用 Windows 当前 SID 独占 ACL，POSIX 根目录 0700、文件 0600。CI 的 `reliability-public-evidence` 产物只包含 `public/*.json` 和已遮盖秘密控件的合成截图；原配置、数据库、Cookie、HAR、trace、运行登记和应用日志不进入该产物。`supportops-evaluation-target` 是单独的程序包产物，包含 JAR、`target.json` 和来源快照，不包含上述私有运行材料。

[Verify 工作流](../.github/workflows/verify.yml) 在 Ubuntu 24.04 使用固定 Node 22.16.0、JDK 21、锁定 Playwright 与匹配 Chromium，顺序执行完整构建、固定目标程序包、资源归属检查、D/E/B 矩阵及索引容量、健康响应和端口兼容边界。2026-10-19 合并提交的两项作业及清理步骤已通过，见 [主线验证记录](github-validation-20261019.md)。结果只适用于所列 SHA，后续提交必须核对各自远端运行。

```text
node scripts/isolate.mjs --cleanup --deployment=<明确 deployment.json> --deployment-id=<相同 UUID>
```

清理前再次核对 daemon、容器、镜像、端口、卷标签和停止证明，并比较同一 Compose 项目的完整容器与卷集合；出现未登记对象即拒绝 stop/down，只删除这份清单的资源。维护锁或进程未知时保留证据；不能按文件日期解锁或删除未登记的容器/卷。原工作台、本地用户配置和其他项目资源保持原有归属。

## 人工处理结果与历史评测数据恢复

人工处理结果、不可变修订、固定回执与来源导出通过全库采集纳入停写备份。拆分前保留的案例、发布修订、数据集、样本和审阅表也随原库保留，但不承担当前案例管理或实验执行。恢复核对全部表的结构、行摘要与来源外键，不只核对最新记录；不会重发保存或发布命令，也不会启动外部评测实验。

拆分前 V10→V11 的历史升级验收保留了原用户、诊断、知识、飞书、评测和修复记录，并验证旧迁移摘要、旧审批失效及 UNKNOWN 不重放，见 [2026-10-02 开发验收记录](diagnosis-outcome-cases-acceptance-20261002.md)。当前遇到旧 V1 整体备份时使用下述显式兼容恢复入口，不能直接套用当时启动旧程序的步骤。

## 拆分前整体备份

STOPPED_COMPOSE_BACKUP_V1使用scripts/restore-legacy.mjs显式恢复新资源，默认不启动归档JAR。新backup/restore使用SUPPORTOPS_BACKUP_V2。AgentEval使用独立备份工具，恢复管理写入前核对新部署代次。
