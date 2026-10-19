package io.supportops.project.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.supportops.project.entity.SystemEnvironmentEntity;
import org.apache.ibatis.annotations.Mapper;

/** 诊断环境的类型安全持久化入口。 */
@Mapper
public interface SystemEnvironmentMapper extends BaseMapper<SystemEnvironmentEntity> {}
