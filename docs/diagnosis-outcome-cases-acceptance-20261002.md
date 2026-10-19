# 诊断处理结果与评测案例沉淀 V1 开发验收记录 — 2026-10-02至2026-10-03

状态：**PASS**。本开发包最低29项矩阵为 **29 PASS、0 FAIL、0 NOT_RUN**。完整入口原始报告为 `.local/outcome-cases-20261002/package-final-attempt5/public/report.json`。本结果表示本地开发验收完成；独立验收与真实模型质量基线尚未开展。

## 范围与实际行为

范围由 [设计](plans/diagnosis-outcome-cases-design.md) 与 [任务](plans/diagnosis-outcome-cases-tasks.md) 共同定义。终态诊断回填处理结果、管理员整理候选、人工脱敏与冻结发布、组成不可变评测集、新案例实际进入现有隔离执行器、原七维审阅与报告已贯通。

处理结果、受控修复执行状态和模型质量分别记录。模型只接收脱敏问题和原只读工具及材料；实际输入捕获和索引检查证明人工标准、原回答、处理结果及审阅哨兵未进入模型请求或知识索引。两个新身份映射migration，第三例映射credentials；核心实验三例重复两次，六个样本分别持久化并审阅。内置六场景及新三例使用实际MySQL、MinIO、Elasticsearch和本地HTTP来源检查，未调用模型或嵌入。

## OC-01至OC-08

| 任务 | 完成情况 |
| --- | --- |
| OC-01 | 范围、身份与协议冻结；工作区和资源基线保留 |
| OC-02 | V11七张新表及发布引用；真实空库、V10升级、新旧完整恢复通过 |
| OC-03 | 结果、证据、历史、权限、版本冲突和固定请求恢复；O01–O06通过 |
| OC-04 | 候选、脱敏、来源刷新、并发冻结、不可变历史、审计与归档/恢复；C01–C06通过 |
| OC-05 | 新身份与场景分离、格式二受控执行、报告/比较及旧格式兼容；C07–C14、R03通过 |
| OC-06 | 真实浏览器正常链路及响应丢失、切换、冲突、存储失败与窄屏；U01–U04通过 |
| OC-07 | 29项矩阵、Java/Node/浏览器、Windows/Linux、恢复及资源保护完成 |
| OC-08 | 用户文档、API/架构/数据库说明、程序与脚本身份、证据索引、资源台账及独立入口完成 |

## 程序、脚本与工作区身份

冻结程序：`.local/outcome-cases-20261002/delivery-final/supportops.jar`。SHA-256：`9424fe69ddc13fe9e2e078e44e7fcc7d4ef2e5e7ad7a5848fd3c5ea0fb74be31`。JDK21构建；430个类和17个关键静态资源/迁移共447份字节与最终编译产物核对一致。Windows、Linux及升级检查使用同一最终程序。八份受控执行文件：

| 文件 | SHA-256 |
| --- | --- |
| scripts/evaluate.mjs | `aa60062debed09fe2a5e9a630af3bdffa4523cbaa91896dba5c65aa88aad77a6` |
| scripts/center-runner.mjs | `fdd067d224f0b46db68539d1a08fea8d24a1dd19ed4c425c963ad88106bcbbe5` |
| scripts/local-config.mjs | `d1f7493ee36794ef204a117423710ce04a11855c0b0b347d48079ddbcef695d9` |
| evaluation/run.mjs | `d3d74931871787283d3d59a8d085968cf515f85847fbb0887487e389afd5ab61` |
| evaluation/report.mjs | `3e682a61b387a5cea6e21d954768f8d4b10f6d32d4d2c4d56ba40a68c859c75d` |
| evaluation/contracts.mjs | `931ff09d3d1643df4d83f60c730fe2b66365f863c022fd3dd238ea85f1e9a6f4` |
| evaluation/cases.json | `b4c77bc73a8fcb59cb2bf6a0f333eed7371534cef6c561a924ef83e8422c9b5b` |
| evaluation/fixtures/injection-2.0.md | `a82bd751680a43d04bfabccab886f4c6ed4043dc2da7406d2e79c202c30d612d` |

实际旧V10程序只读复用：`.local/reliability-independent-recheck-20261002/boundaries/backup-empty/application.jar`，SHA-256 `d94c36f2a4a06544164b035f1ae2bbd395944b7c921db00c8301294feedf575f`，八份原迁移与当前源码字节一致。新迁移未改写旧迁移。完整程序身份为`.local/outcome-cases-20261002/delivery-final/program.json`；验收入口及关键源码摘要见 [证据索引](diagnosis-outcome-cases-evidence-index-20261002.json)。

开始时main HEAD为`b7a34c3ca953a1df60c92fc7afc05ace40855edb`，已有未提交的可靠性、评测及修复工作。472份基线文件、`git-before.txt`、`source-before.json`已封存；前后增量见`source-delta.json`及`git-after.txt`，基线文件零删除。未切换分支、提交、推送、外部发布或替换日常工作台。

