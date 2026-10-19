# 企业独立部署与停写恢复

企业交付采用独立Linux Docker Compose，包含Java21应用、MySQL8.4、MinIO及Elasticsearch8.19.4。应用HTTPS，存储无宿主机公开端口；包内镜像身份和所有公开资产均提供SHA-256。企业包默认关闭模型、向量、飞书、评测与演示目标。

## 打包与安装

开发构建使用JDK21及仓库Maven Wrapper，完成验证后执行 `node scripts/enterprise/package.mjs --jar=<最终JAR绝对路径> --output=<不存在的新目录>`。打包只复制明确列出的公开文件，不收集工作区或`.local`。输出含应用镜像、固定依赖镜像、独立诊断源JAR、客户端、初始化器与手册。发布包内不携带任何运维密码。

在企业Linux主机校验SHA256SUMS，执行 `sh install.sh` 加载离线镜像并核对实际镜像ID。安装不会启动服务。使用Node22（或包内应用镜像的Node）初始化新目录：

```sh
node initialize.mjs --bundle=/srv/supportops-bundle --directory=/srv/supportops-a \
 --origin=https://supportops.example.internal:8443 \
 --tls-p12=/secure/server.p12 --tls-password-file=/secure/server-password \
 --truststore-p12=/secure/truststore.p12 --truststore-password-file=/secure/truststore-password
```

服务器证书必须包含实际HTTPS主机名。信任库只放批准的CA证书；不使用信任所有证书或关闭主机名检查。初始化拒绝已有目录，并生成随机数据库、对象存储、初始化秘密和AES256主密钥，文件仅限所属账号读取。Linux初始化在受控root入口完成，应用运行uid/gid10001；私有文件挂载只读。数据库专用的`mysql-secrets`目录由固定镜像的uid/gid999持有，仅包含数据库初始化和健康检查需要的三项密码，恢复时同步更新，主密钥和TLS密码不挂载给数据库。

初始化默认监听宿主机127.0.0.1。部署者按实际企业网络编辑新目录`.env`的绑定IP，配置防火墙后启动 `docker compose up -d --wait`。企业入口只接受登记的HTTPS Host/Origin。反向代理仅精确登记TCP对端，并必须阻止转发 `/mcp`；转发头不能代替可信连接。内部MCP同时要求真实回环连接和进程随机传输密钥。

首次页面创建管理员需要 `secrets/setup-secret` 的一次性内容，消费与账号创建同事务。创建后不需要再次提交。秘密不可公开记录，主密钥由部署者单独保管，不进入普通备份。管理页面不提供通用解密入口。

## 运行与升级

企业模式持久化领取单实例guard，记录部署、运行、OS身份和实际JAR摘要。Docker里无法凭PID或时间证明旧进程退出，第二实例会拒绝启动。正常停机等待执行资源释放；迟到线程未退出会保留guard，不能自动过期接管。

升级前停止写入和全部专属服务并备份，校验目标程序与镜像后再更换应用版本。Flyway只追加迁移，不能改写已应用SQL或清库。V12至V15扩展资源、快照、策略、列注释及恢复代次；旧记录保留ID、所有者、事件、用量与资料，旧诊断目标明确标记推定。

异常guard恢复由停写维护入口处理。必须先核实持有者对应容器和实际运行身份已退出，记录证明后给出原holder UUID和部署UUID；不靠等待超时清除。维护程序不扫描正常应用，不恢复任务、不启动消费者、不调用模型。

专属Compose使用包内 `ops.mjs`。每次操作指定新的固定请求UUID；结果不确定时保留此UUID和完整参数，同键读取或续办，不换键。恢复入口核对原容器部署标签、镜像、停止状态、容器主机身份及实际JAR摘要，无法证明对应关系时拒绝：

```sh
node ops.mjs --directory=/srv/supportops-a --action=recover-guard \
 --request-id=<固定UUID> --holder-id=<原runtime UUID> --holder-container=<已停止的原容器ID>
```

