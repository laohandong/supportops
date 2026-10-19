package io.supportops.remediation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.supportops.remediation.entity.RemediationDecisionEntity;
import org.apache.ibatis.annotations.Mapper;

/** 修复Decision记录的数据访问；业务事务由服务层管理。 */
@Mapper
public interface RemediationDecisionMapper extends BaseMapper<RemediationDecisionEntity> {
}
