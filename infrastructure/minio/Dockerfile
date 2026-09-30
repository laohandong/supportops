# RELEASE.2025-04-22T22-12-26Z 的官方源码；固定提交和归档摘要，避免依赖已撤下的发行镜像。
FROM golang:1.24.2-bookworm AS build
WORKDIR /src
RUN curl --fail --location --retry 3 \
      https://codeload.github.com/minio/minio/tar.gz/0d7408fc9969caf07de6a8c3a84f9fbb10a6739e \
      --output /tmp/minio.tar.gz \
    && echo '7eb30a913fea30f18069abf194e1e78e4983b558cc526911ae1c11396a9859a5  /tmp/minio.tar.gz' | sha256sum --check \
    && tar --extract --gzip --file /tmp/minio.tar.gz --strip-components=1 \
    && CGO_ENABLED=0 GOTOOLCHAIN=local go build -mod=readonly -trimpath \
      -ldflags='-s -w -X github.com/minio/minio/cmd.Version=2025-04-22T22:12:26Z -X github.com/minio/minio/cmd.ReleaseTag=RELEASE.2025-04-22T22-12-26Z -X github.com/minio/minio/cmd.CommitID=0d7408fc9969caf07de6a8c3a84f9fbb10a6739e -X github.com/minio/minio/cmd.ShortCommitID=0d7408fc9969c' \
      -o /out/minio .

FROM busybox:1.37.0
COPY --from=build /out/minio /usr/local/bin/minio
COPY --from=build /etc/ssl/certs/ca-certificates.crt /etc/ssl/certs/ca-certificates.crt
COPY --from=build /src/LICENSE /licenses/minio-LICENSE
EXPOSE 9000
ENTRYPOINT ["/usr/local/bin/minio"]
