package io.supportops.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.supportops.agent.controller.UsageController;
import io.supportops.agent.service.impl.UsageServiceImpl;
import io.supportops.agent.vo.UsageAnalysis;
import io.supportops.api.ApiErrors;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.UUID;

/** 通过独立 JDBC 造历史记录，真实 Mapper 核对统计、分页与接口参数。 */
class UsageAnalysisTest {
    /** 超过一百轮仍全部计入；跨日与多事件不重复累计，保留失败用量。 */
    @Test
    void aggregatesPersistedHistoryAndPaginates() throws Exception {
        try (MapperTestDatabase db = new MapperTestDatabase()) {
            String session = UUID.randomUUID().toString();
            for (int i = 0; i < 105; i++) {
                insert(db, "run-" + i, session, "2026-09-07T00:00:00Z", "COMPLETED", 10, 2);
            }
            String failedSession = UUID.randomUUID().toString();
            insert(db, "failed", failedSession, "2026-09-07T23:59:59.999Z", "FAILED", 5000, 100);
            insert(db, "outside", session, "2026-09-08T00:00:00Z", "COMPLETED", 9999, 9999);
            insert(db, "before", session, "2026-09-06T23:59:59Z", "COMPLETED", 9999, 9999);
            db.jdbc.update(
                    "INSERT INTO run_events(run_id,kind,content,created_at)"
                            + " VALUES('failed','USAGE','{}','2026-09-07T23:59:59Z')");
            db.jdbc.update(
                    "INSERT INTO run_events(run_id,kind,content,created_at)"
                            + " VALUES('failed','USAGE','{}','2026-09-07T23:59:59Z')");
            UsageServiceImpl service = new UsageServiceImpl(db.runs);
            UsageAnalysis result = service.analyze("2026-09-07", "2026-09-07", "hour");
            assertThat(result.summary().runs()).isEqualTo(106);
            assertThat(result.summary().sessions()).isEqualTo(2);
            assertThat(result.summary().inputTokens()).isEqualTo(6050);
            assertThat(result.summary().outputTokens()).isEqualTo(310);
            assertThat(result.summary().unreported()).isEqualTo(105);
            assertThat(result.summary().completed()).isEqualTo(105);
            assertThat(result.timeline()).hasSize(24);
            assertThat(result.timeline().get(0).runs()).isEqualTo(105);
            assertThat(result.timeline().get(1).runs()).isZero();
            assertThat(result.timeline().get(23).inputTokens()).isEqualTo(5000);
            assertThat(result.topSessions().getFirst().groupId()).isEqualTo(failedSession);
            assertThat(service.session(session, 0)).hasSize(50);
            assertThat(service.session(session, 50)).hasSize(50);
            assertThat(service.session(session, 100)).hasSize(7);
            assertThat(service.session(session, 0).getFirst().question()).isEqualTo("合成提问");
            assertThat(service.session(session, 0).getFirst().answer()).isEqualTo("合成回答");
            assertThat(db.jdbc.queryForObject("SELECT COUNT(*) FROM runs", Long.class))
                    .isEqualTo(108);
            assertThat(service.analyze("2026-09-07", "2026-09-08", "day").timeline()).hasSize(2);
        }
    }

    /** 空范围补零，非法输入返回受控 400，HTTP 返回真实统计字段。 */
    @Test
    void validatesInputsAndExposesHttp() throws Exception {
        try (MapperTestDatabase db = new MapperTestDatabase()) {
            UsageServiceImpl service = new UsageServiceImpl(db.runs);
            assertThat(service.analyze("2026-09-07", "2026-09-07", "day").summary().runs())
                    .isZero();
            assertThat(service.analyze("2026-09-07", "2026-09-07", "day").topSessions()).isEmpty();
            assertThatThrownBy(() -> service.analyze("2026-01-01", "2026-09-07", "day"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> service.analyze("2026-09-08", "2026-09-07", "day"))
                    .isInstanceOf(IllegalArgumentException.class);
            insert(db, "short-id-run", "1-1-1-1-1", "2026-09-01T00:00:00Z", "COMPLETED", 1, 1);
            assertThat(service.session("1-1-1-1-1", 0)).hasSize(1);
            MockMvc mvc =
                    MockMvcBuilders.standaloneSetup(new UsageController(service))
                            .setControllerAdvice(new ApiErrors())
                            .build();
            mvc.perform(get("/api/usage").param("from", "2026-09-07").param("to", "2026-09-07"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.summary.inputTokens").value(0))
                    .andExpect(jsonPath("$.timeline.length()").value(1));
            mvc.perform(get("/api/usage").param("from", "bad").param("to", "2026-09-07"))
                    .andExpect(status().isBadRequest());
            mvc.perform(get("/api/usage").param("from", "2026-02-30").param("to", "2026-09-07"))
                    .andExpect(status().isBadRequest());
            mvc.perform(
                            get("/api/usage")
                                    .param("from", "2026-09-07")
                                    .param("to", "2026-09-07")
                                    .param("granularity", "minute"))
                    .andExpect(status().isBadRequest());
            mvc.perform(get("/api/usage/sessions/invalid")).andExpect(status().isBadRequest());
            mvc.perform(get("/api/usage/sessions/" + UUID.randomUUID()).param("offset", "-1"))
                    .andExpect(status().isBadRequest());
        }
    }

    /** 直接写入兼容旧结构的合成问答，不使用被测 Mapper 造数据。 */
    private void insert(
            MapperTestDatabase db,
            String id,
            String session,
            String time,
            String state,
            long input,
            long output) {
        db.jdbc.update(
                "INSERT INTO runs(id, session_id, question, status, answer, error_code, created_at,"
                        + " input_tokens, output_tokens) VALUES(?,?,?,?,?,?,?,?,?)",
                id,
                session,
                "合成提问",
                state,
                "合成回答",
                "",
                time,
                input,
                output);
    }
}
