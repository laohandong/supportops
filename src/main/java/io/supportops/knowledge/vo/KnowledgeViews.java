package io.supportops.knowledge.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** 文档处理对外响应，动态 Excel 单元格只使用字符串或空值，不泄露持久化实体。 */
public final class KnowledgeViews {
    /** 响应模型容器不可实例化。 */
    private KnowledgeViews() {}

    /** 一次上传受理及处理结果。 */
    @Schema(description = "上传记录；文件成功受理不代表分片或索引已经完成。")
    public record Upload(
            @Schema(description = "上传编号") String id,
            @Schema(description = "逻辑文档编号，可空") String documentId,
            @Schema(description = "修订编号，可空") String versionId,
            @Schema(description = "处理批次，可空") String batchId,
            @Schema(description = "原文件名") String filename,
            @Schema(description = "字节数") long sizeBytes,
            @Schema(
                            description =
                                    "上传状态：RECEIVING 接收中、ACCEPTED 已受理、DUPLICATE 重复、SUCCEEDED"
                                            + " 成功、FAILED 失败")
                    String status,
            @Schema(description = "错误码，无错误为空") String errorCode,
            @Schema(description = "受理时间") String createdAt,
            @Schema(description = "上传用户 UUID；null 表示历史未归属或内部导入", nullable = true) String userId) {}

    /** 文档修订的原件与产品适用信息。 */
    @Schema(description = "不可变文档修订")
    public record Version(
            @Schema(description = "修订编号") String id,
            @Schema(description = "逻辑文档编号") String documentId,
            @Schema(description = "递增修订号") int revision,
            @Schema(description = "适用产品版本") String productVersion,
            @Schema(description = "标题快照") String title,
            @Schema(description = "原件编号") String fileId,
            @Schema(description = "文件类型") String fileType,
            @Schema(description = "修订说明") String note,
            @Schema(description = "创建时间") String createdAt,
            @Schema(description = "原件状态：STORED、MISSING、DELETED 等") String fileStatus) {}

    /** 一次文档解析及索引的汇总状态。 */
    @Schema(description = "处理批次和索引状态")
    public record Batch(
            @Schema(description = "批次编号") String id,
            @Schema(description = "修订编号") String versionId,
            @Schema(description = "分片码点上限；null 表示历史迁移未记录真实参数", nullable = true) Integer chunkSize,
            @Schema(description = "同章节重叠码点数；null 表示历史迁移未记录真实参数", nullable = true) Integer overlap,
            @Schema(
                            description =
                                    "处理状态：PENDING 待处理、RUNNING 处理中、PREVIEW 待确认、READY 已就绪、FAILED"
                                            + " 失败、CANCELLED 已取消")
                    String status,
            @Schema(description = "关键词索引状态") String textStatus,
            @Schema(description = "向量任务状态") String vectorStatus,
            @Schema(description = "文本片段数") int chunkCount,
            @Schema(description = "Excel 数据行数") long rowCount,
            @Schema(description = "错误码") String errorCode,
            @Schema(description = "创建时间") String createdAt) {}

    /** 后台任务及补偿进度。 */
    @Schema(description = "异步任务及自动补偿信息")
    public record Task(
            @Schema(description = "任务编号") String id,
            @Schema(description = "批次编号") String batchId,
            @Schema(
                            description =
                                    "任务类型：IMPORT 导入、TEXT 关键词索引、VECTOR 向量索引、CLEANUP"
                                            + " 文档清理、FILE_CLEANUP 原件清理")
                    String kind,
            @Schema(description = "任务状态") String status,
            @Schema(description = "执行阶段") String stage,
            @Schema(description = "完成片段数") int completedChunks,
            @Schema(description = "执行次数，含首次") int attemptCount,
            @Schema(description = "下次执行 UTC 毫秒") long nextRetryAt,
            @Schema(description = "最近错误码") String errorCode,
            @Schema(description = "创建时间") String createdAt) {}

    /** 每次执行的历史结果。 */
    @Schema(description = "后台任务执行历史")
    public record Attempt(
            @Schema(description = "尝试编号") String id,
            @Schema(description = "任务编号") String taskId,
            @Schema(description = "尝试序号") int attemptNumber,
            @Schema(description = "结果状态") String status,
            @Schema(description = "最后阶段") String stage,
            @Schema(description = "错误码") String errorCode,
            @Schema(description = "开始时间") String startedAt,
            @Schema(description = "结束时间，可空") String finishedAt) {}

    /** 原始 Excel 表头与受控 SQL 列名映射。 */
    @Schema(description = "Excel 列定义与预览样例")
    public record Column(
            @Schema(description = "零基列序号") int columnIndex,
            @Schema(description = "SQL 列名") String columnName,
            @Schema(description = "原始表头") String originalHeader,
            @Schema(
                            description =
                                    "列类型：TEXT 文本、INTEGER 整数、DECIMAL 精确小数、DATE 日期、DATETIME"
                                            + " 日期时间、BOOLEAN 布尔")
                    String dataType,
            @Schema(description = "最多五个原始样例") List<String> samples) {}

    /** 可预览或可查询的 Sheet 数据集。 */
    @Schema(description = "Excel Sheet 数据集；查询表别名固定为 data，不公开物理表名")
    public record Dataset(
            @Schema(description = "数据集编号") String id,
            @Schema(description = "来源文档") String documentId,
            @Schema(description = "来源修订") String versionId,
            @Schema(description = "批次") String batchId,
            @Schema(description = "Sheet 名") String sheetName,
            @Schema(description = "零基 Sheet 序号") int sheetIndex,
            @Schema(description = "表头行号，一基") int headerRow,
            @Schema(description = "数据行数") long rowCount,
            @Schema(description = "数据状态：PREVIEW 预览、STAGING 写入中、READY 可查询、FAILED 失败、DELETED 已删除")
                    String status,
            @Schema(description = "列定义") List<Column> columns) {}

    /** SQL 结果以列名和字符串单元格数组传输，保持 DECIMAL 精度。 */
    @Schema(description = "只读 SQL 结果；汇总来源是数据快照和查询口径，明细含 source_row 原始行号")
    public record SqlResult(
            @Schema(description = "数据集编号") String datasetId,
            @Schema(description = "来源文档") String documentId,
            @Schema(description = "来源修订") String versionId,
            @Schema(description = "Sheet 名") String sheetName,
            @Schema(description = "经校验的 SQL，不含物理表名") String sql,
            @Schema(description = "结果列名") List<String> columns,
            @Schema(description = "二维单元格数组；NULL 用 null，数值使用精确字符串") List<List<String>> rows,
            @Schema(description = "是否达到返回上限，不能把截断结果当作完整明细") boolean truncated) {}
}
