# 评测中心功能与技术设计

状态：开发规划，尚未实现。基于 2026-09-26 仓库源码核对；本文不构成真实模型效果、运行环境可用性或生产部署证明。

配套文档：[开发任务与验收清单](evaluation-center-tasks.md)。现有执行方式见 [评测说明](../../evaluation/README.md)。

## 1. 目标与版本范围

评测中心供管理员固定问题与资料，运行明确的诊断策略，保存原始证据，逐例审阅质量，对比实验并导出报告。重点回答：同样的故障和资料下，修改模型、检索策略或程序之后，哪些案例改善或退步，耗时和用量如何变化，依据是什么。

### 1.1 V1：完整诊断评测流程

- 后台增加“评测中心”；普通用户不可见且不可调用相关接口。
- 内置六场景评测集的版本化登记、列表、详情及案例选择。
- 实验草稿、启动前检查、调用规模确认、后台执行、取消与异常恢复。
- 两种类型：来源检查 `SOURCE_CHECK`、模型诊断 `MODEL_DIAGNOSIS`。
- 一个模型快照下，比较关键词、混合检索、混合检索加 ONNX 三种策略。
- 每场景重复 1–5 次，默认 1 次；默认仅选择一种策略。
- 实验进度、逐例回答、工具记录、检索原文、自动检查、用量和错误。
- 七维人工审阅、证据关联、修订历史、审阅覆盖率。
- 实验内策略对比，以及不同实验之间的模型或程序版本对比；先检查条件是否可比。
- JSON / Markdown 报告；子实例清理后仍可回看已收集证据。
- 保留现有命令行入口及原有语义，CLI 不依赖管理中心才能运行。

首版每个实验只绑定一份对话模型快照。比较其他模型时，管理员通过现有模型配置功能选择模型，再创建另一实验；实验创建和执行本身不得改写日常工作台模型配置。运行中的实验始终使用启动时冻结的配置。

### 1.2 后续阶段

| 阶段 | 功能 | 前提与边界 |
| --- | --- | --- |
| V1.1 | 检索专项评测：Recall@5、MRR@5、nDCG@5 | 先制作固定查询和人工相关性标注，采用稳定来源键；不能从是否命中任意片段推导指标 |
| V1.1 | 多模型档案及单次实验矩阵 | 评测凭据独立管理，不能反复改写全局配置切换模型 |
| V1.1 | 自定义评测集、失败案例转入 | 人员选择、脱敏并确认标准；现场回放需要受控场景适配器 |
| V1.2 | Prompt/Skill 候选版本、回归门禁 | 固定版本、标注案例和明确阈值 |
| V1.2 | 模型辅助审阅 | 单列审阅来源、裁判模型和成本，不能伪装成人工签收 |
| V1.2 | 多执行节点、定时评测 | 先具备节点身份、资源分配、租约与未知执行恢复 |

V1 不包含任意脚本上传、任意 URL 测试、自动修复、多 Agent、生产故障注入、完整工单、通用权限平台、自动账单估算和任意历史文件导入。

## 2. 现有基础与增量

| 源码入口 | 已有能力 | V1 增量 |
| --- | --- | --- |
| `scripts/evaluate.mjs` | JAR 副本、随机存储、回环端口、临时管理员、模型设置副本、清理 | 可复用执行函数、受控清单、版本化进度、合作式取消、提前登记资源 |
| `evaluation/run.mjs` | 六场景、来源检查、实际诊断、终态/事件读取、失败停止 | 参数化案例和问题、逐例回调、冻结输入、分阶段超时、独立清理信号 |
| `evaluation/report.mjs` | 输入指纹、引用/版本/工具检查、Markdown | 保留纯函数，新增版本和策略字段；自动检查不得命名为准确率 |
| `evaluation/cases.json` | 场景、故障条件、审阅要求 | 不可变评测集版本，问题/资料/攻击材料/标准一起冻结 |
| `IsolatedKnowledgeWorkspace` | 回环测试资源创建和清理 | 复用受控 CLI，先登记所有权，拒绝工作台资源，可重复核查清理 |
| `ProviderRegistry` / `LocalModelSettings` | 路由、覆盖优先级、凭据隔离 | config 内部快照服务；公开描述和私密执行值分离 |
| `UserAccessFilter` | Cookie、ADMIN 默认边界、POST 请求头 | 新接口完整权限验证 |
| `app.js` / `index.html` | 静态管理页面 | 独立 `evaluation.js` / `evaluation.css`，复用现有 UI 能力 |

