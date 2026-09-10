package io.supportops.knowledge.controller;

import io.supportops.knowledge.constant.KnowledgeLimits;
import io.supportops.knowledge.dto.ImportOptions;
import io.supportops.knowledge.dto.UploadDocumentForm;
import io.supportops.knowledge.service.DocumentLibraryService;
import io.supportops.knowledge.service.KnowledgeService;
import io.supportops.knowledge.vo.DocumentContent;
import io.supportops.knowledge.vo.KnowledgeDocument;
import io.supportops.knowledge.vo.KnowledgeSearchResult;
import io.supportops.knowledge.vo.KnowledgeViews;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/** 兼容既有文档 URL，新增上传流水与修订接口由 DocumentLibraryController 提供。 */
@RestController
@RequestMapping("/api/documents")
@Tag(name = "知识文档")
public class KnowledgeController {
    private final KnowledgeService knowledge;
    private final DocumentLibraryService library;

    /** 注入文档和检索业务服务。 */
    public KnowledgeController(KnowledgeService knowledge, DocumentLibraryService library) {
        this.knowledge = knowledge;
        this.library = library;
    }

    /** 返回最新目标批次状态，不触发向量调用。 */
    @Operation(
            operationId = "listKnowledgeDocuments",
            summary = "查询已收录文档",
            description = "按上传时间倒序读取元数据及最新目标批次状态，不发起模型调用。")
    @ApiResponse(responseCode = "200", description = "文档元数据、分片数量及后台处理状态。")
    @GetMapping
    public List<KnowledgeDocument> list() {
        return knowledge.documents();
    }

