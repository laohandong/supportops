# 本地部署与启动依赖

## 外部评测集成边界

SupportOps 提供版本化脱敏来源和固定目标程序包，不依赖相邻评测源码、评测执行器或评测数据库。可用 `SUPPORTOPS_EVALUATION_PLATFORM_URL` 配置外部平台首页；双方不共享 Cookie、模型档案与运行目录，详见 [评测集成](evaluation-split.md)。

## 环境要求

| 依赖 | 版本或要求 | 作用 | 默认地址 |
| --- | --- | --- | --- |
| JDK | 21，`JAVA_HOME` 指向该 JDK | 构建与运行后端 | 本机进程 |
| Node.js | 使用 22.x；运行入口最低 22，CI 固定验证 22.16.0 | 配置初始化、启动、导入与自动化验证 | 本机命令 |
| Docker Engine / Docker Desktop | 后端处于运行状态 | 运行三个存储容器 | 本机 Docker 服务 |
| Docker Compose | 2.20.0 或更新版本 | 解析根目录入口并等待依赖健康 | `docker compose` |
| MySQL | 8.4 | 业务记录、任务、用户、文档分片及 Excel 关系表 | `127.0.0.1:13306` |
| MinIO | `RELEASE.2025-04-22T22-12-26Z` | 保存文档原件，默认私有桶 | `http://127.0.0.1:19000` |
| Elasticsearch | 8.19.4 | 关键词与向量索引 | `http://127.0.0.1:19200` |

