package io.supportops.agent.service.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.message.Msg;
import java.io.InputStream;
import org.junit.jupiter.api.Test;

/** 验证网页与飞书的展示规则，以及诊断最终回答的原文保留。 */
class DiagnosisTimeFormatterTest {
    /** 验证时区跨日、纳秒、无效时间和需要原样保留的证据代码块。 */
    @Test
    void formatsSharedCasesWithoutChangingEvidence() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/diagnosis-time-cases.json")) {
            JsonNode cases = new ObjectMapper().readTree(input);
            for (JsonNode example : cases) {
                assertThat(DiagnosisTimeFormatter.format(example.path("input").asText()))
                        .isEqualTo(example.path("expected").asText());
            }
        }
        assertThat(DiagnosisTimeFormatter.format(null)).isNull();
    }

    /** 最终回答交给持久化时必须保留模型原文，由展示入口独立格式化。 */
    @Test
    void retainsOriginalFinalAgentResult() {
        AgentResultEvent event = mock(AgentResultEvent.class);
        Msg message = mock(Msg.class);
        when(message.getTextContent()).thenReturn("观测时间：2026-09-22T10:29:05Z");
        when(event.getResult()).thenReturn(message);
        AgentEventRecorder recorder = new AgentEventRecorder((kind, content) -> {});
        recorder.accept(event);
        assertThat(recorder.result().answer()).isEqualTo("观测时间：2026-09-22T10:29:05Z");
    }
}
