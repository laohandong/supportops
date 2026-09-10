package io.supportops.knowledge.service.impl;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.enums.CellDataTypeEnum;
import com.alibaba.excel.event.AnalysisEventListener;
import com.alibaba.excel.metadata.Cell;
import com.alibaba.excel.metadata.data.DataFormatData;
import com.alibaba.excel.metadata.data.ReadCellData;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.supportops.knowledge.dto.CompiledSql;
import io.supportops.knowledge.dto.ExcelWriteBatch;
import io.supportops.knowledge.dto.ImportOptions;
import io.supportops.knowledge.entity.ExcelColumnEntity;
import io.supportops.knowledge.entity.ExcelDatasetEntity;
import io.supportops.knowledge.entity.IndexTaskEntity;
import io.supportops.knowledge.entity.ProcessingBatchEntity;
import io.supportops.knowledge.mapper.ExcelColumnMapper;
import io.supportops.knowledge.mapper.ExcelDatasetMapper;
import io.supportops.knowledge.mapper.ExcelTableMapper;
import io.supportops.knowledge.mapper.KnowledgeCatalogMapper;
import io.supportops.knowledge.service.ExcelDataService;
import io.supportops.knowledge.service.KnowledgeTaskService;
import io.supportops.knowledge.service.support.ExcelReadSession;
import io.supportops.knowledge.service.support.KnowledgeValues;
import io.supportops.knowledge.service.support.ReadOnlySqlCompiler;
import io.supportops.knowledge.vo.KnowledgeViews;

import org.apache.poi.ss.usermodel.DateUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/** EasyExcel 流式推断与分批入库，全部 Sheet 就绪后才交给上层发布。 */
@Service
public class ExcelDataServiceImpl implements ExcelDataService {
    private final ExcelDatasetMapper datasets;
    private final ExcelColumnMapper columns;
    private final ExcelTableMapper tables;
    private final KnowledgeCatalogMapper catalog;
    private final KnowledgeTaskService tasks;
    private final ExcelReadSession reader;
    private final ReadOnlySqlCompiler compiler;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;
    private final String schema;

    /** 每次推断只保留列定义和少量样例，不累计完整工作簿数据。 */
    private static final class SheetProfile {
        private final ExcelDatasetEntity dataset;
        private final List<ExcelColumnEntity> columns = new ArrayList<>();
        private final Map<Integer, List<String>> samples = new HashMap<>();
        private final List<ExcelWriteBatch.Row> pending = new ArrayList<>();
        private long written;

        /** 为单个 Sheet 创建隔离推断状态。 */
        private SheetProfile(ExcelDatasetEntity dataset) {
            this.dataset = dataset;
        }
    }

    /** 单元格以精确文本及推断类型传递，避免提前转换为 double。 */
    private record CellValue(String text, String type) {}

    /** 注入导入、元数据及独立只读查询边界。 */
    public ExcelDataServiceImpl(
            ExcelDatasetMapper datasets,
            ExcelColumnMapper columns,
            ExcelTableMapper tables,
            KnowledgeCatalogMapper catalog,
            KnowledgeTaskService tasks,
            ExcelReadSession reader,
            ReadOnlySqlCompiler compiler,
            TransactionTemplate transactions,
            ObjectMapper json,
            @Value("${supportops.knowledge.excel.schema:supportops_excel}") String schema) {
        this.datasets = datasets;
        this.columns = columns;
        this.tables = tables;
        this.catalog = catalog;
        this.tasks = tasks;
        this.reader = reader;
        this.compiler = compiler;
        this.transactions = transactions;
        this.json = json;
        this.schema = schema;
    }

