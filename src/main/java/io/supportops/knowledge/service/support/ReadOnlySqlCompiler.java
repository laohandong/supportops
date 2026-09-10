package io.supportops.knowledge.service.support;

import io.supportops.knowledge.dto.CompiledSql;
import io.supportops.knowledge.mapper.ExcelSqlProvider;

import net.sf.jsqlparser.expression.BinaryExpression;
import net.sf.jsqlparser.expression.DoubleValue;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.NullValue;
import net.sf.jsqlparser.expression.Parenthesis;
import net.sf.jsqlparser.expression.SignedExpression;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.expression.operators.relational.Between;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.expression.operators.relational.InExpression;
import net.sf.jsqlparser.expression.operators.relational.IsNullExpression;
import net.sf.jsqlparser.expression.operators.relational.LikeExpression;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.AllColumns;
import net.sf.jsqlparser.statement.select.OrderByElement;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.SelectItem;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 将受限 SELECT AST 重建为参数化 SQL；未知语法一律拒绝，不执行模型原文。 */
@Component
public class ReadOnlySqlCompiler {
    /** 仅允许当前注册 Sheet 的单表查询，data 为对模型公开的固定表别名。 */
    public CompiledSql compile(String sql, String schema, String table, List<String> columns) {
        if (sql == null
                || sql.isBlank()
                || sql.length() > 8000
                || sql.contains(";")
                || sql.contains("--")
                || sql.contains("/*")) {
            throw rejected();
        }
        try {
            Statement statement = CCJSqlParserUtil.parse(sql);
            if (!(statement instanceof PlainSelect select)) {
                throw rejected();
            }
            return new Builder(columns).build(select, ExcelSqlProvider.table(schema, table), sql);
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw rejected();
        }
    }

    /** 固定拒绝码不泄露数据库结构。 */
    private static IllegalArgumentException rejected() {
        return new IllegalArgumentException("SQL_POLICY_REJECTED");
    }

    /** 每次编译独立的绑定值和符号表，避免请求之间共享数据。 */
    private static final class Builder {
        private final List<String> dataColumns;
        private final Set<String> allowed;
        private final Set<String> aliases = new HashSet<>();
        private final Map<String, Object> values = new LinkedHashMap<>();
        private int nodes;
        private boolean aggregate;

        /** 注册当前表允许访问的列。 */
        private Builder(List<String> columns) {
            dataColumns = new ArrayList<>(columns);
            dataColumns.add("source_row");
            allowed = new HashSet<>(dataColumns);
        }

