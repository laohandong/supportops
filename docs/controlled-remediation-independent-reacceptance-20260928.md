# 受控修复中心 V1 独立复验通过记录

验收日期：2026-09-28。依据 [V1 设计](plans/controlled-remediation-design.md)、[任务与验收清单](plans/controlled-remediation-tasks.md)，独立复验 [上轮发现的三个问题](controlled-remediation-independent-recheck-20260927.md)，并重新运行完整自动化回归、真实隔离 MySQL、回环 HTTP 和受影响浏览器流程。

**结论：本次独立复验通过，CR-F01、CR-F02、CR-F03 均关闭。** 当前范围是内置合成 OrderBridge 的固定路径迁移、单管理员显式批准、人员执行及原请求验证。没有发现阻止该 V1 范围验收的新问题；这不构成真实企业订单系统或生产修复授权。此前失败记录保留，不覆盖或改写。

## 三项问题的复验结果

| 问题 | 本次独立证据 | 结论 |
| --- | --- | --- |
| CR-F01 输入拒绝后的永久 pending | 先运行原始旧包，在真实页面输入三个空格产生原请求；SQL 确认无请求行。升级同一数据库后，普通 404 保留原编号；显式原样重发取得持久化 `400 / INVALID_INPUT`，页面解除阻塞。重发结果稳定，原编号改正文返回 409。确认新静态资源后，空白名称和理由均在冻结前提示，数据库请求数保持 3、提案数保持 0。 | 通过 |
| CR-F02 正文停滞突破执行预算 | 对当前编译代码复跑上轮同一个 `TimeoutProbe.java`：下游发送 200 响应头及一个正文字符后暂停。此次 3,015 ms 退出，应用为 APPLIED，验证为 UNKNOWN；不再出现超过 12 秒仍在执行并随后 PASSED 的行为。真实 MySQL/HTTP 回归另验证迟到成功不能覆盖 UNKNOWN、只发送一次业务 POST、超时后共享准入释放及 64 KiB 正文限制。 | 通过 |
| CR-F03 场景重置无持久化审计 | 浏览器完成 migration→healthy、healthy→healthy、healthy→migration 三次操作，修订 2→3→4→5；独立 SQL 与接口确认三条审计具备操作者、固定目标、原实例、前后场景、修订、时间及事实。冷重启后六张修复/审计表、报告及三条场景历史逐字段一致，新实例无新增执行。 | 通过 |

历史卡住请求为 `89f3ef36-bd28-411f-8ebc-efa1d63cb41a`，重发后的持久化结果为 CREATE、BUILTIN_ORDERBRIDGE、400、INVALID_INPUT，未创建提案或执行。没有清空浏览器恢复记录，也没有替换原请求编号。

本次浏览器观察到：升级后首次重新登录可能导航回缓存的旧首页；该页面发出的无效输入已经可通过新服务端的持久化拒绝恢复。对已登录页面再做一次普通刷新后，确认入口资源为 `app.js?v=20260927-remediation-fixes`，才计入新版前端预检查的证据。两次旧缓存页面提交形成额外两条拒绝记录，不能算作前端零请求检查；新版本预检查前后请求表均为三条。

## 回归与正常闭环

| 检查 | 本轮重新执行的结果 |
| --- | --- |
| JDK 21、Maven Wrapper 完整 verify | 149 项：144 通过、0 失败、0 错误、5 项既有可选 ONNX 跳过；BUILD SUCCESS，9 分 37 秒，11:09:24 完成 |
| JavaScript | 82/82 通过，0 跳过 |
| 修复持久化 | 20/20，通过审批绑定、并发、原请求重放、事务故障、真实子 JVM 中断、正文超时和迟到结果检查 |
| 场景审计持久化 | 3/3，通过同名重置、跨环境实例读取、审计写入故障及权限/非法场景检查 |
| 修复增量迁移 | 1/1，通过 V8→V9 与带五类历史修复记录的 V9→V10；旧账号、诊断、评测及审阅记录保持 |
| HTTP 集成 | 29/29，通过权限、字段拒绝回执、旧入口、场景审计、诊断/评测与 OpenAPI 等回归 |
| 浏览器正常闭环 | 创建→显式批准→执行→结果通过：APPLIED / PASSED / SUCCEEDED，HTTP 200、accepted=true |
| 运行包接口文档 | 91 个业务操作仅使用 GET/POST；新增场景审计 GET 与 15 个响应字段存在 |
| 包一致性 | 运行包、最终构建包 SHA256 相同；416 个应用 class/资源文件与本轮测试产物逐字节一致，三个前端入口资源与源码一致 |
| 旧迁移保护 | 旧包内的七个 SQL 迁移与最终包逐字节相同；V10 为新增迁移 |