    /** 第一遍读取完整数据推断类型；第二遍将数据分批写入未发布物理表。 */
    @Override
    public long ingest(
            ProcessingBatchEntity batch,
            ImportOptions options,
            Supplier<InputStream> original,
            IndexTaskEntity task) {
        // 补偿只回收当前处理批次的临时表，其他修订的已发布表不会被覆盖。
        for (ExcelDatasetEntity previous :
                datasets.selectList(
                        Wrappers.<ExcelDatasetEntity>lambdaQuery()
                                .eq(ExcelDatasetEntity::getBatchId, batch.getId())
                                .in(
                                        ExcelDatasetEntity::getStatus,
                                        List.of("STAGING", "READY", "FAILED")))) {
            tasks.requireLease(task);
            tables.drop(schema, previous.getTableName());
            previous.setStatus("REPLACED");
            datasets.updateById(previous);
        }
        Map<Integer, SheetProfile> profiles = new LinkedHashMap<>();
        // 第一遍只推断类型、表头和行数，防止读取到后半段才发现类型不兼容。
        read(original, new WorkbookListener(batch, options, task, profiles, false));
        if (profiles.isEmpty()
                || profiles.values().stream()
                        .allMatch(profile -> profile.dataset.getRowCount() == 0)) {
            throw failure("EXCEL_NO_DATA_ROWS");
        }
        for (Integer selected : options.sheets()) {
            if (!profiles.containsKey(selected)) {
                throw failure("EXCEL_SHEET_NOT_FOUND");
            }
        }
        for (String key : options.columnTypes().keySet()) {
            // 用户覆盖的是 Sheet/列序号；物理表名和列名始终由服务端生成。
            if (!key.matches("[0-9]{1,4}:[0-9]{1,3}")) {
                throw failure("EXCEL_INVALID_COLUMN_OVERRIDE");
            }
            String[] coordinates = key.split(":");
            SheetProfile selected = profiles.get(Integer.parseInt(coordinates[0]));
            if (selected == null || Integer.parseInt(coordinates[1]) >= selected.columns.size()) {
                throw failure("EXCEL_INVALID_COLUMN_OVERRIDE");
            }
        }
        tasks.requireLease(task);
        for (SheetProfile profile : profiles.values()) {
            if (profile.dataset.getRowCount() == 0) {
                throw failure("EXCEL_EMPTY_SHEET_S" + profile.dataset.getSheetIndex());
            }
            for (ExcelColumnEntity column : profile.columns) {
                if (column.getDataType().isEmpty()) {
                    column.setDataType("TEXT");
                }
                String override =
                        options.columnTypes()
                                .get(
                                        profile.dataset.getSheetIndex()
                                                + ":"
                                                + column.getColumnIndex());
                if (override != null) {
                    if (!Set.of("TEXT", "INTEGER", "DECIMAL", "DATE", "DATETIME", "BOOLEAN")
                            .contains(override)) {
                        throw failure("EXCEL_INVALID_COLUMN_TYPE");
                    }
                    column.setDataType(override);
                }
                try {
                    column.setSampleJson(
                            json.writeValueAsString(
                                    profile.samples.getOrDefault(
                                            column.getColumnIndex(), List.of())));
                } catch (Exception exception) {
                    throw failure("EXCEL_METADATA_INVALID");
                }
            }
            profile.dataset.setStatus(options.preview() ? "PREVIEW" : "STAGING");
            // 元数据短事务与建表分开，MySQL DDL 的隐式提交不会破坏业务事务边界。
            transactions.executeWithoutResult(
                    transaction -> {
                        tasks.requireLease(task);
                        datasets.insert(profile.dataset);
                        for (ExcelColumnEntity column : profile.columns) {
                            columns.insert(column);
                        }
                    });
            if (!options.preview()) {
                tables.create(
                        new ExcelWriteBatch(
                                schema,
                                profile.dataset.getTableName(),
                                profile.columns,
                                List.of()));
            }
        }
        if (!options.preview()) {
            // 第二遍按最终类型批量入表，逐 Sheet 核对行数后才能标记完整。
            read(original, new WorkbookListener(batch, options, task, profiles, true));
            for (SheetProfile profile : profiles.values()) {
                flush(profile, task);
                if (profile.written != profile.dataset.getRowCount()) {
                    throw failure("EXCEL_ROW_COUNT_MISMATCH");
                }
            }
            transactions.executeWithoutResult(
                    transaction -> {
                        tasks.requireLease(task);
                        // READY 只是关系表已完整；文档发布指针由上层导入服务统一切换。
                        datasets.update(
                                Wrappers.<ExcelDatasetEntity>lambdaUpdate()
                                        .eq(ExcelDatasetEntity::getBatchId, batch.getId())
                                        .eq(ExcelDatasetEntity::getStatus, "READY")
                                        .set(ExcelDatasetEntity::getStatus, "REPLACED"));
                        for (SheetProfile profile : profiles.values()) {
                            profile.dataset.setStatus("READY");
                            datasets.updateById(profile.dataset);
                        }
                    });
        }
        return profiles.values().stream().mapToLong(profile -> profile.dataset.getRowCount()).sum();
    }

