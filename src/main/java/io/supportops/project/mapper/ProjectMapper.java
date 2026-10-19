package io.supportops.project.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.supportops.project.entity.ProjectEntity;
import org.apache.ibatis.annotations.Mapper;

/** 企业项目的类型安全持久化入口。 */
@Mapper
public interface ProjectMapper extends BaseMapper<ProjectEntity> {}
