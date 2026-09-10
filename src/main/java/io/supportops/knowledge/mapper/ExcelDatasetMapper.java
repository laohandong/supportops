package io.supportops.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import io.supportops.knowledge.entity.ExcelDatasetEntity;

import org.apache.ibatis.annotations.Mapper;

/** Excel Sheet 的隔离物理表与原始坐标元数据的数据访问边界。 */
@Mapper
public interface ExcelDatasetMapper extends BaseMapper<ExcelDatasetEntity> {}
