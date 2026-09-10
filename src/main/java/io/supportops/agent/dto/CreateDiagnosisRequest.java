package io.supportops.agent.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** 创建异步诊断任务。共享示例环境同时只接受一个诊断。 */
@Schema(name = "CreateDiagnosisRequest", description = "创建异步诊断任务。共享示例环境同时只接受一个诊断。")
public record CreateDiagnosisRequest(
        @Schema(
                        description = "当前问题或补充信息，不能为空白，最多 6000 字符。请勿填写密钥。",
                        example = "升级到 2.0 后订单同步失败，请检查配置与日志。",
                        minLength = 1,
                        maxLength = 6000,
                        requiredMode = Schema.RequiredMode.REQUIRED)
                String question,
        @Schema(
                        description = "会话 UUID；省略或留空时创建新会话，传入已有编号加载最近三个已完成问答。",
                        nullable = true,
                        example = "11111111-1111-4111-8111-111111111111")
                String sessionId,
        @Schema(description = "true 强制关键词检索；false 根据配置和索引决定实际检索方式。", defaultValue = "false")
                boolean lexicalOnly) {}
