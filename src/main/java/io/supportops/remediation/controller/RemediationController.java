package io.supportops.remediation.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.supportops.remediation.dto.RemediationRequests.Command;
import io.supportops.remediation.constant.RemediationContract;
import io.supportops.remediation.dto.RemediationRequests.Create;
import io.supportops.remediation.dto.RemediationRequests.PreviewRequest;
import io.supportops.remediation.service.RemediationService;
import io.supportops.remediation.vo.RemediationViews.Action;
import io.supportops.remediation.vo.RemediationViews.Detail;
import io.supportops.remediation.vo.RemediationViews.Event;
import io.supportops.remediation.vo.RemediationViews.Page;
import io.supportops.remediation.vo.RemediationViews.Preview;
import io.supportops.remediation.vo.RemediationViews.RequestResult;
import io.supportops.remediation.vo.RemediationViews.Report;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** 管理员 HTTP 适配，不访问 Mapper；目标与身份由服务端决定。 */
@RestController
@RequestMapping("/api/remediations")
@Tag(name = "受控修复中心", description = "ADMIN专用，单管理员审批，固定内置动作，不调用模型；POST必须带X-SupportOps-Request: 1。")
@ApiResponse(responseCode = "400", description = "INVALID_INPUT：字段未知、缺失或超限")
@ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED：未登录")
@ApiResponse(responseCode = "403", description = "ADMIN_REQUIRED、REQUEST_HEADER_REQUIRED或来源拒绝")
@ApiResponse(responseCode = "404", description = "提案、执行或当前账号原请求不存在")
@ApiResponse(responseCode = "409", description = "REMEDIATION_*：版本、摘要、请求、审批、环境、时效、未决或在途冲突")
@ApiResponse(responseCode = "503", description = "REMEDIATION_RESULT_NOT_PERSISTED：保留原请求并核查，不得新开执行")
public final class RemediationController {
    private final RemediationService service;
    private final RemediationInput input;
    private final ObjectMapper json;

    /** 注入服务与专用严格正文解析。 */
    public RemediationController(RemediationService service, RemediationInput input, ObjectMapper json) {
        this.service = service;
        this.input = input;
        this.json = json;
    }

    /** 固定目录不依赖模型。 */
    @GetMapping("/actions")
    @Operation(summary = "查看固定动作目录", description = "仅OrderBridge 2.0迁移，动作/验证版本固定，最大业务POST次数为1。")
    public List<Action> actions() {
        return service.actions();
    }

