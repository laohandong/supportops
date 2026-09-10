package io.supportops.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import io.supportops.knowledge.entity.TaskAttemptEntity;

import org.apache.ibatis.annotations.Mapper;

/** 每次后台执行的不可变历史和结束结果的数据访问边界。 */
@Mapper
public interface TaskAttemptMapper extends BaseMapper<TaskAttemptEntity> {}