关键现状：`lexicalOnly=true` 不关闭 ONNX；现有 `--compare` 不是重排序开关实验。`NO_DETECTED_VIOLATIONS` 和 `EXECUTION_FINISHED` 均不能转换为模型正确率。

## 3. 页面与业务流程

### 3.1 实验列表

显示名称、类型、数据集版本、策略、模型、创建人/时间与执行状态。独立显示完成样本数、自动问题、已审阅数、质量分布、清理状态。支持分页（默认 20、最大 100）、筛选、新建、查看、复制草稿、选择两组对比。V1 不做物理删除。

### 3.2 新建及预检查

1. 选择实验类型、内置评测集版本、案例子集。
2. 选择策略和重复次数，填写名称/说明。
3. 展示脱敏模型配置、预算、实际执行 JAR 指纹。
4. 检查 Node/JDK/JAR/执行包、测试目标、所需密钥配置、ONNX 文件。
5. 显示计划诊断数 = 案例数 × 策略数 × 重复次数；另列索引/探针可能产生的嵌入调用，不把诊断数当成总请求数或费用上限。
6. 模型实验按钮为“开始评测（将调用模型服务）”；来源检查明确不调用对话/嵌入服务。

预检查不发送推理、不修改故障场景、不创建数据库；配置存在与鉴权成功分开。成功返回绑定实验版本、配置修订、清单摘要的 `preflightId`，有效期 10 分钟。启动时再核对；过期或内容改变返回冲突，保留草稿。

### 3.3 详情与审阅

- 阶段：准备资源、导入资料、建立索引、执行案例、收集证据、清理资源。
- 样本按策略、场景、重复序号排列；展示回答、工具调用/结果、检索片段、版本、位置和事件 ID。
- 引用使用已保存快照；不能跳转到已关闭子实例或已清理原件地址。
- 七维审阅支持事件 ID、片段 ID、回答段落和说明；保存冲突明确提示。
- 日志、答案、资料、备注均为不可信内容；Markdown 禁用原始 HTML，不执行命令。
- 取消先显示“取消中”；刷新从服务器恢复，不重复启动。启动响应丢失后按原请求 ID 查询。

### 3.4 对比与报告

一次比较两个“实验 + 策略”结果集，各自汇总该策略的全部重复，可来自同一或不同实验；按 case_key 和重复序号对齐。底层执行组仍是“策略 × 重复序号”，不能只取其中一次冒充全部重复的统计。展示配置差异、配对覆盖、执行/质量/用量分布，并排阅读回答与证据。改善/退步必须有审阅依据；不兼容记录只能探索性展示。JSON 保留结构和证据，Markdown 提供摘要/条件/限制/逐例记录，均携带导出时审阅版本。

## 4. 实验契约

### 4.1 三策略

| 策略 ID | 召回 | ONNX | 能力要求 |
| --- | --- | --- | --- |
| `LEXICAL_RRF` | BM25 | 明确关闭 | 模型诊断需要对话服务；来源检查无模型密钥要求 |
| `HYBRID_RRF` | BM25 + 向量 + RRF | 明确关闭 | 对话及嵌入服务 |
| `HYBRID_ONNX` | BM25 + 向量 + RRF | 明确开启 | 对话及嵌入服务、真实本地 ONNX |

来源检查只允许 `LEXICAL_RRF`，不能继承工作台向量/重排序开关。参数由服务端生成，仅影响子实例。返回预算 5、ONNX 候选预算 20；不同预算必须建立明确不同的基线，不能悄悄扩大。每次实际检索核对 mode 和 ranking.method；退化到 LEXICAL、ONNX 报错或配置不符不能记为策略成功。

### 4.2 数据集与可复现输入

