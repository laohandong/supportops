package io.supportops.integration.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.supportops.integration.entity.DeploymentRuntimeGuardEntity;
import org.apache.ibatis.annotations.Mapper;

/** 单实例执行保护的类型安全持久化入口。 */
@Mapper
public interface DeploymentRuntimeGuardMapper extends BaseMapper<DeploymentRuntimeGuardEntity> {}
