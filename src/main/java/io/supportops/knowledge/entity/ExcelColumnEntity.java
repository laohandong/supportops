package io.supportops.knowledge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** 保留原始 Excel 列名、单元格语义及 SQL 类型映射。 */
@TableName("excel_columns")
public class ExcelColumnEntity {
    /** 列定义 UUID。 */
    @TableId(type = IdType.INPUT)
    private String id;

    /** Sheet 数据集 UUID。 */
    private String datasetId;

    /** 原始列序号，从 0 开始。 */
    private Integer columnIndex;

    /** 服务端列名，例如 c_0。 */
    private String columnName;

    /** 原始表头。 */
    private String originalHeader;

    /** TEXT、INTEGER、DECIMAL、DATE、DATETIME 或 BOOLEAN。 */
    private String dataType;

    /** 最多五个原始样例值的 JSON。 */
    private String sampleJson;

    /** 识别到的单元格格式或空字符串。 */
    private String formatHint;

    /** 创建供 Mapper 使用的实体。 */
    public ExcelColumnEntity() {}

    /** 返回列定义 UUID。 */
    public String getId() {
        return id;
    }

    /** 设置列定义 UUID。 */
    public void setId(String id) {
        this.id = id;
    }

    /** 返回Sheet 数据集 UUID。 */
    public String getDatasetId() {
        return datasetId;
    }

    /** 设置Sheet 数据集 UUID。 */
    public void setDatasetId(String datasetId) {
        this.datasetId = datasetId;
    }

    /** 返回原始列序号，从 0 开始。 */
    public Integer getColumnIndex() {
        return columnIndex;
    }

    /** 设置原始列序号，从 0 开始。 */
    public void setColumnIndex(Integer columnIndex) {
        this.columnIndex = columnIndex;
    }

    /** 返回服务端列名，例如 c_0。 */
    public String getColumnName() {
        return columnName;
    }

    /** 设置服务端列名，例如 c_0。 */
    public void setColumnName(String columnName) {
        this.columnName = columnName;
    }

    /** 返回原始表头。 */
    public String getOriginalHeader() {
        return originalHeader;
    }

    /** 设置原始表头。 */
    public void setOriginalHeader(String originalHeader) {
        this.originalHeader = originalHeader;
    }

    /** 返回TEXT、INTEGER、DECIMAL、DATE、DATETIME 或 BOOLEAN。 */
    public String getDataType() {
        return dataType;
    }

    /** 设置TEXT、INTEGER、DECIMAL、DATE、DATETIME 或 BOOLEAN。 */
    public void setDataType(String dataType) {
        this.dataType = dataType;
    }

    /** 返回最多五个原始样例值的 JSON。 */
    public String getSampleJson() {
        return sampleJson;
    }

    /** 设置最多五个原始样例值的 JSON。 */
    public void setSampleJson(String sampleJson) {
        this.sampleJson = sampleJson;
    }

    /** 返回识别到的单元格格式或空字符串。 */
    public String getFormatHint() {
        return formatHint;
    }

    /** 设置识别到的单元格格式或空字符串。 */
    public void setFormatHint(String formatHint) {
        this.formatHint = formatHint;
    }
}
