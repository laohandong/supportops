# 本地 Linux 验证工具箱

此 Dockerfile 用于在 Docker Desktop 的 Linux 内核上运行 JDK 21、Node 22.16.0 和 Chromium 回归；它不启动 Elasticsearch，也不是工作台发布镜像。通常使用 [Verify 工作流](../../.github/workflows/verify.yml) 的 Ubuntu 主机命令即可，无需创建这个工具箱。

本地构建时，将 Dockerfile 和经官方 SHA256 校验的 `node-v22.16.0-linux-x64.tar.xz` 放在同一个独立构建目录，例如忽略目录 `.cache/reliability-tools/linux-build/`。Node 归档来自官方发行目录，不纳入源码。基础镜像是仓库使用的 Elasticsearch 8.19.4 所带 Ubuntu，安装原生 JDK 21、Docker CLI 与固定版本 Playwright 配套 Chromium。

```text
docker build -t supportops-reliability-linux:local .cache/reliability-tools/linux-build
```

运行器必须登记独立容器/工作卷 UUID 与 daemon 身份，仓库挂载只读，Linux 工作副本放在其专属卷内。需要操作 Docker 存储时，只连接已核对的本机 daemon；所有测试资源仍由 `isolate.mjs` 的独立清单管理，不能使用工具箱扫描或回收其他项目。退出前复制白名单报告、私有日志和所需备份，核对自有应用已停止后才回收运行器及工作卷。

实际版本、测试数量及使用同一最终运行 JAR 的结果见 [2026-09-30 交付记录](../../docs/reliability-delivery-acceptance-20260930.md)。本地 Linux 实跑与远端 Actions 的运行状态分别记录。
