package io.supportops.memory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import io.supportops.memory.entity.ProjectMemoryEntity;

import org.apache.ibatis.annotations.Mapper;

/** 项目记忆的类型安全增删改查入口。 */
@Mapper
public interface ProjectMemoryMapper extends BaseMapper<ProjectMemoryEntity> {}