- 内置稳定案例 ID：downstream-unavailable、invalid-credentials、configuration-migration、stale-document、insufficient-evidence、document-injection。
- 版本包括问题、固定场景枚举、预期来源状态、审阅标准、资料/攻击材料哈希、分片设置、顺序、格式版本。
- 已被引用的版本不可变；内容更新产生新版本。标准答案、预期状态和审阅标准不得进入模型上下文。
- 每个“策略 × 重复序号”为执行组，各自创建全新数据库、桶、索引和实例。组内固定次序，每例新会话。
- 整个实验固定 JAR、脚本、材料、问题、分片与对话参数；每组不能重新读取工作区最新文件。
- 保存实际副本与 SHA-256。由 JAR 提供的示例资料、Prompt/Skill 应记录 JAR 内真实资源摘要；工作区源码哈希只作补充。
- 跨组对照 JAR SHA-256 必须相同。没有源码与构建映射时标记 `buildProvenance=UNKNOWN`，不伪造构建版本。

### 4.3 配置快照与密钥

- config 内部服务一次捕获有效路由、生成参数和修订；公开描述与私密执行信息分离。
- 数据库/API/导出仅含 Provider、模型、安全端点、参数、指纹；不得保存或输出密钥。
- 私密文件存服务端限定目录；POSIX 限权，Windows 设置/验证当前运行账号 ACL，不能用 mode=0600 证明 Windows 限权。
- 子进程只接收本实验必需凭据与白名单环境变量，清除飞书和无关凭据；密钥不进入命令行、公共事件、异常或报告。
- 执行期间全局配置变化不影响实验，不把旧密钥发送到新地址。
- 确认进程退出后删除临时私密快照；进程未知时保留待核查，不导出、不盲删。
- 复制实验只复制公开意图，重新绑定当前凭据，不能重用历史临时密钥。

## 5. 执行架构

```text
管理员页面 -> EvaluationController
           -> EvaluationService / ReviewService / ComparisonService
           -> MyBatis-Plus + MySQL（实验、结果、审阅、事件）
           -> EvaluationWorker / EvaluationRunner（受控进程适配）
           -> 固定 Node 执行包 + 私密清单
           -> 隔离 Java 实例 + 测试 MySQL / MinIO / ES
           -> 版本化事件/逐例结果 -> 校验脱敏、幂等导入中心数据库
```

### 5.1 职责与部署

- Java 管理认证、快照、状态机、调度、持久化、审阅/对比；Node 复用现有隔离执行与清理，避免两套规则。
- 新模块 `io.supportops.evaluation` 遵循职责分层，进程适配可放 service/support；跨模块通过服务，不访问其他模块 Mapper。
- 评测数据单独存表，不写入日常 runs/run_events，不污染日常 Token 统计。
- Java 使用固定可执行路径和参数数组启动 Node，不使用 shell、cmd /c 或网页命令。
- Node/JAR/执行包/运行根目录/测试连接通过可信部署配置提供；网页不能选择脚本、路径、数据库或任意 URL。
- 执行功能默认关闭，查询功能不依赖执行环境。纯 JAR 部署缺少执行包时显示不可执行，保留历史查看。
- 需要 Node 22、JDK 21、匹配执行包和 JAR、已准备的独立存储；网页不启动 Docker、不下载模型、不构建 JAR。
- 隔离子应用明确关闭评测 Worker、飞书及其他外部入口，防止递归启动或接收真实消息。
- 校验实际测试连接、账号、资源范围；回环不是充分条件，必须排除工作台数据库/桶/索引。创建前落盘 workspaceKey 及计划资源名。
- V1 最多一个活动执行器，排队最多 10；采用数据库协调、租约和 fencing token，不仅靠 JVM 锁。组内串行。
- 与日常工作台数据隔离，但可能共享 CPU/模型端点，报告披露资源竞争限制。

服务端配置至少明确执行开关、Node/JDK/JAR/执行包路径、运行根目录、队列/总时限/证据大小限制和测试存储引用。沿用 Spring 配置方式；如由本地启动器注入环境变量，应同步 `scripts/local-config.mjs` 的白名单与配置示例。测试存储管理凭据不通过网页配置、不进入业务公开配置。现有 IsolatedKnowledgeWorkspace 的 JDBC 保持离线资源工具边界，不搬进业务 Controller/Service。

### 5.2 Java/Node 协议

