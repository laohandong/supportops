package io.supportops.remediation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.supportops.remediation.entity.RemediationExecutionEntity;
import org.apache.ibatis.annotations.Mapper;

/** 修复Execution记录的数据访问；业务事务由服务层管理。 */
@Mapper
public interface RemediationExecutionMapper extends BaseMapper<RemediationExecutionEntity> {
}
