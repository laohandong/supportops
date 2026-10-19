package io.supportops.outcome.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import io.supportops.outcome.entity.OutcomeExportRequestEntity;

import org.apache.ibatis.annotations.Mapper;

/** OutcomeExportRequestEntity 的持久化访问，条件更新由业务服务控制。 */
@Mapper
public interface OutcomeExportRequestMapper extends BaseMapper<OutcomeExportRequestEntity> {}
