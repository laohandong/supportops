package io.supportops.integration.service.support;

import io.supportops.integration.mapper.DeploymentRuntimeGuardMapper;
import org.springframework.stereotype.Component;

/** 单行恢复代次保护；历史授权留存，但不能自动用于新部署的外部执行。 */
@Component
public class RestoreGeneration {
    private final DeploymentRuntimeGuardMapper guard;
    /** 注入唯一部署守卫，读取不修改历史数据。 */
    public RestoreGeneration(DeploymentRuntimeGuardMapper guard) { this.guard = guard; }
    /** 返回当前恢复代次，守卫缺失时拒绝放宽批准。 */
    public int current() {
        Integer value = guard.selectById(1).getRestoreGeneration();
        if (value == null || value < 0) { throw new IllegalStateException("RESTORE_GENERATION_INVALID"); }
        return value;
    }
}
