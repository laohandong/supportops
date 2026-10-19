package io.supportops.demo.controller;

import io.supportops.agent.service.RunService;
import io.supportops.api.vo.ErrorResponse;
import io.supportops.demo.dto.ScenarioRequest;
import io.supportops.demo.service.DemoService;
import io.supportops.demo.service.DemoScenarioService;
import io.supportops.demo.vo.DemoEnvironmentEvent;
import java.util.List;
import io.swagger.v3.oas.annotations.Parameter;
import org.springframework.web.bind.annotation.RequestParam;
import io.supportops.demo.vo.DemoState;
import io.supportops.demo.vo.SyncResult;
import io.supportops.remediation.controller.RemediationInput;
import io.supportops.remediation.dto.RemediationRequests.Command;
import io.supportops.remediation.dto.RemediationRequests.LegacyExecute;
import io.supportops.remediation.service.RemediationService;
import io.supportops.remediation.vo.RemediationViews.Detail;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 人工操作示例环境的 HTTP 入口，不向模型开放写入能力。 */
@RestController
@RequestMapping("/api/demo")
@Tag(name = "示例环境")
public class DemoController {
    private final DemoService demo;
    private final RunService runs;
    private final RemediationService remediations;
    private final RemediationInput remediationInput;
    private final DemoScenarioService scenarios;

    /** 注入示例环境服务与诊断准入控制。 */
    public DemoController(DemoService demo, RunService runs, RemediationService remediations, RemediationInput remediationInput,
            DemoScenarioService scenarios) {
        this.demo = demo;
        this.runs = runs;
        this.remediations = remediations;
        this.remediationInput = remediationInput;
        this.scenarios = scenarios;
    }

    /** 查询示例环境事实 */
    @Operation(
            operationId = "getDemoState",
            summary = "查询示例环境事实",
            description = "读取场景、版本、提供配置、生效配置和最近日志，不额外同步。示例环境重启后恢复默认升级故障。")
    @ApiResponse(
            responseCode = "200",
            description = "当前合成环境快照。",
            content = @Content(schema = @Schema(implementation = DemoState.class)))
    @GetMapping
    DemoState view() {
        return demo.operatorView();
    }

    /** 切换故障场景并发起同步 */
    @Operation(
            operationId = "selectDemoScenario",
            summary = "切换故障场景并发起同步",
            description = "清空示例日志，设置故障条件，并向自建下游发起一次真实 HTTP 同步。此人工操作在诊断运行期间被禁止。")
    @ApiResponse(
            responseCode = "200",
            description = "切换后的环境快照，业务状态见 lastRequest.httpStatus。",
            content = @Content(schema = @Schema(implementation = DemoState.class)))
    @ApiResponse(
            responseCode = "400",
            description = "INVALID_INPUT：场景缺失、标识未知或请求格式错误。",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(
            responseCode = "409",
            description = "DIAGNOSIS_IN_PROGRESS或ENVIRONMENT_OPERATION_IN_PROGRESS：诊断或人工操作尚未结束。",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "503", description = "SCENARIO_RESULT_NOT_PERSISTED：现场可能已改变，原STARTED审计保留，不能假定回滚。",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @PostMapping("/scenario")
    DemoState scenario(@RequestBody ScenarioRequest body) {
        return scenarios.configure(body.scenario() == null ? "" : body.scenario());
    }

    /** 无提案的场景切换也可在重启后按原实例审阅。 */
    @GetMapping("/environment-events")
    @Operation(summary = "读取场景变更审计", description = "ADMIN专用，只读跨实例历史；id大于after升序最多200条。STARTED表示结果尚未保存，不证明成功。")
    @ApiResponse(responseCode = "400", description = "INVALID_INPUT：after必须非负")
    @ApiResponse(responseCode = "403", description = "ADMIN_REQUIRED：普通用户不可访问")
    public List<DemoEnvironmentEvent> environmentEvents(@Parameter(description = "已读递增游标，默认0")
            @RequestParam(defaultValue = "0") long after) {
        return scenarios.events(after);
    }

    /** 人工发起一次订单同步 */
    @Operation(
            operationId = "synchronizeDemoOrder",
            summary = "人工发起一次订单同步",
            description =
                    "使用当前生效配置和示例凭据向自建下游发送真实 HTTP 请求并追加日志。接口返回 200 时，仍需检查 success、httpStatus 或 error"
                            + " 判断业务结果。")
    @ApiResponse(
            responseCode = "200",
            description = "本次同步业务结果。",
            content = @Content(schema = @Schema(implementation = SyncResult.class)))
    @ApiResponse(
            responseCode = "409",
            description = "DIAGNOSIS_IN_PROGRESS：诊断执行中不能人工同步。",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @PostMapping("/sync")
    SyncResult sync() {
        return runs.whenIdle(demo::synchronizeOrder);
    }

    /** 兼容路径须使用同一审批和幂等服务，旧空正文零写入。 */
    @Operation(
            operationId = "repairDemoConfiguration",
            summary = "经批准提案执行路径迁移",
            description = "必须携带planId、requestId、expectedVersion、planHash，与新入口共用执行幂等。缺提案409 REPAIR_APPROVAL_REQUIRED。")
    @ApiResponse(
            responseCode = "200",
            description = "持久化提案和执行事实，配置应用与验证分别记录。",
            content = @Content(schema = @Schema(implementation = Detail.class)))
    @ApiResponse(
            responseCode = "409",
            description = "DIAGNOSIS_IN_PROGRESS：当前诊断尚未结束。",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @PostMapping("/repair-config")
    Detail repair(@io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(schema = @Schema(implementation = LegacyExecute.class)))
            @RequestBody(required = false) String body) {
        JsonNode parsed = body == null ? null : remediationInput.parse(body);
        if (parsed == null || !parsed.hasNonNull("planId")) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "REPAIR_APPROVAL_REQUIRED");
        }
        LegacyExecute request = remediationInput.decodeCommand(body, LegacyExecute.class, "EXECUTE", parsed.path("planId").asText());
        return remediations.execute(request.planId(), new Command(request.requestId(), request.expectedVersion(), request.planHash(), null));
    }
}
