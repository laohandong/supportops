package io.supportops.integration.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.supportops.integration.entity.ConnectorTestEntity;
import org.apache.ibatis.annotations.Mapper;

/** 连接分项测试的类型安全持久化入口。 */
@Mapper
public interface ConnectorTestMapper extends BaseMapper<ConnectorTestEntity> {}
