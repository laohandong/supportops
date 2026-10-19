package io.supportops.integration.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.supportops.integration.entity.CredentialEntity;
import io.supportops.integration.mapper.CredentialMapper;
import io.supportops.integration.service.CredentialService;
import io.supportops.integration.service.support.IntegrationInputs;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 使用外部只读主密钥和逐条 AAD 加密；主密钥不写入数据库。 */
@Service
public class CredentialServiceImpl implements CredentialService {
    private static final Set<String> PURPOSES = Set.of("CONNECTOR", "MODEL_CHAT", "MODEL_EMBEDDING", "FEISHU");
    private final CredentialMapper credentials;
    private final ObjectMapper json;
    private final String keyFile;
    @Value("${supportops.enterprise.deployment-id:}")
    private String expectedDeploymentId;
    private final SecureRandom random = new SecureRandom();
    /** 主密钥文件结构；只在内部反序列化，禁止作为接口模型。 */
    private record KeyRing(String deploymentId, String activeKeyId, Map<String, String> keys) {}
    /** 注入存储及外部 Secret 文件位置；启动不因未使用的秘密缺失而失败。 */
    public CredentialServiceImpl(CredentialMapper credentials, ObjectMapper json,
            @Value("${supportops.enterprise.master-key-file:}") String keyFile) {
        this.credentials = credentials;
        this.json = json;
        this.keyFile = keyFile;
    }
    /** 每次保存使用新的随机 nonce，并绑定不可变资源身份。 */
    @Override
    @Transactional
    public String store(String purpose, String ownerId, int revision, String secret) {
        if (!PURPOSES.contains(purpose) || revision < 1 || secret == null || secret.isBlank() || secret.length() > 8192
                || secret.indexOf('\r') >= 0 || secret.indexOf('\n') >= 0) {
            throw IntegrationInputs.error(400, "INVALID_CREDENTIAL");
        }
        KeyRing ring = ring();
        CredentialEntity value = new CredentialEntity();
        value.setId(UUID.randomUUID().toString());
        value.setPurpose(purpose);
        value.setOwnerId(IntegrationInputs.uuid(ownerId));
        value.setRevision(revision);
        value.setEncryptionOriginDeploymentId(IntegrationInputs.uuid(ring.deploymentId()));
        value.setStatus("ACTIVE");
        value.setCreatedAt(Instant.now().toString());
        encrypt(value, secret, ring);
        credentials.insert(value);
        return value.getId();
    }
    /** 调用方必须提供预计用途与归属，防止跨功能秘密混用。 */
    @Override
    public String reveal(String credentialId, String purpose, String ownerId, int revision) {
        CredentialEntity value = credentials.selectById(IntegrationInputs.uuid(credentialId));
        if (value == null || !"ACTIVE".equals(value.getStatus()) || !purpose.equals(value.getPurpose())
                || !ownerId.equals(value.getOwnerId()) || revision != value.getRevision()) {
            throw IntegrationInputs.error(503, "CREDENTIAL_UNAVAILABLE");
        }
        return decrypt(value, ring());
    }
    /** 撤销只改变可用状态，保留密文以支持历史审计。 */
    @Override
    @Transactional
    public void revoke(String credentialId) {
        credentials.update(Wrappers.<CredentialEntity>lambdaUpdate().eq(CredentialEntity::getId, IntegrationInputs.uuid(credentialId))
                .eq(CredentialEntity::getStatus, "ACTIVE").set(CredentialEntity::getStatus, "REVOKED"));
    }
    /** 整批事务回滚保护旧密文，重复执行跳过已使用当前密钥的行。 */
    @Override
    @Transactional
    public int rotate() {
        KeyRing ring = ring();
        int count = 0;
        for (CredentialEntity value : credentials.selectList(Wrappers.<CredentialEntity>lambdaQuery()
                .ne(CredentialEntity::getKeyId, ring.activeKeyId()).orderByAsc(CredentialEntity::getId))) {
            String previousKey = value.getKeyId();
            String previousCiphertext = value.getCiphertext();
            String secret = decrypt(value, ring);
            encrypt(value, secret, ring);
            int changed = credentials.update(Wrappers.<CredentialEntity>lambdaUpdate().eq(CredentialEntity::getId, value.getId())
                    .eq(CredentialEntity::getKeyId, previousKey).eq(CredentialEntity::getCiphertext, previousCiphertext)
                    .set(CredentialEntity::getKeyId, value.getKeyId()).set(CredentialEntity::getNonce, value.getNonce())
                    .set(CredentialEntity::getCiphertext, value.getCiphertext()).set(CredentialEntity::getTag, value.getTag()));
            if (changed != 1) {
                throw IntegrationInputs.error(409, "CREDENTIAL_VERSION_CONFLICT");
            }
            count++;
        }
        return count;
    }
    /** 有界读取本机 Secret；不存在或结构损坏时禁止降级到明文。 */
    private KeyRing ring() {
        try {
            Path path = Path.of(keyFile);
            if (keyFile.isBlank() || !Files.isRegularFile(path) || Files.size(path) > 16384) {
                throw new IllegalArgumentException();
            }
            KeyRing ring = json.readValue(Files.readString(path), KeyRing.class);
            IntegrationInputs.uuid(ring.deploymentId());
            if (expectedDeploymentId != null && !expectedDeploymentId.isBlank()
                    && !expectedDeploymentId.equals(ring.deploymentId())) { throw new IllegalArgumentException(); }
            if (ring.activeKeyId() == null || !ring.activeKeyId().matches("[a-zA-Z0-9_-]{1,64}")
                    || ring.keys() == null || ring.keys().size() > 16) {
                throw new IllegalArgumentException();
            }
            key(ring, ring.activeKeyId());
            return ring;
        } catch (Exception exception) {
            throw IntegrationInputs.error(503, "MASTER_KEY_UNAVAILABLE");
        }
    }
    /** 验证 AES-256 密钥长度，不向错误消息附带文件内容。 */
    private SecretKeySpec key(KeyRing ring, String id) {
        String encoded = ring.keys().get(id);
        if (encoded == null) {
            throw IntegrationInputs.error(503, "MASTER_KEY_VERSION_MISSING");
        }
        byte[] bytes = Base64.getDecoder().decode(encoded);
        if (bytes.length != 32) {
            throw IntegrationInputs.error(503, "MASTER_KEY_INVALID");
        }
        return new SecretKeySpec(bytes, "AES");
    }
    /** 使用固定顺序的身份串作为附加认证数据。 */
    private byte[] aad(CredentialEntity value) {
        return String.join("\n", value.getEncryptionOriginDeploymentId(), value.getPurpose(), value.getOwnerId(),
                value.getRevision().toString(), value.getId()).getBytes(StandardCharsets.UTF_8);
    }
    /** AES-GCM 的最后16字节单独作为认证标签保存。 */
    private void encrypt(CredentialEntity value, String secret, KeyRing ring) {
        try {
            byte[] nonce = new byte[12];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(ring, ring.activeKeyId()), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(aad(value));
            byte[] encoded = cipher.doFinal(secret.getBytes(StandardCharsets.UTF_8));
            value.setKeyId(ring.activeKeyId());
            value.setNonce(Base64.getEncoder().encodeToString(nonce));
            value.setCiphertext(Base64.getEncoder().encodeToString(Arrays.copyOf(encoded, encoded.length - 16)));
            value.setTag(Base64.getEncoder().encodeToString(Arrays.copyOfRange(encoded, encoded.length - 16, encoded.length)));
        } catch (Exception exception) {
            throw IntegrationInputs.error(503, "CREDENTIAL_ENCRYPTION_FAILED");
        }
    }
    /** 任何 AAD、密文、nonce、标签或密钥损坏都必须明确失败。 */
    private String decrypt(CredentialEntity value, KeyRing ring) {
        try {
            byte[] ciphertext = Base64.getDecoder().decode(value.getCiphertext());
            byte[] tag = Base64.getDecoder().decode(value.getTag());
            byte[] nonce = Base64.getDecoder().decode(value.getNonce());
            if (tag.length != 16 || nonce.length != 12) {
                throw new IllegalArgumentException();
            }
            byte[] encoded = Arrays.copyOf(ciphertext, ciphertext.length + tag.length);
            System.arraycopy(tag, 0, encoded, ciphertext.length, tag.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(ring, value.getKeyId()), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(aad(value));
            return new String(cipher.doFinal(encoded), StandardCharsets.UTF_8);
        } catch (Exception exception) {
            throw IntegrationInputs.error(503, "CREDENTIAL_DECRYPTION_FAILED");
        }
    }
}
