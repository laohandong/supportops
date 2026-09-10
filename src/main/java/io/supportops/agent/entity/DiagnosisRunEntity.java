package io.supportops.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** 诊断任务的持久化状态，时间与计量字段兼容已有记录。 */
@TableName("runs")
public class DiagnosisRunEntity {
    /** 对话逻辑归档时间，UTC ISO 8601；null 表示仍显示在历史列表。 */
    @TableField("archived_at")
    private String archivedAt;

    /** 返回归档时间；统计读取不据此排除记录。 */
    public String getArchivedAt() {
        return archivedAt;
    }

    /** 设置整个对话统一的归档时间。 */
    public void setArchivedAt(String archivedAt) {
        this.archivedAt = archivedAt;
    }

    /** 记录所属用户 UUID；历史及内部迁移记录为 null。 */
    private String userId;
    /** 返回记录所属用户，历史未归属时为空。 */
    public String getUserId() {
        return userId;
    }
    /** 设置服务端确认的记录所属用户。 */
    public void setUserId(String userId) {
        this.userId = userId;
    }

    /** 诊断任务 UUID。 */
    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    /** 会话 UUID。 */
    @TableField("session_id")
    private String sessionId;

    /** 本次诊断问题。 */
    @TableField("question")
    private String question;

    /** 任务状态，取值由 RunStatus 定义。 */
    @TableField("status")
    private String status;

    /** 最终回答，无结果时为空字符串。 */
    @TableField("answer")
    private String answer;

    /** 稳定错误码，正常时为空字符串。 */
    @TableField("error_code")
    private String errorCode;

    /** 任务创建时间，ISO 8601 字符串。 */
    @TableField("created_at")
    private String createdAt;

    /** 终态时间，执行中为 null。 */
    @TableField("finished_at")
    private String finishedAt;

    /** 终态耗时，单位毫秒。 */
    @TableField("elapsed_ms")
    private Long elapsedMs;

    /** 累计输入 Token 数。 */
    @TableField("input_tokens")
    private Long inputTokens;

    /** 累计输出 Token 数。 */
    @TableField("output_tokens")
    private Long outputTokens;

    /** 创建供 MyBatis 使用的空实体。 */
    public DiagnosisRunEntity() {}

    /** 使用明确字段创建实体，调用方负责业务校验。 */
    public DiagnosisRunEntity(
            String id,
            String sessionId,
            String question,
            String status,
            String answer,
            String errorCode,
            String createdAt,
            String finishedAt,
            Long elapsedMs,
            Long inputTokens,
            Long outputTokens) {
        this.id = id;
        this.sessionId = sessionId;
        this.question = question;
        this.status = status;
        this.answer = answer;
        this.errorCode = errorCode;
        this.createdAt = createdAt;
        this.finishedAt = finishedAt;
        this.elapsedMs = elapsedMs;
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
    }

    /** 返回诊断任务 UUID。 */
    public String getId() {
        return id;
    }

    /** 设置诊断任务 UUID。 */
    public void setId(String id) {
        this.id = id;
    }

    /** 返回会话 UUID。 */
    public String getSessionId() {
        return sessionId;
    }

    /** 设置会话 UUID。 */
    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    /** 返回本次诊断问题。 */
    public String getQuestion() {
        return question;
    }

    /** 设置本次诊断问题。 */
    public void setQuestion(String question) {
        this.question = question;
    }

    /** 返回任务状态，取值由 RunStatus 定义。 */
    public String getStatus() {
        return status;
    }

    /** 设置任务状态，取值由 RunStatus 定义。 */
    public void setStatus(String status) {
        this.status = status;
    }

    /** 返回最终回答，无结果时为空字符串。 */
    public String getAnswer() {
        return answer;
    }

    /** 设置最终回答，无结果时为空字符串。 */
    public void setAnswer(String answer) {
        this.answer = answer;
    }

    /** 返回稳定错误码，正常时为空字符串。 */
    public String getErrorCode() {
        return errorCode;
    }

    /** 设置稳定错误码，正常时为空字符串。 */
    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    /** 返回任务创建时间，ISO 8601 字符串。 */
    public String getCreatedAt() {
        return createdAt;
    }

    /** 设置任务创建时间，ISO 8601 字符串。 */
    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    /** 返回终态时间，执行中为 null。 */
    public String getFinishedAt() {
        return finishedAt;
    }

    /** 设置终态时间，执行中为 null。 */
    public void setFinishedAt(String finishedAt) {
        this.finishedAt = finishedAt;
    }

    /** 返回终态耗时，单位毫秒。 */
    public Long getElapsedMs() {
        return elapsedMs;
    }

    /** 设置终态耗时，单位毫秒。 */
    public void setElapsedMs(Long elapsedMs) {
        this.elapsedMs = elapsedMs;
    }

    /** 返回累计输入 Token 数。 */
    public Long getInputTokens() {
        return inputTokens;
    }

    /** 设置累计输入 Token 数。 */
    public void setInputTokens(Long inputTokens) {
        this.inputTokens = inputTokens;
    }

    /** 返回累计输出 Token 数。 */
    public Long getOutputTokens() {
        return outputTokens;
    }

    /** 设置累计输出 Token 数。 */
    public void setOutputTokens(Long outputTokens) {
        this.outputTokens = outputTokens;
    }
}
