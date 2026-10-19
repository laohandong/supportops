package io.supportops.project.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.supportops.project.entity.ManagedSystemEntity;
import org.apache.ibatis.annotations.Mapper;

/** 被诊断系统的类型安全持久化入口。 */
@Mapper
public interface ManagedSystemMapper extends BaseMapper<ManagedSystemEntity> {}