- 请求：schemaVersion、experimentId、attemptId、fencing token、案例、策略、重复数、预算、输入哈希和私密文件引用；内部/公开 DTO 分离。
- 公共事件采用 NDJSON：schemaVersion、experimentId、attemptId、sequence、type、occurredAt、类型明确的 payload。
- 最少事件：RUNNER_STARTED、HEARTBEAT、GROUP_STARTED、RESOURCE_REGISTERED、CASE_STARTED、CASE_FINISHED、GROUP_FINISHED、CLEANUP_RESULT、RUNNER_FINISHED。
- 大型结果先写临时文件再原子改名，事件携带受控相对文件键、长度和摘要；Java 验证路径、大小、哈希、归属、协议后导入。
- 心跳刷新租约，无需无限保存为业务事件；事件按 `(attempt_id, sequence)` 幂等，同序号不同摘要必须失败。
- 逐例证据导入 MySQL，清理运行文件后仍可查看。正常运行不读取未换行尾记录；退出后残缺尾记录/哈希不符必须报协议失败。
- 取消由服务端在限定目录写控制记录，Node 定期读取；强制终止只作超时兜底，Windows 不能把 SIGTERM 等同于优雅退出。

### 5.3 限制与监控

- 1–6 案例、1–3 策略、1–5 次重复，最多 90 样本；服务端校验。
- 诊断沿用 10 轮、120 秒，首版网页不开放任意 Agent 参数；实验总时限默认 60 分钟，可选 5–360 分钟，包含准备/清理预留。
- 推理轮数不等于模型请求数，不保证绝对费用上限。
- 案例公共证据文件建议上限 16 MiB、实验 256 MiB，可由服务端配置；超限明确错误，不静默截断后宣称完整。
- 导出受限/流式生成；没有任意文件下载接口，不公开原始应用日志。
- 前端新增评测进度采用有界分页轮询，活动页约 2 秒，终态停止；不改变现有诊断 SSE。

## 6. 状态与恢复

### 6.1 独立结果维度

| 维度 | 状态 |
| --- | --- |
| 执行 | DRAFT、QUEUED、PREPARING、RUNNING、FINALIZING、COMPLETED、FAILED、CANCELLING、CANCELLED、INTERRUPTED |
| 自动检查 | NOT_RUN、NO_DETECTED_VIOLATIONS、HAS_VIOLATIONS、INCOMPLETE |
| 审阅进度 | NOT_APPLICABLE、NOT_REVIEWED、PARTIALLY_REVIEWED、REVIEWED；质量判定另存 |
| 清理 | NOT_CREATED、PENDING、CLEANED、FAILED、UNKNOWN |

COMPLETED 仅表示计划执行与收集完成，可以同时存在自动检查或质量问题。模型任务失败、基础设施/协议错误、结果未知导致 FAILED/INTERRUPTED；剩余预建样本标记 SKIPPED 和原因，不能从分母消失。

### 6.2 迁移和幂等

- 正常：DRAFT -> QUEUED -> PREPARING -> RUNNING -> FINALIZING -> COMPLETED。
- 预检查失败保留 DRAFT；运行失败先 FINALIZING 收集/清理，再 FAILED。
- 排队取消直接 CANCELLED；运行取消经 CANCELLING，处置后 CANCELLED；无法确认运行状态则 INTERRUPTED + UNKNOWN。
- 启动使用固定 requestId、expectedVersion；同键同内容返回同一结果，不同内容 409。终态禁止旧 Worker/迟到成功覆盖。
- 启动先在事务外准备输入副本并核对副本哈希，再用短事务验证版本并提交受理；复制与检查之间源文件变化必须被发现。受理失败的私密临时包准确清理，已受理的包由执行生命周期管理。
- 对外诊断提交未知不重发。已知 runId 查询/取消；未知 runId 保留未知结果、停止后续样本、处置隔离实例。
- 模型失败或未知默认停止整个实验；自动检查违规但执行正常可以继续其余样本。
- 文件、HTTP、模型和进程操作不在数据库长事务；状态更新核验旧值、版本、fencing token 和影响行数。

### 6.3 重启与资源处置

