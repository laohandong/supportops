package io.supportops.agent.vo;

import io.swagger.v3.oas.annotations.media.Schema;

/** 公开回答的增量文本；新一轮模型调用时重置草稿，不包含思维事件。 */
@Schema(description = "公开回答片段，reset 为 true 时先清空上一轮草稿；不代表最终结论。")
public record AnswerDeltaEvent(
        @Schema(description = "是否开始新的回答草稿。") boolean reset,
        @Schema(description = "本次新增的 Markdown 文本，重置时可以为空。") String delta) {}
