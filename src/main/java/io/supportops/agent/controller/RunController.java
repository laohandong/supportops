package io.supportops.agent.controller;

import io.supportops.agent.service.support.RunEventStream;
import io.supportops.agent.vo.RunStreamSnapshot;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.bind.annotation.RequestHeader;

import io.supportops.agent.dto.CreateDiagnosisRequest;
import io.supportops.agent.service.RunService;
import io.supportops.agent.vo.DiagnosisEvent;
import io.supportops.agent.vo.DiagnosisRun;
import io.supportops.api.vo.ErrorResponse;
import io.supportops.config.ProviderRegistry;
import io.supportops.config.vo.ServiceStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 诊断任务与运行状态的 HTTP 入口，只负责请求和响应适配。 */
@RestController
@RequestMapping("/api")
public class RunController {

    private final RunService runs;
    private final ProviderRegistry providers;
    private final RunEventStream stream;

    /** 注入诊断服务与模型路由查询组件。 */
    public RunController(RunService runs, ProviderRegistry providers,
            RunEventStream stream) {
        this.runs = runs;
        this.providers = providers;
        this.stream = stream;
    }

    /** 查询工作台运行状态 */
    @Tag(name = "运行状态")
    @Operation(
            operationId = "getServiceStatus",
            summary = "查询工作台运行状态",
            description = "只读取本地配置，不调用外部服务；configured 不代表密钥鉴权或网络连通性已验证。")
    @ApiResponse(
            responseCode = "200",
            description = "当前本地配置状态。",
            content = @Content(schema = @Schema(implementation = ServiceStatus.class)))
    @GetMapping("/status")
    ServiceStatus status() {
        ProviderRegistry.Endpoint model = providers.dialogue();
        ProviderRegistry.Endpoint embedding = providers.embedding();
        return new ServiceStatus(
                model.configured(),
                model.model(),
                embedding.configured(),
                embedding.model(),
                "local-demo",
                model.descriptor(),
                embedding.descriptor(),
                model.generationDescriptor());
    }

    /** 查询最近诊断任务 */
    @Tag(name = "诊断任务")
    @Operation(
            operationId = "listDiagnosisRuns",
            summary = "查询最近诊断任务",
            description = "按创建时间倒序返回最多 100 条未归档快照；没有记录时返回空数组。")
    @ApiResponse(responseCode = "200", description = "最近诊断任务列表。")
    @GetMapping("/runs")
    List<DiagnosisRun> list() {
        return runs.list();
    }

