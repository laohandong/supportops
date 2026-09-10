package io.supportops.knowledge.dto;

import io.supportops.knowledge.constant.KnowledgeLimits;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** 可复现的解析配置；列类型映射的键是 Sheet 序号与列序号，属于动态表格协议。 */
@Schema(description = "文本分片及 Excel 导入配置；列类型映射使用 sheetIndex:columnIndex 键。")
public record ImportOptions(
        @Schema(
                        description = "分片 Unicode 码点上限，100 至 8000；章节末片可不足上限。",
                        defaultValue = "" + KnowledgeLimits.DEFAULT_CHUNK_SIZE)
                int chunkSize,
        @Schema(
                        description = "同章节重叠码点数，非负且每次至少前进 10 个码点；不跨章节或 PDF 页重叠。",
                        defaultValue = "" + KnowledgeLimits.DEFAULT_CHUNK_OVERLAP)
                int overlap,
        @Schema(description = "选中的 Sheet 序号，从 0 开始；空数组选择所有非空 Sheet。") List<Integer> sheets,
        @Schema(description = "表头行号，从 1 开始；0 自动选择各 Sheet 首个非空行。") int headerRow,
        @Schema(
                        description =
                                "列类型覆盖，键为 sheetIndex:columnIndex；值为"
                                        + " TEXT、INTEGER、DECIMAL、DATE、DATETIME、BOOLEAN。")
                Map<String, String> columnTypes,
        @Schema(description = "Excel 是否只预览；确认后再发布，文本忽略此值。") boolean preview) {
    /** 校验并复制用户配置，避免任务执行期间可变集合改变参数。 */
    public ImportOptions {
        if (chunkSize < KnowledgeLimits.MIN_CHUNK_SIZE
                || chunkSize > KnowledgeLimits.MAX_CHUNK_SIZE
                || overlap < 0
                || chunkSize - overlap < KnowledgeLimits.MIN_CHUNK_STEP
                || headerRow < 0
                || headerRow > 1000) {
            throw new IllegalArgumentException("INVALID_IMPORT_OPTIONS");
        }
        sheets = sheets == null ? List.of() : sheets.stream().distinct().sorted().toList();
        columnTypes =
                columnTypes == null
                        ? Map.of()
                        : Collections.unmodifiableMap(new TreeMap<>(columnTypes));
        if (sheets.size() > 20 || sheets.stream().anyMatch(index -> index < 0)) {
            throw new IllegalArgumentException("INVALID_SHEETS");
        }
    }

    /** 新建处理批次统一使用 100/10；已有批次继续使用自己的配置快照。 */
    public static ImportOptions defaults() {
        return new ImportOptions(
                KnowledgeLimits.DEFAULT_CHUNK_SIZE,
                KnowledgeLimits.DEFAULT_CHUNK_OVERLAP,
                List.of(),
                0,
                Map.of(),
                false);
    }
}
