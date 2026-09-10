package io.supportops.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import io.supportops.knowledge.entity.DocumentVersionEntity;

import org.apache.ibatis.annotations.Mapper;

/** 逻辑文档的不可变修订及适用产品版本的数据访问边界。 */
@Mapper
public interface DocumentVersionMapper extends BaseMapper<DocumentVersionEntity> {}
