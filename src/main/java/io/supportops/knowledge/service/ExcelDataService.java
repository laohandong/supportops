package io.supportops.knowledge.service;

import io.supportops.knowledge.dto.ImportOptions;
import io.supportops.knowledge.entity.IndexTaskEntity;
import io.supportops.knowledge.entity.ProcessingBatchEntity;
import io.supportops.knowledge.vo.KnowledgeViews;

import java.io.InputStream;
import java.util.List;
import java.util.function.Supplier;

/** Excel 解析、预览、关系表发布与受限查询边界。 */
public interface ExcelDataService {
    /** 分两次流式读取：先推断类型，再向暂存关系表批量写入。 */
    long ingest(
            ProcessingBatchEntity batch,
            ImportOptions options,
            Supplier<InputStream> original,
            IndexTaskEntity task);

    /** 返回指定批次的 Sheet 结构与样例。 */
    List<KnowledgeViews.Dataset> datasets(String batchId);

    /** 返回当前产品版本可查询的已发布数据集，模型仅看到注册结构。 */
    List<KnowledgeViews.Dataset> applicable(String productVersion);
    /** 企业目录只能列出本系统发布的 Sheet。 */
    default List<KnowledgeViews.Dataset> applicableScoped(String systemId, String productVersion) {
        if (!io.supportops.integration.service.support.EnterpriseIds.SYSTEM.equals(systemId)) { throw new IllegalArgumentException("SYSTEM_SCOPE_REQUIRED"); }
        return applicable(productVersion);
    }

    /** 校验单表 SELECT 并用独立只读身份执行，保留来源。 */
    KnowledgeViews.SqlResult query(String datasetId, String sql, String productVersion);
    /** 在执行 SQL 前再次核对数据集系统范围。 */
    default KnowledgeViews.SqlResult queryScoped(String datasetId, String sql, String systemId, String productVersion) {
        if (!io.supportops.integration.service.support.EnterpriseIds.SYSTEM.equals(systemId)) { throw new IllegalArgumentException("SYSTEM_SCOPE_REQUIRED"); }
        return query(datasetId, sql, productVersion);
    }

    /** 清理服务端注册的 Excel 物理表。 */
    void delete(String documentId);
}
