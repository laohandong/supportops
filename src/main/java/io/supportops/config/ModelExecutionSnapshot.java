package io.supportops.config;

import io.supportops.config.ProviderRegistry.Endpoint;
import io.supportops.config.vo.GenerationOptions;
import io.supportops.config.vo.ModelRoute;
import io.swagger.v3.oas.annotations.media.Schema;

/** 配置模块内部捕获的执行值；API 只能使用公开描述，密钥不进入业务持久化。 */
public final class ModelExecutionSnapshot {
    private final String revision;
    private final Endpoint dialogue;
    private final Endpoint embedding;

    /** 固定同一配置修订下的两个角色，不在实验期间重新解析路由。 */
    ModelExecutionSnapshot(String revision, Endpoint dialogue, Endpoint embedding) {
        this.revision = revision;
        this.dialogue = dialogue;
        this.embedding = embedding;
    }

    /** 返回用于预检查冲突检测的修订标记。 */
    public String revision() {
        return revision;
    }

    /** 读取仅供受限执行清单使用的对话端点。 */
    public Endpoint dialogue() {
        return dialogue;
    }

    /** 读取仅供受限执行清单使用的向量端点。 */
    public Endpoint embedding() {
        return embedding;
    }

    /** 返回安全公开描述；不存在的角色为空，不携带凭据。 */
    public Description description() {
        return new Description(revision, dialogue == null ? null : dialogue.descriptor(),
                embedding == null ? null : embedding.descriptor(),
                dialogue == null ? null : dialogue.generationDescriptor());
    }

    /** 固定角色描述与生成参数；配置完整仅说明本地值存在。 */
    @Schema(name="EvaluationModelSnapshot", description="冻结时的配置修订、有效角色与生成参数，无凭据")
    public record Description(@Schema(description="配置修订标记，仅用于冲突核对") String revision,
            @Schema(description="对话有效路由，来源检查为空", schemaResolution=Schema.SchemaResolution.ALL_OF) ModelRoute model,
            @Schema(description="向量有效路由，不用向量时为空", schemaResolution=Schema.SchemaResolution.ALL_OF) ModelRoute embedding,
            @Schema(description="实际生成参数，来源检查为空", schemaResolution=Schema.SchemaResolution.ALL_OF) GenerationOptions generation) {}

    /** 对象日志始终只输出公开描述。 */
    @Override
    public String toString() {
        return description().toString();
    }
}
