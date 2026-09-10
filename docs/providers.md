# 模型服务配置

SupportOps 将 Provider 定义、对话选择、向量选择和凭据引用分开。工作台和评测共用 Java 配置解析器，不在启动脚本中复制平台默认值或密钥回退规则。配置结构参考了 [DeepSeek Harness 的 Provider 配置](https://github.com/deepseek-ai/deepseek-harness/blob/master/docs/user/guide/providers.md)，采用服务商 ID、接口协议、基础地址和凭据变量名作为路由信息。

## 在工作台中配置

管理员登录后，打开“后台管理 → 模型配置”，分别设置对话模型与向量服务。普通用户使用管理员配置的服务，不能读取或修改后台配置。

1. 选择服务商，检查预填的模型名称与接口地址；兼容网关可选择“自定义兼容接口”。
2. 在表单中填写 API 密钥；同服务商同地址可保留已保存的密钥。此时仍是未保存草稿。
3. 点击“获取可用模型”，使用当前表单的地址与密钥读取服务商目录，模型较多时可在“搜索可用模型”中输入关键词，按模型 ID 进行不区分大小写的包含匹配；清空搜索恢复完整目录。页面显示匹配数量，无匹配时保留当前模型 ID。选择后将准确模型 ID 填入表单；仍可手动输入。获取不会保存配置或调用模型生成。空目录、不支持目录、鉴权失败和超时会明确显示；模型列表不代表对话、向量或推理等级能力。
4. 对话模型可选择推理等级，默认不发送 `reasoning_effort`；具体支持值由模型与服务商决定。检查模型 ID 与地址后点击保存，对新诊断生效。
5. 不需要语义检索时，将向量服务选为“关闭向量服务”并保存。文档上传、分片与关键词检索仍可使用。

界面保存立即生效，无需重启。诊断运行期间不能保存或恢复配置。页面中的“已配置”只表示本地参数完整，服务连通性和账号权限在实际调用时验证，保存不会发起付费模型请求。

同一服务商、同一接口地址下，密钥留空保留原值。更换服务商或接口地址后需填写相应密钥，旧密钥不会沿用。勾选“清除密钥”后保存会停用该角色的密钥，不会自动回退到环境变量。“恢复文件配置”删除该角色的界面覆盖值，重新使用文件与环境变量。

配置保存在 `.local/model-settings.json`，包含未加密密钥，不应共享或提交该目录。页面和配置接口只返回密钥是否存在，不返回原值。保存采用同目录临时文件与原子替换；写入失败保留原配置，多页面修改发生冲突时要求重新读取。文件位置可通过 `supportops.settings.file` 指定。

## 内置预设

| Provider ID | 基础地址 | 默认对话模型 | 默认向量模型 | 密钥变量 |
| --- | --- | --- | --- | --- |
| `bailian` | `https://dashscope.aliyuncs.com/compatible-mode/v1` | `qwen-plus` | `text-embedding-v3` | `DASHSCOPE_API_KEY` |
| `deepseek` | `https://api.deepseek.com` | `deepseek-v4-flash` | 预设不提供向量能力 | `DEEPSEEK_API_KEY` |
| `openai` | `https://api.openai.com/v1` | `gpt-4.1-mini` | `text-embedding-3-small` | `OPENAI_API_KEY` |
| `custom` | 显式填写 | 显式填写 | 显式填写 | `CUSTOM_API_KEY` |

预设用于减少配置量，模型名称可以覆盖，不是自动查询账号可用模型的结果。DeepSeek 的接口与模型标识见 [官方接入说明](https://api-docs.deepseek.com/)；OpenAI 预设能力见 [GPT-4.1 mini](https://developers.openai.com/api/docs/models/gpt-4.1-mini) 和 [text-embedding-3-small](https://developers.openai.com/api/docs/models/text-embedding-3-small)。实际可用性由账号权限、服务区域及服务商决定。

## 两个独立选择

在 `.local/model.env` 中，仅启用 DeepSeek 对话：

```dotenv
SUPPORTOPS_MODEL_PROVIDER=deepseek
DEEPSEEK_API_KEY=填写本地密钥
SUPPORTOPS_EMBEDDING_PROVIDER=disabled
```

DeepSeek 对话与 OpenAI 向量可以组合使用：

```dotenv
SUPPORTOPS_MODEL_PROVIDER=deepseek
DEEPSEEK_API_KEY=填写本地对话密钥
SUPPORTOPS_EMBEDDING_PROVIDER=openai
OPENAI_API_KEY=填写本地向量密钥
```

两个选择均使用 OpenAI 时，同一个 `OPENAI_API_KEY` 可以用于两条路由。需要不同账号时，用 `SUPPORTOPS_API_KEY` 和 `SUPPORTOPS_EMBEDDING_API_KEY` 分别覆盖，或设置各自的 `SUPPORTOPS_MODEL_API_KEY_ENV`、`SUPPORTOPS_EMBEDDING_API_KEY_ENV`。

没有模型密钥仍能在存储连接正常时启动应用、上传、分片、关键词检索和管理项目记忆。对话任务只要求对话路由可用。文档关键词索引完成后自动生成向量任务：未配置或禁用时任务显示 BLOCKED，补齐原先缺失的配置后自动唤醒；手动重建及混合评测要求向量配置。无兼容已发布向量时检索使用 `LEXICAL`，进入向量调用后的失败明确报错。Excel 关系查询不调用嵌入服务，模型生成 SQL 使用对话角色。

## 自定义 Provider

将 [providers.example.yml](../providers.example.yml) 复制到 `.local/providers.yml` 后编辑。应用启动时自动加载此文件。下面的配置将对话路由指向企业网关，同时使用百炼向量：

```yaml
supportops:
  model:
    provider: company-gateway
    max-output-tokens: 2500
  embedding:
    provider: bailian
  providers:
    company-gateway:
      api: openai-completions
      base-url: https://gateway.example/v1
      api-key-env: COMPANY_API_KEY
      chat-model: your-chat-model
      embedding-model: your-embedding-model
      max-tokens-field: max_tokens
      temperature: omit
```

密钥写入 `.local/model.env` 的 `COMPANY_API_KEY` 和 `DASHSCOPE_API_KEY`，或由进程环境提供。配置文件只引用变量名。自定义密钥变量使用大写字母、数字和下划线，名称以 `_API_KEY` 结尾。

可定义多个 Provider，再用 `supportops.model.provider` 与 `supportops.embedding.provider` 选择。模型名称覆盖使用 `supportops.model.name` 和 `supportops.embedding.name`。`embeddings: false` 可声明某个 Provider 不用于向量功能。

## 参数与配置优先级

界面保存的服务商、模型名称、地址和密钥优先于下面的文件配置规则；对话与向量分别覆盖。输出上限、温度和 thinking 参数仍由文件配置管理。推理等级可由界面覆盖；选择默认会显式省略该参数，恢复文件配置后重新使用文件值。旧配置文件无需迁移，未保存推理字段时继续使用文件值。

未保存界面覆盖值时，Provider 提供默认地址、模型和兼容参数；`supportops.model` / `supportops.embedding` 下的非空值覆盖对应默认值。密钥解析依次使用角色专用密钥、角色的 `api-key-env` 引用、所选 Provider 的 `api-key-env` 引用。不会查找其他 Provider 的密钥。

应用使用 Spring 配置优先级：命令行参数与对应的进程环境变量可覆盖本地 YAML；本地 YAML 可覆盖内置定义。`SUPPORTOPS_MODEL`、`SUPPORTOPS_EMBEDDING_MODEL`、`SUPPORTOPS_API_KEY` 等原有变量通过内置占位符继续兼容。若在 YAML 中显式设置了 `name` 或 `api-key`，应使用对应标准变量 `SUPPORTOPS_MODEL_NAME`、`SUPPORTOPS_MODEL_API_KEY` 等覆盖，或移除该 YAML 值。建议密钥只保留为环境引用。

Node 启动脚本读取 `.local/model.env` 时，文件中的非空值覆盖同名进程环境值，空值保留进程已有值。直接通过 Java / Maven 启动不读取此文件。文件与环境变量修改需要重启，界面保存无需重启；已有诊断保留原模型路由快照，新任务读取当前配置。

| 对话参数 | 含义 |
| --- | --- |
| `max-output-tokens` | 单次模型调用的输出上限，默认 2500，范围 1–32768 |
| `max-tokens-field` | `max_tokens` 或 `max_completion_tokens`，避免同时发送两个字段 |
| `temperature` | 0–2，或 `omit` 表示不发送该参数 |
| `thinking` | `enabled` / `disabled`，按 DeepSeek 格式发送 `thinking.type` |
| `reasoning-effort` | 显式设置时发送 `reasoning_effort`，取值须符合所选服务与模型 |

OpenAI 预设使用 `max_completion_tokens` 并省略温度参数；DeepSeek 预设使用 `max_tokens`，显式关闭思考模式。两类参数的服务端约定分别见 [OpenAI Chat Completions](https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create) 和 [DeepSeek 思考模式](https://api-docs.deepseek.com/guides/thinking_mode/)。这里的预设与本地协议测试不代表所有推理模型、参数组合均已通过真实服务验证。

当前支持 `openai-completions` 对话协议和兼容 `/embeddings` 接口。配置 `openai-responses` 或 `anthropic-messages` 会报告不支持，不会把请求改发到其他协议。自定义地址应为 HTTPS 基础地址，本地服务可以使用回环 HTTP；地址不能包含凭据、查询参数或完整的 `/chat/completions`、`/embeddings` 路径。

仅覆盖内置 Provider 地址并指向不同来源时，还需要显式绑定角色的 `api-key-env` 或专用密钥，避免无意中把平台密钥发送给其他地址。为企业网关定义独立 Provider 可以更清晰地表达这一关系。

## 运行与检查

```powershell
# 构建后读取本地配置启动工作台。
node scripts/start.mjs

# 仅进行关键词检索的六个真实诊断案例。
node scripts/evaluate.mjs

# 仅向量混合检索组，需要所选向量路由可用。
node scripts/evaluate.mjs --hybrid
```

两种脚本都支持 `--config=另一份本地.env路径`。默认评测在开始时复制界面配置到独立评测目录，整个评测套件使用这一快照；快照也包含本地密钥，应与 `.local/` 一并保护。评测显式指定 `--config` 时使用该文件而不读取界面覆盖值；`--sources-only` 同样不读取界面密钥。

`/api/status` 返回当前路由、模型、凭据来源标识和配置错误码，不返回密钥，也不进行服务端鉴权探测。`configured: true` 只说明本地配置完整，真实 API 调用仍可能失败。任务 `CONTEXT` 事件与评测报告保存当时的路由和生成参数，便于核对实验条件。

更换向量接口或模型后，旧索引与新配置不再匹配，需要重建索引。新的索引建立之前使用关键词检索；评测要求混合模式时，不会把这种回退记作混合检索成功。
