package io.supportops.memory.controller;

import io.supportops.api.vo.ErrorResponse;
import io.supportops.memory.dto.CreateProjectMemoryRequest;
import io.supportops.memory.service.MemoryService;
import io.supportops.memory.vo.ProjectMemory;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 人员确认项目记忆的 HTTP 入口，模型没有对应写入工具。 */
@RestController
@RequestMapping("/api/memories")
@Tag(name = "项目记忆")
public class MemoryController {

    private final MemoryService memories;

    /** 注入项目记忆业务服务。 */
    public MemoryController(MemoryService memories) {
        this.memories = memories;
    }

    /** 查询已确认的项目约束 */
    @Operation(
            operationId = "listProjectMemories",
            summary = "查询已确认的项目约束",
            description = "按创建时间正序返回项目记忆；没有记录时返回空数组。")
    @ApiResponse(responseCode = "200", description = "已确认约束列表。")
    @GetMapping
    List<ProjectMemory> list() {
        return memories.list();
    }

    /** 保存已确认的项目约束 */
    @Operation(
            operationId = "createProjectMemory",
            summary = "保存已确认的项目约束",
            description = "最多保存 30 条。新诊断开始时加载，不改写运行中或已结束任务的上下文。Agent 无写入记忆工具。")
    @ApiResponse(responseCode = "200", description = "已保存的约束。")
    @ApiResponse(
            responseCode = "400",
            description =
                    "EXPLICIT_CONFIRMATION_REQUIRED：未明确确认；INVALID_MEMORY：内容为空白或超长；INVALID_INPUT：请求体格式错误。",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(
            responseCode = "409",
            description = "MEMORY_LIMIT_REACHED：已达到 30 条上限。",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @PostMapping
    ProjectMemory add(@RequestBody CreateProjectMemoryRequest request) {
        return memories.add(request.content(), request.confirmed());
    }

    /** 删除项目约束 */
    @Operation(
            operationId = "deleteProjectMemory",
            summary = "删除项目约束",
            description = "删除后新任务不再加载，已有上下文和历史诊断记录保留；本接口不提供恢复功能。")
    @ApiResponse(responseCode = "200", description = "删除成功，响应体为空。", content = @Content)
    @ApiResponse(
            responseCode = "404",
            description = "MEMORY_NOT_FOUND：约束不存在或已删除。",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @PostMapping("/{id}/delete")
    void delete(
            @Parameter(description = "要删除的约束编号。", example = "11111111-1111-4111-8111-111111111111")
                    @PathVariable
                    String id) {
        memories.delete(id);
    }
}
