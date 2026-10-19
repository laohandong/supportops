package io.supportops.outcome;

import static org.assertj.core.api.Assertions.assertThat;
import io.supportops.persistence.KnowledgeTestResources;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 真实V10数据库增量升级，逐表核对旧记录及旧迁移摘要，不以空库初始化替代。 */
class OutcomeMigrationTest {
    /** 旧业务表与迁移行保持原样，仅新增七表和可空历史关联。 */
    @Test
    void upgradeV10PreservesAllTablesAndMigrationChecksums() {
        try (KnowledgeTestResources resources = new KnowledgeTestResources()) {
            Flyway.configure().dataSource(resources.url(), resources.user, resources.password).target("10").load().migrate();
            JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(resources.url(), resources.user, resources.password));
            jdbc.update("INSERT INTO app_users(id,username,password_hash,role,created_at) VALUES('legacy-owner','oc_legacy','synthetic','ADMIN','2026-09-01T00:00:00Z')");
            jdbc.update("INSERT INTO runs(id,session_id,question,status,answer,error_code,created_at,user_id,input_tokens,output_tokens) VALUES('legacy-source','legacy-session','历史诊断','COMPLETED','旧原文','','2026-09-01T00:00:00Z','legacy-owner',31,7)");
            jdbc.update("INSERT INTO memories(id,content,created_at) VALUES('legacy-memory','人员确认的旧约束','2026-09-01T00:00:00Z')");
            List<String> tables = jdbc.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema=? AND table_name<>'flyway_schema_history' ORDER BY table_name", String.class, resources.database);
            Map<String, List<Map<String, Object>>> before = new HashMap<>();
            for (String table : tables) {
                assertThat(table).matches("[a-z_]+");
                before.put(table, jdbc.queryForList("SELECT * FROM " + table));
            }
            List<Map<String, Object>> migrations = jdbc.queryForList("SELECT version,checksum,success FROM flyway_schema_history ORDER BY installed_rank");
            assertThat(Flyway.configure().dataSource(resources.url(), resources.user, resources.password).target("11").load().migrate().migrationsExecuted).isEqualTo(1);
            for (String table : tables) {
                assertThat(jdbc.queryForList("SELECT * FROM " + table)).as(table).isEqualTo(before.get(table));
            }
            assertThat(jdbc.queryForList("SELECT version,checksum,success FROM flyway_schema_history WHERE version<>'11' ORDER BY installed_rank")).isEqualTo(migrations);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=?", Integer.class, resources.database)).isEqualTo(tables.size() + 8);
            assertThat(jdbc.queryForObject("SELECT is_nullable FROM information_schema.columns WHERE table_schema=? AND table_name='evaluation_cases' AND column_name='library_revision_id'", String.class, resources.database)).isEqualTo("YES");
            assertThat(jdbc.queryForObject("SELECT answer FROM runs WHERE id='legacy-source'", String.class)).isEqualTo("旧原文");
        }
    }
}
