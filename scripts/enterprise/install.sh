#!/bin/sh
set -eu
cd "$(dirname "$0")"
sha256sum -c SHA256SUMS >/dev/null
docker load -i images.tar
sh ./verify-images.sh
# 只加载固定包内镜像。初始化与启动为两个明确步骤；此入口不自动运行服务。
printf '%s\n' '包校验及离线镜像加载完成。按 enterprise-deployment.md 初始化新目录，再使用 --pull never 启动。'
