package io.supportops.agent.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** 同一连接按序发送任务快照与增量事件，终态快照包含终态前的全部剩余事件。 */
@Schema(description = "SSE snapshot 事件的数据；先读取 run 再读取 events，游标为最后事件编号。")
public record RunStreamSnapshot(
        @Schema(description = "本次任务状态。", schemaResolution = Schema.SchemaResolution.ALL_OF) DiagnosisRun run,
        @Schema(description = "严格大于已读游标的持久化事件。") List<DiagnosisEvent> events) {}