    /** 每次读取重新打开已持久化原件，文件内容由原件版本固定。 */
    private void read(Supplier<InputStream> original, WorkbookListener listener) {
        try (InputStream input = original.get()) {
            EasyExcel.read(input, listener).headRowNumber(0).autoTrim(false).doReadAll();
        } catch (RuntimeException exception) {
            Throwable cause = exception;
            while (cause.getCause() != null
                    && !KnowledgeValues.error(cause).startsWith("EXCEL_")
                    && !KnowledgeValues.error(cause).startsWith("TASK_")) {
                cause = cause.getCause();
            }
            if (cause instanceof IllegalArgumentException invalid) {
                throw invalid;
            }
            if (KnowledgeValues.error(cause).startsWith("TASK_")
                    && cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw failure("EXCEL_PARSE_FAILED");
        } catch (Exception exception) {
            throw failure("EXCEL_PARSE_FAILED");
        }
    }

    /** 监听每一行，累计容量约束，使用单元格元数据识别日期、公式与前导零。 */
    private final class WorkbookListener extends AnalysisEventListener<Map<Integer, String>> {
        private final ProcessingBatchEntity batch;
        private final ImportOptions options;
        private final IndexTaskEntity task;
        private final Map<Integer, SheetProfile> profiles;
        private final boolean writing;
        private long cells;
        private long characters;
        private long rows;

        /** 每一遍读取独立计量，避免压缩文件展开后无限占用资源。 */
        private WorkbookListener(
                ProcessingBatchEntity batch,
                ImportOptions options,
                IndexTaskEntity task,
                Map<Integer, SheetProfile> profiles,
                boolean writing) {
            this.batch = batch;
            this.options = options;
            this.task = task;
            this.profiles = profiles;
            this.writing = writing;
        }

        /** 只有选中的 Sheet 参与数据集构造；原始行号不随空行过滤改变。 */
        @Override
        public void invoke(Map<Integer, String> values, AnalysisContext context) {
            int sheet = context.readSheetHolder().getSheetNo();
            int row = context.readRowHolder().getRowIndex() + 1;
            if (!options.sheets().isEmpty() && !options.sheets().contains(sheet)) {
                return;
            }
            for (Map.Entry<Integer, Cell> entry : context.readRowHolder().getCellMap().entrySet()) {
                if (entry.getValue() instanceof ReadCellData<?> raw
                        && (raw.getType() == CellDataTypeEnum.ERROR
                                || raw.getFormulaData() != null
                                        && (raw.getType() == CellDataTypeEnum.EMPTY
                                                || !values.containsKey(entry.getKey())))) {
                    cellValue(raw, values.get(entry.getKey()), false, sheet, row, entry.getKey());
                }
            }
            if (values.values().stream().allMatch(value -> value == null || value.isBlank())) {
                return;
            }
            if (++rows > 100000 || profiles.size() > 20) {
                throw failure("EXCEL_ROW_OR_SHEET_LIMIT");
            }
            int width = values.keySet().stream().mapToInt(Integer::intValue).max().orElse(-1) + 1;
            if (width > 200) {
                throw failure("EXCEL_COLUMN_LIMIT");
            }
            for (String value : values.values()) {
                if (value != null) {
                    cells++;
                    characters += value.length();
                }
            }
            if (cells > 2000000 || characters > 40000000) {
                throw failure("EXCEL_EXPANDED_CONTENT_LIMIT");
            }
            if (rows % 250 == 0) {
                tasks.requireLease(task);
            }
            SheetProfile profile = profiles.get(sheet);
            if (!writing && profile == null) {
                if (profiles.size() >= 20) {
                    throw failure("EXCEL_ROW_OR_SHEET_LIMIT");
                }
                if (options.headerRow() > 0 && row < options.headerRow()) {
                    return;
                }
                if (options.headerRow() > 0 && row != options.headerRow()) {
                    throw failure("EXCEL_HEADER_ROW_MISSING");
                }
                profile =
                        profile(
                                batch,
                                sheet,
                                context.readSheetHolder().getSheetName(),
                                row,
                                values,
                                width);
                profiles.put(sheet, profile);
                return;
            }
            if (profile == null || row <= profile.dataset.getHeaderRow()) {
                return;
            }
            if (width > profile.columns.size()) {
                throw failure("EXCEL_ROW_WIDER_THAN_HEADER_S" + sheet + "_R" + row);
            }
            List<String> output = new ArrayList<>();
            for (ExcelColumnEntity column : profile.columns) {
                int index = column.getColumnIndex();
                Cell cell = context.readRowHolder().getCellMap().get(index);
                ReadCellData<?> raw = cell instanceof ReadCellData<?> data ? data : null;
                CellValue value =
                        cellValue(
                                raw,
                                values.get(index),
                                Boolean.TRUE.equals(
                                        context.readWorkbookHolder()
                                                .getGlobalConfiguration()
                                                .getUse1904windowing()),
                                sheet,
                                row,
                                index);
                if (writing) {
                    output.add(convert(value.text(), column.getDataType(), sheet, row, index));
                } else {
                    if (raw != null
                            && raw.getDataFormatData() != null
                            && raw.getDataFormatData().getFormat() != null) {
                        String format = raw.getDataFormatData().getFormat();
                        column.setFormatHint(format.substring(0, Math.min(200, format.length())));
                    }
                    boolean identifier =
                            column.getOriginalHeader()
                                    .toLowerCase(Locale.ROOT)
                                    .matches(".*(编号|编码|订单号|手机号|电话|邮编|\\bid\\b|code).*");
                    if (value.text() != null) {
                        column.setDataType(
                                identifier ? "TEXT" : merge(column.getDataType(), value.type()));
                        List<String> samples =
                                profile.samples.computeIfAbsent(index, key -> new ArrayList<>());
                        if (samples.size() < 5) {
                            samples.add(
                                    value.text()
                                            .substring(0, Math.min(200, value.text().length())));
                        }
                    }
                }
            }
            if (writing) {
                profile.pending.add(new ExcelWriteBatch.Row(row, output));
                if (profile.pending.size() >= 500) {
                    flush(profile, task);
                }
            } else {
                profile.dataset.setRowCount(profile.dataset.getRowCount() + 1);
            }
        }

        /** 结束回调不发布数据，发布由所有 Sheet 的整体结果决定。 */
        @Override
        public void doAfterAllAnalysed(AnalysisContext context) {}
    }

    /** 从表头建立列序号映射，空表头使用可说明位置的名称。 */
    private SheetProfile profile(
            ProcessingBatchEntity batch,
            int index,
            String name,
            int header,
            Map<Integer, String> values,
            int width) {
        ExcelDatasetEntity dataset = new ExcelDatasetEntity();
        dataset.setId(KnowledgeValues.id());
        dataset.setDocumentId(batch.getDocumentId());
        dataset.setVersionId(batch.getVersionId());
        dataset.setBatchId(batch.getId());
        dataset.setSheetIndex(index);
        dataset.setSheetName(name);
        dataset.setHeaderRow(header);
        dataset.setTableName("xl_" + dataset.getId().replace("-", ""));
        dataset.setRowCount(0L);
        dataset.setStatus("PREVIEW");
        dataset.setCreatedAt(KnowledgeValues.now());
        SheetProfile result = new SheetProfile(dataset);
        for (int col = 0; col < width; col++) {
            String title = values.get(col);
            if (title == null || title.isBlank()) {
                title = "列 " + (col + 1);
            }
            if (title.length() > 500) {
                throw failure("EXCEL_HEADER_TOO_LONG");
            }
            ExcelColumnEntity column = new ExcelColumnEntity();
            column.setId(KnowledgeValues.id());
            column.setDatasetId(dataset.getId());
            column.setColumnIndex(col);
            column.setColumnName("c_" + col);
            column.setOriginalHeader(title);
            column.setDataType("");
            column.setSampleJson("[]");
            column.setFormatHint("");
            result.columns.add(column);
        }
        return result;
    }

    /** 原始数值保留 BigDecimal；日期只在明确日期格式时解释序列号。 */
    private CellValue cellValue(
            ReadCellData<?> cell, String text, boolean date1904, int sheet, int row, int column) {
        if (cell != null && cell.getType() == CellDataTypeEnum.ERROR) {
            throw failure("EXCEL_CELL_ERROR_S" + sheet + "_R" + row + "_C" + column);
        }
        if (cell != null
                && cell.getFormulaData() != null
                && (cell.getType() == CellDataTypeEnum.EMPTY || text == null)) {
            throw failure("EXCEL_FORMULA_RESULT_MISSING_S" + sheet + "_R" + row + "_C" + column);
        }
        if (text == null || text.isEmpty()) {
            return new CellValue(null, "TEXT");
        }
        if (text.length() > 32767) {
            throw failure("EXCEL_CELL_TOO_LONG");
        }
        if (cell != null && cell.getType() == CellDataTypeEnum.BOOLEAN) {
            return new CellValue(
                    Boolean.TRUE.equals(cell.getBooleanValue()) ? "1" : "0", "BOOLEAN");
        }
        if (cell != null && cell.getType() == CellDataTypeEnum.NUMBER) {
            BigDecimal number =
                    cell.getOriginalNumberValue() == null
                            ? cell.getNumberValue()
                            : cell.getOriginalNumberValue();
            DataFormatData format = cell.getDataFormatData();
            if (format != null
                    && format.getIndex() != null
                    && DateUtil.isADateFormat(format.getIndex(), format.getFormat())) {
                LocalDateTime date = DateUtil.getLocalDateTime(number.doubleValue(), date1904);
                return date.toLocalTime().equals(LocalTime.MIDNIGHT)
                        ? new CellValue(date.toLocalDate().toString(), "DATE")
                        : new CellValue(date.toString(), "DATETIME");
            }
            if (format != null
                    && format.getFormat() != null
                    && format.getFormat().matches("0{2,}")) {
                String value = number.toBigIntegerExact().toString();
                return new CellValue(
                        "0".repeat(Math.max(0, format.getFormat().length() - value.length()))
                                + value,
                        "TEXT");
            }
            BigDecimal normalized = number.stripTrailingZeros();
            if (normalized.scale() <= 0) {
                try {
                    number.longValueExact();
                    return new CellValue(number.toPlainString(), "INTEGER");
                } catch (ArithmeticException exception) {
                    return new CellValue(number.toPlainString(), "TEXT");
                }
            }
            String type =
                    normalized.scale() <= 10 && normalized.precision() - normalized.scale() <= 28
                            ? "DECIMAL"
                            : "TEXT";
            return new CellValue(number.toPlainString(), type);
        }
        return new CellValue(text, "TEXT");
    }

    /** 混合类型保守提升到文本，数字列的整数与小数统一为精确小数。 */
    private String merge(String previous, String current) {
        if (previous.isEmpty() || previous.equals(current)) {
            return current;
        }
        if (Set.of("INTEGER", "DECIMAL").contains(previous)
                && Set.of("INTEGER", "DECIMAL").contains(current)) {
            return "DECIMAL";
        }
        if (Set.of("DATE", "DATETIME").contains(previous)
                && Set.of("DATE", "DATETIME").contains(current)) {
            return "DATETIME";
        }
        return "TEXT";
    }

    /** 显式类型转换失败必须指出原始坐标，不接受 MySQL 隐式截断。 */
    private String convert(String value, String type, int sheet, int row, int column) {
        if (value == null) {
            return null;
        }
        try {
            return switch (type) {
                case "INTEGER" -> Long.toString(new BigDecimal(value).longValueExact());
                case "DECIMAL" -> {
                    BigDecimal number = new BigDecimal(value).stripTrailingZeros();
                    if (number.scale() > 10 || number.precision() - number.scale() > 28) {
                        throw new ArithmeticException();
                    }
                    yield number.toPlainString();
                }
                case "DATE" -> LocalDate.parse(value).toString();
                case "DATETIME" ->
                        (value.length() == 10
                                        ? LocalDate.parse(value).atStartOfDay()
                                        : LocalDateTime.parse(value.replace(' ', 'T')))
                                .toString()
                                .replace('T', ' ');
                case "BOOLEAN" -> {
                    if (Set.of("1", "true", "TRUE").contains(value)) {
                        yield "1";
                    }
                    if (Set.of("0", "false", "FALSE").contains(value)) {
                        yield "0";
                    }
                    throw new IllegalArgumentException();
                }
                case "TEXT" -> value;
                default -> throw new IllegalArgumentException();
            };
        } catch (RuntimeException exception) {
            throw failure("EXCEL_TYPE_MISMATCH_S" + sheet + "_R" + row + "_C" + column);
        }
    }

    /** 已写批次仍不可见，只有所有 Sheet 完整成功后才发布。 */
    private void flush(SheetProfile profile, IndexTaskEntity task) {
        if (profile.pending.isEmpty()) {
            return;
        }
        tasks.requireLease(task);
        int count = profile.pending.size();
        transactions.executeWithoutResult(
                transaction -> {
                    tasks.requireLease(task);
                    if (tables.insert(
                                    new ExcelWriteBatch(
                                            schema,
                                            profile.dataset.getTableName(),
                                            profile.columns,
                                            profile.pending))
                            != count) {
                        throw failure("EXCEL_WRITE_INCOMPLETE");
                    }
                });
        profile.written += count;
        profile.pending.clear();
    }

    /** 返回指定处理批次的结构与预览，隐藏物理表标识。 */
    @Override
    public List<KnowledgeViews.Dataset> datasets(String batchId) {
        return datasets
                .selectList(
                        Wrappers.<ExcelDatasetEntity>lambdaQuery()
                                .eq(ExcelDatasetEntity::getBatchId, batchId)
                                .orderByAsc(ExcelDatasetEntity::getSheetIndex))
                .stream()
                .map(this::view)
                .toList();
    }

    /** 数据集范围先由 MySQL 文档发布关系过滤。 */
    @Override
    public List<KnowledgeViews.Dataset> applicable(String productVersion) {
        List<String> batches = catalog.applicableBatches(productVersion);
        if (batches.isEmpty()) {
            return List.of();
        }
        return datasets
                .selectList(
                        Wrappers.<ExcelDatasetEntity>lambdaQuery()
                                .in(ExcelDatasetEntity::getBatchId, batches)
                                .eq(ExcelDatasetEntity::getStatus, "READY"))
                .stream()
                .map(this::view)
                .toList();
    }

    /** 用独立数据库身份执行受控 SELECT，返回前再次核对资料未被删除或替换。 */
    @Override
    public KnowledgeViews.SqlResult query(String datasetId, String sql, String productVersion) {
        ExcelDatasetEntity dataset = datasets.selectById(datasetId);
        if (dataset == null
                || !dataset.getStatus().equals("READY")
                || !catalog.applicableBatches(productVersion).contains(dataset.getBatchId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "DATASET_NOT_APPLICABLE");
        }
        List<ExcelColumnEntity> definitions = columnDefinitions(datasetId);
        CompiledSql compiled =
                compiler.compile(
                        sql,
                        schema,
                        dataset.getTableName(),
                        definitions.stream().map(ExcelColumnEntity::getColumnName).toList());
        List<Map<String, Object>> result;
        try {
            result = reader.query(compiled);
        } catch (RuntimeException exception) {
            // Agent 工具也会转述异常；不得把驱动错误中的物理表名、SQL 或连接信息带入模型。
            throw new IllegalStateException("SQL_QUERY_FAILED");
        }
        if (!catalog.applicableBatches(productVersion).contains(dataset.getBatchId())) {
            throw new IllegalStateException("DATASET_CHANGED_DURING_QUERY");
        }
        List<List<String>> rows = new ArrayList<>();
        for (Map<String, Object> record : result.stream().limit(compiled.limit()).toList()) {
            List<String> row = new ArrayList<>();
            for (String column : compiled.columns()) {
                Object value = record.get(column);
                row.add(
                        value == null
                                ? null
                                : value instanceof BigDecimal decimal
                                        ? decimal.toPlainString()
                                        : value.toString());
            }
            rows.add(row);
        }
        return new KnowledgeViews.SqlResult(
                datasetId,
                dataset.getDocumentId(),
                dataset.getVersionId(),
                dataset.getSheetName(),
                compiled.displaySql(),
                compiled.columns(),
                rows,
                result.size() > compiled.limit());
    }

    /** 从固定列记录生成含中文表头的结构响应。 */
    private KnowledgeViews.Dataset view(ExcelDatasetEntity dataset) {
        List<KnowledgeViews.Column> fields =
                columnDefinitions(dataset.getId()).stream()
                        .map(
                                column -> {
                                    try {
                                        return new KnowledgeViews.Column(
                                                column.getColumnIndex(),
                                                column.getColumnName(),
                                                column.getOriginalHeader(),
                                                column.getDataType(),
                                                json.readValue(
                                                        column.getSampleJson(),
                                                        new TypeReference<List<String>>() {}));
                                    } catch (Exception exception) {
                                        throw failure("EXCEL_METADATA_INVALID");
                                    }
                                })
                        .toList();
        return new KnowledgeViews.Dataset(
                dataset.getId(),
                dataset.getDocumentId(),
                dataset.getVersionId(),
                dataset.getBatchId(),
                dataset.getSheetName(),
                dataset.getSheetIndex(),
                dataset.getHeaderRow(),
                dataset.getRowCount(),
                dataset.getStatus(),
                fields);
    }

    /** 按原始列序号读取类型定义。 */
    private List<ExcelColumnEntity> columnDefinitions(String id) {
        return columns.selectList(
                Wrappers.<ExcelColumnEntity>lambdaQuery()
                        .eq(ExcelColumnEntity::getDatasetId, id)
                        .orderByAsc(ExcelColumnEntity::getColumnIndex));
    }

    /** 只删除数据集注册表记录过的物理表，保留元数据历史。 */
    @Override
    public void delete(String documentId) {
        for (ExcelDatasetEntity dataset :
                datasets.selectList(
                        Wrappers.<ExcelDatasetEntity>lambdaQuery()
                                .eq(ExcelDatasetEntity::getDocumentId, documentId))) {
            tables.drop(schema, dataset.getTableName());
            dataset.setStatus("DELETED");
            datasets.updateById(dataset);
        }
    }

    /** 返回包含坐标但不包含单元格原文的错误码。 */
    private IllegalArgumentException failure(String code) {
        return new IllegalArgumentException(code);
    }
}
