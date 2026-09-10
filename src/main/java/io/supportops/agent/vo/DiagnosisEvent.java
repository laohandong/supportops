package io.supportops.agent.vo;

import io.supportops.knowledge.vo.KnowledgeSearchResult;
import io.supportops.knowledge.vo.KnowledgeViews;
import io.swagger.v3.oas.annotations.media.Schema;

/** 持久化执行事件。按 id 升序返回，用最后一条 id 作为下一次 after 游标。 */
@Schema(name = "DiagnosisEvent", description = "持久化执行事件。按 id 升序返回，用最后一条 id 作为下一次 after 游标。")
public record DiagnosisEvent(
        @Schema(description = "单调递增的事件编号，为整型游标。", example = "12") long id,
        @Schema(
                        description =
                                "事件种类：CONTEXT 上下文；TOOL_CALL 工具调用；TOOL_RESULT 工具结果；KNOWLEDGE"
                                    + " 检索结果；SQL_RESULT 表格查询证据；ANSWER_DELTA 公开回答片段；USAGE 用量；ERROR"
                                    + " 执行异常。",
                        allowableValues = {
                            "CONTEXT",
                            "ANSWER_DELTA",
                            "TOOL_CALL",
                            "TOOL_RESULT",
                            "KNOWLEDGE",
                            "SQL_RESULT",
                            "USAGE",
                            "ERROR"
                        })
                String kind,
        @Schema(
                        description = "事件载荷，结构由 kind 决定；具体字段见各载荷模型。",
                        anyOf = {
                            ContextEvent.class,
                            AnswerDeltaEvent.class,
                            ToolCallEvent.class,
                            ToolResultEvent.class,
                            KnowledgeSearchResult.class,
                            KnowledgeViews.SqlResult.class,
                            UsageEvent.class,
                            ExecutionErrorEvent.class
                        })
                Object content,
        @Schema(description = "事件写入时间，ISO 8601。", format = "date-time") String createdAt) {}