    /** 只读预检查。 */
    @PostMapping("/preview")
    @Operation(summary = "只读预检查", description = "读取现场与确定差异，不写配置、不发订单POST、不调用模型。")
    public Preview preview(@io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(schema = @Schema(implementation = PreviewRequest.class))) @RequestBody String body) {
        return service.preview(input.decode(body, PreviewRequest.class).actionId());
    }

    /** 冻结服务端采集内容。 */
    @PostMapping
    @Operation(summary = "创建不可变提案", description = "有效30分钟，来源诊断必须已结束。修改需新提案与审批；创建不执行。")
    public Detail create(@io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(schema = @Schema(implementation = Create.class))) @RequestBody String body) {
        return service.create(input.decodeCommand(body, Create.class, "CREATE", RemediationContract.TARGET));
    }

    /** 稳定时间和ID倒序分页。 */
    @GetMapping
    @Operation(summary = "分页查询提案", description = "page从0开始，size默认20最多100；status为提案生命周期。")
    public Page list(@Parameter(description = "可选状态", schema = @Schema(allowableValues = {"PENDING_APPROVAL", "APPROVED", "EXECUTING", "FINISHED", "REJECTED", "CANCELLED", "EXPIRED", "STALE"})) @RequestParam(required = false) String status,
            @Parameter(description = "页码，从0开始") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "页大小，1至100") @RequestParam(defaultValue = "20") int size) {
        return service.list(status, page, size);
    }

    /** 详情分开应用与验证事实。 */
    @GetMapping("/{id}")
    @Operation(summary = "查看提案与证据", description = "GET只读，能力提示不替代命令重新校验。")
    public Detail get(@Parameter(description = "提案UUID", required = true) @PathVariable String id) {
        return service.get(id);
    }

    /** 批准不自动执行。 */
    @PostMapping("/{id}/approve")
    @Operation(summary = "批准固定提案", description = "绑定版本与摘要，10分钟窗口不晚于提案期限；允许同人审批。")
    public Detail approve(@Parameter(description = "提案UUID", required = true) @PathVariable String id,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(schema = @Schema(implementation = Command.class))) @RequestBody String body) {
        return service.approve(id, input.decodeCommand(body, Command.class, "APPROVE", id));
    }

    /** 驳回理由必填。 */
    @PostMapping("/{id}/reject")
    @Operation(summary = "驳回提案", description = "reason必填，驳回后不能再次批准或执行。")
    public Detail reject(@Parameter(description = "提案UUID", required = true) @PathVariable String id,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(schema = @Schema(implementation = Command.class))) @RequestBody String body) {
        return service.reject(id, input.decodeCommand(body, Command.class, "REJECT", id));
    }

    /** 撤销未消费的审批。 */
    @PostMapping("/{id}/cancel")
    @Operation(summary = "撤销未执行提案", description = "旧状态与版本决定撤销和执行竞争的唯一成功者。")
    public Detail cancel(@Parameter(description = "提案UUID", required = true) @PathVariable String id,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(schema = @Schema(implementation = Command.class))) @RequestBody String body) {
        return service.cancel(id, input.decodeCommand(body, Command.class, "CANCEL", id));
    }

    /** 原请求优先查询并一次执行。 */
    @PostMapping("/{id}/execute")
    @Operation(summary = "执行已批准修复", description = "一次消费审批，应用和验证分别落库。共享诊断互斥，不重试、不自动回滚；响应丢失查询原requestId。")
    public Detail execute(@Parameter(description = "提案UUID", required = true) @PathVariable String id,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(schema = @Schema(implementation = Command.class))) @RequestBody String body) {
        return service.execute(id, input.decodeCommand(body, Command.class, "EXECUTE", id));
    }

    /** 当前账号原请求查询。 */
    @GetMapping("/requests/{requestId}")
    @Operation(summary = "查询原请求受理结果", description = "404或读取失败不证明未执行，保留固定请求；不生成替代写入。")
    public RequestResult request(@Parameter(description = "固定请求UUID", required = true) @PathVariable String requestId) {
        return service.request(requestId);
    }

    /** 游标审计追加读取。 */
    @GetMapping("/{id}/events")
    @Operation(summary = "读取审计事件", description = "返回id大于after的记录，升序最多200条。")
    public List<Event> events(@Parameter(description = "提案UUID", required = true) @PathVariable String id,
            @Parameter(description = "已读游标，默认0") @RequestParam(defaultValue = "0") long after) {
        return service.events(id, after);
    }

    /** 只核查原操作。 */
    @PostMapping("/executions/{id}/reconcile")
    @Operation(summary = "核查原执行", description = "原处理器须退出；只读原回执，无业务POST。缺证据保持UNKNOWN。")
    public Detail reconcile(@Parameter(description = "执行UUID", required = true) @PathVariable String id,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(schema = @Schema(implementation = Command.class))) @RequestBody String body) {
        return service.reconcile(id, input.decodeCommand(body, Command.class, "RECONCILE", id));
    }

    /** 人工结束不明不代表成功。 */
    @PostMapping("/executions/{id}/close-unknown")
    @Operation(summary = "人工结束未决记录", description = "reason必填，原处理器退出才允许。保留UNKNOWN事实，后续必须新提案和审批。")
    public Detail closeUnknown(@Parameter(description = "执行UUID", required = true) @PathVariable String id,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(schema = @Schema(implementation = Command.class))) @RequestBody String body) {
        return service.closeUnknown(id, input.decodeCommand(body, Command.class, "CLOSE_UNKNOWN", id));
    }

    /** 导出持久化事实，无新的现场请求。 */
    @GetMapping("/{id}/report")
    @Operation(summary = "导出修复报告", description = "format仅json或markdown，包含审批、原证据和审计，标记合成环境。")
    @ApiResponse(responseCode = "200", description = "已持久化证据报告，合成环境分类；不会触发新的现场调用。", content = {
            @Content(mediaType = "application/json", schema = @Schema(implementation = Report.class)),
            @Content(mediaType = "text/markdown", schema = @Schema(type = "string", description = "安全代码块中的原证据和合成环境说明"))})
    @ApiResponse(responseCode = "413", description = "REMEDIATION_REPORT_TOO_LARGE：超过1MiB")
    public ResponseEntity<String> report(@Parameter(description = "提案UUID", required = true) @PathVariable String id,
            @Parameter(description = "报告格式", schema = @Schema(allowableValues = {"json", "markdown"})) @RequestParam(defaultValue = "json") String format) throws Exception {
        Report report = service.report(id);
        String encoded = json.writerWithDefaultPrettyPrinter().writeValueAsString(report);
        if (encoded.getBytes(StandardCharsets.UTF_8).length > 1_048_576) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "REMEDIATION_REPORT_TOO_LARGE");
        }
        if ("json".equals(format)) {
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(encoded);
        }
        if (!"markdown".equals(format)) {
            throw new IllegalArgumentException("INVALID_INPUT");
        }
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/markdown;charset=UTF-8"))
                .body("# 受控修复报告\n\n内置合成OrderBridge回环HTTP，非企业系统验收。\n\n~~~~~~json\n"
                        + encoded.replace("~", "\\u007e") + "\n~~~~~~\n");
    }
}
