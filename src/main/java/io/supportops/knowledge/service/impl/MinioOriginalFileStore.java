package io.supportops.knowledge.service.impl;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.supportops.knowledge.service.OriginalFileStore;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

/** 使用 MinIO 私有桶保存原始文件，不向客户端暴露存储凭据。 */
@Service
public class MinioOriginalFileStore implements OriginalFileStore {
    private final MinioClient client;
    private final String bucket;
    private volatile boolean ready;

    /** 配置延迟校验，未配置存储不妨碍查看既有记录。 */
    public MinioOriginalFileStore(
            @Value("${supportops.knowledge.storage.endpoint}") String endpoint,
            @Value("${supportops.knowledge.storage.access-key:}") String access,
            @Value("${supportops.knowledge.storage.secret-key:}") String secret,
            @Value("${supportops.knowledge.storage.bucket:supportops}") String bucket) {
        this.bucket = bucket;
        this.client =
                access.isBlank() || secret.isBlank()
                        ? null
                        : MinioClient.builder()
                                .endpoint(endpoint)
                                .credentials(access, secret)
                                .build();
        if (this.client != null) {
            this.client.setTimeout(5000, 60000, 60000);
        }
    }

    /** 初始化私有桶；并发创建由再次检查存在性收敛。 */
    private void prepare() {
        if (client == null) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "MINIO_NOT_CONFIGURED");
        }
        if (ready) {
            return;
        }
        try {
            if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                try {
                    client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                } catch (Exception exception) {
                    if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                        throw exception;
                    }
                }
            }
            ready = true;
        } catch (Exception exception) {
            throw new IllegalStateException("MINIO_UNAVAILABLE");
        }
    }

    /** 保存原件；对象写入成功后才允许记录 STORED 状态。 */
    @Override
    public void put(String key, byte[] content, String mediaType) {
        prepare();
        try (InputStream input = new ByteArrayInputStream(content)) {
            client.putObject(
                    PutObjectArgs.builder().bucket(bucket).object(key).stream(
                                    input, content.length, -1)
                            .contentType(mediaType)
                            .build());
        } catch (Exception exception) {
            throw new IllegalStateException("MINIO_WRITE_FAILED");
        }
    }

    /** 通过应用代理原件读取，不创建永久公开 URL。 */
    @Override
    public InputStream open(String key) {
        prepare();
        try {
            return client.getObject(GetObjectArgs.builder().bucket(bucket).object(key).build());
        } catch (Exception exception) {
            throw new IllegalStateException("ORIGINAL_FILE_UNAVAILABLE");
        }
    }

    /** 删除指定对象，保留失败供后台补偿。 */
    @Override
    public void delete(String key) {
        prepare();
        try {
            client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build());
        } catch (Exception exception) {
            throw new IllegalStateException("MINIO_DELETE_FAILED");
        }
    }
}
