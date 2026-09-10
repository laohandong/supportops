package io.supportops.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import io.supportops.knowledge.entity.ProcessingBatchEntity;

import org.apache.ibatis.annotations.Mapper;

/** 一次解析、分片或 Excel 导入配置及发布状态的数据访问边界。 */
@Mapper
public interface ProcessingBatchMapper extends BaseMapper<ProcessingBatchEntity> {}
