package io.supportops.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.supportops.persistence.KnowledgeTestResources;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

/** 从真实V11数据库逐表核对原字段事实，新增归属不能修改旧问答、事件和资料。 */
class EnterpriseUpgradeTest {
    /** 普通/管理员/无归属、归档、事件、文档、分片和记忆保留；升级再执行不重复迁移。 */
    @Test void upgradePreservesEveryOriginalColumn() throws Exception {
        try (KnowledgeTestResources resources = new KnowledgeTestResources();
                Connection connection = DriverManager.getConnection(resources.url(), resources.user, resources.password);
                Statement sql = connection.createStatement()) {
            Flyway.configure().dataSource(resources.url(), resources.user, resources.password).target("11").load().migrate();
            sql.execute("INSERT INTO app_users(id,username,password_hash,role,created_at) VALUES ('11111111-1111-4111-8111-111111111111','upgrade_admin','synthetic-hash','ADMIN','2026-10-01T00:00:00Z'),('22222222-2222-4222-8222-222222222222','upgrade_user','synthetic-hash','USER','2026-10-01T00:00:00Z')");
            sql.execute("INSERT INTO runs(id,session_id,question,status,answer,error_code,created_at,user_id,archived_at,input_tokens,output_tokens) VALUES ('33333333-3333-4333-8333-333333333331','33333333-3333-4333-8333-333333333330','管理员历史','COMPLETED','保留回答','','2026-10-01T00:00:00Z','11111111-1111-4111-8111-111111111111',NULL,20,5),('33333333-3333-4333-8333-333333333332','33333333-3333-4333-8333-333333333330','普通归档历史','FAILED','保留失败回答','MODEL_ERROR','2026-10-01T00:00:01Z','22222222-2222-4222-8222-222222222222','2026-10-02T00:00:00Z',0,0),('33333333-3333-4333-8333-333333333333','33333333-3333-4333-8333-333333333339','未归属历史','CANCELLED','','CANCELLED','2026-10-01T00:00:02Z',NULL,NULL,0,0)");
            sql.execute("INSERT INTO run_events(run_id,kind,content,created_at) VALUES ('33333333-3333-4333-8333-333333333331','USAGE','{\"inputTokens\":20,\"outputTokens\":5}','2026-10-01T00:00:01Z'),('33333333-3333-4333-8333-333333333332','ERROR','{\"code\":\"MODEL_ERROR\"}','2026-10-01T00:00:02Z')");
            sql.execute("INSERT INTO documents(id,title,version,filename,checksum,created_at,embedding_key) VALUES ('44444444-4444-4444-8444-444444444444','历史通用文档','*','historical.md',REPEAT('a',64),'2026-10-01T00:00:00Z','')");
            sql.execute("INSERT INTO chunks(id,document_id,location,content) VALUES ('historical-chunk','44444444-4444-4444-8444-444444444444','line:1','保留原文及引用位置')");
            sql.execute("INSERT INTO memories(id,content,created_at) VALUES ('55555555-5555-4555-8555-555555555555','人员确认的历史约束','2026-10-01T00:00:00Z')");
            Map<String, List<String>> columns = columns(sql);
            Map<String, List<Map<String, Object>>> before = capture(sql, columns);
            // V12–V19 均须执行，原列逐字段比对继续覆盖拆分新增迁移。
            assertThat(Flyway.configure().dataSource(resources.url(), resources.user, resources.password).load().migrate().migrationsExecuted).isEqualTo(8);
            assertThat(capture(sql, columns)).isEqualTo(before);
            assertThat(Flyway.configure().dataSource(resources.url(), resources.user, resources.password).load().migrate().migrationsExecuted).isZero();
            assertThat(count(sql, "SELECT COUNT(*) FROM run_target_snapshots WHERE snapshot_kind='LEGACY_INFERRED'")).isEqualTo(3);
            assertThat(count(sql, "SELECT COUNT(*) FROM runs WHERE actor_type='LEGACY_UNKNOWN' AND user_id IS NULL AND application_id IS NULL")).isEqualTo(1);
            assertThat(count(sql, "SELECT COUNT(*) FROM project_members WHERE project_id='00000000-0000-4000-8000-000000000001'")).isEqualTo(2);
            assertThat(count(sql, "SELECT COUNT(*) FROM documents WHERE system_id='00000000-0000-4000-8000-000000000002'")).isEqualTo(1);
            assertThat(count(sql, "SELECT COUNT(*) FROM memories WHERE project_id='00000000-0000-4000-8000-000000000001' AND system_id IS NULL AND environment_id IS NULL")).isEqualTo(1);
        }
    }
    /** 元数据来自当前隔离库，只接受固定标识符，排除本身会新增迁移记录的历史表。 */
    private Map<String, List<String>> columns(Statement sql) throws Exception {
        Map<String, List<String>> result = new LinkedHashMap<>();
        try (ResultSet rows = sql.executeQuery("SELECT table_name,column_name FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name<>'flyway_schema_history' ORDER BY table_name,ordinal_position")) {
            while (rows.next()) {
                String table = rows.getString(1), column = rows.getString(2);
                if (!table.matches("[a-z][a-z0-9_]*") || !column.matches("[a-z][a-z0-9_]*")) { throw new IllegalStateException("METADATA_IDENTIFIER_INVALID"); }
                result.computeIfAbsent(table, key -> new ArrayList<>()).add(column);
            }
        }
        return result;
    }
    /** 新旧版本均投影原列，新增字段不会掩盖或替代旧事实差异。 */
    private Map<String, List<Map<String, Object>>> capture(Statement sql, Map<String, List<String>> columns) throws Exception {
        Map<String, List<Map<String, Object>>> result = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> table : columns.entrySet()) {
            List<Map<String, Object>> facts = new ArrayList<>();
            String projection = table.getValue().stream().map(value -> "`" + value + "`").reduce((left, right) -> left + "," + right).orElseThrow();
            try (ResultSet rows = sql.executeQuery("SELECT " + projection + " FROM `" + table.getKey() + "` ORDER BY 1")) {
                while (rows.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (String column : table.getValue()) { row.put(column, rows.getObject(column)); }
                    facts.add(row);
                }
            }
            result.put(table.getKey(), facts);
        }
        return result;
    }
    /** 独立SQL计数核对新增迁移映射。 */
    private int count(Statement sql, String query) throws Exception {
        try (ResultSet rows = sql.executeQuery(query)) { rows.next(); return rows.getInt(1); }
    }
}
