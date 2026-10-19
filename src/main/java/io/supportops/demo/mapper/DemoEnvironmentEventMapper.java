package io.supportops.demo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.supportops.demo.entity.DemoEnvironmentEventEntity;
import org.apache.ibatis.annotations.Mapper;

/** 场景审计记录的类型安全持久化；事务编排留在服务层。 */
@Mapper
public interface DemoEnvironmentEventMapper extends BaseMapper<DemoEnvironmentEventEntity> {}
