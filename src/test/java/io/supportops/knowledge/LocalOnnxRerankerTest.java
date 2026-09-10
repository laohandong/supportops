package io.supportops.knowledge;

import io.supportops.knowledge.dto.RerankResult;
import io.supportops.knowledge.service.impl.OnnxPassageReranker;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.mock.env.MockEnvironment;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 显式传入本地文件执行真实模型；不下载权重、不访问外部模型或工作台数据。 */
@EnabledIfSystemProperty(named = "rerank.test.model", matches = ".+")
class LocalOnnxRerankerTest {
    private static OnnxPassageReranker reranker;

    /** 独立加载一次真实模型，文件路径由测试命令提供。 */
    @BeforeAll
    static void setup() {
        reranker = new OnnxPassageReranker(environment(30000));
    }

    /** 释放本测试的分词器、会话与监控线程。 */
    @AfterAll
    static void close() {
        reranker.close();
    }

    /** 对明确相关与无关中文材料验证成对编码、真实推理与稳定模型身份。 */
    @Test
    void relevantChinesePassageScoresAboveUnrelatedContent() {
        RerankResult result = reranker.score("升级到2.0后旧配置键为什么不生效？", List.of(
                "2.0版本停止读取旧配置键sync.path，需要迁移为sync.targetPath，旧键不会再生效。",
                "今天食堂提供米饭和蔬菜，周末天气晴朗，适合去公园散步。"));
        assertThat(result.scores()).hasSize(2).allMatch(Double::isFinite);
        assertThat(result.scores().get(0)).isGreaterThan(result.scores().get(1));
        assertThat(result.ranking().modelId()).contains("onnx-sha256:", ";tokenizer-sha256:");
        System.out.println("LOCAL_ONNX_CHINESE scores=" + result.scores() + " elapsedMs=" + result.ranking().elapsedMs());
    }

    /** 超长问题与中文正文受 tokenizer token 预算约束，可正常完成推理。 */
    @Test
    void longPairsAreTruncatedAndTwentyCandidatesStayBounded() {
        RerankResult result = reranker.score("配置升级问题".repeat(200),
                Collections.nCopies(20, "升级后请检查实际生效配置，并按迁移说明调整。".repeat(100)));
        assertThat(result.scores()).hasSize(20).allMatch(Double::isFinite);
        assertThat(result.ranking().maxInputTokens()).isEqualTo(512);
        System.out.println("LOCAL_ONNX_TWENTY elapsedMs=" + result.ranking().elapsedMs());
    }

    /** 原生推理期间取消线程，必须退出且不接受部分评分；随后资源仍可复用。 */
    @Test
    void cancellationStopsInferenceAndDoesNotPoisonSession() throws Exception {
        reranker.score("预热", List.of("预热本地模型"));
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread request = Thread.ofPlatform().start(() -> {
            try {
                reranker.score("排查升级配置问题", Collections.nCopies(20, "配置升级日志".repeat(300)));
            } catch (Throwable exception) {
                failure.set(exception);
            }
        });
        Thread.sleep(100);
        request.interrupt();
        request.join(10000);
        assertThat(request.isAlive()).isFalse();
        assertThat(failure.get()).hasMessage("RERANK_CANCELLED");
        assertThat(reranker.score("配置", List.of("配置说明")).scores()).hasSize(1);
    }

    /** 短预算包括首次加载；初始化完成后的后续推理仍需遵循同一时间限制。 */
    @Test
    void timeoutDoesNotReturnPartialResults() {
        try (OnnxPassageReranker shortBudget = new OnnxPassageReranker(environment(1))) {
            assertThatThrownBy(() -> shortBudget.score("升级", List.of("配置说明"))).hasMessage("RERANK_TIMED_OUT");
            assertThatThrownBy(() -> shortBudget.score("升级", Collections.nCopies(20, "配置说明".repeat(300))))
                    .hasMessage("RERANK_TIMED_OUT");
        }
    }

    /** 使用显式模型路径和 CPU 限额构造隔离测试配置。 */
    private static MockEnvironment environment(long timeoutMs) {
        return new MockEnvironment().withProperty("supportops.reranking.enabled", "true")
                .withProperty("supportops.reranking.model-path", System.getProperty("rerank.test.model"))
                .withProperty("supportops.reranking.tokenizer-path", System.getProperty("rerank.test.tokenizer"))
                .withProperty("supportops.reranking.timeout-ms", Long.toString(timeoutMs));
    }
}
