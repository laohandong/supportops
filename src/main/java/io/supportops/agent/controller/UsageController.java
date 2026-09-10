package io.supportops.agent.controller;

import io.supportops.agent.service.UsageService;
import io.supportops.agent.vo.DiagnosisRun;
import io.supportops.agent.vo.UsageAnalysis;
import io.supportops.api.vo.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 后台用量分析和会话回看，沿用本地访问边界。 */
@RestController
@RequestMapping("/api/usage")
@Tag(name = "用量分析")
public class UsageController {
    private final UsageService usage;

    /** 注入只读分析服务。 */
    public UsageController(UsageService usage) {
        this.usage = usage;
    }

    /** 查询诊断创建时间范围内的已报告用量。 */
    @GetMapping
    @Operation(
            summary = "查询分时用量与会话 Top 20",
            description = "UTC 日期含首尾，最多 90 天；按诊断创建时间归属，非供应商账单。包含失败、取消及执行中的已报告用量；未报告不等于零消耗。不含向量用量。")
    @ApiResponse(responseCode = "200", description = "总览、补零趋势和排行")
    @ApiResponse(
            responseCode = "400",
            description = "INVALID_INPUT：日期、范围或粒度无效",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public UsageAnalysis analyze(
            @Parameter(description = "开始日期 yyyy-MM-dd，UTC", required = true) @RequestParam
                    String from,
            @Parameter(description = "结束日期 yyyy-MM-dd，UTC，含当天", required = true) @RequestParam
                    String to,
            @Parameter(description = "hour 小时或 day 天") @RequestParam(defaultValue = "day")
                    String granularity) {
        return usage.analyze(from, to, granularity);
    }

    /** 分页读取会话问答和每轮用量。 */
    @GetMapping("/sessions/{sessionId}")
    @Operation(
            summary = "回看会话问答",
            description = "按创建时间、任务 ID 升序，每页最多 50 轮；包含统计范围之外的历史。空页表示无更多记录；未完成的回答片段可通过任务事件接口读取。")
    @ApiResponse(responseCode = "200", description = "问答页，不存在时返回空数组")
    @ApiResponse(
            responseCode = "400",
            description = "INVALID_INPUT：会话 ID 或 offset 无效",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public List<DiagnosisRun> session(
            @Parameter(description = "会话 UUID", required = true) @PathVariable String sessionId,
            @Parameter(description = "跳过轮数，0 至 1000000") @RequestParam(defaultValue = "0")
                    int offset) {
        return usage.session(sessionId, offset);
    }
}
