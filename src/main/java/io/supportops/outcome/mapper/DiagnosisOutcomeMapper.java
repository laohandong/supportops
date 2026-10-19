package io.supportops.outcome.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.supportops.outcome.entity.DiagnosisOutcomeEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Param;

/** 处理结果主记录数据访问；业务事务由服务管理。 */
@Mapper
public interface DiagnosisOutcomeMapper extends BaseMapper<DiagnosisOutcomeEntity> {
    /** 在调用方短事务内锁定主记录；不存在时返回空。 */
    @Select("SELECT * FROM diagnosis_outcomes WHERE run_id = #{id} FOR UPDATE")
    DiagnosisOutcomeEntity lock(@Param("id") String id);
}
