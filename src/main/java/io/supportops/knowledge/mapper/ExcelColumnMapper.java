package io.supportops.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import io.supportops.knowledge.entity.ExcelColumnEntity;

import org.apache.ibatis.annotations.Mapper;

/** 保留原始 Excel 列名、单元格语义及 SQL 类型映射的数据访问边界。 */
@Mapper
public interface ExcelColumnMapper extends BaseMapper<ExcelColumnEntity> {}
