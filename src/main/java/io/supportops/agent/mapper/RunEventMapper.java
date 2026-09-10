package io.supportops.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import io.supportops.agent.entity.RunEventEntity;

import org.apache.ibatis.annotations.Mapper;

/** 持久化诊断事件；事件 ID 由原数据库自增序列生成。 */
@Mapper
public interface RunEventMapper extends BaseMapper<RunEventEntity> {}
