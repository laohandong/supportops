package io.supportops.remediation.config;

import io.swagger.v3.oas.models.media.ComposedSchema;
import io.swagger.v3.oas.models.media.Schema;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 保留共享record引用属性的中文语义，避免转换器折叠字段说明。 */
@Configuration
public class RemediationOpenApiConfiguration {
    /** 合法allOf组合保持模型引用与上下文描述，并固定命令白名单字段。 */
    @Bean
    OpenApiCustomizer remediationSchemas() {
        return api -> {
            referenced(api.getComponents().getSchemas().get("RemediationPreview"), "action", "RemediationAction", "固定动作和验证策略版本");
            referenced(api.getComponents().getSchemas().get("RemediationPreview"), "before", "RemediationSnapshot", "服务端当前脱敏现场");
            referenced(api.getComponents().getSchemas().get("RemediationRequestResult"), "detail", "RemediationDetail", "原请求关联的持久化详情；拒绝结果可为空");
            referenced(api.getComponents().getSchemas().get("RemediationReport"), "remediation", "RemediationDetail", "提案、决策和原执行证据");
            for (String name : new String[] {"RemediationCreate", "RemediationCommand", "RemediationPreviewRequest", "RemediationLegacyExecute"}) {
                Schema<?> schema = api.getComponents().getSchemas().get(name);
                if (schema != null) {
                    schema.setAdditionalProperties(false);
                }
            }
        };
    }

    /** 只修复存在的修复模型，不变更诊断或评测协议。 */
    private void referenced(Schema<?> parent, String field, String model, String description) {
        if (parent != null) {
            parent.addProperty(field, new ComposedSchema().description(description)
                    .addAllOfItem(new Schema<>().$ref("#/components/schemas/" + model)));
        }
    }
}
