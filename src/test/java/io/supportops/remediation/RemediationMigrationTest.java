package io.supportops.remediation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import io.supportops.persistence.KnowledgeTestResources;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** R18：V8已有账号、诊断、事件、评测与审阅升级V9保持原样。 */
class RemediationMigrationTest {
    /** 真实MySQL升级与外键/唯一约束核对，不用空库替代增量验证。 */
    @Test
    void upgradeV8PreservesAllExistingRowsAndAddsOnlyRemediationTables() {
        try (KnowledgeTestResources resources = new KnowledgeTestResources()) {
            Flyway.configure().dataSource(resources.url(), resources.user, resources.password).target("8").load().migrate();
            JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(resources.url(), resources.user, resources.password));
            String time = "2026-09-26T00:00:00Z";
            jdbc.update("INSERT INTO app_users(id,username,password_hash,role,created_at) VALUES ('legacy-admin','legacy_admin','synthetic-hash','ADMIN',?)", time);
            jdbc.update("INSERT INTO runs(id,session_id,question,status,answer,error_code,created_at,user_id,input_tokens,output_tokens) VALUES ('legacy-run','legacy-session','question','COMPLETED','answer','',?,'legacy-admin',19,7)", time);
            jdbc.update("INSERT INTO run_events(run_id,kind,content,created_at) VALUES ('legacy-run','ANSWER','original event',?)", time);
            // 评测完整旧记录由正式V6结构的最小合法字段建立。
            jdbc.update("INSERT INTO evaluation_datasets(id,dataset_key,name,version,content_sha256,manifest_json,created_at) VALUES ('legacy-data','legacy','dataset','v1',?,'{}',?)", "a".repeat(64), time);
            jdbc.update("INSERT INTO evaluation_cases(id,dataset_id,case_key,ordinal,question,scenario,expected_http_status,review_criteria) VALUES ('legacy-case','legacy-data','migration',1,'old question','migration',410,'original criteria')");
            jdbc.update("INSERT INTO evaluation_experiments(id,name,description,kind,dataset_id,case_keys_json,strategies_json,repeats,total_minutes,status,cleanup_status,version,creator_id,created_at) VALUES ('legacy-experiment','old','keep','SOURCE_CHECK','legacy-data','[]','[]',1,10,'DRAFT','NOT_REQUIRED',0,'legacy-admin',?)", time);
            jdbc.update("INSERT INTO evaluation_groups(id,experiment_id,strategy,repeat_index,status,metadata_json) VALUES ('legacy-group','legacy-experiment','LEXICAL_RRF',1,'COMPLETED','{}')");
            jdbc.update("INSERT INTO evaluation_samples(id,experiment_id,group_id,case_id,case_key,ordinal,status,answer) VALUES ('legacy-sample','legacy-experiment','legacy-group','legacy-case','migration',1,'COMPLETED','original answer')");
            jdbc.update("INSERT INTO evaluation_reviews(id,sample_id,reviewer_id,revision,dimensions_json,note,verdict,created_at) VALUES ('legacy-review','legacy-sample','legacy-admin',1,'{}','original review','PASS',?)", time);
            Map<String, List<Map<String, Object>>> before = new HashMap<>();
            List<String> tables = List.of("app_users", "runs", "run_events", "evaluation_datasets", "evaluation_experiments", "evaluation_groups", "evaluation_samples", "evaluation_reviews");
            for (String table : tables) {
                before.put(table, jdbc.queryForList("SELECT * FROM " + table + " ORDER BY id"));
            }
            assertThat(Flyway.configure().dataSource(resources.url(), resources.user, resources.password).target("9").load().migrate().migrationsExecuted).isEqualTo(1);
            for (String table : tables) {
                assertThat(jdbc.queryForList("SELECT * FROM " + table + " ORDER BY id")).as(table).isEqualTo(before.get(table));
            }
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=? AND table_name LIKE 'remediation_%'", Integer.class, resources.database)).isEqualTo(5);
            assertThatThrownBy(() -> jdbc.update("INSERT INTO remediation_plans(id,name,status,version,plan_hash,snapshot_json,creator_id,target_id,instance_id,created_at,expires_at) VALUES ('orphan','x','PENDING_APPROVAL',1,?,'{}','missing-user','BUILTIN_ORDERBRIDGE','x',?,1)", "b".repeat(64), time)).hasMessageContaining("foreign key");
            // 已经应用V9并有完整修复历史时，V10只新增独立场景审计，不改写旧外键与事实。
            jdbc.update("INSERT INTO remediation_plans(id,name,status,version,plan_hash,snapshot_json,creator_id,target_id,instance_id,created_at,expires_at) VALUES ('old-plan','原提案','FINISHED',3,?,'{}','legacy-admin','BUILTIN_ORDERBRIDGE','old-instance',?,1)", "b".repeat(64), time);
            jdbc.update("INSERT INTO remediation_decisions(id,plan_id,plan_hash,kind,actor_id,reason,expires_at,created_at) VALUES ('old-decision','old-plan',?,'APPROVED','legacy-admin','原审批',1,?)", "b".repeat(64), time);
            jdbc.update("INSERT INTO remediation_executions(id,plan_id,target_id,operation_id,verification_request_id,instance_id,process_id,actor_id,status,application_status,verification_status,receipt_json,created_at) VALUES ('old-execution','old-plan','BUILTIN_ORDERBRIDGE','old-operation','old-verification','old-instance',1,'legacy-admin','SUCCEEDED','APPLIED','PASSED','{\"original\":true}',?)", time);
            jdbc.update("INSERT INTO remediation_requests(id,actor_id,request_id,kind,target_id,digest,plan_id,execution_id,http_status,created_at) VALUES ('old-request','legacy-admin','original-id','EXECUTE','old-plan',?,'old-plan','old-execution',200,?)", "c".repeat(64), time);
            jdbc.update("INSERT INTO remediation_events(plan_id,execution_id,actor_id,kind,payload_json,created_at) VALUES ('old-plan','old-execution','legacy-admin','FINISHED','{\"keep\":true}',?)", time);
            List<String> remediation = List.of("remediation_plans", "remediation_decisions", "remediation_executions", "remediation_requests", "remediation_events");
            for (String table : remediation) {
                before.put(table, jdbc.queryForList("SELECT * FROM " + table + " ORDER BY id"));
            }
            assertThat(Flyway.configure().dataSource(resources.url(), resources.user, resources.password).target("10").load().migrate().migrationsExecuted).isEqualTo(1);
            for (String table : before.keySet()) {
                assertThat(jdbc.queryForList("SELECT * FROM " + table + " ORDER BY id")).as("V10 " + table).isEqualTo(before.get(table));
            }
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM demo_environment_events", Integer.class)).isZero();
            assertThatThrownBy(() -> jdbc.update("INSERT INTO demo_environment_events(actor_id,target_id,instance_id,kind,scenario_before,scenario_after,before_revision,before_json,status,started_at) VALUES ('missing-user','BUILTIN_ORDERBRIDGE','instance','SCENARIO_RESET','migration','healthy',1,'{}','STARTED',?)", time)).hasMessageContaining("foreign key");
        }
    }
}