## 主密钥与旧配置

`master-key.json` 包含部署UUID、activeKeyId和最多16个AES256密钥。轮换时先加入新唯一keyId并设为active，在应用停止后运行 `EnterpriseMaintenance` 的rotate操作。所有凭据重新加密共用事务，AAD保留源部署/用途/资源/修订/凭据ID，失败保留旧密文。校验全部行完成后再移除旧密钥，源文件单独留存。

旧format1模型配置只能显式停写导入。import-model入口读取受限私有源文件，将秘密存为加密引用后原子写format2新文件，保留原明文文件及固定导入日志，不联网鉴权。不能直接把旧文件复制进企业配置或普通备份。

```sh
node ops.mjs --directory=/srv/supportops-a --action=rotate --request-id=<固定UUID>
node ops.mjs --directory=/srv/supportops-a --action=import-model --request-id=<固定UUID> \
 --source-file=/secure/legacy-model-settings.json --target-file=imported-model.json
```

运行前停止应用容器。Linux主机维护入口由受控root账号准备文件所有权；独立Java维护进程以应用uid/gid10001运行，保留全部能力删除，只读取只读秘密挂载。私有维护目录和证明文件归该身份，不能用容器root或增加DAC能力绕过目录权限。维护入口只启动专属存储依赖，结束后应用保持停止。导入目标为应用卷内新的独立JSON文件，不能指定当前 `model-settings.json`；管理员审阅后在该部署配置中明确切换 `supportops.settings.file`。原文件与导入日志保持私有且不进入普通备份；激活后的受保护设置按备份边界保存。

## 专属Compose冷备份与恢复

`node backup.mjs --directory=<部署目录> --output=<不存在的新私有目录>` 先核对部署、镜像、容器、卷及非共享身份，再停止该部署全部专属服务。备份MySQL/MinIO/ES冷卷、受保护模型引用配置和恢复所需的运维凭据。源部署保持停止；失败材料保留，不自动清理或启动。

备份不包含主密钥、setup Secret、TLS私钥或明文导入源。备份本身包含业务资料及运维凭据，必须按企业秘密材料保存；主密钥使用独立保管渠道。此流程限固定版本专属卷，归档最多100000项，不承诺任意企业数据规模。磁盘、路径、特殊文件或摘要验证失败明确停止。

先用初始化器创建不同部署UUID的新目录。恢复器拒绝已有容器、启动过的应用、非空或共享卷；所有摘要、镜像、归档路径和文件类型验证完成后才写入新卷：

```sh
node restore.mjs --directory=/srv/supportops-restored --backup=/secure/backup-a \
 --source-master-key-file=/separate/source-master-key.json
```

源密钥可省略。省略时加密秘密不可恢复，历史事实仍可读取，模型与目标不能错误回退至明文。提供源密钥时恢复器保留源AAD，以目标新密钥重新加密；全部成功后只保留目标新keyId。源密钥和备份不被覆盖。

恢复只递增部署单行代次，不清除历史授权、密钥或运行事实。旧连接批准、应用批准、访问密钥及资料外发批准在新代次无效。模型路由停用，应用仍保持停止；管理员检查差异后重新测试/启用连接、批准应用并签发新密钥、批准资料外发和模型配置。旧Cookie为进程内会话，不随冷卷恢复。

恢复任一步失败保留目标卷、原始日志和固定证明，不能对同目标盲目再次导入。恢复成功以数据库、对象、索引、权限和秘密扫描的差异核对为准。

## 自有存储

上述冷卷脚本拒绝自有数据库、共享卷和现有其他部署。企业使用自有MySQL/MinIO/ES时，由存储负责人按统一停写边界执行数据库、对象和索引备份，再在新隔离目标验证恢复；不能套用专属Compose的已通过结论。真实模型认证、企业网络、业务数据权限、备份规模和恢复耗时需在明确试点范围另验。