## 29项矩阵

下表证据路径以`.local/outcome-cases-20261002/`为根；每项的原始断言、耗时、身份与证明存于子报告。

| 编号 | 状态 | 必须证明的行为 | 原始证据 |
| --- | --- | --- | --- |
| O01 | PASS | 四状态、六终态、无记录及归档后回填；非终态零写入 | package-final-attempt5/windows/public/report.json 及 private/ |
| O02 | PASS | ADMIN/普通A/普通B/匿名权限及GET无隐式写入 | package-final-attempt5/windows/public/report.json 及 private/ |
| O03 | PASS | 不存在、跨诊断及跨修复证据拒绝；普通响应隔离 | package-final-attempt5/windows/public/report.json 及 private/ |
| O04 | PASS | 故障、UNKNOWN及验证失败不支持已解决；人员观察独立保存 | package-final-attempt5/windows/public/report.json 及 private/ |
| O05 | PASS | 同键并发一次持久化、不同键版本冲突及失败事务回滚 | package-final-attempt5/windows/public/report.json 及 private/ |
| O06 | PASS | 修订、重启、归档及原回答/事件/Token不变 | package-final-attempt5/windows/public/report.json 及 private/ |
| C01 | PASS | 同来源唯一、缺结果拒绝及管理员案例库边界 | package-final-attempt5/windows/public/report.json 及 private/ |
| C02 | PASS | 合理未完成候选可留；未知适配器/场景/路径/参数不可发布 | package-final-attempt5/windows/public/report.json 及 private/ |
| C03 | PASS | 敏感、Authorization、脚本及超限拒绝；安全渲染与导出 | package-final-attempt5/windows/public/report.json 及 private/ |
| C04 | PASS | 草稿与来源版本绑定、并发发布及固定请求重试 | package-final-attempt5/windows/public/report.json 及 private/ |
| C05 | PASS | 历史修订和数据集摘要不变；归档/恢复无物理删除 | package-final-attempt5/windows/public/report.json 及 private/ |
| C06 | PASS | 用途固定、混组/重复条目/31例拒绝 | package-final-attempt5/windows/public/report.json 及 private/ |
| C07 | PASS | 三个独立案例身份、两个场景、排序、七维审阅与报告 | package-final-attempt5/windows/public/report.json 及 private/ |
| C08 | PASS | 内置六例和新三例实际来源检查，每例四MCP工具，零模型/嵌入 | package-final-attempt5/windows/public/report.json 及 private/ |
| C09 | PASS | 三例重复两次，实际本地HTTP模型协议、持久化及报告 | package-final-attempt5/windows/public/report.json 及 private/ |
| C10 | PASS | 实际请求和索引无人工标准、原回答、处理结果及审阅哨兵 | package-final-attempt5/windows/public/report.json 及 private/ |
| C11 | PASS | 修订/标准不兼容明确；协议夹具质量分母零 | package-final-attempt5/windows/public/report.json 及 private/ |
| C12 | PASS | 执行脚本/材料漂移、未知版本和字段在运行前阻断 | package-final-attempt5/windows/public/report.json 及 private/ |
| C13 | PASS | 取消、执行中重启、未知提交停止、证据补录及事件去重 | package-final-attempt5/windows/public/report.json 及 private/ |
| C14 | PASS | 注入后普通场景无材料串例；未暴露不算防护通过 | package-final-attempt5/windows/public/report.json 及 private/ |
| U01 | PASS | 真实浏览器回填、发布、数据集、模型实验、七维审阅及报告闭环 | package-final-attempt5/windows/browser-results/playwright-private.json |
| U02 | PASS | 保存/发布/组成响应丢失及401/404/刷新保留原命令，一次持久化 | package-final-attempt5/windows/browser-results/playwright-private.json |
| U03 | PASS | 双标签页冲突、记录切换、迟到响应及登出换账号隔离 | package-final-attempt5/windows/browser-results/playwright-private.json |
| U04 | PASS | 浏览器存储失败内存恢复、窄屏、键盘、空列表及重开零自动POST | package-final-attempt5/windows/browser-results/playwright-private.json |
| R01 | PASS | 空库与真实V10升级；旧用户/诊断/知识/飞书/评测/修复兼容 | package-final-attempt5/upgrade/public/report.json |
| R02 | PASS | 新完整备份向全新隔离目标恢复，全部数据快照相同 | package-final-attempt5/windows/public/report.json 及 private/ |
| R03 | PASS | 旧格式一数据集/报告/冻结包及旧队列真实存储恢复回归 | package-final-attempt5/windows/public/report.json 及 private/ |
| R04 | PASS | 旧版本完整备份恢复后升级，旧审批失效及UNKNOWN不重放 | package-final-attempt5/upgrade/public/report.json |
| R05 | PASS | 本轮资源回收；原31容器/38卷及日常工作台保留 | original-resources-final.json、package-final-attempt5/public/report.json |

