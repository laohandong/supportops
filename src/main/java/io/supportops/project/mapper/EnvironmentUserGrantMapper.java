package io.supportops.project.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.supportops.project.entity.EnvironmentUserGrantEntity;
import org.apache.ibatis.annotations.Mapper;

/** 人员环境授权的类型安全持久化入口。 */
@Mapper
public interface EnvironmentUserGrantMapper extends BaseMapper<EnvironmentUserGrantEntity> {}
