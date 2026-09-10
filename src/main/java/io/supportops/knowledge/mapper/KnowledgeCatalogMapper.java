package io.supportops.knowledge.mapper;

import io.supportops.knowledge.dto.ChunkSource;
import io.supportops.knowledge.entity.KnowledgeDocumentEntity;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 文档发布和索引所需的参数绑定连接查询及短事务锁。 */
@Mapper
public interface KnowledgeCatalogMapper {
    /** 锁定逻辑文档，在短事务内分配修订号或发布批次。 */
    KnowledgeDocumentEntity lockDocument(@Param("id") String id);

    /** 返回批次内分片及来源元数据，按数字序号排序并分页。 */
    List<ChunkSource> sources(
            @Param("batchId") String batchId, @Param("after") int after, @Param("limit") int limit);

    /** 返回可检索的批次 ID，版本过滤在数据库执行。 */
    List<String> applicableBatches(@Param("version") String version);
}