    /** 创建诊断任务 */
    @Tag(name = "诊断任务")
    @Operation(
            operationId = "createDiagnosisRun",
            summary = "创建诊断任务",
            description =
                    "提交后异步运行并返回快照，HTTP 200"
                        + " 不代表诊断完成。通过任务和事件接口继续轮询。实际执行会调用配置的模型和只读工具；未配置向量服务不阻止关键词诊断。请求返回不确定时不要自动重复提交。")
    @ApiResponse(responseCode = "200", description = "已受理的任务，可能仍在等待或执行，也可能已经结束。")
    @ApiResponse(
            responseCode = "400",
            description =
                    "INVALID_QUESTION：问题为空白或超长；INVALID_SESSION_ID：会话编号无效；INVALID_INPUT：请求体格式或类型错误。",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(
            responseCode = "409",
            description = "DIAGNOSIS_IN_PROGRESS：当前任务尚未完全结束；SESSION_ARCHIVED：该对话已归档，请新建会话。",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(
            responseCode = "503",
            description = "MODEL_NOT_CONFIGURED：对话路由未就绪，请先配置模型。",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @PostMapping("/runs")
    DiagnosisRun start(@RequestBody CreateDiagnosisRequest request) {
        return runs.start(request.question(), request.sessionId(), request.lexicalOnly());
    }

    /** 查询单个诊断任务 */
    @Tag(name = "诊断任务")
    @Operation(
            operationId = "getDiagnosisRun",
            summary = "查询单个诊断任务",
            description = "返回任务状态、结果、错误原因与累计用量；查询不会重新执行任务。")
    @ApiResponse(responseCode = "200", description = "诊断任务快照。")
    @ApiResponse(
            responseCode = "404",
            description = "RUN_NOT_FOUND：任务不存在。",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @GetMapping("/runs/{id}")
    DiagnosisRun get(
            @Parameter(
                            description = "创建诊断时返回的任务编号。",
                            example = "11111111-1111-4111-8111-111111111111")
                    @PathVariable
                    String id) {
        return runs.get(id);
    }

    /** 取消诊断任务 */
    @Tag(name = "诊断任务")
    @Operation(
            operationId = "cancelDiagnosisRun",
            summary = "取消诊断任务",
            description = "尝试取消未结束任务，保留已有证据和用量。已结束任务返回原状态；取消不能撤回已发送给模型的请求或已产生的费用。")
    @ApiResponse(responseCode = "200", description = "取消后的任务或原终态快照。")
    @ApiResponse(
            responseCode = "404",
            description = "RUN_NOT_FOUND：任务不存在。",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @PostMapping("/runs/{id}/cancel")
    DiagnosisRun cancel(
            @Parameter(description = "要取消的任务编号。", example = "11111111-1111-4111-8111-111111111111")
                    @PathVariable
                    String id) {
        return runs.cancel(id);
    }

    /** 逻辑归档任务所属整个对话，保留历史统计和证据。 */
    @Tag(name = "诊断任务")
    @Operation(operationId = "archiveDiagnosisSession", summary = "归档整个对话",
            description = "指定任意一轮任务编号，归档所属会话全部轮次；重复调用幂等。普通用户仅可归档本人对话，管理员可归档可见对话。归档后列表隐藏且禁止续聊，用量统计与历史回看保留。")
    @ApiResponse(responseCode = "200", description = "归档成功，无响应体。")
    @ApiResponse(responseCode = "404", description = "RUN_NOT_FOUND：任务不存在或不属于当前用户。",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "DIAGNOSIS_IN_PROGRESS：该对话仍有任务执行，请结束后归档。",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @PostMapping("/runs/{id}/archive")
    void archive(@Parameter(description = "需要归档的对话中任意一轮任务 UUID。", required = true)
            @PathVariable String id) {
        runs.archive(id);
    }

    /** 按已读事件编号续接流式响应，断开连接不会改变任务状态。 */
    @Tag(name = "诊断任务")
    @Operation(operationId = "streamDiagnosisRun", summary = "流式读取诊断回答与证据",
            description = "SSE snapshot 事件返回任务及增量事件；id 是已读游标。自动重连优先使用 Last-Event-ID。终态发送后关闭连接，断线不会重新执行任务。")
    @ApiResponse(responseCode = "200", description = "text/event-stream，data 为快照 JSON。",
            content = @Content(mediaType = "text/event-stream", schema = @Schema(implementation = RunStreamSnapshot.class)))
    @ApiResponse(responseCode = "404", description = "RUN_NOT_FOUND：任务不存在。",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @GetMapping(value = "/runs/{id}/stream", produces = "text/event-stream")
    SseEmitter stream(
            @Parameter(description = "诊断任务编号。") @PathVariable String id,
            @Parameter(description = "首次连接的已读事件编号，默认 0。") @RequestParam(defaultValue = "0") long after,
            @Parameter(description = "浏览器自动重连时的已读游标，优先于 after。")
            @RequestHeader(value = "Last-Event-ID", required = false) Long lastEventId) {
        return stream.open(id, Math.max(0, lastEventId == null ? after : lastEventId));
    }

    /** 增量读取诊断证据与事件 */
    @Tag(name = "诊断任务")
    @Operation(
            operationId = "listDiagnosisEvents",
            summary = "增量读取诊断证据与事件",
            description = "按 id 升序返回严格大于 after 的事件，没有新事件时返回空数组。轮询先读取任务状态，再读取事件，以获取终态前写入的最后证据。")
    @ApiResponse(responseCode = "200", description = "增量事件列表，content 结构由 kind 决定。")
    @ApiResponse(
            responseCode = "404",
            description = "RUN_NOT_FOUND：任务不存在。",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @GetMapping("/runs/{id}/events")
    List<DiagnosisEvent> events(
            @Parameter(description = "诊断任务编号。", example = "11111111-1111-4111-8111-111111111111")
                    @PathVariable
                    String id,
            @Parameter(description = "已读取的最后事件编号，首次传 0。", example = "0")
                    @RequestParam(defaultValue = "0")
                    long after) {
        return runs.events(id, after);
    }
}
