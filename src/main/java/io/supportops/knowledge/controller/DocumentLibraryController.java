package io.supportops.knowledge.controller;

import io.supportops.knowledge.dto.ImportOptions;
import io.supportops.knowledge.service.DocumentLibraryService;
import io.supportops.knowledge.service.ExcelDataService;
import io.supportops.knowledge.service.KnowledgeTaskService;
import io.supportops.knowledge.vo.DocumentChunk;
import io.supportops.knowledge.vo.KnowledgeViews;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/** 原件、上传历史、修订及处理任务的 HTTP 入口。 */
@RestController
@RequestMapping("/api/documents")
@Tag(name = "文档版本与处理任务")
public class DocumentLibraryController {
    private final DocumentLibraryService library;
    private final KnowledgeTaskService tasks;
    private final ExcelDataService excel;

    /** 注入业务边界，控制器不读取持久化映射。 */
    public DocumentLibraryController(
            DocumentLibraryService library, KnowledgeTaskService tasks, ExcelDataService excel) {
        this.library = library;
        this.tasks = tasks;
        this.excel = excel;
    }

    /** 受理文件和不可变解析参数；客户端通过上传编号继续查询处理进度。 */
    @Operation(
            summary = "上传文件并自动处理",
            description =
                    "支持 MD、PDF、XLS、XLSX，单文件最大 20 MiB。先保存 MinIO 原件，再异步分片、索引或 Excel 导入。options 部分使用"
                            + " application/json；相同 requestKey 和内容复用上传结果，不同内容返回 409。")
    @ApiResponse(responseCode = "202", description = "原件已保存并受理处理，响应含上传编号、文档、修订及批次。")
    @PostMapping(value = "/uploads", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<KnowledgeViews.Upload> upload(
            @Parameter(description = "原文件，最大 20 MiB", required = true) @RequestPart
                    MultipartFile file,
            @Parameter(description = "标题，1 至 180 字符", required = true) @RequestParam String title,
            @Parameter(description = "适用产品版本或 *，最长 30 字符", required = true) @RequestParam
                    String version,
            @Parameter(description = "新增修订所属文档；新文档省略") @RequestParam(required = false)
                    String documentId,
            @Parameter(description = "修订说明，最长 1000 字符") @RequestParam(defaultValue = "")
                    String note,
            @Parameter(description = "幂等请求键，最长 100 字符") @RequestParam(required = false)
                    String requestKey,
            @Parameter(description = "JSON 解析参数；省略使用 100/10，Excel 自动表头与类型")
                    @RequestPart(required = false)
                    ImportOptions options)
            throws IOException {
        return ResponseEntity.accepted()
                .body(
                        library.submit(
                                file.getOriginalFilename(),
                                title,
                                version,
                                documentId,
                                note,
                                requestKey,
                                "UPLOAD",
                                file.getBytes(),
                                options == null ? ImportOptions.defaults() : options));
    }

    /** 上传记录包括重复和失败请求。 */
    @Operation(summary = "查询上传记录", description = "按受理时间倒序返回最近 200 条，不等同于文档可检索状态。")
    @ApiResponse(responseCode = "200", description = "操作成功，返回当前持久化结果；写入受理不代表后台处理已经完成。")
    @GetMapping("/uploads")
    public List<KnowledgeViews.Upload> uploads(
            @Parameter(description = "文档编号；省略查询最近上传") @RequestParam(required = false)
                    String documentId) {
        return library.uploads(documentId);
    }

    /** 根据上传编号查询最终分片或入表结果。 */
    @Operation(summary = "查询一次上传结果", description = "根据上传编号读取受理、重复或失败状态及错误码。")
    @ApiResponse(responseCode = "200", description = "操作成功，返回当前持久化结果；写入受理不代表后台处理已经完成。")
    @GetMapping("/uploads/{uploadId}")
    public KnowledgeViews.Upload upload(
            @Parameter(description = "上传编号") @PathVariable String uploadId) {
        return library.upload(uploadId);
    }

    /** 修订号与产品适用版本分别展示。 */
    @Operation(summary = "查询文档修订历史", description = "按修订号倒序返回标题、产品适用版本和原件状态。")
    @ApiResponse(responseCode = "200", description = "操作成功，返回当前持久化结果；写入受理不代表后台处理已经完成。")
    @GetMapping("/{id}/versions")
    public List<KnowledgeViews.Version> versions(
            @Parameter(description = "文档编号") @PathVariable String id) {
        return library.versions(id);
    }

    /** 每次重新分片保留独立批次。 */
    @Operation(summary = "查询解析与索引批次", description = "返回每次解析参数、分片或入表数量以及关键词和向量状态。")
    @ApiResponse(responseCode = "200", description = "操作成功，返回当前持久化结果；写入受理不代表后台处理已经完成。")
    @GetMapping("/{id}/batches")
    public List<KnowledgeViews.Batch> batches(
            @Parameter(description = "文档编号") @PathVariable String id) {
        return library.batches(id);
    }

    /** 查询确切分片批次，便于核对历史引用。 */
    @Operation(summary = "查询指定批次的分片", description = "只读取指定文档所属批次的已持久化原始片段；批次省略时选择最新目标。")
    @ApiResponse(responseCode = "200", description = "操作成功，返回当前持久化结果；写入受理不代表后台处理已经完成。")
    @GetMapping("/{id}/chunks")
    public List<DocumentChunk> chunks(
            @Parameter(description = "文档编号") @PathVariable String id,
            @Parameter(description = "批次编号；省略选择最新批次") @RequestParam(required = false)
                    String batchId) {
        return library.content(id, batchId);
    }

    /** 修改参数产生新批次，Excel 预览确认时将 preview 设为 false。 */
    @Operation(
            summary = "重新处理修订原件",
            description = "生成新批次并异步执行。旧发布结果保留至新批次成功；preview=true 只生成 Excel 表结构预览。")
    @ApiResponse(responseCode = "200", description = "操作成功，返回当前持久化结果；写入受理不代表后台处理已经完成。")
    @PostMapping("/versions/{versionId}/reprocess")
    public KnowledgeViews.Batch reprocess(
            @Parameter(description = "原件修订编号") @PathVariable String versionId,
            @RequestBody ImportOptions options) {
        return library.reprocess(versionId, options);
    }

    /** 返回私有原件的服务端流，禁止公共桶或长期 URL。 */
    @Operation(summary = "查看或下载指定修订原件", description = "PDF 默认内联显示，其余格式下载。原件缺失或文档已删除返回 404。")
    @ApiResponse(
            responseCode = "200",
            description = "指定修订的原文件流，PDF 内联，其余格式下载。",
            content = {
                @Content(
                        mediaType = "application/pdf",
                        schema =
                                @Schema(
                                        type = "string",
                                        format = "binary",
                                        description = "PDF 原件字节")),
                @Content(
                        mediaType = "application/octet-stream",
                        schema = @Schema(type = "string", format = "binary", description = "原文件字节"))
            })
    @GetMapping("/versions/{versionId}/original")
    public ResponseEntity<StreamingResponseBody> original(
            @Parameter(description = "修订编号") @PathVariable String versionId) {
        String filename = library.originalFilename(versionId);
        boolean pdf = filename.toLowerCase(Locale.ROOT).endsWith(".pdf");
        InputStream input = library.original(versionId);
        StreamingResponseBody body =
                output -> {
                    try (InputStream source = input) {
                        source.transferTo(output);
                    }
                };
        ContentDisposition disposition =
                (pdf ? ContentDisposition.inline() : ContentDisposition.attachment())
                        .filename(filename, StandardCharsets.UTF_8)
                        .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-Content-Type-Options", "nosniff")
                .contentType(pdf ? MediaType.APPLICATION_PDF : MediaType.APPLICATION_OCTET_STREAM)
                .body(body);
    }

    /** 按既有内容摘要恢复旧库缺失的原件，校验失败不修改修订。 */
    @Operation(
            summary = "补传历史修订原件",
            description = "只允许 MISSING 原件，SHA-256 必须与历史记录一致；最大 20 MiB。不会创建新修订。")
    @ApiResponse(responseCode = "200", description = "操作成功，返回当前持久化结果；写入受理不代表后台处理已经完成。")
    @PostMapping(
            value = "/versions/{versionId}/original",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public void restoreOriginal(
            @Parameter(description = "缺失原件的修订编号") @PathVariable String versionId,
            @Parameter(description = "与历史内容摘要一致的原文件") @RequestPart MultipartFile file)
            throws IOException {
        library.restoreOriginal(versionId, file.getBytes());
    }

    /** 任务进度不暴露租约令牌和服务商配置。 */
    @Operation(summary = "查询文档后台任务", description = "返回任务状态、执行阶段、尝试次数及下次补偿时间。")
    @ApiResponse(responseCode = "200", description = "操作成功，返回当前持久化结果；写入受理不代表后台处理已经完成。")
    @GetMapping("/{id}/tasks")
    public List<KnowledgeViews.Task> tasks(
            @Parameter(description = "文档编号") @PathVariable String id) {
        return tasks.list(id);
    }

    /** 保留自动和人工重试的每次执行历史。 */
    @Operation(summary = "查询任务尝试历史", description = "按时间顺序返回领取、失败、中断和成功历史，不返回执行令牌。")
    @ApiResponse(responseCode = "200", description = "操作成功，返回当前持久化结果；写入受理不代表后台处理已经完成。")
    @GetMapping("/tasks/{taskId}/attempts")
    public List<KnowledgeViews.Attempt> attempts(
            @Parameter(description = "任务编号") @PathVariable String taskId) {
        return tasks.attempts(taskId);
    }

    /** 人工补偿只接受失败或阻塞任务。 */
    @Operation(
            summary = "重试失败任务",
            description = "仅 FAILED、BLOCKED、RETRY_WAIT 可重试；成功、取消或已被替代的任务不可重试。")
    @ApiResponse(responseCode = "200", description = "操作成功，返回当前持久化结果；写入受理不代表后台处理已经完成。")
    @PostMapping("/tasks/{taskId}/retry")
    public void retry(@Parameter(description = "任务编号") @PathVariable String taskId) {
        tasks.retry(taskId);
    }

    /** 预览包括受控列名、原始表头、类型与样例。 */
    @Operation(summary = "查询批次 Excel 数据集", description = "返回 Sheet 结构、样例、受控列名和数据类型，隐藏物理表名。")
    @ApiResponse(responseCode = "200", description = "操作成功，返回当前持久化结果；写入受理不代表后台处理已经完成。")
    @GetMapping("/batches/{batchId}/datasets")
    public List<KnowledgeViews.Dataset> datasets(
            @Parameter(description = "批次编号") @PathVariable String batchId) {
        return excel.datasets(batchId);
    }

    /** 数据集目录供界面与 Agent 选择符合版本的数据源。 */
    @Operation(summary = "查询已发布 Excel 数据集", description = "仅返回与请求产品版本精确匹配或通用的当前已发布数据集。")
    @ApiResponse(responseCode = "200", description = "操作成功，返回当前持久化结果；写入受理不代表后台处理已经完成。")
    @GetMapping("/datasets")
    public List<KnowledgeViews.Dataset> applicable(
            @Parameter(description = "适用产品版本或 *") @RequestParam String version) {
        return excel.applicable(version);
    }

    /** 只读查询也使用 POST 承载结构化 SQL 请求，不允许任意数据源。 */
    @Operation(
            summary = "查询 Excel 关系数据",
            description =
                    "仅接受已发布数据集上的单表 SELECT，表名固定 data，列名使用目录中的 c_序号。禁止多语句、子查询、连接和任意函数；最多返回 200 行，超出标记"
                            + " truncated。")
    @ApiResponse(responseCode = "200", description = "操作成功，返回当前持久化结果；写入受理不代表后台处理已经完成。")
    @PostMapping("/datasets/{datasetId}/query")
    public KnowledgeViews.SqlResult query(
            @Parameter(description = "数据集编号") @PathVariable String datasetId,
            @RequestBody SqlQuery request) {
        return excel.query(datasetId, request.sql(), request.version());
    }

    /** SQL 输入不包含数据库、连接地址或物理表名。 */
    @Schema(description = "受限 Excel 查询请求")
    public record SqlQuery(
            @Schema(description = "产品版本，最长 30 字符", requiredMode = Schema.RequiredMode.REQUIRED)
                    String version,
            @Schema(
                            description = "单表 SELECT，最长 8000 字符",
                            requiredMode = Schema.RequiredMode.REQUIRED)
                    String sql) {}
}
