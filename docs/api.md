# 后端接口文档

SupportOps 使用 springdoc-openapi 与 Swagger UI，从控制器和数据模型生成 OpenAPI 3 文档。接口名称、用途、参数、响应字段、枚举含义和错误说明均以中文维护在代码中。

## 访问入口

启动应用后访问：

| 入口 | 地址 |
| --- | --- |
| Swagger UI | [交互式接口文档](http://127.0.0.1:18080/swagger-ui/index.html) |
| 快捷跳转 | [swagger-ui.html](http://127.0.0.1:18080/swagger-ui.html) |
| OpenAPI JSON | [JSON 定义](http://127.0.0.1:18080/v3/api-docs) |
| OpenAPI YAML | [YAML 定义](http://127.0.0.1:18080/v3/api-docs.yaml) |

文档访问不需要模型密钥，不会调用外部模型。Swagger UI 的调试操作由人员显式执行；创建诊断和建立向量索引会调用已配置的模型服务，其他写接口会实际改变本地数据。

当前工作台只允许回环 Host 和同源浏览器请求，并使用账号 Cookie 认证。首次创建管理员及登录见 [用户系统](users.md)，Swagger/OpenAPI 仅管理员可读，写请求必须携带 X-SupportOps-Request: 1。模型 API 密钥属于模型配置，不是访问工作台的 Bearer Token。Swagger UI 的脚本与样式随应用提供，不依赖外部 CDN；在线规范校验服务已禁用。

## 接口分组

| 分组 | 接口数 | 能力 |
| --- | ---: | --- |
| 用户与登录 | 7 | 首次初始化、登录、退出、当前账号、用户列表与创建 |
| 运行状态 | 1 | 本地配置状态、当前模型路由及生成参数 |
| 诊断任务 | 7 | 创建、列表、详情、取消、归档、增量事件与 SSE |
| 用量分析 | 2 | 用量汇总、趋势、排行与会话历史 |
| 知识文档 | 7 | 上传、列表、原文、删除、索引、示例导入和检索 |
| 文档版本与处理任务 | 15 | 上传历史、修订、批次、原件、补偿与 Excel 查询 |
| 项目记忆 | 3 | 查询、显式确认保存和删除项目约束 |
| 示例环境 | 4 | 查询现场、切换故障、人工同步和配置修复 |
| 模型配置 | 4 | 读取、模型列表查询、保存指定角色和恢复文件配置 |

共 50 个业务接口、9 个中文分组。`/mcp` 使用独立的 Streamable HTTP JSON-RPC 协议，通过 MCP 客户端初始化和 `tools/list` 获取工具定义，不与 REST 接口混合展示。自建下游的随机端口用于内部故障环境，不属于工作台公共 API。

## 请求方式

业务 Controller 仅使用 GET 和 POST。GET 读取数据，POST 执行新增、修改、删除及其他有副作用的操作；删除和恢复使用明确的动作路径。

| 操作 | 请求 |
| --- | --- |
| 归档整个对话（保留用量统计） | `POST /api/runs/{id}/archive`，无需请求体，成功返回 200 空响应；执行中返回 409，无权访问返回 404 |
| 逻辑删除知识文档并受理外部存储清理 | `POST /api/documents/{id}/delete`；保留关系库历史与诊断证据 |
| 删除项目记忆 | `POST /api/memories/{id}/delete` |
| 获取服务商模型目录 | `POST /api/model-settings/{role}/models`，使用配置草稿及最新 `revision`；返回 `models` ID 数组，不保存 |
| 保存指定角色的模型配置 | `POST /api/model-settings/{role}`，JSON 请求体包含配置与 `revision` |
| 恢复指定角色的文件配置 | `POST /api/model-settings/{role}/reset?revision=当前版本标记` |

旧版 PUT、DELETE 调用已移除，调用方应使用上表中的 POST 接口。文档和记忆删除成功返回 HTTP 200、空响应体；配置保存和恢复返回完整配置视图。对已有路径使用不受支持的请求方式时返回 HTTP 405 和 `METHOD_NOT_ALLOWED`，`Allow` 响应头列出该路径支持的方法；已移除且没有其他操作的路径返回 HTTP 404 和 `RESOURCE_NOT_FOUND`。框架对 HEAD、OPTIONS 的处理及 MCP 传输遵循各自协议，不作为业务操作列入上述约定。

## 调用顺序

以下资料导入与配置步骤由管理员执行。普通用户可提交和读取本人的诊断，通过诊断证据快照查看引用片段，不能直接调用文档、模型配置或用量管理接口。

1. 登录后调用 `GET /api/status` 查看对话与向量路由是否已配置。配置完整不代表服务商鉴权和连通性已经通过验证。
2. 需要修改模型时，先调用 `GET /api/model-settings` 获取服务商预设和 `revision`，再通过 `POST /api/model-settings/{role}` 保存。`role` 为 `model` 或 `embedding`，可分别使用不同平台；向量角色允许设置为 `disabled`。
3. 调用 `POST /api/documents/import-examples` 导入示例资料，或用 `POST /api/documents/uploads` 上传文件。原件保存后异步解析、关键词索引及向量化；向量未配置时任务阻塞，关键词仍可用。按上传、批次和任务状态确认完成后再检索。
4. 调用 `POST /api/runs` 提交问题。返回任务编号只表示请求已受理，后续通过任务详情判断是否完成。
5. 使用 GET /api/runs/{id}/stream?after=上次事件编号接收 SSE snapshot 事件，data 为包含 run 和 events 的 JSON，id 为已读游标。自动重连的 Last-Event-ID 优先于 after；终态快照发送后关闭连接。连接本身不执行或取消任务。也可继续使用查询接口；轮询时先读取任务详情，再读取 `/api/runs/{id}/events?after=上次事件编号`，避免遗漏终态前写入的最后一批证据。任务创建返回不确定时，不应直接重复提交。

只体验资料检索时，可在未配置任何密钥的情况下导入示例文档，并调用 `GET /api/documents/search?query=sync.targetPath&version=2.0&lexicalOnly=true`。

## 响应与状态

业务响应直接返回对象或数组，不套用额外的成功包装。新上传接口 `/api/documents/uploads` 返回 HTTP 202 和上传记录；原 `/api/documents` 保留 HTTP 200 及文档投影，但处理已改为异步。重新分片、向量重建和人工重试的成功响应表示已受理，不能当作执行完成。删除接口返回 HTTP 200、无响应体。诊断创建与执行、业务同步请求与下游状态也分别表达。

常规业务错误使用以下结构，具体错误码说明以各接口响应区域为准：

```json
{"error":"MODEL_NOT_CONFIGURED"}
```

| HTTP 状态 | 含义与典型错误 |
| --- | --- |
| 400 | 参数或请求体无效；如 `INVALID_INPUT`、`INVALID_QUESTION`、`INVALID_DOCUMENT`、`EXPLICIT_CONFIRMATION_REQUIRED` |
| 401 | 未登录、会话失效或账号密码不正确 |
| 403 | 管理员权限不足、缺少写请求头，或本地访问边界拒绝 |
| 404 | 任务、文档、项目约束或接口路径不存在；无对应路由时为 `RESOURCE_NOT_FOUND` |
| 405 | 当前路径不支持此请求方式；`METHOD_NOT_ALLOWED` |
| 409 | 诊断运行中不允许修改现场或模型配置、会话执行中不能归档、`SESSION_ARCHIVED` 拒绝续聊、配置版本过期、上传幂等键载荷冲突、项目记忆达到上限 |
| 413 | 单文件超过 20 MiB 或 multipart 请求超过 25 MiB；应用受理后的解析上限错误记录在后台任务中 |
| 415 | 上传文件不是 MD、PDF、XLS 或 XLSX |
| 422 | 文档没有可提取文字等解析错误；异步受理后的错误通过上传和任务状态查看 |
| 429 | 登录或首次初始化请求超限；`LOGIN_RATE_LIMITED`，按 `Retry-After` 等待 |
| 500 | 存储或已选择的外部服务调用失败；配置持久化失败时保留旧配置 |
| 503 | 对话或向量路由不可用，或本地重排序发生模型加载、推理与超时等错误 |

模型配置错误中的 `MODEL_` 和 `EMBEDDING_` 前缀分别表示对话与向量角色。常见后缀含义：`NAME_REQUIRED` 缺少模型名称，`INVALID_BASE_URL` 地址不合法，`UNSUPPORTED_PROTOCOL` 协议不支持，`UNSUPPORTED_PROVIDER` 服务商不支持该能力，`INVALID_CREDENTIAL_REFERENCE` 密钥变量名无效，`CREDENTIAL_BINDING_REQUIRED` 新接口地址需要显式绑定凭据，`INVALID_OPTIONS` 生成参数无效。

诊断事件包括 `CONTEXT`、`TOOL_CALL`、`TOOL_RESULT`、`KNOWLEDGE`、`SQL_RESULT`、`ANSWER_DELTA`、`USAGE`、`ERROR` 八种。`SQL_RESULT` 保存 Excel 数据集、修订、Sheet、SQL、列名、精确字符串结果及截断标记；`KNOWLEDGE` 的片段包含 `versionId`、`batchId`、`originalUrl`。`ANSWER_DELTA` 的 `reset=true` 表示清空上一轮草稿，`delta` 为新增 Markdown；完成后以任务 `answer` 为准，失败或取消草稿须标记未完成。

## 检索排序信息

`GET /api/documents/search` 的 `mode` 继续表示召回方式 `LEXICAL` 或 `HYBRID`；`lexicalOnly=true` 仅关闭向量召回，不关闭已启用的本地模型重排序。新增 `ranking` 返回实际排序方式 `RRF`、`ONNX` 或 `EMPTY`、模型与分词文件指纹、候选数、输入 token 上限和毫秒耗时。

`passages[].score` 仍为 RRF 分数，新增 `rerankScore` 是可空的模型原始 logits；模型精排成功时按后者降序排列，分数可为负，不是概率或诊断置信度。旧历史事件没有新增字段时继续按原语义读取。模型加载、推理、超时等错误由检索接口返回 HTTP 503 和 `RERANK_*` 错误码，不以 RRF 结果替代失败。配置和完整边界见 [本地重排序](reranking.md)。

## 文档上传与 Excel 调用

`POST /api/documents/uploads` 使用 multipart：必填 `file`、`title`、`version`；可选 `documentId`（指定新增修订归属）、`note`、`requestKey`。可选 `options` 部分必须是 `application/json`，示例：

```json
{"chunkSize":100,"overlap":10,"sheets":[0,1],"headerRow":0,"columnTypes":{},"preview":true}
```

未指定上传配置时，文本默认使用 `chunkSize=100`、`overlap=10`。`chunkSize` 为 100–8000 个 Unicode 码点的上限，`overlap` 非负且 `chunkSize-overlap >= 10`；短章节和末片可以不足上限，重叠不跨章节或 PDF 页。历史 H2 迁移批次未记录真实参数，其响应中的这两个字段为 `null`。

上传响应包含服务端确认的 `userId`（历史记录可空），以及 `id`、`documentId`、`versionId`、`batchId`、`status` 和 `errorCode`。使用 `GET /api/documents/uploads/{uploadId}` 核对上传；`GET /api/documents/{id}/batches` 和 `/tasks` 查询解析、关键词、向量各自状态。修订由 `GET /api/documents/{id}/versions` 查询。`POST /api/documents/versions/{versionId}/reprocess` 以相同配置结构创建新批次；原件由该修订的 `/original` GET 返回，MISSING 原件可用同一路径 POST 文件补传。

Excel 通过 `GET /api/documents/batches/{batchId}/datasets` 查看预览，确认后提交 `preview=false` 的新批次。`GET /api/documents/datasets?version=2.0` 只返回当前适用且已发布的数据集；查询使用 `POST /api/documents/datasets/{datasetId}/query`：

```json
{"version":"2.0","sql":"SELECT c_0, SUM(c_2) AS total FROM data GROUP BY c_0"}
```

列名必须来自该数据集目录。查询不接受物理数据库名或表名，最多 200 行，`truncated=true` 时不能作为完整明细。`POST /api/documents/tasks/{taskId}/retry` 重试失败/阻塞任务，`GET /api/documents/tasks/{taskId}/attempts` 查询执行历史。完整状态和限制见 [知识存储与处理](knowledge.md)，历史数据维护见 [数据库维护](database.md)。

## 模型配置与密钥

模型配置请求可包含 `reasoningEffort`：空字符串明确省略，缺失保留现值；支持 `none`、`minimal`、`low`、`medium`、`high`、`xhigh`、`max`、`ultra`，服务商是否接受在实际调用时验证。向量角色忽略此字段。目录请求不要求模型名，但要求有效地址、密钥动作和修订标记；保留密钥仅限同服务商同完整基础地址。目录失败返回固定错误码，不返回服务商错误正文或凭据。

`apiKey` 标记为仅写入字段。配置读取、保存响应和 OpenAPI 定义均不含当前密钥，文档示例也不预填密钥。保存配置使用最新 `revision` 防止旧页面覆盖新值；保存和恢复在诊断运行期间会返回冲突。

界面保存的配置优先于文件与环境变量。密钥保存在本机未加密文件中，存储范围及服务商配置规则见 [模型服务配置](providers.md)。不要共享 `.local/` 目录或带真实密钥的调试请求。

## 维护与验证

接口的中文分组与操作说明位于各模块 `controller` 中的 `@Tag`、`@Operation`、`@Parameter` 和 `@ApiResponse`；请求对象位于 `dto`，响应对象位于 `vo`，字段通过 `@Schema` 描述。固定结构采用实际 DTO/VO，数据库实体与公开接口模型分开维护。`OpenApiConfiguration` 维护元信息和通用错误响应。

集成测试会将文档中的接口与实际 Spring MVC 路由比对，并检查中文说明、上传字段、密钥只写属性、事件载荷、响应数组、空响应及文档访问边界。生成的测试定义位于 `target/openapi.json`；运行中的 `/v3/api-docs` 是当前实例的定义来源。

版本选择参考 [springdoc 的 Spring Boot 兼容矩阵](https://springdoc.org/v2/#what-is-the-compatibility-matrix-of-springdoc-openapi-with-spring-boot)；本项目固定使用 [springdoc-openapi 2.8.17](https://github.com/springdoc/springdoc-openapi/releases/tag/v2.8.17)。

## 用量分析接口

- GET /api/usage?from=2026-09-01&to=2026-09-07&granularity=day：UTC 日期含首尾，最多 90 天，粒度 day 或 hour。返回 summary、补零 timeline、会话累计 Token 降序 topSessions（最多 20 条）。时间归属与缺失用量口径见架构说明的“用量分析”。
- GET /api/usage/sessions/{sessionId}?offset=0：按创建时间和任务 ID 升序返回会话历史，每页最多 50 轮，offset 范围 0–1000000；包含统计范围以外的问答。空数组表示没有记录或没有更多记录。任务事件仍通过已有 /api/runs/{id}/events 读取。

参数错误统一返回 HTTP 400 和 INVALID_INPUT；查询不调用外部模型，不修改记录。用量分析仅管理员可用。
