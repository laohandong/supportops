package io.supportops.agent.vo;

import io.supportops.config.vo.GenerationOptions;
import io.supportops.config.vo.ModelRoute;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** CONTEXT 事件载荷：任务开始时的上下文与模型快照。 */
@Schema(name = "ContextEvent", description = "CONTEXT 事件载荷：任务开始时的上下文与模型快照。")
public record ContextEvent(
        @Schema(description = "已加载的项目约束编号列表。") List<String> memoryIds,
        @Schema(description = "已加载的历史问答数量，最多 3 轮。") int historyTurns,
        @Schema(description = "知识查询使用的产品版本。", example = "2.0") String versionFilter,
        @Schema(description = "是否强制只使用关键词检索。") boolean lexicalOnly,
        @Schema(description = "对话路由快照，不含密钥。", schemaResolution = Schema.SchemaResolution.ALL_OF)
                ModelRoute modelRoute,
        @Schema(description = "生成参数快照。", schemaResolution = Schema.SchemaResolution.ALL_OF)
                GenerationOptions generation) {}
