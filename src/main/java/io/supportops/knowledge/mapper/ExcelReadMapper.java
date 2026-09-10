package io.supportops.knowledge.mapper;

import io.supportops.knowledge.dto.CompiledSql;

import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.SelectProvider;

import java.util.List;
import java.util.Map;

/** 动态 SQL 结果行遵循已校验的列清单；此 Mapper 仅由独立只读会话注册。 */
public interface ExcelReadMapper {
    /** 返回动态列名到数据库标量值的映射，执行时间上限五秒。 */
    @SelectProvider(type = ExcelSqlProvider.class, method = "query")
    @Options(timeout = 5, fetchSize = 201)
    List<Map<String, Object>> query(@Param("query") CompiledSql query);
}
