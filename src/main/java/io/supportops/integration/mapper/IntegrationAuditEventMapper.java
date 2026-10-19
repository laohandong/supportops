package io.supportops.integration.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.supportops.integration.entity.IntegrationAuditEventEntity;
import org.apache.ibatis.annotations.Mapper;

/** 追加接入审计的类型安全持久化入口。 */
@Mapper
public interface IntegrationAuditEventMapper extends BaseMapper<IntegrationAuditEventEntity> {}
