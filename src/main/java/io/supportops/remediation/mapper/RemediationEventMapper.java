package io.supportops.remediation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.supportops.remediation.entity.RemediationEventEntity;
import org.apache.ibatis.annotations.Mapper;

/** 修复Event记录的数据访问；业务事务由服务层管理。 */
@Mapper
public interface RemediationEventMapper extends BaseMapper<RemediationEventEntity> {
}
