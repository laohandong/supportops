package io.supportops.knowledge.mapper;

import io.supportops.knowledge.dto.ExcelWriteBatch;

import org.apache.ibatis.annotations.InsertProvider;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.UpdateProvider;

/** Excel 导入身份的数据访问；DDL 不依赖事务回滚，通过未发布表和补偿隔离。 */
@Mapper
public interface ExcelTableMapper {
    /** 创建未发布的物理表。 */
    @UpdateProvider(type = ExcelSqlProvider.class, method = "create")
    void create(@Param("batch") ExcelWriteBatch batch);

    /** 参数绑定批量写入数据行。 */
    @InsertProvider(type = ExcelSqlProvider.class, method = "insert")
    int insert(@Param("batch") ExcelWriteBatch batch);

    /** 幂等清理已登记物理表。 */
    @UpdateProvider(type = ExcelSqlProvider.class, method = "drop")
    void drop(@Param("schema") String schema, @Param("table") String table);
}
