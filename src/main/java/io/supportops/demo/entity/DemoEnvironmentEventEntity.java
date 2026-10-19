package io.supportops.demo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** 无关联提案的人工场景审计实体，完整保留原实例与修订。 */
@TableName("demo_environment_events")
public class DemoEnvironmentEventEntity {
    /** 单调递增审计游标。 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 服务端操作账号。 */
    private String actorId;
    /** 固定目标标识。 */
    private String targetId;
    /** 实际变更实例UUID。 */
    private String instanceId;
    /** SCENARIO_RESET事件类型。 */
    private String kind;
    /** 变更前场景。 */
    private String scenarioBefore;
    /** 请求切换的场景。 */
    private String scenarioAfter;
    /** 变更前修订号。 */
    private Long beforeRevision;
    /** 读回修订号，未知为空。 */
    private Long afterRevision;
    /** 有界脱敏变更前事实JSON。 */
    private String beforeJson;
    /** 有界读回事实及HTTP结果，未保存为空。 */
    private String afterJson;
    /** STARTED、APPLIED、UNKNOWN结果状态。 */
    private String status;
    /** 稳定错误码，无错误为空串。 */
    private String errorCode;
    /** UTC受理时间。 */
    private String startedAt;
    /** UTC收尾时间，未保存为空。 */
    private String finishedAt;

    /** 返回单调递增审计游标。 */
    public Long getId() {
        return id;
    }

    /** 设置单调递增审计游标。 */
    public void setId(Long value) {
        this.id = value;
    }

    /** 返回服务端操作账号。 */
    public String getActorId() {
        return actorId;
    }

    /** 设置服务端操作账号。 */
    public void setActorId(String value) {
        this.actorId = value;
    }

    /** 返回固定目标标识。 */
    public String getTargetId() {
        return targetId;
    }

    /** 设置固定目标标识。 */
    public void setTargetId(String value) {
        this.targetId = value;
    }

    /** 返回实际变更实例UUID。 */
    public String getInstanceId() {
        return instanceId;
    }

    /** 设置实际变更实例UUID。 */
    public void setInstanceId(String value) {
        this.instanceId = value;
    }

    /** 返回SCENARIO_RESET事件类型。 */
    public String getKind() {
        return kind;
    }

    /** 设置SCENARIO_RESET事件类型。 */
    public void setKind(String value) {
        this.kind = value;
    }

    /** 返回变更前场景。 */
    public String getScenarioBefore() {
        return scenarioBefore;
    }

    /** 设置变更前场景。 */
    public void setScenarioBefore(String value) {
        this.scenarioBefore = value;
    }

    /** 返回请求切换的场景。 */
    public String getScenarioAfter() {
        return scenarioAfter;
    }

    /** 设置请求切换的场景。 */
    public void setScenarioAfter(String value) {
        this.scenarioAfter = value;
    }

    /** 返回变更前修订号。 */
    public Long getBeforeRevision() {
        return beforeRevision;
    }

    /** 设置变更前修订号。 */
    public void setBeforeRevision(Long value) {
        this.beforeRevision = value;
    }

    /** 返回读回修订号，未知为空。 */
    public Long getAfterRevision() {
        return afterRevision;
    }

    /** 设置读回修订号，未知为空。 */
    public void setAfterRevision(Long value) {
        this.afterRevision = value;
    }

    /** 返回有界脱敏变更前事实JSON。 */
    public String getBeforeJson() {
        return beforeJson;
    }

    /** 设置有界脱敏变更前事实JSON。 */
    public void setBeforeJson(String value) {
        this.beforeJson = value;
    }

    /** 返回有界读回事实及HTTP结果，未保存为空。 */
    public String getAfterJson() {
        return afterJson;
    }

    /** 设置有界读回事实及HTTP结果，未保存为空。 */
    public void setAfterJson(String value) {
        this.afterJson = value;
    }

    /** 返回STARTED、APPLIED、UNKNOWN结果状态。 */
    public String getStatus() {
        return status;
    }

    /** 设置STARTED、APPLIED、UNKNOWN结果状态。 */
    public void setStatus(String value) {
        this.status = value;
    }

    /** 返回稳定错误码，无错误为空串。 */
    public String getErrorCode() {
        return errorCode;
    }

    /** 设置稳定错误码，无错误为空串。 */
    public void setErrorCode(String value) {
        this.errorCode = value;
    }

    /** 返回UTC受理时间。 */
    public String getStartedAt() {
        return startedAt;
    }

    /** 设置UTC受理时间。 */
    public void setStartedAt(String value) {
        this.startedAt = value;
    }

    /** 返回UTC收尾时间，未保存为空。 */
    public String getFinishedAt() {
        return finishedAt;
    }

    /** 设置UTC收尾时间，未保存为空。 */
    public void setFinishedAt(String value) {
        this.finishedAt = value;
    }
}
