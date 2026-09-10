package io.supportops.knowledge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** Excel Sheet 的隔离物理表与原始坐标元数据。 */
@TableName("excel_datasets")
public class ExcelDatasetEntity {
    /** 数据集 UUID。 */
    @TableId(type = IdType.INPUT)
    private String id;

    /** 逻辑文档 UUID。 */
    private String documentId;

    /** 修订 UUID。 */
    private String versionId;

    /** 处理批次 UUID。 */
    private String batchId;

    /** Sheet 序号，从 0 开始。 */
    private Integer sheetIndex;

    /** 原始工作表名称。 */
    private String sheetName;

    /** 表头所在原始行号，从 1 开始。 */
    private Integer headerRow;

    /** 仅由服务端 UUID 生成的物理表名。 */
    private String tableName;

    /** 有效数据行数。 */
    private Long rowCount;

    /** PREVIEW、STAGING、READY、FAILED 或 DELETED。 */
    private String status;

    /** 创建时间。 */
    private String createdAt;

    /** 创建供 Mapper 使用的实体。 */
    public ExcelDatasetEntity() {}

    /** 返回数据集 UUID。 */
    public String getId() {
        return id;
    }

    /** 设置数据集 UUID。 */
    public void setId(String id) {
        this.id = id;
    }

    /** 返回逻辑文档 UUID。 */
    public String getDocumentId() {
        return documentId;
    }

    /** 设置逻辑文档 UUID。 */
    public void setDocumentId(String documentId) {
        this.documentId = documentId;
    }

    /** 返回修订 UUID。 */
    public String getVersionId() {
        return versionId;
    }

    /** 设置修订 UUID。 */
    public void setVersionId(String versionId) {
        this.versionId = versionId;
    }

    /** 返回处理批次 UUID。 */
    public String getBatchId() {
        return batchId;
    }

    /** 设置处理批次 UUID。 */
    public void setBatchId(String batchId) {
        this.batchId = batchId;
    }

    /** 返回Sheet 序号，从 0 开始。 */
    public Integer getSheetIndex() {
        return sheetIndex;
    }

    /** 设置Sheet 序号，从 0 开始。 */
    public void setSheetIndex(Integer sheetIndex) {
        this.sheetIndex = sheetIndex;
    }

    /** 返回原始工作表名称。 */
    public String getSheetName() {
        return sheetName;
    }

    /** 设置原始工作表名称。 */
    public void setSheetName(String sheetName) {
        this.sheetName = sheetName;
    }

    /** 返回表头所在原始行号，从 1 开始。 */
    public Integer getHeaderRow() {
        return headerRow;
    }

    /** 设置表头所在原始行号，从 1 开始。 */
    public void setHeaderRow(Integer headerRow) {
        this.headerRow = headerRow;
    }

    /** 返回仅由服务端 UUID 生成的物理表名。 */
    public String getTableName() {
        return tableName;
    }

    /** 设置仅由服务端 UUID 生成的物理表名。 */
    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    /** 返回有效数据行数。 */
    public Long getRowCount() {
        return rowCount;
    }

    /** 设置有效数据行数。 */
    public void setRowCount(Long rowCount) {
        this.rowCount = rowCount;
    }

    /** 返回PREVIEW、STAGING、READY、FAILED 或 DELETED。 */
    public String getStatus() {
        return status;
    }

    /** 设置PREVIEW、STAGING、READY、FAILED 或 DELETED。 */
    public void setStatus(String status) {
        this.status = status;
    }

    /** 返回创建时间。 */
    public String getCreatedAt() {
        return createdAt;
    }

    /** 设置创建时间。 */
    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }
}
