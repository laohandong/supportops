package io.supportops.config.controller;

import io.supportops.agent.service.RunService;
import io.supportops.api.vo.ErrorResponse;
import io.supportops.config.ModelCatalogClient;
import io.supportops.config.ProviderRegistry;
import io.supportops.config.dto.UpdateModelSettingsRequest;
import io.supportops.config.vo.AvailableModels;
import io.supportops.config.vo.ModelSettingsView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 模型配置的 HTTP 入口，响应不缓存且不返回密钥。 */
@RestController
@RequestMapping("/api/model-settings")
@Tag(name = "模型配置")
public class ModelSettingsController {
    private final ProviderRegistry providers;
    private final RunService runs;
    private final ModelCatalogClient catalog;

    /** 注入配置管理与诊断准入边界。 */
    public ModelSettingsController(
            ProviderRegistry providers, RunService runs, ModelCatalogClient catalog) {
        this.providers = providers;
        this.runs = runs;
        this.catalog = catalog;
    }

    /** 读取模型配置与服务商预设 */
    @Operation(
            operationId = "getModelSettings",
            summary = "读取模型配置与服务商预设",
            description = "返回对话与向量服务的当前配置、来源、密钥是否存在和 revision。密钥不回显，不探测服务商。保存或恢复时需传回本次 revision。")
    @ApiResponse(
            responseCode = "200",
            description = "模型配置视图；Cache-Control 为 no-store。",
            content = @Content(schema = @Schema(implementation = ModelSettingsView.class)))
    @GetMapping
    public ResponseEntity<ModelSettingsView> read() {
        return safe(providers.settingsView());
    }

