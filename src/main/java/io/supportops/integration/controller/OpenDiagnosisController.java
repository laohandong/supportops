package io.supportops.integration.controller;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.supportops.integration.dto.OpenDiagnosisCommand;
import io.supportops.integration.service.OpenDiagnosisService;
import io.supportops.integration.service.support.CurrentApplication;
import io.supportops.integration.service.support.IntegrationInputs;
import io.supportops.integration.vo.OpenViews;
import io.supportops.project.vo.TargetView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 独立应用 Bearer 的版本化只读诊断 API。 */
@RestController
@RequestMapping("/open-api/v1")
@Tag(name = "开放诊断 API V1")
@SecurityRequirement(name = "ApplicationBearer")
@ApiResponse(responseCode = "401", description = "应用密钥缺失、失效、过期或已撤销；Cookie 不能替代")
@ApiResponse(responseCode = "403", description = "应用权限或环境授权不足")
@ApiResponse(responseCode = "404", description = "本应用无该请求、任务或证据")
@ApiResponse(responseCode = "429", description = "速率、应用活动任务或全局环境容量不足；原请求未受理时仍按固定请求恢复",
        headers = @Header(name = "Retry-After", description = "建议等待秒数，当前60秒", schema = @Schema(type = "integer", example = "60")))
public class OpenDiagnosisController {
    private final OpenDiagnosisService diagnoses;
    private final ObjectMapper strict;
    /** 创建独立严格 JSON 解码器，保留其他旧接口的解析行为。 */
    public OpenDiagnosisController(OpenDiagnosisService diagnoses, ObjectMapper json) {
        this.diagnoses = diagnoses;
        this.strict = json.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);
    }
    /** 查询应用明确获准使用的可用目标。 */
    @GetMapping("/environments") @Operation(summary = "查询授权环境", description = "要求 READ；不返回地址和凭据")
    public List<TargetView> environments() { return diagnoses.environments(CurrentApplication.get()); }
    /** 首次受理202，同键同参重放200，不代表执行完成。 */
    @PostMapping("/diagnoses") @Operation(summary = "受理独立诊断", description = "固定 requestId；问题1至6000字符；unknown字段拒绝。首次202、重放200、异参409；响应丢失查询原请求")
    @ApiResponse(responseCode = "202", description = "run、目标、固定回执已提交；任务异步执行")
    @ApiResponse(responseCode = "409", description = "同键异参或目标未启用")
    public ResponseEntity<OpenViews.Receipt> submit(@RequestBody
            @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
                    content = @io.swagger.v3.oas.annotations.media.Content(schema = @io.swagger.v3.oas.annotations.media.Schema(implementation = OpenDiagnosisCommand.class)))
            JsonNode input) {
        OpenDiagnosisCommand command;
        try {
            if (!input.isObject()) { throw new IllegalArgumentException(); }
            ObjectNode normalized = input.deepCopy();
            if (!normalized.has("lexicalOnly")) { normalized.put("lexicalOnly", false); }
            command = strict.treeToValue(normalized, OpenDiagnosisCommand.class);
        }
        catch (Exception exception) { throw IntegrationInputs.error(400, "INVALID_INPUT"); }
        OpenViews.Receipt receipt = diagnoses.submit(CurrentApplication.get(), command);
        return ResponseEntity.status(receipt.replayed() ? 200 : 202).body(receipt);
    }
    /** 查询响应不确定的原固定请求。 */
    @GetMapping("/requests/{requestId}") @Operation(summary = "查询受理回执", description = "404后仅重发同一 requestId 和请求体；查询失败维持 UNKNOWN，不产生新键")
    public OpenViews.Receipt request(@io.swagger.v3.oas.annotations.Parameter(description = "固定请求UUID") @PathVariable String requestId) { return diagnoses.request(CurrentApplication.get(), requestId); }
    /** 查询本应用任务。 */
    @GetMapping("/diagnoses/{runId}") @Operation(summary = "查询诊断任务", description = "本应用所有权和当前环境授权重新核对；读取不重新执行")
    public OpenViews.Diagnosis diagnosis(@io.swagger.v3.oas.annotations.Parameter(description = "诊断任务UUID，仅本主体可读") @PathVariable String runId) { return diagnoses.diagnosis(CurrentApplication.get(), runId); }
    /** 游标分页读取安全事件。 */
    @GetMapping("/diagnoses/{runId}/events") @Operation(summary = "轮询诊断事件", description = "after非负，limit默认100、1至200；nextAfter继续；页小于1MiB，事件大于16KiB返回摘要")
    public OpenViews.EventPage events(@io.swagger.v3.oas.annotations.Parameter(description = "诊断任务UUID，仅本主体可读") @PathVariable String runId, @io.swagger.v3.oas.annotations.Parameter(description = "已读事件游标，非负整数") @RequestParam(defaultValue = "0") long after,
            @io.swagger.v3.oas.annotations.Parameter(description = "每页条数，1至200") @RequestParam(defaultValue = "100") int limit) { return diagnoses.events(CurrentApplication.get(), runId, after, limit); }
    /** 读取安全事实快照。 */
    @GetMapping("/diagnoses/{runId}/evidence/{eventId}") @Operation(summary = "读取持久化证据", description = "仅现场事实、资料及SQL结果；最多2MiB，超限413；不返回原始模型请求或重读现场")
    public OpenViews.Evidence evidence(@io.swagger.v3.oas.annotations.Parameter(description = "诊断任务UUID，仅本主体可读") @PathVariable String runId, @io.swagger.v3.oas.annotations.Parameter(description = "本任务安全事实事件编号，正整数") @PathVariable long eventId) { return diagnoses.evidence(CurrentApplication.get(), runId, eventId); }
    /** 终态结果不自动声明问题解决。 */
    @GetMapping("/diagnoses/{runId}/result") @Operation(summary = "查询终态结果", description = "执行中409 RESULT_NOT_READY；失败、取消和中断等终态也200，状态保留真实事实")
    public OpenViews.Result result(@io.swagger.v3.oas.annotations.Parameter(description = "诊断任务UUID，仅本主体可读") @PathVariable String runId) { return diagnoses.result(CurrentApplication.get(), runId); }
    /** 对本应用任务请求取消。 */
    @PostMapping("/diagnoses/{runId}/cancel") @Operation(summary = "取消诊断", description = "要求 CANCEL；重复取消保持原终态；资源释放前仍占容量")
    public OpenViews.Diagnosis cancel(@io.swagger.v3.oas.annotations.Parameter(description = "诊断任务UUID，仅本主体可读") @PathVariable String runId) { return diagnoses.cancel(CurrentApplication.get(), runId); }
}
