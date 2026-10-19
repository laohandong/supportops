package io.supportops.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.supportops.integration.mapper.CredentialMapper;
import io.supportops.integration.service.impl.CredentialServiceImpl;
import io.supportops.persistence.MapperTestDatabase;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 真实隔离MySQL核对密文、AAD和事务失败，秘密均为本测试随机生成。 */
class EnterpriseCredentialTest {
    @TempDir Path directory;
    private final ObjectMapper json = new ObjectMapper();
    /** 加密不能读错归属或用途；部分轮换失败回滚全部行，恢复原数据后可续办。 */
    @Test void encryptedStorageAndAtomicRotation() throws Exception {
        try (MapperTestDatabase database = new MapperTestDatabase()) {
            Path file = directory.resolve("master.json"); String deployment = UUID.randomUUID().toString();
            String first = key(), second = key(), owner = UUID.randomUUID().toString();
            ring(file, deployment, "old", Map.of("old", first));
            CredentialServiceImpl service = new CredentialServiceImpl(database.mapper(CredentialMapper.class), json, file.toString());
            String id = service.store("CONNECTOR", owner, 1, "synthetic-private-connector");
            String other = service.store("MODEL_CHAT", UUID.randomUUID().toString(), 1, "synthetic-private-model");
            assertThat(database.jdbc.queryForObject("SELECT ciphertext FROM credentials WHERE id=?", String.class, id)).doesNotContain("synthetic-private");
            assertThat(service.reveal(id, "CONNECTOR", owner, 1)).isEqualTo("synthetic-private-connector");
            assertThatThrownBy(() -> service.reveal(id, "MODEL_CHAT", owner, 1)).hasMessageContaining("CREDENTIAL_UNAVAILABLE");
            String brokenId = id.compareTo(other) > 0 ? id : other;
            String originalTag = database.jdbc.queryForObject("SELECT tag FROM credentials WHERE id=?", String.class, brokenId);
            database.jdbc.update("UPDATE credentials SET tag=? WHERE id=?", Base64.getEncoder().encodeToString(new byte[16]), brokenId);
            ring(file, deployment, "new", Map.of("old", first, "new", second));
            assertThatThrownBy(() -> database.transactions.execute(status -> service.rotate())).hasMessageContaining("CREDENTIAL_DECRYPTION_FAILED");
            assertThat(database.jdbc.queryForObject("SELECT COUNT(*) FROM credentials WHERE key_id='old'", Integer.class)).isEqualTo(2);
            database.jdbc.update("UPDATE credentials SET tag=? WHERE id=?", originalTag, brokenId);
            Integer rotated = database.transactions.execute(status -> service.rotate());
            assertThat(rotated).isEqualTo(2);
            ring(file, deployment, "new", Map.of("new", second));
            assertThat(service.reveal(id, "CONNECTOR", owner, 1)).isEqualTo("synthetic-private-connector");
            Integer replayed = database.transactions.execute(status -> service.rotate());
            assertThat(replayed).isZero();
            database.jdbc.update("UPDATE credentials SET encryption_origin_deployment_id=? WHERE id=?", UUID.randomUUID().toString(), id);
            assertThatThrownBy(() -> service.reveal(id, "CONNECTOR", owner, 1)).hasMessageContaining("CREDENTIAL_DECRYPTION_FAILED");
            Files.delete(file);
            assertThatThrownBy(() -> service.reveal(id, "CONNECTOR", owner, 1)).hasMessageContaining("MASTER_KEY_UNAVAILABLE");
        }
    }
    /** 写入隔离密钥文件，不将任何密钥输出至报告。 */
    private void ring(Path file, String deployment, String active, Map<String, String> keys) throws Exception {
        Files.writeString(file, json.writeValueAsString(Map.of("deploymentId", deployment, "activeKeyId", active, "keys", keys)));
    }
    /** 生成随机AES256测试密钥。 */
    private String key() { byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes); return Base64.getEncoder().encodeToString(bytes); }
}
