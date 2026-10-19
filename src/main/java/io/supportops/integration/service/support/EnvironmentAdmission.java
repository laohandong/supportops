package io.supportops.integration.service.support;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 按实际资源释放维护全局容量和环境排他，不以任务终态提前释放。 */
@Component
public class EnvironmentAdmission {
    private final int capacity;
    private final Set<String> held = new HashSet<>();
    private boolean maintenance;
    /** 企业默认四个不同环境并行；本地示例默认一项。 */
    public EnvironmentAdmission(@Value("${supportops.enterprise.enabled:false}") boolean enterprise,
            @Value("${supportops.enterprise.capacity:4}") int capacity) {
        if (capacity < 1 || capacity > 32) { throw new IllegalArgumentException("INVALID_DIAGNOSIS_CAPACITY"); }
        this.capacity = enterprise ? capacity : 1;
    }
    /** 受理名额，不增加等待队列；重复环境始终排他。 */
    public synchronized void acquire(String environmentId, boolean openApi) {
        if (maintenance || held.size() >= capacity || !held.add(environmentId)) {
            throw IntegrationInputs.error(openApi ? 429 : 409, openApi ? "CAPACITY_EXCEEDED" : "DIAGNOSIS_IN_PROGRESS");
        }
    }
    /** 仅在模型、工具及连接都释放后归还名额。 */
    public synchronized void release(String environmentId) { held.remove(environmentId); }
    /** 环境维护在同一排他边界内完成。 */
    public <T> T environment(String id, Supplier<T> action) {
        acquire(id, false);
        try { return action.get(); } finally { release(id); }
    }
    /** 模型配置、备份等全局操作必须等待全部资源退出。 */
    public <T> T globally(Supplier<T> action) {
        synchronized (this) {
            if (maintenance || !held.isEmpty()) { throw IntegrationInputs.error(409, "DIAGNOSIS_IN_PROGRESS"); }
            maintenance = true;
        }
        try { return action.get(); } finally { synchronized (this) { maintenance = false; } }
    }
}
