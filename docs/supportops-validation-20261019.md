# SupportOps 验证与交付范围

本记录仅覆盖 SupportOps。评测平台实现、案例、执行器、模型档案及其测试由独立项目维护。完整 Java 回归执行于 2026-10-11；2026-10-19 更新文档和源码交付边界，并复验 JavaScript、文档路径、证据摘要及待提交文件。两次检查分别报告，不将历史结果表述为本轮新执行。

## 单次完整回归：2026-10-11

在新的独立源码根使用 JDK 21 和 Maven Wrapper 执行完整 `verify`，连接随机数据域的隔离 MySQL、MinIO 和 Elasticsearch，使用只包含系统变量与 JAVA_HOME 的环境。未过滤测试或跳过测试，Maven 退出码为 0，构建成功。

| 检查 | 结果 |
| --- | --- |
| Java | 171 项：166 通过，0 失败，0 错误，5 项条件跳过 |
| 原始 Surefire XML | 34 份，逐文件摘要和用例计数一致 |
| JavaScript | 79 项通过，0 失败，0 跳过 |
| JAR SHA-256 | `e23d23feed2fbc0245b22098cf9ba18857ac1600faf2e4f1b63eac839204b264` |

仅以下五项因未设置 `rerank.test.model`、缺少真实 ONNX 文件而跳过：

| 类 | 用例 |
| --- | --- |
| LocalOnnxRerankerTest | relevantChinesePassageScoresAboveUnrelatedContent |
| LocalOnnxRerankerTest | longPairsAreTruncatedAndTwentyCandidatesStayBounded |
| LocalOnnxRerankerTest | cancellationStopsInferenceAndDoesNotPoisonSession |
| LocalOnnxRerankerTest | timeoutDoesNotReturnPartialResults |
| KnowledgeInfrastructureTest | realLocalRerankingWorksAfterHybridAndLexicalRecall |

企业双系统检查中的真实 ONNX 分支同样未执行，不另计为真实精排通过。模型协议使用本地 HTTP 夹具，不能推导真实服务商鉴权、模型效果或生产性能。

此前完整回归有一次旧迁移计数断言失败，新增 V19 后修正计数并定向复验。原失败与定向结果保留；本表以修复后的单次完整运行作为结论，没有拼接多轮通过项。

## 文档与交付检查：2026-10-19

公开范围以 SupportOps 为单位，保留诊断、知识、人工处理结果、来源导出、企业接入、受控修复和运维工具。旧评测 API 与 CLI 保留退出语义，已应用 Flyway 文件和旧表保留。版本化来源 JSON、固定目标程序包及可选外部首页是跨项目边界，不共享业务数据库、账号、模型设置或相邻源码。

源码交付排除独立 AgentEval 项目、前端试作、output、运行配置、缓存、构建产物及两项目联合拆分材料。联合历史记录仍保留在本地；本记录和[证据索引](supportops-validation-evidence-20261019.json)只包含 SupportOps 结果。生产代码、Java 测试、构建配置及既有迁移未因本轮文档更新改变，按实际完整回归根逐字节核对；源码打包脚本变更另行执行 JavaScript 与实际打包检查。

Node 22.16.0 下 79 项 JavaScript 全部通过。初次运行在沙箱临时目录发生两项文件重命名 EPERM，改用本工作区专属临时目录后完整复验通过；未修改代码或断言。首轮失败日志保留。依赖审计满足 high 阈值：0 高危、0 严重，仍有 minio / stream-json 两项中危受影响包记录，未自动降级依赖。

本轮实际交付检查结果：

| 检查 | 结果 |
| --- | --- |
| 公开源码 | 639 份文件，未包含独立项目、试作、运行数据和联合拆分材料 |
| 被测输入等价 | 503 份生产、测试、协议、依赖及构建输入与完整回归根逐字节一致 |
| Flyway | 17 份文件保持原字节，未改写已应用迁移 |
| 文档路径 | 392 个本地链接目标存在于交付范围，0 个失效目标 |
| 原始证据 | 44 份文件的大小与 SHA-256 逐项核对通过 |
| 源码归档 | ZIP 解压后的每份文件与源码清单逐字节一致 |

差异空白检查发现三个既有问题：recovery-checks 中一处空行含空格、V17 迁移及 legacy-outcome 测试夹具末尾各有多余空行。三份文件均与本轮前快照一致，未为清理格式而改写已应用迁移；本轮新增内容没有这类问题。常见密钥格式扫描未发现命中，不构成完整秘密审计。

## 证据与使用限制

[证据索引](supportops-validation-evidence-20261019.json)记录逐文件 SHA-256、大小、完整回归计数、跳过项及本轮检查口径。原日志、34 份 XML、被测源码清单和本轮检查输出保留在本地 `.local/supportops-github-prepare-20261019/evidence/`，不作为公开源代码提交。索引中的路径相对于 `artifactRoot`；它证明所附文件的身份，不表示公开仓库包含原始运行数据。

2026-10-11 完整回归后，测试 Java 已退出，日志没有清理错误；Docker 管理接口不可用，最终容器状态和原数据库集合未能复核，此项仍为环境阻塞，不能宣称已全部恢复原状。没有因此重启共享 Docker/WSL、删除卷或访问业务库。

SP-18、当前工作台更新与业务切换未执行。尚无本次提交对应的远端 CI 结果；旧真实模型、真实 ONNX 和浏览器记录仅适用于各自日期及程序，不能自动作为当前结果。
