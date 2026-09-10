package io.supportops.config;

import io.supportops.api.vo.ErrorResponse;
import io.supportops.api.vo.LocalAccessError;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.media.ComposedSchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.tags.Tag;

import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/** 配置中文 OpenAPI 元信息、业务分组和通用错误模型。 */
@Configuration
public class OpenApiConfiguration {
    /** 创建当前工作台的文档元信息，服务地址随当前实例解析。 */
    @Bean
    OpenAPI supportOpsOpenApi() {
        return new OpenAPI()
                .info(
                        new Info()
                                .title("SupportOps 后端接口文档")
                                .version("0.1.0")
                                .description(
                                        "面向软件实施与技术支持人员的本地 Java Agent 工作台。\n\n"
                                            + "业务接口仅使用 GET 和 POST：GET 查询，POST"
                                            + " 执行新增、修改、删除及其他操作。接口按业务模块分组，包含中文字段说明、请求约束、响应及错误码。文档查看无需模型密钥，也不会调用外部模型。\n\n"
                                            + "建议流程：读取运行状态 → 配置对话模型 → 导入或上传文档 → 创建诊断 →"
                                            + " 轮询任务与增量事件。向量服务可选，未配置时可使用关键词检索。开始诊断和建立向量索引会调用所配置的外部服务；保存配置、确认记忆、删除文档和操作示例环境会改变本地状态。\n\n"
                                            + "当前仅支持本机访问及同源浏览器请求，使用 Cookie 账号认证；后台及文档仅管理员可用。模型密钥是配置数据，不是此 API"
                                            + " 的登录凭据。/mcp 使用独立的 Streamable HTTP JSON-RPC 协议，通过 MCP"
                                            + " 客户端初始化及 tools/list 获取工具说明，不属于下列 REST 接口。")
                                .license(
                                        new License()
                                                .name("Apache-2.0")
                                                .url(
                                                        "https://www.apache.org/licenses/LICENSE-2.0")))
                .servers(List.of(new Server().url("/").description("当前工作台所在服务")))
                .tags(
                        List.of(
                                tag("用户与登录", "登录、首次创建管理员和管理员创建用户。"),
                                tag("运行状态", "读取本地模型路由和工作台状态，不探测外部服务。"),
                                tag("诊断任务", "创建、查询、取消诊断，并增量读取执行证据。"),
                                tag("知识文档", "上传、分片、检索和管理按产品版本隔离的知识资料。"),
                                tag("文档版本与处理任务", "管理原件、上传历史、修订、异步补偿及 Excel 关系查询。"),
                                tag("项目记忆", "保存人员明确确认的项目约束，在后续诊断中使用。"),
                                tag("示例环境", "人工操作自建 OrderBridge 故障环境，不向 Agent 开放写入能力。"),
                                tag("模型配置", "分别管理对话和向量服务，立即生效，密钥只写入不回显。")));
    }

    /** 创建包含中文名称和职责说明的接口分组。 */
    private static Tag tag(String name, String description) {
        return new Tag().name(name).description(description);
    }

    /** 在文档生成阶段补齐错误响应及引用模型，防止未被控制器引用的模型被裁剪。 */
    @Bean
    OpenApiCustomizer commonApiResponses() {
        return api -> {
            // 在 springdoc 清理未引用模型之后补充错误模型，保证通用响应中的引用仍可解析。
            if (api.getComponents() == null) {
                api.setComponents(new Components());
            }
            for (Class<? extends Record> type :
                    List.of(ErrorResponse.class, LocalAccessError.class)) {
                ModelConverters.getInstance().read(type).forEach(api.getComponents()::addSchemas);
            }
            // 共享 record 引用的描述可能被转换器折叠；以合法的 allOf 保留属性语义。
            Schema<?> analysis = api.getComponents().getSchemas().get("UsageAnalysis");
            if (analysis != null) {
                analysis.addProperty(
                        "summary",
                        new ComposedSchema()
                                .description("所选范围内的用量总览")
                                .addAllOfItem(
                                        new Schema<>().$ref("#/components/schemas/UsageGroup")));
            }
            api.getPaths()
                    .values()
                    .forEach(
                            path ->
                                    path.readOperations()
                                            .forEach(
                                                    operation -> {
                                                        operation.addParametersItem(new Parameter()
                                                                .in("header").name("X-SupportOps-Request")
                                                                .description("写请求必填固定值 1，防止跨站表单提交。")
                                                                .schema(new StringSchema()._default("1")));
                                                        operation.getResponses().putIfAbsent("401",
                                                                error("未登录或会话失效。", "AUTHENTICATION_REQUIRED"));
                                                        operation
                                                                .getResponses()
                                                                .putIfAbsent(
                                                                        "400",
                                                                        error(
                                                                                "请求参数或 JSON"
                                                                                    + " 格式不正确。INVALID_INPUT：字段类型、必填参数或请求体无效。",
                                                                                "INVALID_INPUT"));
                                                        operation
                                                                .getResponses()
                                                                .putIfAbsent(
                                                                        "403",
                                                                        new ApiResponse()
                                                                                .description(
                                                                                        "ADMIN_REQUIRED：需要管理员；REQUEST_HEADER_REQUIRED：缺少写请求头；或请求不符合本地访问边界：Host"
                                                                                            + " 非回环地址、Origin"
                                                                                            + " 与当前服务不一致或跨站请求被拒绝。")
                                                                                .content(
                                                                                        new Content()
                                                                                                .addMediaType(
                                                                                                        "application/json",
                                                                                                        new MediaType()
                                                                                                                .schema(
                                                                                                                        new Schema<>()
                                                                                                                                .$ref(
                                                                                                                                        "#/components/schemas/LocalAccessError")))
                                                                                                .addMediaType(
                                                                                                        "text/html",
                                                                                                        new MediaType()
                                                                                                                .schema(
                                                                                                                        new StringSchema()
                                                                                                                                .description(
                                                                                                                                        "浏览器请求可能收到"
                                                                                                                                            + " HTML"
                                                                                                                                            + " 错误页面。")))));
                                                        operation
                                                                .getResponses()
                                                                .putIfAbsent(
                                                                        "405",
                                                                        error(
                                                                                "METHOD_NOT_ALLOWED：该路径不支持此请求方式，请按接口定义使用"
                                                                                    + " GET 或"
                                                                                    + " POST。Allow"
                                                                                    + " 响应头列出路径支持的方法。",
                                                                                "METHOD_NOT_ALLOWED"));
                                                        operation
                                                                .getResponses()
                                                                .putIfAbsent(
                                                                        "500",
                                                                        error(
                                                                                "INTERNAL_ERROR：操作失败。可能包括存储故障或已选择的外部向量服务调用失败；不会返回服务商原始错误信息或伪造成功结果。",
                                                                                "INTERNAL_ERROR"));
                                                    }));
        };
    }

    /** 构造引用统一错误模型的 JSON 响应说明，并提供与实际状态匹配的错误码示例。 */
    private static ApiResponse error(String description, String errorCode) {
        return new ApiResponse()
                .description(description)
                .content(
                        new Content()
                                .addMediaType(
                                        "application/json",
                                        new MediaType()
                                                .example(new ErrorResponse(errorCode))
                                                .schema(
                                                        new Schema<>()
                                                                .$ref(
                                                                        "#/components/schemas/ApiError"))));
    }
}
