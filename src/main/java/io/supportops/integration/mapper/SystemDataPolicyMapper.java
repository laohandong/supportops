package io.supportops.integration.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.supportops.integration.entity.SystemDataPolicyEntity;
import org.apache.ibatis.annotations.Mapper;

/** 系统外发策略数据访问，不承担批准编排。 */
@Mapper
public interface SystemDataPolicyMapper extends BaseMapper<SystemDataPolicyEntity> {}
