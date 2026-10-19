package io.supportops.outcome.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** outcome_export_requests 的持久化映射；不作为 HTTP 响应。 */
@TableName("outcome_export_requests")
public class OutcomeExportRequestEntity {
    /** 回执UUID。 */
    @TableId(type = IdType.INPUT)
    private String id;

    /** 请求所属管理员UUID。 */
    private String actorId;

    /** 客户端固定请求UUID。 */
    private String requestId;

    /** 方法目标及正文摘要。 */
    private String requestDigest;

    /** 成功的导出UUID；失败为空。 */
    private String exportId;

    /** 原终局HTTP状态。 */
    private Integer httpStatus;

    /** 失败代码；成功为空。 */
    private String errorCode;

    /** 受理UTC时间。 */
    private String createdAt;

    /** 读取回执UUID。 */
    public String getId() {
        return id;
    }

    /** 设置回执UUID。 */
    public void setId(String value) {
        this.id = value;
    }

    /** 读取请求所属管理员UUID。 */
    public String getActorId() {
        return actorId;
    }

    /** 设置请求所属管理员UUID。 */
    public void setActorId(String value) {
        this.actorId = value;
    }

    /** 读取客户端固定请求UUID。 */
    public String getRequestId() {
        return requestId;
    }

    /** 设置客户端固定请求UUID。 */
    public void setRequestId(String value) {
        this.requestId = value;
    }

    /** 读取方法目标及正文摘要。 */
    public String getRequestDigest() {
        return requestDigest;
    }

    /** 设置方法目标及正文摘要。 */
    public void setRequestDigest(String value) {
        this.requestDigest = value;
    }

    /** 读取成功的导出UUID；失败为空。 */
    public String getExportId() {
        return exportId;
    }

    /** 设置成功的导出UUID；失败为空。 */
    public void setExportId(String value) {
        this.exportId = value;
    }

    /** 读取原终局HTTP状态。 */
    public Integer getHttpStatus() {
        return httpStatus;
    }

    /** 设置原终局HTTP状态。 */
    public void setHttpStatus(Integer value) {
        this.httpStatus = value;
    }

    /** 读取失败代码；成功为空。 */
    public String getErrorCode() {
        return errorCode;
    }

    /** 设置失败代码；成功为空。 */
    public void setErrorCode(String value) {
        this.errorCode = value;
    }

    /** 读取受理UTC时间。 */
    public String getCreatedAt() {
        return createdAt;
    }

    /** 设置受理UTC时间。 */
    public void setCreatedAt(String value) {
        this.createdAt = value;
    }
}
