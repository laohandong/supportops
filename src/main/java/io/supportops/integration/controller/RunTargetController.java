package io.supportops.integration.controller;

import io.supportops.agent.service.RunService;
import io.supportops.integration.service.DiagnosticTargetService;
import io.supportops.integration.vo.DiagnosticScope;
import io.supportops.project.vo.TargetView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** 在本人历史授权边界内读取受理目标，避免同一会话在网页上换现场。 */
@RestController
@Tag(name = "诊断目标")
public class RunTargetController {
    private final RunService runs;
    private final DiagnosticTargetService targets;
    /** 先通过原任务读取权限，再读取不可变快照。 */
    public RunTargetController(RunService runs, DiagnosticTargetService targets) { this.runs = runs; this.targets = targets; }
    /** 旧记录明确保持推定标识，不补造完整企业身份。 */
    @GetMapping("/api/runs/{runId}/target")
    @Operation(summary = "查询诊断受理目标", description = "当前本人权限重新核对；CAPTURED为已捕获目标，LEGACY_INFERRED为历史推定，target为空")
    public Snapshot target(@io.swagger.v3.oas.annotations.Parameter(description = "诊断任务UUID，仅本主体可读") @PathVariable String runId) {
        runs.get(runId); DiagnosticScope value = targets.snapshot(runId);
        return new Snapshot(value == null ? "LEGACY_INFERRED" : "CAPTURED", value == null ? null : value.target());
    }
    /** 仅返回公开目标信息，不返回连接地址、凭据和发起密钥。 */
    @Schema(name = "RunTargetSnapshot", description = "受理目标公开快照，历史推定不伪造完整身份")
    public record Snapshot(@Schema(description = "类型：CAPTURED真实捕获、LEGACY_INFERRED历史推定") String kind,
            @Schema(description = "当时目标；旧记录为空") TargetView target) {}
}
