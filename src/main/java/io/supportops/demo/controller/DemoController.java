package io.supportops.demo.controller;

import io.supportops.agent.service.RunService;
import io.supportops.api.vo.ErrorResponse;
import io.supportops.demo.dto.ScenarioRequest;
import io.supportops.demo.service.DemoService;
import io.supportops.demo.vo.DemoState;
import io.supportops.demo.vo.SyncResult;
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

    /** 注入示例环境服务与诊断准入控制。 */
    public DemoController(DemoService demo, RunService runs) {
        this.demo = demo;
        this.runs = runs;
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
            description = "DIAGNOSIS_IN_PROGRESS：当前诊断尚未结束。",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @PostMapping("/scenario")
    DemoState scenario(@RequestBody ScenarioRequest body) {
        return runs.whenIdle(() -> demo.configure(body.scenario() == null ? "" : body.scenario()));
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

    /** 人工应用 2.0 路径配置并验证 */
    @Operation(
            operationId = "repairDemoConfiguration",
            summary = "人工应用 2.0 路径配置并验证",
            description =
                    "将示例配置设为 sync.targetPath=/v2/orders，再同步验证。不修复下游停机或凭据错误，不能保证业务成功；不会对真实企业环境变更。")
    @ApiResponse(
            responseCode = "200",
            description = "应用配置后的同步结果，success 为 false 时需继续排查。",
            content = @Content(schema = @Schema(implementation = SyncResult.class)))
    @ApiResponse(
            responseCode = "409",
            description = "DIAGNOSIS_IN_PROGRESS：当前诊断尚未结束。",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @PostMapping("/repair-config")
    SyncResult repair() {
        return runs.whenIdle(demo::repairConfiguration);
    }
}
