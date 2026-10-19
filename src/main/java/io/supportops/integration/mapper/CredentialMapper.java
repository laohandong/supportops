package io.supportops.integration.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.supportops.integration.entity.CredentialEntity;
import org.apache.ibatis.annotations.Mapper;

/** 加密凭据的类型安全持久化入口。 */
@Mapper
public interface CredentialMapper extends BaseMapper<CredentialEntity> {}
