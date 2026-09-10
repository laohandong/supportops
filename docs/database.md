# 数据库维护

## 首次使用与自动初始化

新用户按 [本地部署与启动依赖](deployment.md) 启动即可。应用通过内置 Flyway 自动创建业务表、索引和初始化锁记录；不预置管理员或登录密码，无需手动执行 SQL，也无需额外安装 Flyway 命令行工具或服务。

Flyway 随 Java 应用运行，用于记录已应用的结构版本、校验迁移文件并执行后续增量。当前空库依次执行 B3 全量基线与 V4 对话归档；正常重启不会重复建表。MyBatis-Plus 负责业务数据访问，Flyway 负责表结构，两者职责不同。

| 使用情况 | 处理方式 |
| --- | --- |
| 新用户、空业务库 | 使用默认配置启动，由应用自动初始化 |
| 已由当前 B3 管理的版本 3 库 | 备份并核对连接后启动，自动应用 V4 |
| 已完成 B3 / V4 的库 | 启动时校验历史，仅执行以后新增的迁移 |
| 已完整执行已移除的旧 V1–V3 文件 | 按本文“已有 MySQL 数据库接管”核对结构与历史后接管 |
| 旧 H2 文件 | 使用本文显式离线迁移入口 |

后续结构变更新增高于已发布版本的迁移文件；已应用的 B3、V4 不改写、不合并。本文以下步骤面向已有数据或手动维护场景。

## 手动初始化空业务库

手动导入 B3 后以版本 3 建立基线，再由应用执行 [V4](../src/main/resources/db/migration/V4__archive_conversations.sql)，增加 `runs.archived_at` 和历史列表索引，原记录默认未归档。不要将仅导入 B3 的库标记为版本 4。已有旧 V1–V3 库使用下一节的接管步骤，不重复导入全量脚本。

[`src/main/resources/db/migration/B3__supportops_initial.sql`](../src/main/resources/db/migration/B3__supportops_initial.sql) 是唯一的 MySQL 8.4 业务初始化脚本，以 Flyway 全量基线版本 3 管理，包含 16 张表、158 个字段、索引、外键、角色约束及首次管理员创建所需的固定锁记录。表和字段使用中文 `COMMENT`，可由数据库客户端直接查看。脚本不创建默认管理员，管理员仍通过首次启动页面创建。

数据库与账号权限由部署环境准备；[mysql-init.sql](../infrastructure/mysql-init.sql) 仅负责本地建库和授权，不包含业务表。Excel 物理表按上传的 Sheet 动态创建，不属于固定初始化结构。原 V1–V3 及旧 H2 建表文件已移除，H2 离线工具直接读取已有数据库。

1. 准备空业务数据库，确认连接指向该库。脚本不包含 `DROP TABLE` 或 `IF NOT EXISTS`，遇到同名表会报错；不要对已有业务库或导入失败的半成品库重复执行，也不要让客户端忽略错误继续导入。MySQL DDL 不支持整份脚本的事务回滚。
2. 使用 UTF-8 客户端导入。以下命令在 MySQL 客户端执行，`supportops` 替换为实际空库名称，路径替换为本机仓库绝对路径；Windows 客户端可使用正斜杠路径：

   ```sql
   USE supportops;
   SOURCE /path/to/SupportOps/src/main/resources/db/migration/B3__supportops_initial.sql;
   ```

3. 确认导入完整成功后，首次启动应用时显式设置 Flyway 基线版本为 `3`，让 Flyway 接管这个已经具有 V3 结构的数据库。PowerShell 示例：

   ```powershell
   $env:SPRING_FLYWAY_BASELINE_ON_MIGRATE='true'
   $env:SPRING_FLYWAY_BASELINE_VERSION='3'
   node scripts/start.mjs
   ```

4. 首次启动成功、Flyway 已建立基线后，移除这两个临时环境变量；后续启动保留默认 Flyway 校验和迁移行为。不要在全局配置中长期开启自动基线，也不要对未知结构的非空库设置版本 3 基线。

### 已有 MySQL 数据库接管

全量脚本不会给已有表补注释，也不能直接用于升级未完成旧迁移的数据库。已经完整执行旧 V1–V3 的数据库，其 Flyway 历史仍记录了已删除的迁移文件；直接使用新代码启动会因缺失旧迁移而校验失败。

接管前停止应用并备份，核实业务结构已完整达到版本 3、没有失败迁移或结构漂移。保留原迁移历史表的独立备份，并将其重命名为明确的审计表名；使用相同版本 Flyway 和当前 `db/migration` 路径，显式执行版本 `3` 的 `baseline`，再执行 `migrate`。默认迁移校验保持开启，新历史表应包含版本 3 的 BASELINE 和 V4 的成功记录，原业务表不重建。

不要仅对旧 V1–V3 执行 `repair` 后直接迁移：当旧迁移全部被标记为删除时，Flyway 可能把迁移状态视为空库，并尝试执行 B3 建表，遇到已有表失败。若已出现该情况，应先核对失败语句、现有结构和数据摘要，再保存该迁移历史并按上述基线接管；不能直接反复重跑、关闭校验或清空业务库。

尚未达到版本 3 的旧库需要根据实际差异制定升级 SQL，不能通过直接设定版本号跳过结构变更。当前已包含 V4，后续结构变更使用高于已发布版本 4 的新迁移，不改写 B3 或 V4。

## H2 离线迁移

旧 H2 不会在新版本启动时自动改写。迁移工具只读取明确指定的 H2 文件，并要求 MySQL 目标业务表为空；业务数据复制及文档结构转换在同一目标事务中提交。源文件不存在、目标非空或复制失败均停止，不通过清空数据解决。

1. 停止旧工作台及准备连接目标库的新工作台，备份旧 `.mv.db` 文件和本地模型设置。
2. 创建空 MySQL 目标数据库及权限，准备新的已构建 JAR。不要预先向该目标导入示例资料。
3. 显式设置连接参数并运行迁移。H2 地址不带 `.mv.db` 扩展名，工具追加只读与 IFEXISTS 选项。

```powershell
$env:JAVA_HOME='你的 JDK 21 目录'
$env:SUPPORTOPS_MIGRATION_H2_URL='jdbc:h2:file:E:/backup/supportops'
$env:SUPPORTOPS_DB_URL='jdbc:mysql://127.0.0.1:13306/supportops?useUnicode=true&characterEncoding=UTF-8&serverTimezone=UTC'
$env:SUPPORTOPS_DB_USER='supportops'
$env:SUPPORTOPS_DB_PASSWORD='目标数据库口令'
node scripts/migrate-h2.mjs
```

脚本也读取 `.local/model.env`，非空文件值优先于进程环境，运行前核对其中目标连接。输出各旧表的复制行数。保留原文档/分片 ID、记忆、诊断、事件、自增事件号及已记录的用量；旧向量不当作 ES 可用索引，转换后创建关键词任务，启动新工作台再自动生成 ES 索引与向量。

旧版本没有留存的原件标记 `MISSING`，分片和历史证据仍可核对。页面允许补传 SHA-256 完全一致的原件；不同内容应上传新修订。确认迁移数量、历史问答、分片内容及索引任务状态后，保留源备份再启用新工作台。离线迁移不会自动停止操作者的进程。

迁移验证应核对源备份、行数、ID、历史问答、用量与索引发布状态。相关测试使用独立数据库，见 [验证结果与范围](validation.md)；当前账号与历史归属规则见 [用户系统](users.md)。
