# 受控修复中心 V1

修复中心连接现场预检查、不可变提案、人工审批、一次执行、执行后验证及追加审计。当前只支持本实例内置的合成 OrderBridge 2.0 路径配置迁移；订单请求通过真实回环 HTTP 发送，不代表企业订单落库或真实系统连接器验收。

## 固定动作与权限

动作 `ORDERBRIDGE_MIGRATE_SYNC_PATH` 的动作、验证、提案结构和审批策略版本均为 1。现场必须为 2.0，提供 `orders.path=/v2/orders`，尚无 `sync.targetPath`，实际生效路径为 `/v1/orders`，且必要事实可读取、下游可用、示例凭据条件有效。服务端按实际字段判断，不依赖场景标签或模型建议。

迁移删除旧键并设置 `sync.targetPath=/v2/orders`，保留其他配置。页面不能传配置键、目标地址、数据库、文件路径、脚本或任意 HTTP 参数。MCP 四个环境工具仍只读；模型没有批准和执行能力。创建、审批、执行和核查都不调用模型。

全部修复 API 仅 ADMIN。批准与执行分别捕获服务端身份；执行再次查询审批者和执行者的有效 ADMIN 角色。V1 允许同一管理员提案与审批，属于单人显式审批，不是双人复核。

## 使用流程

1. 在“修复中心”新建提案，或从已结束诊断、示例环境进入。关联诊断只提供来源和原事件游标，不提供执行授权。已归档诊断不会因此恢复续聊。
2. 查看实例、修订、配置差异、影响和验证方式，填写名称与理由。预检查和创建不发送订单请求、不应用配置。
3. 提案创建后内容冻结。默认有效 30 分钟；批准后的执行窗口为 10 分钟且不晚于提案期限。审批与执行是独立按钮。
4. 人员点击执行。服务端重新验证摘要、版本、时效、角色和实际现场，一次消费审批，至多应用一次配置并发送一次新订单请求。
5. 查看配置应用和业务验证两个事实栏，读取审批及执行审计，可导出 JSON 或 Markdown 报告。

需要改理由或重新采集现场时创建新提案，不修改旧快照或继承旧审批。拒绝、撤销、过期、失效、已执行提案均不能重新批准执行。每次启动具有新的实例 UUID；同名场景重置和将配置改回原值仍增加环境修订，不能恢复旧审批。

## 状态与结论

| 字段 | 状态 | 含义 |
| --- | --- | --- |
| 提案 | PENDING_APPROVAL / APPROVED / EXECUTING / FINISHED | 待批、批准、执行受理、已收尾 |
| 提案 | REJECTED / CANCELLED / EXPIRED / STALE | 驳回、撤销、过期、环境或版本失效 |
| applicationStatus | NOT_STARTED / NOT_APPLIED / APPLIED / UNKNOWN | 尚未开始、确认未应用、确认已应用、缺少可靠事实 |
| verificationStatus | NOT_RUN / RUNNING / PASSED / FAILED / UNKNOWN | 未验证、验证中、通过、失败、结果不明 |
| executionStatus | RUNNING / SUCCEEDED / FAILED | 在处理、应用且验证通过、确认未应用 |
| executionStatus | VERIFICATION_FAILED / UNKNOWN / CLOSED_UNRESOLVED | 已应用但验证失败、待核查、人工结束未决 |

通过需要原 operationId 的精确应用回执、配置读回、应用后新业务请求、HTTP 200、`accepted=true`、下游回显的请求编号与路径一致，以及原证据原子落库。健康检查 200、历史独立请求成功和当前配置正常均不能代替本次验证。报告最多 1000 条审计事件及 1 MiB；超限明确返回 413，不以截断报告冒充完整证据。

401、503 或明确业务拒绝保存 APPLIED + FAILED，综合状态为 VERIFICATION_FAILED。配置保持已应用，V1 不自动回滚。传输结果不明保留 UNKNOWN，不以第二次业务 POST 代替原响应。

## 固定请求与恢复

所有有状态命令携带固定 `requestId`；服务端以操作者 + requestId 唯一约束，同时绑定命令种类、资源和规范化正文摘要。同请求返回原结果，同编号不同内容返回 `REMEDIATION_REQUEST_CONFLICT`。每个提案最多一个审批决定和一个执行，新旧入口共享同一服务。

页面在发送前按账号保存完整 method/path/body/requestId；响应丢失、刷新、读取失败或暂时 404 都保留原请求，不生成替代执行。页面可以查询原请求或原样重发，服务端先查询原受理结果。另一账号不能读取或复用该恢复记录。

名称、理由的空白和长度等限制在冻结之前校验。服务器对有合法 requestId 的有界可解析写请求，即使字段校验失败，也保存绑定账号、操作、目标和正文摘要的 `400 INVALID_INPUT` 拒绝回执。旧版本已经卡住的无效请求可以原样重发，再查询持久化拒绝回执解除待确认状态；不能凭单次400、404或网络错误清除恢复记录，也不能修改旧正文复用编号。无合法编号或无法解析的正文不能登记为可确认原请求。