Compose 使用 `include` 复用 [infrastructure/compose.yml](../infrastructure/compose.yml)，根目录与原入口使用相同服务定义、卷名和初始化脚本。最低 Compose 版本来自 [Docker 的 include 文档](https://docs.docker.com/reference/compose-file/include/)。仅安装旧版 `docker-compose` 命令不满足此要求。

Maven Wrapper 已包含在仓库中，不要求另外安装 Maven。首次构建和拉取容器镜像需要联网。Docker 需有足够可用内存运行三个服务；ES 堆大小固定为 512 MiB，但容器总内存还包括堆外开销。建议为 Docker 预留至少 4 GiB 可用内存，实际用量随文档和并发变化。

MinIO 的旧发行镜像和二进制下载已不可公开获取，因此 Compose 使用 [Dockerfile](../infrastructure/minio/Dockerfile) 构建同一发行版的[官方源码](https://github.com/minio/minio/tree/0d7408fc9969caf07de6a8c3a84f9fbb10a6739e)。构建固定提交与源码归档 SHA-256，保留 AGPL 许可证；首次启动需下载 Go 构建镜像、源码和模块，耗时会增加，后续复用 Docker 构建缓存。宿主无需安装 Go。MinIO 健康检查使用 `/minio/health/ready`，既有端口、合成账号和数据卷保持兼容。

**模型服务按功能选配：** 启动、账号初始化、文档上传与关键词检索不需要 API Key；Agent 诊断与自然语言生成 SQL 需要对话模型，管理员直接提交受限 SQL 不需要模型，向量索引需要嵌入服务。首次配置模板关闭向量与本地重排序；启用 ONNX 精排还需单独准备模型与 tokenizer，见 [本地重排序](reranking.md)。

## 首次启动

以下命令均在仓库根目录执行。先启动 Docker Desktop 或 Docker Engine，确认 `docker version` 同时返回客户端和服务端信息，`docker compose version` 满足最低版本。

1. 启动依赖并等待健康检查：

   ```shell
   docker compose up -d --wait --wait-timeout 180
   docker compose ps
   ```

   三项服务均应显示 `healthy`。检查分别覆盖 MySQL 业务库可查询、MinIO 服务就绪、ES 集群达到 yellow 或 green。命令超时或返回非零时，先排查依赖，再启动应用。

2. 生成匹配 Compose 的本地配置：

   ```shell
   node scripts/setup-local.mjs
   ```

   脚本从 [local.env.example](../infrastructure/local.env.example) 创建 `.local/model.env`，不覆盖已有文件。模板包含公开的合成本地账号，无真实密钥。已有配置的用户应自行核对地址、库名、账号和端口；脚本不会修改 `.local/model-settings.json` 中的界面配置。

3. 设置 `JAVA_HOME` 指向本机 JDK 21，构建并启动应用。

   Windows PowerShell：

   ```powershell
   .\mvnw.cmd -s .mvn/settings.xml -DskipTests package
   node scripts/start.mjs
   ```

   macOS / Linux：

   ```bash
   sh ./mvnw -s .mvn/settings.xml -DskipTests package
   node scripts/start.mjs
   ```

   快速启动跳过测试；完整验证使用下文的独立测试环境。启动脚本检查 Java 版本、复制 JAR 到本次运行目录，并读取 `.local/model.env`。直接用 Java 或 Maven 启动时需自行设置进程环境变量。

4. 打开 [工作台](http://127.0.0.1:18080)，创建首个管理员。没有默认管理员或默认登录密码。进入“知识文档”导入示例，等待关键词处理成功即可验证上传与检索；配置对话模型后再运行诊断。

MySQL 容器首次初始化空卷时创建 `supportops`、`supportops_excel` 和写入、只读两种账号；应用首次连接空业务库时自动创建业务表、索引和必要的初始化记录，无需手动导入 SQL 或另装 Flyway。已有数据的升级、手动初始化和历史导入见 [数据库维护](database.md)。

## 停止、重启与数据位置

应用终端按 Ctrl+C 停止 Java 子进程。依赖使用以下命令，均保留数据卷：

```shell
docker compose stop
docker compose up -d --wait --wait-timeout 180
```

默认 Compose 项目名为 `supportops`，卷为 `supportops_mysql-data`、`supportops_minio-data`、`supportops_es-data`。`.local/` 保存模型配置、运行目录和本地报告，不由 Compose 管理；容器重建不会恢复这些文件。完整备份应配套保存主库、Excel 动态库、MinIO 原件、ES 索引与本地配置。已登记的独占可靠性部署支持停止应用写入后导出官方 ES 快照，并恢复至全新目标，见 [可靠性运行与恢复](reliability-delivery.md)；日常默认部署不自动获得该工具的资源归属授权。

`docker compose down` 删除容器与网络、保留命名卷；`down --volumes` 会删除卷及其中数据，日常停止不要使用。不要随意改变项目名，否则 Compose 会连接另一组卷，看起来像全新数据库。

## 端口调整与常见故障

| 情况 | 处理方式 |
| --- | --- |
| Docker 管道不存在、无法连接 daemon | 先确认 Docker 后端已启动；容器配置无法修复宿主 Docker 故障 |
| `include` 无法解析或 `--wait` 不识别 | 升级 Docker Compose 并核对实际执行版本 |
| 端口已占用 | 检查 `docker ps` 和本机监听；设置 `MYSQL_PORT`、`MINIO_PORT`、`ES_PORT` 后，同时修改应用连接配置 |
| 依赖未健康 | 使用 `docker compose ps -a` 和 `docker compose logs --tail 100 mysql minio elasticsearch` 查看实际错误 |
| MySQL 登录或 Flyway 失败 | 核对 `.local/model.env`、历史卷的账号及迁移状态；初始化 SQL 只在首次空卷生效 |
| 没有构建 JAR / Java 版本过低 | 先用 JDK 21 完成 `package`，再运行 Node 启动脚本 |
| 配置向量或重排序后检索报错 | 按对应功能检查端点、凭据或模型文件；这些运行错误不会被隐藏为关键词成功 |

例如在 PowerShell 设置 `$env:MYSQL_PORT='13307'`，再执行 Compose 启动时，还必须将 `.local/model.env` 的 `SUPPORTOPS_DB_URL` 端口改为 `13307`。Compose 不读取 `.local/model.env`；应用也不把 `MYSQL_PORT` 自动转换为 JDBC 地址。

## 隔离测试与评测

测试使用真实存储服务，默认端口为 MySQL `23306`、MinIO `29000`、ES `29200`。另建 Compose 项目和数据卷，不复用日常工作台。

Windows PowerShell：

```powershell
$env:MYSQL_PORT='23306'
$env:MINIO_PORT='29000'
$env:ES_PORT='29200'
docker compose -p supportops-tests up -d --wait --wait-timeout 180
.\mvnw.cmd -s .mvn/settings.xml verify
node --test src/test/js/*.test.mjs
docker compose -p supportops-tests stop
Remove-Item Env:MYSQL_PORT, Env:MINIO_PORT, Env:ES_PORT
```

macOS / Linux：

```bash
MYSQL_PORT=23306 MINIO_PORT=29000 ES_PORT=29200 docker compose -p supportops-tests up -d --wait --wait-timeout 180
sh ./mvnw -s .mvn/settings.xml verify
node --test src/test/js/*.test.mjs
MYSQL_PORT=23306 MINIO_PORT=29000 ES_PORT=29200 docker compose -p supportops-tests stop
```

测试创建随机数据库、桶和索引并清理自身资源。六场景来源评测在AgentEval通过固定程序包完成，不要求当前工作台在线。

[GitHub Actions](../.github/workflows/verify.yml) 使用同一 Compose 基础和专属资源清单，等待依赖健康后完整执行 Maven 验证与打包、固定 SupportOps 目标程序包、运行身份、真实 Chromium 回归、全新目标恢复与容量、健康和端口边界；JavaScript 测试独立运行。程序包产物包含 JAR、目标清单和来源快照；脱敏运行证据另行归档 JSON 与合成截图，不上传应用日志、原始配置、数据库或运行登记。清理只作用于该清单的资源。2026-10-19 合并主线的实际通过结果见 [GitHub 验证记录](github-validation-20261019.md)。CI 不读取本地模型密钥，不下载可选 ONNX 权重；相应真实模型用例需按重排序文档单独启用。精确命令和安全边界见 [可靠性运行与恢复](reliability-delivery.md)。

## 适用边界

此 Compose 面向本机开发与演示，端口仅绑定回环地址，使用公开的合成凭据，ES 关闭认证。企业独立部署使用 [企业部署入口](enterprise-deployment.md)，配置 HTTPS、独立秘密、登记的访问来源、项目与环境授权及停写备份，不能直接把本地示例改为公网监听。当前交付面向单企业、单实例；共享 SaaS 多租户、高可用集群与多实例灾备不在已验证范围。

## 受控修复部署

修复功能本身不需要模型密钥或新增外部服务。按正常流程备份并应用V9及V10；已有V9只增量应用V10，不改写历史迁移。应用启动产生新的内置目标实例身份，旧未执行提案失效，确认退出的旧RUNNING执行转UNKNOWN，不自动续写。内存原回执不会跨进程保存，缺证据须人工核查；场景审计来自持久化历史，读取不会重做操作。受控修复隔离验收必须使用专用数据库、端口和运行目录；不能据此替换日常工作台。见 [受控修复中心](controlled-remediation.md)。
