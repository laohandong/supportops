package io.supportops.integration.service.support;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.supportops.integration.entity.DeploymentRuntimeGuardEntity;
import io.supportops.integration.mapper.DeploymentRuntimeGuardMapper;
import jakarta.annotation.PreDestroy;
import java.net.InetAddress;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** 企业实例启动前持久化领取执行权，不以超时或心跳过期抢占旧实例。 */
@Component
@DependsOn("flywayInitializer")
public class DeploymentRuntimeGuard {
    private final DeploymentRuntimeGuardMapper guard;
    private final String runtimeId;
    private final boolean enabled;
    private final java.util.List<java.util.function.BooleanSupplier> released = new java.util.concurrent.CopyOnWriteArrayList<>();
    /** 消费者登记实际资源释放检查，迟到线程未退出时保持持有者阻塞。 */
    public void protect(java.util.function.BooleanSupplier check) { released.add(check); }
    /** 在任何任务恢复服务构造之前领取 guard；无法证明旧进程退出时明确阻止启动。 */
    public DeploymentRuntimeGuard(DeploymentRuntimeGuardMapper guard, TransactionTemplate transactions,
            @Value("${supportops.enterprise.enabled:false}") boolean enabled,
            @Value("${supportops.enterprise.deployment-id:}") String deploymentId,
            @Value("${supportops.enterprise.host-id:}") String hostId,
            io.supportops.config.RuntimeIdentity identity) throws Exception {
        this.guard = guard; this.enabled = enabled;
        this.runtimeId = "unknown".equals(identity.view().runtimeId()) ? UUID.randomUUID().toString() : identity.view().runtimeId();
        if (!enabled) { return; }
        String deployment = IntegrationInputs.uuid(deploymentId);
        String host = hostId.isBlank() ? InetAddress.getLocalHost().getHostName() : IntegrationInputs.text(hostId, 128);
        ProcessHandle process = ProcessHandle.current();
        String started = process.info().startInstant().orElseThrow(() -> new IllegalStateException("PROCESS_IDENTITY_UNKNOWN")).toString();
        transactions.executeWithoutResult(status -> {
            DeploymentRuntimeGuardEntity value = guard.selectOne(Wrappers.<DeploymentRuntimeGuardEntity>lambdaQuery()
                    .eq(DeploymentRuntimeGuardEntity::getId, 1).last("FOR UPDATE"));
            if (value == null) { throw new IllegalStateException("DEPLOYMENT_GUARD_MISSING"); }
            if (value.getDeploymentId() != null && !deployment.equals(value.getDeploymentId())) {
                throw new IllegalStateException("DEPLOYMENT_ID_MISMATCH");
            }
            if (value.getHolderRuntimeId() != null) {
                boolean exited = false;
                if (!java.nio.file.Files.exists(java.nio.file.Path.of("/.dockerenv")) && host.equals(value.getHost())
                        && value.getPid() != null && value.getProcessStartedAt() != null) {
                    java.util.Optional<ProcessHandle> old = ProcessHandle.of(value.getPid());
                    exited = old.isEmpty() || old.get().info().startInstant()
                            .map(time -> !time.equals(Instant.parse(value.getProcessStartedAt()))).orElse(false);
                }
                if (!exited) { throw new IllegalStateException("DEPLOYMENT_ALREADY_RUNNING_OR_UNKNOWN"); }
            }
            int changed = guard.update(Wrappers.<DeploymentRuntimeGuardEntity>lambdaUpdate().eq(DeploymentRuntimeGuardEntity::getId, 1)
                    .eq(DeploymentRuntimeGuardEntity::getVersion, value.getVersion())
                    .set(DeploymentRuntimeGuardEntity::getDeploymentId, deployment).set(DeploymentRuntimeGuardEntity::getHolderRuntimeId, runtimeId)
                    .set(DeploymentRuntimeGuardEntity::getHost, host).set(DeploymentRuntimeGuardEntity::getPid, process.pid())
                    .set(DeploymentRuntimeGuardEntity::getProgramSha256,
                            "unknown".equals(identity.view().jarSha256()) ? null : identity.view().jarSha256())
                    .set(DeploymentRuntimeGuardEntity::getProcessStartedAt, started).set(DeploymentRuntimeGuardEntity::getVersion, value.getVersion() + 1));
            if (changed != 1) { throw new IllegalStateException("DEPLOYMENT_GUARD_CONFLICT"); }
        });
    }
    /** 依赖消费者全部关闭后再释放，旧实例不能清除新持有者。 */
    @PreDestroy public void close() {
        if (enabled && released.stream().allMatch(java.util.function.BooleanSupplier::getAsBoolean)) {
            guard.update(Wrappers.<DeploymentRuntimeGuardEntity>lambdaUpdate().eq(DeploymentRuntimeGuardEntity::getId, 1)
                    .eq(DeploymentRuntimeGuardEntity::getHolderRuntimeId, runtimeId).set(DeploymentRuntimeGuardEntity::getHolderRuntimeId, null));
        }
    }
}