        /** 只输出支持的 SELECT 子集，不保留注释、Hint、锁、导出和任意连接。 */
        private CompiledSql build(PlainSelect select, String physical, String display) {
            if (!(select.getFromItem() instanceof Table from)
                    || !from.getFullyQualifiedName().equalsIgnoreCase("data")
                    || from.getAlias() != null
                    || select.getJoins() != null
                    || select.getWithItemsList() != null
                    || select.getIntoTables() != null
                    || select.getIntoTempTable() != null
                    || select.getForMode() != null
                    || select.getForUpdateTable() != null
                    || select.getOracleHint() != null
                    || select.getTop() != null
                    || select.getFetch() != null
                    || select.getQualify() != null
                    || select.getWindowDefinitions() != null
                    || select.getOracleHierarchical() != null) {
                throw rejected();
            }
            List<String> projections = new ArrayList<>();
            List<String> labels = new ArrayList<>();
            for (SelectItem<?> item : select.getSelectItems()) {
                if (item.getExpression() instanceof AllColumns) {
                    if (item.getAlias() != null) {
                        throw rejected();
                    }
                    for (String column : dataColumns) {
                        projections.add(ExcelSqlProvider.column(column));
                        labels.add(column);
                    }
                } else {
                    String expression = expression(item.getExpression(), false, 0);
                    String label =
                            item.getAlias() != null
                                    ? item.getAlias().getName()
                                    : item.getExpression() instanceof Column column
                                            ? column.getColumnName()
                                            : "value_" + labels.size();
                    if (!label.matches("[A-Za-z_][A-Za-z0-9_]{0,50}") || labels.contains(label)) {
                        throw rejected();
                    }
                    aliases.add(label);
                    projections.add(expression + " AS `" + label + "`");
                    labels.add(label);
                }
            }
            if (!aggregate
                    && select.getDistinct() == null
                    && select.getGroupBy() == null
                    && !labels.contains("source_row")) {
                projections.add("`source_row`");
                labels.add("source_row");
            }
            if (labels.isEmpty()
                    || labels.size() > 201
                    || new HashSet<>(labels).size() != labels.size()) {
                throw rejected();
            }
            StringBuilder result = new StringBuilder("SELECT /*+ MAX_EXECUTION_TIME(3000) */ ");
            if (select.getDistinct() != null) {
                if (select.getDistinct().getOnSelectItems() != null) {
                    throw rejected();
                }
                result.append("DISTINCT ");
            }
            result.append(String.join(",", projections)).append(" FROM ").append(physical);
            if (select.getWhere() != null) {
                result.append(" WHERE ").append(expression(select.getWhere(), false, 0));
            }
            if (select.getGroupBy() != null) {
                List<String> group = new ArrayList<>();
                ExpressionList<?> expressions = select.getGroupBy().getGroupByExpressionList();
                for (Expression item : expressions) {
                    group.add(expression(item, false, 0));
                }
                if (group.isEmpty() || group.size() > 10) {
                    throw rejected();
                }
                result.append(" GROUP BY ").append(String.join(",", group));
            }
            if (select.getHaving() != null) {
                result.append(" HAVING ").append(expression(select.getHaving(), true, 0));
            }
            if (select.getOrderByElements() != null) {
                List<String> order = new ArrayList<>();
                for (OrderByElement item : select.getOrderByElements()) {
                    order.add(
                            expression(item.getExpression(), true, 0)
                                    + (item.isAsc() ? " ASC" : " DESC"));
                }
                result.append(" ORDER BY ").append(String.join(",", order));
            }
            int limit = 200;
            long offset = 0;
            if (select.getLimit() != null) {
                if (!(select.getLimit().getRowCount() instanceof LongValue count)) {
                    throw rejected();
                }
                if (count.getValue() < 1) {
                    throw rejected();
                }
                limit = (int) Math.min(200, count.getValue());
                if (select.getLimit().getOffset() != null) {
                    if (!(select.getLimit().getOffset() instanceof LongValue start)) {
                        throw rejected();
                    }
                    offset = start.getValue();
                }
            }
            if (select.getOffset() != null) {
                if (!(select.getOffset().getOffset() instanceof LongValue start)) {
                    throw rejected();
                }
                offset = start.getValue();
            }
            if (offset < 0 || offset > 100000) {
                throw rejected();
            }
            result.append(" LIMIT ").append(limit + 1).append(" OFFSET ").append(offset);
            return new CompiledSql(result.toString(), values, List.copyOf(labels), limit, display);
        }

