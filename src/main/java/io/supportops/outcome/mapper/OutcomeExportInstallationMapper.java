package io.supportops.outcome.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 单例安装身份只由服务端生成；锁住导出请求短事务。 */
@Mapper
public interface OutcomeExportInstallationMapper {
    /** 锁定安装身份，使同键并发导出稳定返回原回执。 */
    @Select("SELECT source_system_id FROM outcome_export_installation WHERE id=1 FOR UPDATE")
    String lock();

    /** 首次导出设置身份，后续不得覆盖。 */
    @Update(
            "UPDATE outcome_export_installation SET source_system_id=#{id} WHERE id=1 AND"
                + " source_system_id IS NULL")
    int initialize(String id);
}
