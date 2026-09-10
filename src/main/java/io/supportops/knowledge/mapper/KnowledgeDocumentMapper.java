package io.supportops.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import io.supportops.knowledge.entity.KnowledgeDocumentEntity;
import io.supportops.knowledge.vo.KnowledgeDocument;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 文档存储与含分片数量的只读投影查询。 */
@Mapper
public interface KnowledgeDocumentMapper extends BaseMapper<KnowledgeDocumentEntity> {
    /** 按上传时间倒序读取文档及分片数量。 */
    List<KnowledgeDocument> selectDocuments();

    /** 按 UUID 读取单份文档及分片数量，不存在时返回 null。 */
    KnowledgeDocument selectDocument(@Param("id") String id);
}
