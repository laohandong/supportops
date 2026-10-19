package io.supportops.integration.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** 不可变目标快照的持久化映射，不直接作为接口响应。 */
@TableName("run_target_snapshots")
public class RunTargetSnapshotEntity {
    /** 唯一任务 UUID。 */
    @TableId(value = "run_id", type = IdType.INPUT)
    private String runId;
    /** 目标环境 UUID。 */
    private String environmentId;
    /** 安全目标快照。 */
    private String snapshotJson;
    /** CAPTURED 或 LEGACY_INFERRED。 */
    private String snapshotKind;

    /** 创建空映射对象，由业务服务显式校验并设置字段。 */
    public RunTargetSnapshotEntity() {}

    /** 返回唯一任务 UUID。 */
    public String getRunId() {
        return runId;
    }

    /** 设置唯一任务 UUID，调用方负责业务约束。 */
    public void setRunId(String value) {
        this.runId = value;
    }

    /** 返回目标环境 UUID。 */
    public String getEnvironmentId() {
        return environmentId;
    }

    /** 设置目标环境 UUID，调用方负责业务约束。 */
    public void setEnvironmentId(String value) {
        this.environmentId = value;
    }

    /** 返回安全目标快照。 */
    public String getSnapshotJson() {
        return snapshotJson;
    }

    /** 设置安全目标快照，调用方负责业务约束。 */
    public void setSnapshotJson(String value) {
        this.snapshotJson = value;
    }

    /** 返回CAPTURED 或 LEGACY_INFERRED。 */
    public String getSnapshotKind() {
        return snapshotKind;
    }

    /** 设置CAPTURED 或 LEGACY_INFERRED，调用方负责业务约束。 */
    public void setSnapshotKind(String value) {
        this.snapshotKind = value;
    }
}
