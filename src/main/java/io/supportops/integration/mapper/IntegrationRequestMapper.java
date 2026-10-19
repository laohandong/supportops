package io.supportops.integration.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.supportops.integration.entity.IntegrationRequestEntity;
import org.apache.ibatis.annotations.Mapper;

/** 固定诊断请求的类型安全持久化入口。 */
@Mapper
public interface IntegrationRequestMapper extends BaseMapper<IntegrationRequestEntity> {}
