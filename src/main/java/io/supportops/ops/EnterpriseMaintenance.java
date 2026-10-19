package io.supportops.ops;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.supportops.integration.entity.CredentialEntity;
import io.supportops.integration.entity.DeploymentRuntimeGuardEntity;
import io.supportops.integration.entity.IntegrationAuditEventEntity;
import io.supportops.integration.mapper.CredentialMapper;
import io.supportops.integration.mapper.DeploymentRuntimeGuardMapper;
import io.supportops.integration.mapper.IntegrationAuditEventMapper;
import io.supportops.integration.service.CredentialService;
import io.supportops.integration.service.impl.CredentialServiceImpl;
import io.supportops.integration.service.support.IntegrationInputs;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.transaction.support.TransactionTemplate;

/** 独立停写运维入口：不扫描应用，不恢复任务、不启动消费者或调用模型。 */
public final class EnterpriseMaintenance {
    /** 禁止实例化运维入口。 */
    private EnterpriseMaintenance() {}
    /** 受控配置仅加载数据访问和凭据服务，普通工作台不会激活此配置。 */
    @Configuration(proxyBeanMethods = false)
    @Profile("enterprise-maintenance")
    @EnableAutoConfiguration
    @MapperScan("io.supportops.integration.mapper")
    @Import(CredentialServiceImpl.class)
    public static class MaintenanceConfiguration {
        /** 明确隔离配置的构造语义。 */
        public MaintenanceConfiguration() {}
    }
    /** 只输出安全原因码和结果，不打印异常正文或配置。 */
    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(MaintenanceConfiguration.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setAdditionalProfiles("enterprise-maintenance");
        application.setDefaultProperties(Map.of("spring.flyway.enabled", "false", "spring.main.banner-mode", "off",
                "logging.level.root", "OFF"));
        try (ConfigurableApplicationContext context = application.run(args)) {
            Environment env = context.getEnvironment();
            String deployment = IntegrationInputs.uuid(env.getRequiredProperty("supportops.enterprise.deployment-id"));
            DeploymentRuntimeGuardMapper guards = context.getBean(DeploymentRuntimeGuardMapper.class);
            DeploymentRuntimeGuardEntity guard = guards.selectById(1);
            String action = env.getRequiredProperty("supportops.ops.action");
            if (!"reset-restored".equals(action) && !deployment.equals(guard.getDeploymentId())) {
                throw new IllegalStateException("DEPLOYMENT_ID_MISMATCH");
            }
            if (!"recover-guard".equals(action) && !"reset-restored".equals(action) && guard.getHolderRuntimeId() != null) {
                throw new IllegalStateException("DEPLOYMENT_NOT_STOPPED");
            }
            ObjectMapper json = context.getBean(ObjectMapper.class);
            int count;
            if ("rotate".equals(action)) { count = context.getBean(CredentialService.class).rotate(); }
            else if ("import-model".equals(action)) { count = importModel(context, env, json); }
            else if ("recover-guard".equals(action)) { count = recoverGuard(context, env, deployment, guard, json); }
            else if ("reset-restored".equals(action)) { count = resetRestored(context, env, deployment, guard, json); }
            else { throw new IllegalStateException("MAINTENANCE_ACTION_INVALID"); }
            Map<String, Object> receipt = Map.of("operation", action, "deploymentId", deployment, "changed", count,
                    "completedAt", Instant.now().toString(), "status", "COMPLETED");
            Path receiptFile = Path.of(env.getRequiredProperty("supportops.ops.receipt-file"));
            atomic(json, receiptFile, receipt);
            System.out.println("OPS:" + json.writeValueAsString(receipt));
        } catch (Exception exception) {
            String reason = exception instanceof org.springframework.web.server.ResponseStatusException response
                    ? response.getReason() : exception.getMessage();
            String code = reason != null && reason.matches("[A-Z][A-Z_]+") ? reason : "ENTERPRISE_MAINTENANCE_FAILED";
            System.out.println("OPS:{\"status\":\"FAILED\",\"code\":\"" + code + "\"}");
            System.exit(1);
        }
    }
    /** 同一导入日志固定源摘要和角色归属；部分保存失败后只恢复相同源。 */
    private static int importModel(ConfigurableApplicationContext context, Environment env, ObjectMapper json) throws Exception {
        Path source = Path.of(env.getRequiredProperty("supportops.ops.source-file")).toAbsolutePath().normalize();
        Path target = Path.of(env.getRequiredProperty("supportops.settings.file")).toAbsolutePath().normalize();
        if (source.equals(target) || !Files.isRegularFile(source) || Files.size(source) > 65536) {
            throw new IllegalStateException("IMPORT_SOURCE_INVALID");
        }
        String original = Files.readString(source);
        String hash = IntegrationInputs.digest(original);
        JsonNode old = json.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).readTree(original);
        if (old.path("format").asInt() != 1 || !old.path("roles").isObject()) { throw new IllegalStateException("IMPORT_FORMAT_INVALID"); }
        requireFields(old, Set.of("format", "roles"));
        requireFields(old.path("roles"), Set.of("model", "embedding"));
        for (String role : List.of("model", "embedding")) {
            JsonNode value = old.path("roles").get(role);
            if (value == null) { continue; }
            requireFields(value, Set.of("provider", "name", "base-url", "api-key", "reasoning-effort"));
            for (String key : List.of("provider", "name", "base-url", "api-key")) {
                if (!value.path(key).isTextual() || value.path(key).asText().length() > 8192) { throw new IllegalStateException("IMPORT_FORMAT_INVALID"); }
            }
            if (value.has("reasoning-effort") && !value.path("reasoning-effort").isTextual()) { throw new IllegalStateException("IMPORT_FORMAT_INVALID"); }
        }
        Path journal = Path.of(env.getRequiredProperty("supportops.ops.receipt-file") + ".journal");
        Map<String, String> owners = new LinkedHashMap<>();
        if (Files.exists(journal)) {
            JsonNode state = json.readTree(Files.readString(journal));
            if (!hash.equals(state.path("sourceHash").asText()) || !source.toString().equals(state.path("sourcePath").asText())
                    || !target.toString().equals(state.path("targetPath").asText())
                    || !env.getRequiredProperty("supportops.enterprise.deployment-id").equals(state.path("deploymentId").asText())) {
                throw new IllegalStateException("IMPORT_SOURCE_CHANGED");
            }
            state.path("owners").fields().forEachRemaining(field -> owners.put(field.getKey(), field.getValue().asText()));
            if (!Set.of("model", "embedding").containsAll(owners.keySet())) { throw new IllegalStateException("IMPORT_JOURNAL_INVALID"); }
            for (Map.Entry<String, String> owner : owners.entrySet()) {
                IntegrationInputs.uuid(owner.getValue());
                if (!old.path("roles").has(owner.getKey())) { throw new IllegalStateException("IMPORT_JOURNAL_INVALID"); }
            }
        } else {
            for (String role : List.of("model", "embedding")) {
                if (old.path("roles").has(role)) { owners.put(role, UUID.randomUUID().toString()); }
            }
            if (Files.exists(target)) { throw new IllegalStateException("IMPORT_TARGET_EXISTS"); }
            atomic(json, journal, Map.of("sourceHash", hash, "owners", owners, "sourcePath", source.toString(),
                    "targetPath", target.toString(), "deploymentId", env.getRequiredProperty("supportops.enterprise.deployment-id")));
        }
        CredentialService credentials = context.getBean(CredentialService.class);
        CredentialMapper rows = context.getBean(CredentialMapper.class);
        Map<String, Object> roles = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : owners.entrySet()) {
            JsonNode value = old.path("roles").path(entry.getKey());
            Map<String, String> safe = new LinkedHashMap<>();
            for (String key : List.of("provider", "name", "base-url", "api-key")) {
                if (!value.path(key).isTextual()) { throw new IllegalStateException("IMPORT_FORMAT_INVALID"); }
                safe.put(key, "api-key".equals(key) ? "" : value.path(key).asText());
            }
            if (value.has("reasoning-effort")) { safe.put("reasoning-effort", value.path("reasoning-effort").asText()); }
            String secret = value.path("api-key").asText();
            if (!secret.isEmpty()) {
                String purpose = "model".equals(entry.getKey()) ? "MODEL_CHAT" : "MODEL_EMBEDDING";
                CredentialEntity existing = rows.selectOne(Wrappers.<CredentialEntity>lambdaQuery()
                        .eq(CredentialEntity::getPurpose, purpose).eq(CredentialEntity::getOwnerId, entry.getValue())
                        .eq(CredentialEntity::getRevision, 1));
                String id = existing == null ? credentials.store(purpose, entry.getValue(), 1, secret) : existing.getId();
                if (!secret.equals(credentials.reveal(id, purpose, entry.getValue(), 1))) { throw new IllegalStateException("IMPORT_VERIFICATION_FAILED"); }
                safe.put("credential-id", id); safe.put("credential-owner", entry.getValue()); safe.put("credential-revision", "1");
            }
            roles.put(entry.getKey(), safe);
        }
        Map<String, Object> protectedSettings = Map.of("format", 2, "roles", roles);
        if (Files.exists(target) && !json.readTree(Files.readString(target)).equals(json.valueToTree(protectedSettings))) {
            throw new IllegalStateException("IMPORT_TARGET_CHANGED");
        }
        atomic(json, target, protectedSettings);
        return owners.size();
    }
    /** 运维先证明旧容器已停止；部署、持有者及版本全部条件匹配才释放，并同时记录审计。 */
    private static int recoverGuard(ConfigurableApplicationContext context, Environment env, String deployment,
            DeploymentRuntimeGuardEntity guard, ObjectMapper json) throws Exception {
        String holder = IntegrationInputs.uuid(env.getRequiredProperty("supportops.ops.holder-id"));
        Path file = Path.of(env.getRequiredProperty("supportops.ops.stopped-proof"));
        if (!Files.isRegularFile(file) || Files.size(file) > 16384) { throw new IllegalStateException("GUARD_RECOVERY_PROOF_REQUIRED"); }
        JsonNode proof = json.readTree(Files.readString(file));
        if (!"STOPPED_COMPOSE_HOLDER_V1".equals(proof.path("kind").asText())
                || !deployment.equals(guard.getDeploymentId()) || !holder.equals(guard.getHolderRuntimeId())
                || !deployment.equals(proof.path("deploymentId").asText()) || !holder.equals(proof.path("holderRuntimeId").asText())
                || !proof.path("containerId").asText().matches("[0-9a-f]{64}") || !proof.path("stopped").asBoolean()
                || !proof.path("containerHost").asText().equals(guard.getHost())
                || guard.getProgramSha256() == null || !guard.getProgramSha256().equals(proof.path("jarSha256").asText())) {
            throw new IllegalStateException("GUARD_RECOVERY_PROOF_REQUIRED");
        }
        return context.getBean(TransactionTemplate.class).execute(status -> {
            int changed = context.getBean(DeploymentRuntimeGuardMapper.class).update(Wrappers.<DeploymentRuntimeGuardEntity>lambdaUpdate()
                    .eq(DeploymentRuntimeGuardEntity::getId, 1).eq(DeploymentRuntimeGuardEntity::getDeploymentId, deployment)
                    .eq(DeploymentRuntimeGuardEntity::getHolderRuntimeId, holder).eq(DeploymentRuntimeGuardEntity::getVersion, guard.getVersion())
                    .set(DeploymentRuntimeGuardEntity::getHolderRuntimeId, null).set(DeploymentRuntimeGuardEntity::getVersion, guard.getVersion() + 1));
            if (changed != 1) { throw new IllegalStateException("GUARD_RECOVERY_CONFLICT"); }
            IntegrationAuditEventEntity audit = new IntegrationAuditEventEntity();
            audit.setActorType("OPS"); audit.setActorId(deployment); audit.setAction("GUARD_RECOVERY");
            audit.setResourceType("DEPLOYMENT"); audit.setResourceId(deployment);
            audit.setDetailJson("{\"operatorConfirmedStopped\":true}"); audit.setCreatedAt(Instant.now().toString());
            context.getBean(IntegrationAuditEventMapper.class).insert(audit);
            return changed;
        });
    }
    /** 旧格式导入必须在任何保存前完整校验，未知角色或字段不能部分导入。 */
    private static void requireFields(JsonNode value, Set<String> allowed) {
        if (!value.isObject()) { throw new IllegalStateException("IMPORT_FORMAT_INVALID"); }
        value.fieldNames().forEachRemaining(name -> {
            if (!allowed.contains(name)) { throw new IllegalStateException("IMPORT_FORMAT_INVALID"); }
        });
    }
    /** 临时文件与最终文件在同一目录，原子替换不暴露半份引用配置。 */
    private static void atomic(ObjectMapper json, Path target, Object value) throws Exception {
        Path temporary = target.resolveSibling(target.getFileName() + "." + UUID.randomUUID() + ".tmp");
        Files.writeString(temporary, json.writeValueAsString(value), StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE_NEW);
        if (Files.getFileStore(temporary).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(temporary, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        }
        try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        finally { Files.deleteIfExists(temporary); }
    }
    /** 新隔离卷证明匹配后仅更新一行部署代次，完整保留源授权和会话历史。 */
    private static int resetRestored(ConfigurableApplicationContext context, Environment env, String deployment,
            DeploymentRuntimeGuardEntity guard, ObjectMapper json) throws Exception {
        String source = IntegrationInputs.uuid(env.getRequiredProperty("supportops.ops.source-deployment-id"));
        Path proofFile = Path.of(env.getRequiredProperty("supportops.ops.fresh-restore-proof"));
        if (!Files.isRegularFile(proofFile) || Files.size(proofFile) > 16384) { throw new IllegalStateException("FRESH_RESTORE_PROOF_REQUIRED"); }
        JsonNode proof = json.readTree(Files.readString(proofFile));
        if (!"FRESH_ENTERPRISE_VOLUMES_V1".equals(proof.path("kind").asText())
                || !deployment.equals(proof.path("targetDeploymentId").asText())
                || !source.equals(proof.path("sourceDeploymentId").asText()) || source.equals(deployment)
                || !source.equals(guard.getDeploymentId()) || !proof.path("applicationNeverStarted").asBoolean()
                || !proof.path("backupManifestSha256").asText().matches("[0-9a-f]{64}")
                || proof.path("freshVolumes").size() != 4) {
            throw new IllegalStateException("FRESH_RESTORE_PROOF_REQUIRED");
        }
        return context.getBean(TransactionTemplate.class).execute(status -> {
            int changed = context.getBean(DeploymentRuntimeGuardMapper.class).update(Wrappers.<DeploymentRuntimeGuardEntity>lambdaUpdate()
                    .eq(DeploymentRuntimeGuardEntity::getId, 1).eq(DeploymentRuntimeGuardEntity::getDeploymentId, source)
                    .eq(DeploymentRuntimeGuardEntity::getVersion, guard.getVersion())
                    .set(DeploymentRuntimeGuardEntity::getDeploymentId, deployment)
                    .set(DeploymentRuntimeGuardEntity::getHolderRuntimeId, null)
                    .set(DeploymentRuntimeGuardEntity::getHost, null).set(DeploymentRuntimeGuardEntity::getPid, null)
                    .set(DeploymentRuntimeGuardEntity::getProcessStartedAt, null)
                    .set(DeploymentRuntimeGuardEntity::getRestoreGeneration, Math.addExact(guard.getRestoreGeneration(), 1))
                    .set(DeploymentRuntimeGuardEntity::getVersion, guard.getVersion() + 1));
            if (changed != 1) { throw new IllegalStateException("RESTORE_GUARD_CONFLICT"); }
            IntegrationAuditEventEntity event = new IntegrationAuditEventEntity();
            event.setActorType("OPS"); event.setActorId(deployment); event.setAction("RESTORE_RESET");
            event.setResourceType("DEPLOYMENT"); event.setResourceId(deployment);
            event.setDetailJson("{\"sourceDeploymentId\":\"" + source + "\",\"externalCapabilitiesRequireApproval\":true}");
            event.setCreatedAt(Instant.now().toString()); context.getBean(IntegrationAuditEventMapper.class).insert(event);
            return changed;
        });
    }
}
