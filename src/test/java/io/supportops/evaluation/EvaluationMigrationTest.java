package io.supportops.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import io.supportops.persistence.KnowledgeTestResources;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.List;
import java.util.Map;

/** 独立 MySQL 先迁移到 V5，写旧业务记录再升级 V10，不使用空库替代增量兼容验证。 */
class EvaluationMigrationTest {
    /** 旧问答、事件、上传和飞书收发记录的字段与排序在升级后保持原样。 */
    @Test
    void upgradeV5PreservesDiagnosisUploadAndFeishuRows() {
        try (KnowledgeTestResources resources = new KnowledgeTestResources()) {
            Flyway.configure().dataSource(resources.url(), resources.user, resources.password).target("5").load().migrate();
            JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(resources.url(), resources.user, resources.password));
            String time = "2026-09-25T12:00:00Z";
            jdbc.update("INSERT INTO app_users(id,username,password_hash,role,created_at) VALUES ('legacy-user','legacy_admin','synthetic-hash','ADMIN',?)", time);
            jdbc.update("INSERT INTO runs(id,session_id,question,status,answer,error_code,created_at,user_id,input_tokens,output_tokens) VALUES ('legacy-run','legacy-session','old question','COMPLETED','raw old answer','',?,'legacy-user',19,7)", time);
            jdbc.update("INSERT INTO run_events(run_id,kind,content,created_at) VALUES ('legacy-run','ANSWER','original event',?)", time);
            jdbc.update("INSERT INTO document_uploads(id,request_key,request_hash,filename,size_bytes,checksum,source,status,error_code,created_at,user_id) VALUES ('legacy-upload','old-request',?,'old.md',3,?,'UPLOAD','FAILED','old-error',?,'legacy-user')", "a".repeat(64), "b".repeat(64), time);
            jdbc.update("INSERT INTO feishu_bindings(id,app_id,tenant_key,user_id,open_id,created_at) VALUES ('old-binding','old-app','old-tenant','legacy-user','old-open',?)", time);
            jdbc.update("INSERT INTO feishu_inbox(id,app_id,tenant_key,message_id,open_id,binding_id,user_id,question,state,created_at) VALUES ('old-inbox','old-app','old-tenant','old-msg','old-open','old-binding','legacy-user','old message','FINISHED',?)", time);
            jdbc.update("INSERT INTO feishu_outbox(id,inbox_id,kind,content,state,next_attempt_at,created_at) VALUES ('old-outbox','old-inbox','RESULT','frozen old reply','UNKNOWN',0,?)", time);
            Map<String,List<Map<String,Object>>> before = Map.of("runs", jdbc.queryForList("SELECT * FROM runs ORDER BY id"), "run_events", jdbc.queryForList("SELECT * FROM run_events ORDER BY id"),
                    "uploads", jdbc.queryForList("SELECT * FROM document_uploads ORDER BY id"), "bindings", jdbc.queryForList("SELECT * FROM feishu_bindings ORDER BY id"),
                    "inbox", jdbc.queryForList("SELECT * FROM feishu_inbox ORDER BY id"), "outbox", jdbc.queryForList("SELECT * FROM feishu_outbox ORDER BY id"));
            assertThat(Flyway.configure().dataSource(resources.url(), resources.user, resources.password).target("10").load().migrate().migrationsExecuted).isEqualTo(5);
            assertThat(jdbc.queryForList("SELECT * FROM runs ORDER BY id")).isEqualTo(before.get("runs"));
            assertThat(jdbc.queryForList("SELECT * FROM run_events ORDER BY id")).isEqualTo(before.get("run_events"));
            assertThat(jdbc.queryForList("SELECT * FROM document_uploads ORDER BY id")).isEqualTo(before.get("uploads"));
            assertThat(jdbc.queryForList("SELECT * FROM feishu_bindings ORDER BY id")).isEqualTo(before.get("bindings"));
            assertThat(jdbc.queryForList("SELECT * FROM feishu_inbox ORDER BY id")).isEqualTo(before.get("inbox"));
            assertThat(jdbc.queryForList("SELECT * FROM feishu_outbox ORDER BY id")).isEqualTo(before.get("outbox"));
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=? AND table_name LIKE 'evaluation_%'", Integer.class, resources.database)).isEqualTo(10);
            assertThat(jdbc.queryForObject("SELECT fencing_token FROM evaluation_runner_guard WHERE id=1", Long.class)).isZero();
        }
    }
}