本轮成功提案 `68491e06-d8ff-4b47-b20a-19cd81b8fc50`，原操作 `00193d6d-9418-4802-bed7-b0474b91db92`，验证请求 `88b895d3-5b2d-42a3-a535-e158cd9a6036`。配置应用将环境修订 1 增至 2，执行与四条提案审计已保存。三次后续场景操作不改变该历史执行的原始证据。

冷重启前实例为 `2e543aa7-92fc-47fa-9adc-63e20555397e`，重启后为 `806df483-891d-4327-9ec3-cfcd43882eca`。新实例回到默认迁移场景及修订 1；页面展示的三条场景历史仍绑定原实例，未将历史成功当作当前现场状态。

## 独立环境与证据

- 页面：`http://127.0.0.1:19196/#remediation`，专用测试账号 `cr_reacceptance`。
- 隔离 MySQL：`so_eval_0aaf332e65e1ae25f067f223`；Excel 数据域：`so_xl_0aaf332e65e1ae25f067f223`；存储与索引前缀：`so-eval-0aaf332e65e1ae25f067f223`。
- 存储依赖：已核实的 SupportOps 隔离 MySQL 23306、MinIO 29000、Elasticsearch 29200。本轮 Docker 启动遭遇损坏的临时套接字，授权后备份相应临时目录并恢复，仅启动这三个既有测试容器，未删除或重建数据卷。
- 最终进程 PID 10860，启动器 PID 55128，运行副本由 `scripts/start.mjs` 创建；精确路径与启动身份见本地 `runtime-final.json`。
- 运行包及最终包 SHA256：`25a893b84ffe8deb8834e6c2ee7abd4c053ff73afa62db6a983cd6c5e6783f31`。
- 对话配置无密钥、向量禁用；评测执行器、飞书与知识后台任务关闭，本轮没有调用真实模型服务。

原始证据保存在忽略目录 `.local/controlled-remediation-reacceptance-20260928/`：

- `verify.log`、`javascript.log`、`compile.log`：本轮构建与测试日志。
- `TimeoutProbe.java`、`timeout-probe.log`：沿用上轮失败探针及此次结果。
- `old-pending.png`、`upgraded-pending-preserved.png`、`old-pending-recovered.png`、`old-sql.txt`：历史原请求产生、保留及恢复证据。
- `fixed-blank-name.png`、`fixed-blank-reason.png`、`before-preflight-sql.txt`、`after-preflight-sql.txt`：确认新资源版本后的前端校验与零新增业务记录。
- `browser-success.png`、`audit-before-restart.png`、`audit-after-restart.png`：正常闭环和场景审计页面。
- `http-before.json`、`http-after.json`：接口结果及六张表的独立 SQL 快照；脚本逐字段比较通过。
- `artifact-check.json`、`openapi-check.json`、`runtime-final.json`：最终运行产物与接口核对。
- `docker-recovery.json`、`docker-recovery-second.json`：临时通信文件备份位置；未改动数据卷。

## 验收边界

本轮未修改业务代码、迁移或已有测试；新增独立复验证据与本记录，并更新文档导航。已有评测中心及其他未提交改动保留，未提交、推送或更新日常工作台。

五项 ONNX 检查需要另行配置真实模型和 tokenizer 文件，本轮未执行。本轮浏览器重点复跑三项补修及正常闭环，没有把历史窄屏、账号切换、响应丢失等截图写成本轮重拍；相关自动化回归已重新执行，既有人工证据仍见 [补修验收记录](controlled-remediation-fixes-acceptance-20260927.md)。真实企业连接器、双人复核、自动回滚、多节点执行和真实模型效果不属于本次验收结论。
