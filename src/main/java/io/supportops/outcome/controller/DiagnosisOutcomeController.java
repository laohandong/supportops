package io.supportops.outcome.controller;

import io.supportops.api.support.FixedCommandJson;
import io.supportops.api.support.FixedCommandJson.Rejected;
import io.supportops.outcome.dto.OutcomeRequests.Save;
import io.supportops.outcome.service.DiagnosisOutcomeService;
import io.supportops.outcome.vo.OutcomeViews.Detail;
import io.supportops.outcome.vo.OutcomeViews.History;
import io.supportops.outcome.vo.OutcomeViews.RequestResult;
import io.supportops.remediation.vo.RemediationViews;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
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
import java.util.List;

/** 诊断回填HTTP适配；普通用户只读写本人来源，关联修复证据仅管理员。 */
@RestController
@RequestMapping("/api/runs/{runId}/outcome")
@Tag(name = "诊断处理结果", description = "人员判断、修复执行和模型质量独立；POST必须带X-SupportOps-Request: 1，所有者或ADMIN访问")
@ApiResponse(responseCode = "400", description = "INVALID_INPUT：未知字段、枚举或超限字段；拒绝伪造身份和执行参数")
@ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED：未登录")
@ApiResponse(responseCode = "403", description = "ADMIN_REQUIRED：修复证据目录仅管理员；REQUEST_HEADER_REQUIRED：缺少固定请求头")
@ApiResponse(responseCode = "404", description = "RUN_NOT_FOUND：来源不存在或不属于当前用户；OUTCOME_REQUEST_NOT_FOUND：原请求未知，不能据此换键")
@ApiResponse(responseCode = "409", description = "OUTCOME_RUN_NOT_TERMINAL、OUTCOME_VERSION_CONFLICT、OUTCOME_EVIDENCE_INVALID、OUTCOME_POST_VERIFICATION_REQUIRED、OUTCOME_CHANGE_REASON_REQUIRED或REQUEST_CONTENT_CONFLICT")
@ApiResponse(responseCode = "413", description = "COMMAND_BODY_TOO_LARGE：UTF-8原正文超过256KiB")
public final class DiagnosisOutcomeController {
    private final DiagnosisOutcomeService service;
    private final FixedCommandJson input;

    /** 注入业务服务和严格输入解析，不访问数据库。 */
    public DiagnosisOutcomeController(DiagnosisOutcomeService service, FixedCommandJson input) {
        this.service = service;
        this.input = input;
    }

    /** 查询没有记录时返回明确空结果。 */
    @GetMapping
    @Operation(summary = "读取处理结果", description = "只读；outcome=null、version=0表示尚未回填，归档不影响授权读取")
    public Detail get(@Parameter(description = "来源诊断轮次UUID", required = true) @PathVariable String runId) {
        return service.get(runId);
    }

    /** 历史修订按数据库分页返回角色对应证据。 */
    @GetMapping("/history")
    @Operation(summary = "处理结果修订历史", description = "不可变历史，普通用户不会获得管理员回执")
    public History history(@Parameter(description = "来源诊断UUID", required = true) @PathVariable String runId,
            @Parameter(description = "0起始页，最大100000") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "每页1至100，默认20") @RequestParam(defaultValue = "20") int size) {
        return service.history(runId, page, size);
    }

    /** ADMIN目录只读取持久化关联事实。 */
    @GetMapping("/remediation-options")
    @Operation(summary = "关联修复证据目录", description = "仅ADMIN，按sourceRunId筛选，不执行、不核查、不发送业务验证HTTP")
    public List<RemediationViews.Detail> options(@Parameter(description = "来源诊断UUID", required = true) @PathVariable String runId,
            @Parameter(description = "0起始页") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "1至100") @RequestParam(defaultValue = "20") int size) {
        return service.remediationOptions(runId, page, size);
    }

    /** 固定请求只追加一次修订；输入拒绝只保留规范摘要。 */
    @PostMapping
    @Parameter(name = "X-SupportOps-Request", in = ParameterIn.HEADER, required = true, description = "写操作必填防跨站标识，固定值1", schema = @Schema(type = "string", allowableValues = "1"))
    @Operation(summary = "确认并保存处理结果", description = "终态才可回填；首次版本0；已解决需要处理后的人员观察或PASSED关联验证。未知响应查询或显式重试原键")
    public ResponseEntity<RequestResult> save(@Parameter(description = "来源诊断UUID", required = true) @PathVariable String runId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(schema = @Schema(implementation = Save.class))) @RequestBody String body) {
        RequestResult result;
        try {
            Save request = input.decode(body, Save.class);
            result = service.save(runId, request);
        } catch (IllegalArgumentException invalid) {
            Rejected rejected = input.rejected(body, "SAVE", runId);
            if (rejected == null) {
                throw invalid;
            }
            result = service.rejectInput(runId, rejected.requestId(), rejected.digest());
        }
        return ResponseEntity.status(result.httpStatus()).body(result);
    }

    /** 当前操作者且仍有来源访问权才能恢复原请求。 */
    @GetMapping("/requests/{requestId}")
    @Operation(summary = "查询原处理结果请求", description = "404、401或网络读取失败均不能证明未受理；回执固定引用原修订")
    public RequestResult request(@Parameter(description = "来源诊断UUID", required = true) @PathVariable String runId,
            @Parameter(description = "原请求UUID", required = true) @PathVariable String requestId) {
        return service.request(runId, requestId);
    }
}
