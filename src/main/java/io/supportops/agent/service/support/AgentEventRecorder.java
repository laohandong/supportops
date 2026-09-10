package io.supportops.agent.service.support;

import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEventType;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.supportops.agent.vo.AnswerDeltaEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.supportops.agent.constant.RunLimits;
import io.supportops.agent.service.DiagnosticEngine.Result;
import io.supportops.agent.vo.ToolCallEvent;
import io.supportops.agent.vo.ToolResultEvent;
import io.supportops.agent.vo.UsageEvent;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

/** 将 AgentScope 流式事件转换为公开证据，缓冲与计量仅属于一次诊断。 */
public final class AgentEventRecorder {
    private final BiConsumer<String, Object> events;
    private final AtomicReference<String> answer = new AtomicReference<>("");
    private final AtomicLong inputTokens = new AtomicLong();
    private final AtomicLong outputTokens = new AtomicLong();
    private final AtomicBoolean limitReached = new AtomicBoolean();
    private final Map<String, StringBuilder> toolTexts = new ConcurrentHashMap<>();
    private final Map<String, StringBuilder> toolArguments = new ConcurrentHashMap<>();

    /** 绑定当前任务的事件持久化回调，不共享其他任务的缓冲区。 */
    public AgentEventRecorder(BiConsumer<String, Object> events) {
        this.events = events;
    }

    /** 合并工具流式片段，并在完成事件到达时发布有明确结构的证据。 */
    public void accept(AgentEvent event) {
        if (event instanceof ModelCallStartEvent) {
            events.accept("ANSWER_DELTA", new AnswerDeltaEvent(true, ""));
        } else if (event instanceof TextBlockDeltaEvent delta) {
            if (delta.getDelta() != null && !delta.getDelta().isEmpty()) {
                events.accept("ANSWER_DELTA", new AnswerDeltaEvent(false, delta.getDelta()));
            }
        } else if (event instanceof ToolCallDeltaEvent delta) {
            append(toolArguments, delta.getToolCallId(), delta.getDelta());
        } else if (event instanceof ToolCallEndEvent end) {
            events.accept(
                    "TOOL_CALL",
                    new ToolCallEvent(
                            end.getToolCallId(),
                            end.getToolCallName(),
                            take(toolArguments, end.getToolCallId())));
        } else if (event instanceof ToolResultTextDeltaEvent delta) {
            append(toolTexts, delta.getToolCallId(), delta.getDelta());
        } else if (event instanceof ToolResultEndEvent end) {
            events.accept(
                    "TOOL_RESULT",
                    new ToolResultEvent(
                            end.getToolCallId(),
                            end.getToolCallName(),
                            end.getState().getValue(),
                            take(toolTexts, end.getToolCallId())));
        } else if (event instanceof ModelCallEndEvent end && end.getUsage() != null) {
            // 只有模型明确报告的用量才累计；任务持久化层通过事件保留取消前的用量。
            inputTokens.addAndGet(end.getUsage().getInputTokens());
            outputTokens.addAndGet(end.getUsage().getOutputTokens());
            events.accept(
                    "USAGE",
                    new UsageEvent(
                            end.getUsage().getInputTokens(), end.getUsage().getOutputTokens()));
        } else if (event instanceof AgentResultEvent result) {
            answer.set(result.getResult().getTextContent());
        } else if (event.getType() == AgentEventType.EXCEED_MAX_ITERS) {
            limitReached.set(true);
        }
    }

    /** 构造执行汇总；没有最终回答时区分步数耗尽与空模型响应。 */
    public Result result() {
        if (answer.get() == null || answer.get().isBlank()) {
            throw new IllegalStateException(
                    limitReached.get() ? "STEP_LIMIT_REACHED" : "EMPTY_MODEL_RESPONSE");
        }
        return new Result(answer.get(), inputTokens.get(), outputTokens.get(), limitReached.get());
    }

    /** 按调用编号累计有限长度文本，避免并行工具串用缓冲或无限占用内存。 */
    private void append(Map<String, StringBuilder> values, String id, String delta) {
        if (delta == null) {
            return;
        }
        StringBuilder buffer = values.computeIfAbsent(id, key -> new StringBuilder());
        synchronized (buffer) {
            if (buffer.length() < RunLimits.TOOL_TEXT_LENGTH) {
                buffer.append(
                        delta,
                        0,
                        Math.min(delta.length(), RunLimits.TOOL_TEXT_LENGTH - buffer.length()));
            }
        }
    }

    /** 完成后移除缓冲，缺少流式片段时返回空文本。 */
    private String take(Map<String, StringBuilder> values, String id) {
        StringBuilder value = values.remove(id);
        return value == null ? "" : value.toString();
    }
}
