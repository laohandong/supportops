package io.supportops.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import io.supportops.knowledge.entity.SourceFileEntity;

import org.apache.ibatis.annotations.Mapper;

/** 原始文件在 MinIO 中的不可变对象记录的数据访问边界。 */
@Mapper
public interface SourceFileMapper extends BaseMapper<SourceFileEntity> {}
