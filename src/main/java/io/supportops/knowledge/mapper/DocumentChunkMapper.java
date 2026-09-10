package io.supportops.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import io.supportops.knowledge.dto.StoredPassage;
import io.supportops.knowledge.entity.DocumentChunkEntity;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 分片存储与版本隔离查询，过滤在正文进入模型前完成。 */
@Mapper
public interface DocumentChunkMapper extends BaseMapper<DocumentChunkEntity> {
    /** 仅返回目标版本或通用资料的片段，按引用 ID 升序。 */
    List<StoredPassage> selectApplicablePassages(@Param("version") String version);
}
