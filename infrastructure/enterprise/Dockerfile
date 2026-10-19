FROM supportops-reliability-linux:20260929 AS node-runtime
FROM eclipse-temurin:21.0.8_9-jre-jammy@sha256:db1689535962d757a5adabf57387584ed543d38c0b9d1fe870123ea362ad73b0
COPY --from=node-runtime /opt/node/bin/node /usr/local/bin/node
RUN groupadd --gid 10001 supportops && useradd --uid 10001 --gid 10001 --no-create-home supportops \
    && mkdir -p /opt/supportops /var/lib/supportops && chown 10001:10001 /var/lib/supportops
COPY application.jar /opt/supportops/application.jar
COPY initialize.mjs /opt/supportops/initialize.mjs
COPY enterprise.yml /opt/supportops/enterprise.yml
USER 10001:10001
WORKDIR /var/lib/supportops
EXPOSE 8443
ENTRYPOINT ["java","-jar","/opt/supportops/application.jar","--spring.config.additional-location=file:/opt/supportops/enterprise.yml"]
