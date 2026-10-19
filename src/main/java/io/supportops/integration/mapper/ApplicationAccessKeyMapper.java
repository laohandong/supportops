package io.supportops.integration.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.supportops.integration.entity.ApplicationAccessKeyEntity;
import org.apache.ibatis.annotations.Mapper;

/** 访问密钥摘要的类型安全持久化入口。 */
@Mapper
public interface ApplicationAccessKeyMapper extends BaseMapper<ApplicationAccessKeyEntity> {}
