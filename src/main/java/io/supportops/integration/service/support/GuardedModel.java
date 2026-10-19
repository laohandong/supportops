package io.supportops.integration.service.support;

import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import java.util.List;
import reactor.core.publisher.Flux;

/** 在每次真实模型流订阅前复核权限，不依赖晚于发送的观察事件。 */
public final class GuardedModel implements Model {
    private final Model delegate;
    private final Runnable authorization;
    /** 绑定本次运行的模型快照和权限检查。 */
    public GuardedModel(Model delegate, Runnable authorization) { this.delegate = delegate; this.authorization = authorization; }
    /** 权限失败不创建模型网络请求，也不执行自动回退。 */
    @Override public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        return Flux.defer(() -> { authorization.run(); return delegate.stream(messages, tools, options); });
    }
    /** 保持模型名称。 */
    @Override public String getModelName() { return delegate.getModelName(); }
    /** 保持结构化输出能力。 */
    @Override public boolean supportsNativeStructuredOutput() { return delegate.supportsNativeStructuredOutput(); }
    /** 保持工具与结构化输出能力。 */
    @Override public boolean supportsNativeStructuredOutputWithTools() { return delegate.supportsNativeStructuredOutputWithTools(); }
    /** 保持模型上下文窗口描述。 */
    @Override public int getContextWindowSize() { return delegate.getContextWindowSize(); }
}
