package io.supportops.integration.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.supportops.integration.entity.ManagementRequestEntity;
import org.apache.ibatis.annotations.Mapper;

/** 安全管理回执的类型安全持久化入口。 */
@Mapper
public interface ManagementRequestMapper extends BaseMapper<ManagementRequestEntity> {}
