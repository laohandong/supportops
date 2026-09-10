package io.supportops.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import io.supportops.knowledge.entity.IndexTaskEntity;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 带租约的持久化后台任务，调度与补偿共用同一领取入口的数据访问边界。 */
@Mapper
public interface IndexTaskMapper extends BaseMapper<IndexTaskEntity> {
    /** 发布或批量写入事务内锁定执行权，防止检查后被另一个实例重新领取。 */
    @Select("SELECT * FROM knowledge_tasks WHERE id=#{id} FOR UPDATE")
    IndexTaskEntity lock(@Param("id") String id);

    /** 人工重试前检查文档仍然存活，清理任务除外。 */
    @Select(
            "SELECT COUNT(*) FROM knowledge_tasks t JOIN documents d ON d.id=t.document_id WHERE"
                    + " t.id=#{id} AND (d.deleted=FALSE OR t.kind='CLEANUP')")
    int retryableDocument(@Param("id") String id);
}