受理事务失败不开始外部写入；完成事务失败时返回 `REMEDIATION_RESULT_NOT_PERSISTED`，原操作可能已应用。可在同实例核查原回执补录，无新的配置或订单请求。回执缓存最多 64 个，受理前预留容量，待补录和在途回执不驱逐；证据单条最多 64 KiB，超限不伪装为成功。

执行适配器自预留起使用10秒总预算，单次HTTP完整响应最多3秒且不超过剩余总预算。计时覆盖响应头和正文，正文在收取时逐块检查64 KiB上限；超时取消订阅和请求，不再等迟到正文，不发送第二次POST。配置已经应用而完整验证响应未取得时保存APPLIED/UNKNOWN，准入在结果持久化后释放。只收到HTTP 200响应头不能记录PASSED。

启动恢复只修改保守记录状态，不续做配置或业务请求。原执行以实例 UUID、PID 和进程启动时间绑定。只有原 PID 不存在、明确退出或启动身份变化，才可确认旧处理器退出；读取身份失败不按超时抢占。重启后内存回执丢失，缺证据继续 UNKNOWN。

同一固定目标有 RUNNING 或 UNKNOWN 时禁止新修复，包括旧实例历史未决。场景重置不能清除这个约束。核查只读原操作；在途或退出身份不明时拒绝核查收尾及人工结束。人工结束要求理由，保存 CLOSED_UNRESOLVED 并保留 UNKNOWN 事实。后续重新提案通过 `supersedesExecutionId` 关联原记录，重新采集和审批。

## API 与旧入口

全部写请求 POST，带 `X-SupportOps-Request: 1`，沿用 Cookie 和来源保护；GET 不产生业务副作用。正文拒绝未知字段。详情能力提示不替代独立命令校验。

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| GET | `/api/remediations/actions` | 固定动作目录 |
| POST | `/api/remediations/preview` | 只读现场及差异 |
| POST / GET | `/api/remediations` | 创建或按状态分页，默认20最多100 |
| GET | `/api/remediations/{id}` | 详情 |
| POST | `/api/remediations/{id}/approve`、`/reject`、`/cancel`、`/execute` | 版本/摘要绑定命令 |
| GET | `/api/remediations/requests/{requestId}` | 当前账号原请求 |
| GET | `/api/remediations/{id}/events?after=0` | 升序游标审计，最多200条 |
| POST | `/api/remediations/executions/{id}/reconcile`、`/close-unknown` | 核查或人工结束 |
| GET | `/api/remediations/{id}/report?format=json` | JSON或markdown持久化报告 |
| GET | `/api/demo/environment-events?after=0` | 无提案的场景变更历史，升序最多200条，仅ADMIN |

`POST /api/demo/repair-config` 保留兼容路径，正文必须包含已批准 `planId`、`requestId`、`expectedVersion`、`planHash`。空正文或缺少提案返回 409 `REPAIR_APPROVAL_REQUIRED`，零配置与订单副作用。

`POST /api/demo/scenario` 仍是管理员构造合成场景的独立入口，可能重置为健康；这不属于经审批修复。重置增加修订，通过 `DemoScenarioService` 保存操作者、固定目标、原实例、前后修订、场景、时间、配置和同步结果；同名重置也追加记录。诊断和修复执行期间均拒绝。修复中心列表可读取跨实例场景历史。`/api/demo/sync` 仍是独立人员订单请求，其日志不能冒充修复验证。

## 存储、部署与边界

V9 仅新增 `remediation_plans`、`remediation_decisions`、`remediation_executions`、`remediation_requests`、`remediation_events` 五张表，不改写 B3、V4–V8 或删除旧数据。正常 Flyway 升级既有 V8 数据库；部署前应按现有流程备份并核实活动任务。不能通过清库解决升级问题。

V10增量新增 `demo_environment_events`，不改写已经应用的V9或其历史记录。场景意图先以STARTED在短事务提交，才改变内存现场并发送有界合成同步；读回和结果以条件更新收尾。APPLIED表示场景配置确认应用，业务HTTP结果另见原证据，不能等同修复验证成功。结果落库失败保留STARTED并返回 `503 SCENARIO_RESULT_NOT_PERSISTED`；读回不匹配记录UNKNOWN。历史读取和重启不自动重做场景操作，也不以新实例状态反推旧操作结果。

受理和完成为独立短事务；状态、请求、证据和审计分别在对应事务原子提交，网络调用在事务外。准入与诊断共享本进程边界。V1 不支持多个进程共享一个可变企业目标、后台调度、双人复核、自动回滚或真实企业连接器。

修复回归独立于诊断评测中心，不改变诊断评分、模型用量、评测审阅、SSE、飞书或归档语义。功能实现和隔离验收不等于当前日常工作台已经更新，也不证明生产可用性。
