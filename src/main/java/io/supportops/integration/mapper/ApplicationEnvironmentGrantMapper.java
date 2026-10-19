package io.supportops.integration.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.supportops.integration.entity.ApplicationEnvironmentGrantEntity;
import org.apache.ibatis.annotations.Mapper;

/** 应用环境授权的类型安全持久化入口。 */
@Mapper
public interface ApplicationEnvironmentGrantMapper extends BaseMapper<ApplicationEnvironmentGrantEntity> {}
