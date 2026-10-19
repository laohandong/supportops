package io.supportops.project.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.supportops.project.entity.ProjectMemberEntity;
import org.apache.ibatis.annotations.Mapper;

/** 项目成员的类型安全持久化入口。 */
@Mapper
public interface ProjectMemberMapper extends BaseMapper<ProjectMemberEntity> {}
