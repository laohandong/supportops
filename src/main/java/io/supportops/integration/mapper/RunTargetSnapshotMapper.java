package io.supportops.integration.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.supportops.integration.entity.RunTargetSnapshotEntity;
import org.apache.ibatis.annotations.Mapper;

/** 不可变目标快照的类型安全持久化入口。 */
@Mapper
public interface RunTargetSnapshotMapper extends BaseMapper<RunTargetSnapshotEntity> {}
