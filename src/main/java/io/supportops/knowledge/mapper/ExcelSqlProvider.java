package io.supportops.knowledge.mapper;

import io.supportops.knowledge.dto.CompiledSql;
import io.supportops.knowledge.dto.ExcelWriteBatch;
import io.supportops.knowledge.entity.ExcelColumnEntity;

import java.util.Map;
import java.util.StringJoiner;

/** 动态关系表的唯一 SQL 生成边界；标识符受服务端语法限制，单元格值全部参数绑定。 */
public final class ExcelSqlProvider {
    /** SQL Provider 无需外部构造依赖。 */
    public ExcelSqlProvider() {}

    /** 校验服务端生成的数据库和表名。 */
    public static String table(String schema, String table) {
        if (!schema.matches("[a-z][a-z0-9_]{0,40}") || !table.matches("xl_[a-f0-9]{32}")) {
            throw new IllegalArgumentException("INVALID_EXCEL_TABLE");
        }
        return "`" + schema + "`.`" + table + "`";
    }

    /** 物理列名由零基序号产生，不使用原始表头拼 SQL。 */
    public static String column(String name) {
        if (!name.matches("c_[0-9]{1,3}|source_row")) {
            throw new IllegalArgumentException("INVALID_EXCEL_COLUMN");
        }
        return "`" + name + "`";
    }

    /** 建表语句只使用枚举映射的 SQL 类型。 */
    public String create(Map<String, Object> arguments) {
        ExcelWriteBatch batch = (ExcelWriteBatch) arguments.get("batch");
        StringJoiner fields = new StringJoiner(",");
        fields.add("`source_row` INT NOT NULL PRIMARY KEY");
        for (ExcelColumnEntity column : batch.columns()) {
            fields.add(
                    column(column.getColumnName()) + " " + sqlType(column.getDataType()) + " NULL");
        }
        return "CREATE TABLE "
                + table(batch.schema(), batch.table())
                + " ("
                + fields
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin";
    }

    /** 类型使用明确的精度及可空规则。 */
    private String sqlType(String type) {
        return switch (type) {
            case "INTEGER" -> "BIGINT";
            case "DECIMAL" -> "DECIMAL(38,10)";
            case "DATE" -> "DATE";
            case "DATETIME" -> "DATETIME(6)";
            case "BOOLEAN" -> "BOOLEAN";
            case "TEXT" -> "MEDIUMTEXT";
            default -> throw new IllegalArgumentException("INVALID_EXCEL_TYPE");
        };
    }

    /** 一批最多 500 行，所有单元格均使用预编译绑定。 */
    public String insert(Map<String, Object> arguments) {
        ExcelWriteBatch batch = (ExcelWriteBatch) arguments.get("batch");
        if (batch.rows().isEmpty() || batch.rows().size() > 500) {
            throw new IllegalArgumentException("INVALID_EXCEL_BATCH");
        }
        StringJoiner columns = new StringJoiner(",");
        columns.add("source_row");
        for (ExcelColumnEntity column : batch.columns()) {
            columns.add(column(column.getColumnName()));
        }
        StringJoiner rows = new StringJoiner(",");
        for (int row = 0; row < batch.rows().size(); row++) {
            StringJoiner values = new StringJoiner(",", "(", ")");
            values.add("#{batch.rows[" + row + "].sourceRow}");
            for (int col = 0; col < batch.columns().size(); col++) {
                values.add("#{batch.rows[" + row + "].values[" + col + "],jdbcType=VARCHAR}");
            }
            rows.add(values.toString());
        }
        return "INSERT INTO "
                + table(batch.schema(), batch.table())
                + " ("
                + columns
                + ") VALUES "
                + rows;
    }

    /** 只清理已登记且满足命名规则的表。 */
    public String drop(Map<String, Object> arguments) {
        return "DROP TABLE IF EXISTS "
                + table((String) arguments.get("schema"), (String) arguments.get("table"));
    }

    /** 执行入口只接受受控编译结果，不接受用户或模型的原始 SQL。 */
    public String query(Map<String, Object> arguments) {
        return ((CompiledSql) arguments.get("query")).sql();
    }
}