    /** 保持原上传响应字段，同时受理自动异步处理。 */
    @Operation(
            operationId = "uploadKnowledgeDocument",
            summary = "上传知识文档",
            description =
                    "支持 UTF-8 MD、可提取文字的 PDF、XLS、XLSX。最大 20 MiB、PDF 500 页、文本 10000 片。原件写入 MinIO"
                            + " 后受理异步处理；返回成功不表示分片或索引已完成。相同内容和产品版本可去重；完整上传记录见 /uploads。",
            requestBody =
                    @RequestBody(
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "multipart/form-data",
                                            schema =
                                                    @Schema(
                                                            implementation =
                                                                    UploadDocumentForm.class))))
    @ApiResponse(responseCode = "200", description = "已受理或重复命中的文档，处理状态通过后续查询更新。")
    @ApiResponse(
            responseCode = "400",
            description = "INVALID_DOCUMENT、INVALID_IMPORT_OPTIONS：上传参数无效。")
    @ApiResponse(responseCode = "413", description = "FILE_TOO_LARGE：文件超过 20 MiB。")
    @ApiResponse(responseCode = "415", description = "ONLY_MD_PDF_XLS_XLSX_SUPPORTED：文件类型不支持。")
    @ApiResponse(
            responseCode = "503",
            description = "MINIO_NOT_CONFIGURED、MINIO_WRITE_FAILED：原件存储不可用。")
    @PostMapping(consumes = "multipart/form-data")
    public KnowledgeDocument upload(
            @Parameter(hidden = true) @RequestParam MultipartFile file,
            @Parameter(hidden = true) @RequestParam String title,
            @Parameter(hidden = true) @RequestParam String version,
            @Parameter(hidden = true)
                    @RequestParam(defaultValue = "" + KnowledgeLimits.DEFAULT_CHUNK_SIZE)
                    int chunkSize,
            @Parameter(hidden = true)
                    @RequestParam(defaultValue = "" + KnowledgeLimits.DEFAULT_CHUNK_OVERLAP)
                    int overlap)
            throws IOException {
        KnowledgeViews.Upload upload =
                library.submit(
                        file.getOriginalFilename(),
                        title,
                        version,
                        null,
                        "",
                        null,
                        "API",
                        file.getBytes(),
                        new ImportOptions(chunkSize, overlap, List.of(), 0, Map.of(), false));
        return library.document(upload.documentId());
    }

    /** 查询最新目标批次，历史批次通过 chunks 接口选择。 */
    @Operation(
            operationId = "getKnowledgeDocument",
            summary = "查看文档与分片",
            description = "返回文档元数据和最新目标批次分片，用于核对原文；不会返回存储向量。")
    @ApiResponse(responseCode = "200", description = "文档元数据与原文片段。")
    @ApiResponse(
            responseCode = "404",
            description = "DOCUMENT_NOT_FOUND、DOCUMENT_DELETED：文档不存在或已删除。")
    @GetMapping("/{id}")
    public DocumentContent document(@Parameter(description = "文档编号") @PathVariable String id) {
        return new DocumentContent(knowledge.document(id), knowledge.content(id));
    }

    /** 删除立即停止公开检索，后台负责可重复的物理清理。 */
    @Operation(
            operationId = "deleteKnowledgeDocument",
            summary = "删除知识文档",
            description = "逻辑删除立即生效，保留上传、修订、批次与任务审计。MinIO、Excel 关系表和 ES 索引由后台清理；历史诊断证据快照保留。")
    @ApiResponse(responseCode = "200", description = "删除已受理，无响应体。")
    @PostMapping("/{id}/delete")
    public void delete(@Parameter(description = "文档编号") @PathVariable String id) {
        knowledge.delete(id);
    }

    /** 重建不会在 HTTP 请求线程等待模型。 */
    @Operation(
            operationId = "indexKnowledgeDocument",
            summary = "异步重建向量索引",
            description = "要求文本分片和关键词索引已经完成。新向量代完整成功后切换发布指针，失败保留旧可用代；进度和补偿见 tasks 接口。")
    @ApiResponse(responseCode = "200", description = "新任务已受理后的文档状态。")
    @ApiResponse(
            responseCode = "400",
            description = "DOCUMENT_NOT_SEARCHABLE、EXCEL_USES_SQL：文档暂不适合向量重建。")
    @ApiResponse(
            responseCode = "503",
            description = "EMBEDDING_NOT_CONFIGURED、EMBEDDING_DISABLED：向量服务不可用。")
    @PostMapping("/{id}/index")
    public KnowledgeDocument index(@Parameter(description = "文档编号") @PathVariable String id) {
        return knowledge.reindex(id);
    }

    /** 内置示例复用正式上传链路并保留去重记录。 */
    @Operation(
            operationId = "importExampleDocuments",
            summary = "导入三份内置示例文档",
            description = "受理原件保存及自动异步处理，重复导入复用文档和修订。需要 MinIO 与 MySQL；关键词发布需要 ES，向量配置可稍后补齐。")
    @ApiResponse(responseCode = "200", description = "返回三份已受理或去重命中的示例文档。")
    @PostMapping("/import-examples")
    public List<KnowledgeDocument> examples() {
        return knowledge.importExamples();
    }

    /** 产品范围和发布批次始终先于 ES 排名生效。 */
    @Operation(
            operationId = "searchKnowledge",
            summary = "检索适用版本的知识片段",
            description =
                    "BM25 关键词与兼容模型向量召回按 RRF 合并；启用本地重排序时默认初筛 20 条，再按 ONNX 模型分数取最多 5 片。ranking 说明实际排序方式。向量未配置或没有兼容已发布向量时返回 LEXICAL；进入向量调用后失败明确报错。ES"
                            + " 不可用也明确报错。")
    @ApiResponse(responseCode = "200", description = "实际模式和带修订原文链接的引用。")
    @ApiResponse(responseCode = "400", description = "INVALID_INPUT：查询或版本参数无效。")
    @ApiResponse(responseCode = "503", description = "重排序错误：RERANK_MODEL_MISSING、RERANK_MODEL_INVALID、RERANK_TIMED_OUT、RERANK_CANCELLED、RERANK_INFERENCE_FAILED、RERANK_OUTPUT_INVALID、RERANK_UNAVAILABLE；不会降级为 RRF。")
    @GetMapping("/search")
    public KnowledgeSearchResult search(
            @Parameter(
                            description = "查询内容，1 至 2000 字符",
                            schema = @Schema(minLength = 1, maxLength = 2000))
                    @RequestParam
                    String query,
            @Parameter(description = "适用产品版本或 *，最长 30 字符", schema = @Schema(maxLength = 30))
                    @RequestParam
                    String version,
            @Parameter(description = "是否只用关键词召回；不关闭已启用的本地模型重排序") @RequestParam(defaultValue = "false")
                    boolean lexicalOnly) {
        return knowledge.search(query, version, 5, lexicalOnly);
    }
}
