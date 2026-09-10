# 本地部署与启动依赖

SupportOps 的本地部署由三个存储服务和一个 Java 应用组成。根目录 [docker-compose.yml](../docker-compose.yml) 启动 MySQL、MinIO 和 Elasticsearch；应用通过 Maven 构建，再由 Node 脚本启动。Compose 不包含应用镜像。

## 环境要求

| 依赖 | 版本或要求 | 作用 | 默认地址 |
| --- | --- | --- | --- |
| JDK | 21，`JAVA_HOME` 指向该 JDK | 构建与运行后端 | 本机进程 |
| Node.js | 22 或更新版本 | 配置初始化、启动、导入、评测与前端测试 | 本机命令 |
| Docker Engine / Docker Desktop | 后端处于运行状态 | 运行三个存储容器 | 本机 Docker 服务 |
| Docker Compose | 2.20.0 或更新版本 | 解析根目录入口并等待依赖健康 | `docker compose` |
| MySQL | 8.4 | 业务记录、任务、用户、文档分片及 Excel 关系表 | `127.0.0.1:13306` |
| MinIO | `RELEASE.2025-04-22T22-12-26Z` | 保存文档原件，默认私有桶 | `http://127.0.0.1:19000` |
| Elasticsearch | 8.19.4 | 关键词与向量索引 | `http://127.0.0.1:19200` |

Compose 使用 `include` 复用 [infrastructure/compose.yml](../infrastructure/compose.yml)，根目录与原入口使用相同服务定义、卷名和初始化脚本。最低 Compose 版本来自 [Docker 的 include 文档](https://docs.docker.com/reference/compose-file/include/)。仅安装旧版 `docker-compose` 命令不满足此要求。

Maven Wrapper 已包含在仓库中，不要求另外安装 Maven。首次构建和拉取容器镜像需要联网。Docker 需有足够可用内存运行三个服务；ES 堆大小固定为 512 MiB，但容器总内存还包括堆外开销。建议为 Docker 预留至少 4 GiB 可用内存，实际用量随文档和并发变化。

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

默认 Compose 项目名为 `supportops`，卷为 `supportops_mysql-data`、`supportops_minio-data`、`supportops_es-data`。`.local/` 保存模型配置、运行目录和本地报告，不由 Compose 管理；容器重建不会恢复这些文件。备份应同时考虑 MySQL、MinIO 原件和本地配置，ES 索引可重建但不替代原始业务数据。

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
node scripts/evaluate.mjs --sources-only --config=infrastructure/local.env.example
docker compose -p supportops-tests stop
Remove-Item Env:MYSQL_PORT, Env:MINIO_PORT, Env:ES_PORT
```

macOS / Linux：

```bash
MYSQL_PORT=23306 MINIO_PORT=29000 ES_PORT=29200 docker compose -p supportops-tests up -d --wait --wait-timeout 180
sh ./mvnw -s .mvn/settings.xml verify
node --test src/test/js/*.test.mjs
node scripts/evaluate.mjs --sources-only --config=infrastructure/local.env.example
MYSQL_PORT=23306 MINIO_PORT=29000 ES_PORT=29200 docker compose -p supportops-tests stop
```

测试创建随机数据库、桶和索引并清理自身资源。来源评测额外从打包 JAR 启动独立应用、创建一次性管理员、导入资料并等待异步索引，覆盖六个故障环境与版本检索场景；不调用外部模型，不评价诊断准确率。测试不要求普通工作台先启动。更多配置及报告含义见 [知识存储](knowledge.md#隔离测试与评测)和 [评测说明](../evaluation/README.md)。

[GitHub Actions](../.github/workflows/verify.yml) 使用相同 Compose 定义和测试端口，等待三个依赖健康后执行 Maven `verify` 与来源评测；JavaScript 测试独立运行。失败时输出服务日志，上传测试与评测记录，结束时清理本次 CI 环境。CI 不读取本地模型密钥，不下载可选 ONNX 权重；相应真实模型用例需按重排序文档单独启用。

## 适用边界

此 Compose 面向本机开发与演示，端口仅绑定回环地址，使用公开的合成凭据，ES 关闭认证。不应直接改为公网监听投入企业生产环境。应用默认也监听本机；生产部署需要独立凭据、传输加密、备份和企业访问边界，当前仓库不提供生产编排。
