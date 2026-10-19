# 本地 Linux 验证工具箱；不是工作台发布镜像，不启动 Elasticsearch。
FROM docker.elastic.co/elasticsearch/elasticsearch:8.19.4
USER 0
RUN apt-get update && apt-get install -y --no-install-recommends \
    openjdk-21-jdk-headless docker.io docker-compose-v2 xz-utils ca-certificates \
    && rm -rf /var/lib/apt/lists/*
COPY node-v22.16.0-linux-x64.tar.xz /tmp/node.tar.xz
RUN mkdir -p /opt/node && tar -xJf /tmp/node.tar.xz -C /opt/node --strip-components=1 \
    && rm /tmp/node.tar.xz
ENV JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
ENV PATH=/opt/node/bin:/usr/lib/jvm/java-21-openjdk-amd64/bin:/usr/bin:/bin
ENV PLAYWRIGHT_BROWSERS_PATH=/opt/playwright-browsers
RUN npx --yes playwright@1.63.0 install --with-deps chromium
WORKDIR /work
ENTRYPOINT ["/bin/bash"]
