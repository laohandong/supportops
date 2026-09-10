package io.supportops.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import io.supportops.knowledge.entity.DocumentReleaseEntity;

import org.apache.ibatis.annotations.Mapper;

/** 每份文档在每个适用产品版本中的当前可检索快照的数据访问边界。 */
@Mapper
public interface DocumentReleaseMapper extends BaseMapper<DocumentReleaseEntity> {}
