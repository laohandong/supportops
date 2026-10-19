package io.supportops.remediation.service.support;

import io.supportops.remediation.entity.RemediationExecutionEntity;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.Optional;

/** PID 与启动时间联合确认原处理器是否退出；不能凭超时抢占。 */
@Component
public final class RemediationProcessIdentity {
    /** 捕获本进程 PID。 */
    public long pid() {
        return ProcessHandle.current().pid();
    }

    /** 无法读取启动时间时保留空值，不伪造身份。 */
    public String startedAt() {
        return ProcessHandle.current().info().startInstant().map(Instant::toString).orElse(null);
    }

    /** 原 PID 不存在或启动身份已变化才确认退出。 */
    public boolean exited(RemediationExecutionEntity execution) {
        try {
            Optional<ProcessHandle> process = ProcessHandle.of(execution.getProcessId());
            if (process.isEmpty() || !process.get().isAlive()) {
                return true;
            }
            Optional<Instant> started = process.get().info().startInstant();
            return execution.getProcessStartedAt() != null && started.isPresent()
                    && !execution.getProcessStartedAt().equals(started.get().toString());
        } catch (SecurityException exception) {
            return false;
        }
    }
}