## 回归、失败与修复复验

- Java最终完整`verify-sixth.log`：170项，165通过、0失败、0错误、5项可选ONNX跳过；`java-sixth-reports/`保留每个原XML。旧队列与恢复10项在完整回归及验收入口的独立MySQL上通过。实际OpenAPI覆盖111个GET/POST、14个中文分组、19个新增操作，并核对请求头、DTO引用、枚举和中文说明。
- 最终入口JavaScript为108项全部通过，0失败、0跳过；新增协议、固定命令、容量及停写保护保留行为断言。
- Windows新案例、来源、协议HTTP、取消/中断、旧格式、七维审阅及四项Playwright检查通过。完整备份恢复比较所有业务数据快照；真实旧V10备份先恢复，再升级新迁移并核对旧审批失效及UNKNOWN不重放。
- Linux在既有工具箱镜像`sha256:f215b4cf9e2956c5f0e2c697d57c1209bb9577db0e30a3bfb91ea6fa5b1eb658`中执行同一程序与八份执行文件，新协议、实际来源检查、模型协议HTTP、取消与恢复通过；所有本轮子存储和工具箱卷按完整身份回收。本地Linux结果不表示远程CI运行。
- 原失败均保留：历史计数/中文OpenAPI说明、单测加载夹具、HTTP状态和JSON夹具、浏览器导航/请求等待、Windows临时目录清理、Linux漏复制原资料/pom.xml、恢复返回对象误用，以及Docker/WSL和执行盘不足。对应修复复验见`failure-history.json`、各尝试原报告及 [证据索引](diagnosis-outcome-cases-evidence-index-20261002.json)。未删除断言、忽略异常或禁用必测项消除失败。
- 第四次完整入口在V10升级通过后因瞬时UNKNOWN_DATABASE_WRITER停止后续阶段。实际复查仅剩系统线程和检查自身连接，完整停写证明通过后精确回收该目标；清理入口增加最多15秒只读重查，每次仍取完整证明，持续未知写入者、活动应用和身份不明进程仍拒绝。三个保护测试及最终完整入口通过。重复副本回收曾因原件已存在安全拒绝覆盖，摘要核对后复验通过，历次清单与原错误保留。
- Git默认空白检查将既有混合行尾中的CR判为尾部空白；显式保留尾部空白、末尾空行和缩进检查并识别CRLF后复查通过。两次原输出保留，源码及冻结程序字节未为此改写。
- 5项ONNX因缺真实模型/tokenizer跳过；本轮未改ONNX行为，也未下载权重。所有模型调用限定本地协议夹具，不计真实质量分母。

## 环境故障、保护与资源台账

多套本轮环境驻留时曾出现内存余量约1.6GiB、Docker/WSL失联；经明确授权重启Docker，保留遗留通信端点目录并恢复原三个运行容器。后来执行盘仅约61MiB使模型实验冻结失败；核实无Java进程后按路径、摘要与本轮归属回收71份运行副本，每个程序版本保留完整原件和摘要映射，原日志/备份不删除。

验收入口增加内存、磁盘与运行部署数量检查，串行逐阶段回收；清理失败停止后续阶段。完整功能套件至少8GiB磁盘余量，恢复和主机Linux入口至少2GiB，内存至少4GiB，运行部署最多三套（含原环境及恢复源/目标）。这些措施防止本轮已发现的资源累积，不宣称保证Docker/WSL永久无故障。

最终原31容器、38卷的身份、标签、原端口配置与运行状态核对通过；本轮容器和卷均回收。停止容器的Docker列表端口显示变化曾导致初检FAIL，持久HostConfig配置复核通过，原差异保留。详见 [资源台账](diagnosis-outcome-cases-resources-20261002.json)、`original-resources-final.json`与各清理记录。

私有合成账号、Cookie、配置、原请求、备份、日志及截图留在受限`.local/`，不进入源码交付。正式文档和索引仅保留非秘密身份、状态、摘要和路径。运行时重复JAR副本可依据回收清单中的SHA-256和保留路径恢复；最终交付程序及完整备份原件保留。

## 独立复验入口与开发包边界

入口为`scripts/test-outcome-cases-package.mjs`。使用JDK21、Node22、Docker、已安装依赖和Playwright Chromium、既有Linux工具箱，按 [使用文档](diagnosis-outcomes.md#独立复验入口) 指定冻结程序、真实旧包、固定身份和全新独占目录。入口仅在29项、Linux、相同程序/执行文件以及资源保护全部通过时输出PASS，原失败写入新目录。

本包停在开发交付边界，供另一对话独立验收。真实模型/嵌入服务、真实质量基线、Prompt优化、外部Java目标接入、提交/推送/外部发布均未开展。
