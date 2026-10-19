package io.supportops.outcome.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.supportops.outcome.entity.DiagnosisOutcomeRequestEntity;
import org.apache.ibatis.annotations.Mapper;

/** 原命令终局回执数据访问；业务事务由服务管理。 */
@Mapper
public interface DiagnosisOutcomeRequestMapper extends BaseMapper<DiagnosisOutcomeRequestEntity> {
}
