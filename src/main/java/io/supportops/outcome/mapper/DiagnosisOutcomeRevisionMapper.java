package io.supportops.outcome.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.supportops.outcome.entity.DiagnosisOutcomeRevisionEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Param;
import java.util.List;

/** 不可变处理修订数据访问；业务事务由服务管理。 */
@Mapper
public interface DiagnosisOutcomeRevisionMapper extends BaseMapper<DiagnosisOutcomeRevisionEntity> {
    /** 来源行已加锁后使用当前读，避免默认隔离级别的旧快照漏掉刚提交修订。 */
    @Select("SELECT * FROM diagnosis_outcome_revisions WHERE id=#{id} FOR SHARE")
    DiagnosisOutcomeRevisionEntity current(@Param("id") String id);

    /** 不可变结果历史按修订号倒序分页。 */
    @Select("SELECT * FROM diagnosis_outcome_revisions WHERE outcome_id=#{id} ORDER BY revision_number DESC LIMIT #{size} OFFSET #{offset}")
    List<DiagnosisOutcomeRevisionEntity> page(@Param("id") String id, @Param("offset") long offset, @Param("size") int size);
}
