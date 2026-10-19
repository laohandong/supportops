package io.supportops.remediation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.supportops.remediation.entity.RemediationPlanEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Param;
import java.util.List;

/** 修复Plan记录的数据访问；业务事务由服务层管理。 */
@Mapper
public interface RemediationPlanMapper extends BaseMapper<RemediationPlanEntity> {
    /** 只读来源关联提案，参数绑定分页不枚举其他诊断。 */
    @Select("SELECT * FROM remediation_plans WHERE source_run_id=#{sourceRunId} ORDER BY created_at DESC,id DESC LIMIT #{size} OFFSET #{offset}")
    List<RemediationPlanEntity> outcomePage(@Param("sourceRunId") String sourceRunId,
            @Param("offset") long offset, @Param("size") int size);
    /** 在短事务中锁定原提案，网络调用前释放行锁。 */
    @Select("SELECT * FROM remediation_plans WHERE id=#{id} FOR UPDATE")
    RemediationPlanEntity lock(String id);
}
