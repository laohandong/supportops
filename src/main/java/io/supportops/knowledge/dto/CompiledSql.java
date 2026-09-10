package io.supportops.knowledge.dto;

import java.util.List;
import java.util.Map;

/** SQL 编译器输出的受控查询；绑定值仅限 SQL 字符串与数字字面量。 */
public record CompiledSql(
        String sql,
        Map<String, Object> bindings,
        List<String> columns,
        int limit,
        String displaySql) {}
