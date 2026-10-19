package io.supportops.outcome.controller;

import io.supportops.api.support.FixedCommandJson;
import io.supportops.outcome.dto.EvaluationExportRequest;
import io.supportops.outcome.service.EvaluationExportService;
import io.supportops.outcome.service.EvaluationExportService.Receipt;
import io.supportops.source.vo.SourcePackage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 管理员手动导出来源，不调用评测服务。 */
@RestController
@Tag(name = "评测来源导出", description = "管理员专用；POST必须携带X-SupportOps-Request: 1；400非法输入，409版本或确认冲突")
public class EvaluationExportController {
    private final EvaluationExportService service;
    private final FixedCommandJson json;

    /** 注入来源导出和严格命令编解码。 */
    public EvaluationExportController(EvaluationExportService service, FixedCommandJson json) {
        this.service = service;
        this.json = json;
    }

    /** 原键重试返回相同包，响应未知时查询原回执。 */
    @PostMapping("/api/runs/{runId}/evaluation-exports")
    @Operation(
            summary = "确认并导出脱敏来源",
            description = "白名单文字；锁定expectedOutcomeRevisionId，201返回不可变包；409要求重新检查来源")
    public ResponseEntity<Receipt> create(
            @Parameter(description = "已结束诊断的唯一标识") @PathVariable String runId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            required = true,
                            content =
                                    @Content(
                                            schema =
                                                    @Schema(
                                                            implementation =
                                                                    EvaluationExportRequest.class)))
                    @RequestBody
                    String body) {
        EvaluationExportRequest input;
        try {
            input = json.decode(body, EvaluationExportRequest.class);
        } catch (IllegalArgumentException invalid) {
            Receipt rejected = service.reject(json.rejected(body, "EVALUATION_EXPORT", runId));
            return ResponseEntity.status(rejected.httpStatus()).body(rejected);
        }
        Receipt value = service.create(runId, input);
        return ResponseEntity.status(value.httpStatus()).body(value);
    }

    /** 已保存包脱离来源当前修订独立读取。 */
    @GetMapping("/api/evaluation-exports/{id}")
    @Operation(summary = "下载不可变脱敏来源包", description = "管理员读取已保存的脱敏包，来源后续修订不改变该包")
    public SourcePackage get(@Parameter(description = "导出包唯一标识") @PathVariable String id) {
        return service.get(id);
    }

    /** 查询当前管理员的固定请求结果。 */
    @GetMapping("/api/evaluation-exports/requests/{id}")
    @Operation(summary = "恢复来源导出固定请求", description = "仅当前管理员可读取自己提交的固定请求；不存在时返回404")
    public Receipt request(@Parameter(description = "当前管理员的固定请求唯一标识") @PathVariable String id) {
        return service.request(id);
    }
}
