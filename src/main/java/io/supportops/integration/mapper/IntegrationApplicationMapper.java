package io.supportops.integration.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.supportops.integration.entity.IntegrationApplicationEntity;
import org.apache.ibatis.annotations.Mapper;

/** 调用应用身份的类型安全持久化入口。 */
@Mapper
public interface IntegrationApplicationMapper extends BaseMapper<IntegrationApplicationEntity> {}