    /** 保存一个角色的模型配置 */
    @Operation(
            operationId = "saveModelSettings",
            summary = "保存一个角色的模型配置",
            description =
                    "对话和向量分别保存，成功后立即生效并持久化，优先于文件和环境变量。密钥留空可保存未就绪配置；不会在保存时验证连通性。切换服务商或基础地址不沿用旧密钥。embedding"
                        + " 可选 disabled。诊断运行期间不能保存。密钥存储在本机未加密配置文件。",
            requestBody =
                    @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description =
                                    "一个角色的配置和最新 revision。示例不包含密钥；需要更换时自行填写 apiKey 并选择 replace。",
                            content =
                                    @Content(
                                            schema =
                                                    @Schema(
                                                            implementation =
                                                                    UpdateModelSettingsRequest
                                                                            .class),
                                            examples =
                                                    @ExampleObject(
                                                            name = "保存对话配置",
                                                            description =
                                                                    "先读取配置并替换"
                                                                        + " revision；示例只保存参数，不填写新密钥。",
                                                            value =
                                                                    "{\"provider\":\"deepseek\",\"model\":\"deepseek-v4-flash\",\"baseUrl\":\"https://api.deepseek.com\",\"keyAction\":\"keep\",\"revision\":\"从读取接口复制当前"
                                                                        + " revision\"}"))))
    @ApiResponse(
            responseCode = "200",
            description = "保存后的完整配置视图及新 revision，不返回密钥。",
            content = @Content(schema = @Schema(implementation = ModelSettingsView.class)))
    @ApiResponse(
            responseCode = "400",
            description =
                    "MODEL_SETTINGS_INVALID_ROLE/PROVIDER/KEY_ACTION/INPUT：角色、服务商、密钥动作或字段无效；MODEL_"
                        + " 或 EMBEDDING_ 前缀的"
                        + " NAME_REQUIRED、INVALID_BASE_URL、UNSUPPORTED_PROTOCOL、UNSUPPORTED_PROVIDER、INVALID_CREDENTIAL_REFERENCE、CREDENTIAL_BINDING_REQUIRED、INVALID_OPTIONS"
                        + " 表示对应路由校验失败；INVALID_INPUT 表示请求格式错误。",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(
            responseCode = "409",
            description =
                    "DIAGNOSIS_IN_PROGRESS：诊断尚未结束；MODEL_SETTINGS_CHANGED：revision 已过期，请重新读取后合并修改。",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(
            responseCode = "500",
            description = "MODEL_SETTINGS_SAVE_FAILED：本机持久化失败，原配置继续有效；INTERNAL_ERROR：其他内部故障。",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @PostMapping("/{role}")
    public ResponseEntity<ModelSettingsView> save(
            @Parameter(
                            description = "要保存的配置角色：model 对话，embedding 向量。",
                            schema = @Schema(allowableValues = {"model", "embedding"}),
                            example = "model")
                    @PathVariable
                    String role,
            @RequestBody UpdateModelSettingsRequest update) {
        return safe(runs.whenIdle(() -> providers.saveSettings(role, update)));
    }

    /** 使用当前草稿获取目录，既不保存配置，也不调用模型生成。 */
    @Operation(
            operationId = "discoverModels",
            summary = "获取可用模型",
            description =
                    "使用草稿地址和凭据请求服务商 GET /models。密钥 keep 仅限同服务商同地址；模型名可空。revision"
                        + " 必须最新。目录不代表模型能力，不跟随重定向。")
    @ApiResponse(
            responseCode = "200",
            description = "模型 ID 列表，可能为空。",
            content = @Content(schema = @Schema(implementation = AvailableModels.class)))
    @ApiResponse(
            responseCode = "400",
            description = "角色、地址、凭据动作或路由无效；未配置密钥。",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(
            responseCode = "409",
            description = "MODEL_SETTINGS_CHANGED：请重新读取配置。",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(
            responseCode = "502",
            description =
                    "MODEL_CATALOG_UNAUTHORIZED、UNSUPPORTED、RATE_LIMITED、INVALID_RESPONSE 或"
                        + " FAILED：鉴权、目录支持、限流、协议或连接失败。",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(
            responseCode = "504",
            description = "MODEL_CATALOG_TIMEOUT：服务商超时。",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @PostMapping("/{role}/models")
    public ResponseEntity<AvailableModels> discover(
            @Parameter(description = "model 对话或 embedding 向量", required = true) @PathVariable
                    String role,
            @RequestBody UpdateModelSettingsRequest draft) {
        return ResponseEntity.ok().body(catalog.fetch(providers.discoveryEndpoint(role, draft)));
    }

    /** 恢复一个角色的文件配置 */
    @Operation(
            operationId = "resetModelSettings",
            summary = "恢复一个角色的文件配置",
            description =
                    "删除指定角色的界面覆盖值及其中的密钥，重新使用当前进程的文件和环境变量配置。不会重新读取已经修改但尚未重启的 YAML，不影响另一角色；诊断期间禁止恢复。")
    @ApiResponse(
            responseCode = "200",
            description = "恢复后的完整配置及新 revision。",
            content = @Content(schema = @Schema(implementation = ModelSettingsView.class)))
    @ApiResponse(
            responseCode = "400",
            description = "MODEL_SETTINGS_INVALID_ROLE：角色无效；INVALID_INPUT：缺少 revision 或参数格式不正确。",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(
            responseCode = "409",
            description = "DIAGNOSIS_IN_PROGRESS：诊断未结束；MODEL_SETTINGS_CHANGED：revision 已过期。",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(
            responseCode = "500",
            description = "MODEL_SETTINGS_SAVE_FAILED：持久化失败，原配置继续有效；INTERNAL_ERROR：其他内部故障。",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @PostMapping("/{role}/reset")
    public ResponseEntity<ModelSettingsView> reset(
            @Parameter(
                            description = "要恢复的角色：model 对话，embedding 向量。",
                            schema = @Schema(allowableValues = {"model", "embedding"}),
                            example = "model")
                    @PathVariable
                    String role,
            @Parameter(description = "读取配置时返回的最新版本标记。", example = "从读取接口复制当前 revision")
                    @RequestParam
                    String revision) {
        return safe(runs.whenIdle(() -> providers.resetSettings(role, revision)));
    }

    /** 为配置响应设置禁止缓存，避免浏览器持久保存角色配置。 */
    private ResponseEntity<ModelSettingsView> safe(ModelSettingsView value) {
        return ResponseEntity.ok().body(value);
    }
}