        /** 逐节点编译表达式，未知函数和子查询不会到达数据库。 */
        private String expression(Expression item, boolean useAliases, int depth) {
            if (++nodes > 500 || depth > 24) {
                throw rejected();
            }
            if (item instanceof Column column) {
                String name = column.getColumnName();
                if (column.getTable() != null
                        && column.getTable().getName() != null
                        && !column.getTable().getName().isEmpty()
                        && !column.getTable().getName().equalsIgnoreCase("data")) {
                    throw rejected();
                }
                if (allowed.contains(name)) {
                    return ExcelSqlProvider.column(name);
                }
                if (useAliases && aliases.contains(name)) {
                    return "`" + name + "`";
                }
                throw rejected();
            }
            if (item instanceof StringValue value) {
                return bind(value.getValue());
            }
            if (item instanceof LongValue value) {
                return bind(value.getBigIntegerValue());
            }
            if (item instanceof DoubleValue value) {
                return bind(new BigDecimal(value.toString()));
            }
            if (item instanceof NullValue) {
                return "NULL";
            }
            if (item instanceof Parenthesis value) {
                return "(" + expression(value.getExpression(), useAliases, depth + 1) + ")";
            }
            if (item instanceof SignedExpression signed
                    && (signed.getSign() == '-' || signed.getSign() == '+')) {
                return signed.getSign() + expression(signed.getExpression(), useAliases, depth + 1);
            }
            if (item instanceof Function function) {
                String name = function.getName().toUpperCase(Locale.ROOT);
                if (!Set.of(
                                "COUNT",
                                "SUM",
                                "AVG",
                                "MIN",
                                "MAX",
                                "YEAR",
                                "MONTH",
                                "DATE",
                                "DATE_FORMAT")
                        .contains(name)) {
                    throw rejected();
                }
                if (Set.of("COUNT", "SUM", "AVG", "MIN", "MAX").contains(name)) {
                    aggregate = true;
                }
                List<String> arguments = new ArrayList<>();
                if (function.getParameters() != null) {
                    for (Expression argument : function.getParameters()) {
                        if (argument instanceof AllColumns && name.equals("COUNT")) {
                            arguments.add("*");
                        } else {
                            arguments.add(expression(argument, false, depth + 1));
                        }
                    }
                }
                if (arguments.size() != (name.equals("DATE_FORMAT") ? 2 : 1)) {
                    throw rejected();
                }
                return name
                        + "("
                        + (function.isDistinct() ? "DISTINCT " : "")
                        + String.join(",", arguments)
                        + ")";
            }
            if (item instanceof LikeExpression like) {
                if (like.isCaseInsensitive()
                        || like.isUseBinary()
                        || !like.getStringExpression().equals("LIKE")) {
                    throw rejected();
                }
                String escape =
                        like.getEscape() == null
                                ? ""
                                : " ESCAPE " + expression(like.getEscape(), false, depth + 1);
                return "("
                        + expression(like.getLeftExpression(), useAliases, depth + 1)
                        + (like.isNot() ? " NOT LIKE " : " LIKE ")
                        + expression(like.getRightExpression(), useAliases, depth + 1)
                        + escape
                        + ")";
            }
            if (item instanceof BinaryExpression binary) {
                String operator = binary.getStringExpression().toUpperCase(Locale.ROOT);
                if (!Set.of("=", "<>", "!=", ">", ">=", "<", "<=", "AND", "OR", "+", "-", "*", "/")
                        .contains(operator)) {
                    throw rejected();
                }
                return "("
                        + expression(binary.getLeftExpression(), useAliases, depth + 1)
                        + " "
                        + operator
                        + " "
                        + expression(binary.getRightExpression(), useAliases, depth + 1)
                        + ")";
            }
            if (item instanceof IsNullExpression test) {
                return expression(test.getLeftExpression(), useAliases, depth + 1)
                        + (test.isNot() ? " IS NOT NULL" : " IS NULL");
            }
            if (item instanceof Between between) {
                return "("
                        + expression(between.getLeftExpression(), useAliases, depth + 1)
                        + (between.isNot() ? " NOT BETWEEN " : " BETWEEN ")
                        + expression(between.getBetweenExpressionStart(), useAliases, depth + 1)
                        + " AND "
                        + expression(between.getBetweenExpressionEnd(), useAliases, depth + 1)
                        + ")";
            }
            if (item instanceof InExpression in
                    && in.getRightExpression() instanceof ExpressionList<?> list) {
                List<String> items = new ArrayList<>();
                for (Expression value : list) {
                    items.add(expression(value, useAliases, depth + 1));
                }
                if (items.isEmpty() || items.size() > 100) {
                    throw rejected();
                }
                return expression(in.getLeftExpression(), useAliases, depth + 1)
                        + (in.isNot() ? " NOT IN (" : " IN (")
                        + String.join(",", items)
                        + ")";
            }
            throw rejected();
        }

        /** 字面量全部放入绑定集合，不自行转义后拼接。 */
        private String bind(Object value) {
            String key = "p" + values.size();
            values.put(key, value);
            return "#{query.bindings." + key + "}";
        }
    }
}