- 重启可领取尚未开始的 QUEUED；已开始先 INTERRUPTED 核查，不自动继续或重跑模型。
- 恢复核查仅针对旧 owner 已失效的执行尝试；另一个实例仍持有有效租约时不得在启动阶段把它的活动实验批量改为中断。
- 租约过期不代表旧进程死亡；不得直接由新 Worker 重跑。
- 联合核对 PID、开始时间、可执行文件身份、JAR 摘要、attempt/workspace；不按端口/进程名杀进程，不仅凭 PID。
- 只停止确认归属的子进程；身份未知时保留并阻止第二活动执行器。
- 抢救证据/清理使用独立且有上限的信号，不复用已 aborted 主信号。
- cleanupError 独立于原始执行错误；核对已无进程后按精确资源清单重试清理，不重新执行实验。
- 重复清理对已不存在的本次资源幂等，但目标身份和资源清单不匹配必须拒绝。

## 7. 数据模型

新增下一号 Flyway 迁移；本次核对最新 V5，如开发时仍如此使用 `V6__evaluation_center.sql`，不改 B3/V4/V5。

| 表 | 职责/核心字段 | 关键约束 |
| --- | --- | --- |
| evaluation_datasets | dataset_key、version、name、manifest、content_sha256 | 版本唯一，已引用不可变 |
| evaluation_cases | dataset_id、case_key、ordinal、question、scenario、来源标准、rubric | `(dataset_id, case_key)` 唯一 |
| evaluation_experiments | name、kind、dataset、选择、repeats、status、配置/输入快照、creator、version、request_id/digest、时间/错误 | 请求键唯一，无密钥，状态/时间索引 |
| evaluation_groups | experiment_id、strategy、repeat_index、status、配置、attempt_id | `(experiment_id, strategy, repeat_index)` 唯一 |
| evaluation_samples | group_id、case_id、status、run_id、answer、耗时、Token、usage_state、checks、错误、证据摘要 | `(group_id, case_id)` 唯一，未知用量不补零 |
| evaluation_evidence | sample_id、ordinal、source_event_id、kind、时间、payload | `(sample_id, ordinal)` 唯一，原事件 ID 按样本解释 |
| evaluation_reviews | sample_id、reviewer_id、revision、七维结果、note、时间 | 追加历史，单样本一个当前审阅，非多人评分聚合 |
| evaluation_attempts | owner、token、租约/心跳、进程身份、workspace 清单、cleanup、version | 未知执行不可双领，私密路径不进公开 VO |
| evaluation_events | experiment_id、attempt_id、sequence、type、payload、摘要 | `(attempt_id, sequence)` 唯一 |
| evaluation_runner_guard | 固定单行、attempt、owner、token、租约、version | 持久化全局互斥，不能普通先查再插入 |

ID、时间、账号外键字符集/排序规则与现有库一致。固定字段采用明确实体/DTO/VO，JSON 只承载版本化清单、协议载荷和维度集合。样本和证据导入、审阅修订、状态迁移各自保持短事务原子性。

实验记录另需持久化 preflightId、有效期及预检查摘要；样本显式保存七维审阅的当前版本号（或等价并发保护），避免只读取 MAX(revision) 后并发插入。draft 更新、预检查发布、启动受理与审阅保存的版本条件必须有并发测试。

## 8. API 草案

统一前缀 `/api/evaluations`，仅 ADMIN；POST 保留 `X-SupportOps-Request: 1`，不改变 `/api/runs`。

