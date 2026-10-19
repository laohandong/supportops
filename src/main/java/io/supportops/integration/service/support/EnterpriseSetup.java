package io.supportops.integration.service.support;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.supportops.integration.entity.DeploymentSetupStateEntity;
import io.supportops.integration.mapper.DeploymentSetupStateMapper;
import io.supportops.user.service.UserService;
import io.supportops.user.vo.UserView;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** 首次企业管理员必须证明拥有部署 Secret；摘要消费和账号创建原子提交。 */
@Component
public class EnterpriseSetup {
    private final boolean enabled;
    private final String secretFile;
    private final DeploymentSetupStateMapper setup;
    private final UserService users;
    private final TransactionTemplate transactions;
    /** 公开初始化方式，不公开 Secret 文件位置或内容。 */
    public boolean requiredSecret() { return enabled; }
    /** 注入外部 Secret 位置，明文不写数据库、回执或异常。 */
    public EnterpriseSetup(@Value("${supportops.enterprise.enabled:false}") boolean enabled,
            @Value("${supportops.enterprise.setup-secret-file:}") String secretFile, DeploymentSetupStateMapper setup,
            UserService users, TransactionTemplate transactions) {
        this.enabled = enabled; this.secretFile = secretFile; this.setup = setup; this.users = users; this.transactions = transactions;
    }
    /** 本地示例保留原初始化；企业使用持久化行锁保证只消费一次。 */
    public UserView create(String username, String password, String supplied) {
        if (!enabled) { return users.setup(username, password); }
        String expected;
        try {
            Path file = Path.of(secretFile);
            if (secretFile.isBlank() || !Files.isRegularFile(file) || Files.size(file) > 512) { throw new IllegalArgumentException(); }
            expected = Files.readString(file).strip();
            if (expected.length() < 43 || expected.length() > 128) { throw new IllegalArgumentException(); }
        } catch (Exception exception) { throw IntegrationInputs.error(503, "SETUP_SECRET_UNAVAILABLE"); }
        if (supplied == null || supplied.length() > 128 || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8))) {
            throw IntegrationInputs.error(403, "SETUP_SECRET_REQUIRED");
        }
        String hash = IntegrationInputs.digest(expected);
        return transactions.execute(status -> {
            DeploymentSetupStateEntity state = setup.selectOne(Wrappers.<DeploymentSetupStateEntity>lambdaQuery()
                    .eq(DeploymentSetupStateEntity::getId, 1).last("FOR UPDATE"));
            if (state == null || state.getConsumedAt() != null) { throw IntegrationInputs.error(409, "SETUP_COMPLETED"); }
            if (state.getSetupSecretHash() != null && !hash.equals(state.getSetupSecretHash())) { throw IntegrationInputs.error(403, "SETUP_SECRET_MISMATCH"); }
            UserView user = users.setup(username, password);
            int updated = setup.update(Wrappers.<DeploymentSetupStateEntity>lambdaUpdate().eq(DeploymentSetupStateEntity::getId, 1)
                    .isNull(DeploymentSetupStateEntity::getConsumedAt).set(DeploymentSetupStateEntity::getSetupSecretHash, hash)
                    .set(DeploymentSetupStateEntity::getConsumedAt, Instant.now().toString()).set(DeploymentSetupStateEntity::getAdministratorId, user.id()));
            if (updated != 1) { throw IntegrationInputs.error(409, "SETUP_COMPLETED"); }
            return user;
        });
    }
}
