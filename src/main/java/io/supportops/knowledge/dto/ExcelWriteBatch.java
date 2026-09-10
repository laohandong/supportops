package io.supportops.knowledge.dto;

import io.supportops.knowledge.entity.ExcelColumnEntity;

import java.util.List;

/** 动态表格协议：每行按列序号提供可空字符串，数据库类型由已登记列定义决定。 */
public record ExcelWriteBatch(
        String schema, String table, List<ExcelColumnEntity> columns, List<Row> rows) {
    /** 保留原始 Excel 行号及列值；值通过 MyBatis 参数绑定。 */
    public record Row(int sourceRow, List<String> values) {}
}
