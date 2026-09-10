# 本地模型重排序

文本检索支持在 RRF 初筛后使用本地 ONNX 模型精排。关键词和向量各召回最多 50 条，按片段 ID 去重；启用精排时，以 RRF 保留默认 20 条候选；问题与每条候选正文成对分词，模型计算原始相关性 logits，按分数降序返回最多 5 条，同分按片段 ID 排序。未启用时直接按 RRF 返回最多 5 条。仅关键词模式也可以启用精排，`lexicalOnly=true` 不关闭它。Excel SQL 结果不参与重排。

## 启用与依赖

默认关闭，适用于未安装模型的环境。在 `.local/model.env` 中设置外部文件路径并启用，使用 `node scripts/start.mjs` 启动；Java/Maven 启动须在进程环境中设置相同变量：

```dotenv
SUPPORTOPS_RERANK_ENABLED=true
SUPPORTOPS_RERANK_MODEL_PATH=.local/models/reranker/model_quantized.onnx
SUPPORTOPS_RERANK_TOKENIZER_PATH=.local/models/reranker/tokenizer.json
SUPPORTOPS_RERANK_CANDIDATES=20
SUPPORTOPS_RERANK_MAX_INPUT_TOKENS=512
SUPPORTOPS_RERANK_THREADS=2
SUPPORTOPS_RERANK_TIMEOUT_MS=30000
```

模型及 tokenizer 不打包进 JAR。适配器使用 ONNX Runtime CPU 1.17.1 和 DJL Hugging Face tokenizers 0.33.0，支持 `input_ids`、`attention_mask` 两个 INT64 二维输入，以及 FLOAT `[batch, 1]` 的 `logits` 输出；不支持以任意嵌入模型替代重排序模型。分词文件必须与权重配套。模型和 tokenizer 首次使用时加载，运行期间复用，更换文件或配置后需要重启。

默认问题与正文合计最多 512 个 token，使用 tokenizer 的成对特殊 token 模板及最长序列优先截断。长片段尾部可能不参与评分，但返回引用仍保留完整原文；增大输入预算需重新评估延迟与内存。候选数量允许 5–100，token 上限允许 32–8192，CPU 线程数允许 1–16；实际模型的上下文能力仍需核对，配置上限不保证模型支持。当前不设置相关性阈值，分数可为负，不能解释为正确率或诊断置信度。

## 资源和错误边界

同一实例串行执行重排序以约束内存。默认 30 秒预算包含等待、首次加载和全部候选；原生推理通过监控调用线程中断及截止时间触发终止。模型初始化不是可抢占操作，加载超出预算时在加载返回后报告超时，不提交结果；取消后的诊断资源释放语义保持不变。分词原生库首次使用会由 DJL 解压到其缓存目录，可在启动进程设置 `DJL_CACHE_DIR` 指定可写位置。

启用后缺少文件返回 `RERANK_MODEL_MISSING`，文件或签名不兼容返回 `RERANK_MODEL_INVALID`，超时与取消分别为 `RERANK_TIMED_OUT`、`RERANK_CANCELLED`，推理及输出异常为 `RERANK_INFERENCE_FAILED`、`RERANK_OUTPUT_INVALID`。HTTP 检索入口返回 503；Agent 工具调用记录失败，不能把失败视为取得资料。不会在失败时静默改用 RRF。无候选时返回 `EMPTY` 并跳过模型。

## 响应与历史兼容

响应中的 `mode` 继续表示 `LEXICAL` / `HYBRID` 召回方式；新增 `ranking` 保存排序方式 `RRF` / `ONNX` / `EMPTY`、模型及分词文件 SHA-256、候选数、输入预算和耗时。原 `passages[].score` 保留 RRF 含义，新增可空 `rerankScore` 为模型原始 logits。旧事件没有这些新字段，仍按原语义读取。精排后再次核对发布指针，删除或被新版替换的片段不返回，不改写历史证据。

## 验证

先按 [隔离部署步骤](deployment.md#隔离测试与评测) 启动真实测试存储。本地真实模型验证需显式提供测试文件；常规构建不下载权重，未提供时会明确跳过真实模型用例：

```powershell
.\mvnw.cmd -s .mvn/settings.xml `
  '-Dtest=KnowledgeRerankingTest,LocalOnnxRerankerTest,KnowledgeInfrastructureTest' `
  '-Drerank.test.model=.local/models/reranker/model_quantized.onnx' `
  '-Drerank.test.tokenizer=.local/models/reranker/tokenizer.json' test
```

`LocalOnnxRerankerTest` 验证中文正反例、20 条长文本、取消、超时及取消后复用；`KnowledgeInfrastructureTest` 的精排用例使用真实 MySQL、ES、MinIO 和本地重排序模型，但嵌入服务仍是固定 HTTP 协议夹具。少量正反例及链路测试不等于通用排序质量评测。