| 方法 | 后续路径 | 用途 |
| --- | --- | --- |
| GET | /capabilities | 开关、策略、限制、安全的环境状态 |
| GET | /datasets、/datasets/{id} | 版本和案例 |
| POST | /datasets/import-builtin | 幂等登记内置版本，无目录/任意文件参数 |
| POST | /experiments | 新建草稿，201 |
| GET | /experiments、/experiments/{id} | 分页列表/详情 |
| POST | /experiments/{id}/update | 仅草稿修改，检查版本 |
| POST | /experiments/{id}/preflight | 检查并保存短期预检查结果 |
| POST | /experiments/{id}/start | requestId、expectedVersion、preflightId，202 |
| GET | /requests/{requestId} | 未知启动查询；404 不触发换键盲重试 |
| POST | /experiments/{id}/cancel | 幂等取消，202 或现有终态 |
| POST | /experiments/{id}/clone | 复制公开方案为草稿，不启动 |
| GET | /experiments/{id}/events?after=...&limit=... | 有界游标进度 |
| GET | /experiments/{id}/samples、/samples/{id} | 样本和证据 |
| GET | /samples/{id}/reviews | 当前/历史审阅 |
| POST | /samples/{id}/reviews | expectedVersion + 七维判定和证据关联 |
| GET | /comparison?leftExperiment=...&leftStrategy=...&rightExperiment=...&rightStrategy=...&axis=... | 两个策略结果集的可比性与全部重复统计 |
| GET | /experiments/{id}/export?format=... | 仅 json/markdown，从数据库脱敏导出 |
| POST | /experiments/{id}/cleanup | 身份核实且停止后重试清理 |

错误码至少覆盖执行器禁用、环境不可用、配置变更、版本冲突、请求键冲突、队列满、输入改变、协议错误、证据超限、进程身份未确认。全部接口维护中文 OpenAPI、枚举、大小限制、分页、冲突和异步受理含义；内部私密清单不可直接作公开响应。

## 9. 审阅与指标

### 9.1 人工审阅

七维：事实准确性、引用支持度、因果谨慎性、摘要与结论证据一致性、可操作性、证据不足处理、指令边界。每维 PASS / FAIL / INSUFFICIENT_EVIDENCE / NOT_APPLICABLE；未填写即未审阅。

- FAIL/证据不足必须说明，有原始记录时关联 ID；不得要求为缺失证据伪造 ID。
- 七维填完才算已审阅；任一 FAIL 为发现问题，否则有证据不足则不能通过，其余适用项全 PASS 方为人工通过。全部不适用单列，不计通过。
- 来源检查质量不适用。开发夹具标记 PROTOCOL_FIXTURE，不计真实模型质量。
- 自动与人工结果独立，存在矛盾要显示；人工修订用 expectedVersion 防覆盖并保留历史。
- 注入评测区分文档命中、具体恶意片段暴露、回答行为；文档 ID 命中不能代表全部攻击都测到。

### 9.2 V1 指标口径

| 指标 | 规则 |
| --- | --- |
| 执行完成率 | 正常完成数 / 计划数；失败、取消、跳过单列 |
| 自动问题占比 | 违规数 / 已完整检查数；另列未检查/不完整，不叫正确率 |
| 审阅覆盖率 | 七维完成数 / 已产出可审阅回答的模型样本数；另列无回答失败 |
| 人工通过占比 | 通过数 / 有适用项且完成审阅数，证据不足在分母；同时展示分布 |
| 耗时 | 诊断与准备/索引/清理分开，显示 N、中位数、均值；N<20 不强调 P95 |
| Token | 已报告输入/输出、每例、未报告/可能不完整数；未知不补零，不折算费用 |
| 成对变化 | 相同案例/重复序号且两边可判定的配对为分母；列出未配对和无法判断 |

V1 不展示未经相关性标注计算的 Recall/MRR/nDCG，也不从自动检查生成“模型准确率”。

### 9.3 可比性

- RETRIEVAL：问题/语料/分片/JAR/Prompt/Skill/对话参数/预算相同，允许召回和排序差异。
- MODEL：问题/语料/程序/检索/Prompt/Skill/预算相同，允许对话路由及相应生成参数差异。
- BUILD：案例和标准一致，展示程序/Prompt/Skill 差异；资料同时改变则探索性。
- EXPLORATORY：全部已知差异显示，不作单变量归因；关键字段缺失也归此类。
- 少量重复不宣称统计显著；Provider 模型名不能证明代理上游内部模型版本。

## 10. 首版完成条件

完成“创建实验 -> 真实隔离来源检查 -> 模型协议夹具三策略 -> 原始证据 -> 人工审阅 -> 对比 -> 导出”，取消和重启故障路径有证据。不能只做页面或空接口。

真实模型/真实 ONNX 效果验收需要明确的环境、服务商和调用范围授权；本地凭据存在不等于授权。未执行时单列未验证项，不用协议夹具替代。详细开发任务见 [任务清单](evaluation-center-tasks.md)。
