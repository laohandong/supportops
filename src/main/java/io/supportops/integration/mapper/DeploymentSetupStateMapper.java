package io.supportops.integration.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.supportops.integration.entity.DeploymentSetupStateEntity;
import org.apache.ibatis.annotations.Mapper;

/** 首次初始化状态的类型安全持久化入口。 */
@Mapper
public interface DeploymentSetupStateMapper extends BaseMapper<DeploymentSetupStateEntity> {}
