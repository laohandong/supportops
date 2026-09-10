package io.supportops.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import io.supportops.config.ProviderRegistry;
import io.supportops.knowledge.dto.ChunkSource;
import io.supportops.knowledge.dto.RerankResult;
import io.supportops.knowledge.entity.DocumentReleaseEntity;
import io.supportops.knowledge.entity.IndexTaskEntity;
import io.supportops.knowledge.mapper.DocumentReleaseMapper;
import io.supportops.knowledge.mapper.IndexTaskMapper;
import io.supportops.knowledge.mapper.KnowledgeCatalogMapper;
import io.supportops.knowledge.service.DocumentLibraryService;
import io.supportops.knowledge.service.ElasticKnowledgeIndex;
import io.supportops.knowledge.service.ElasticKnowledgeIndex.Hit;
import io.supportops.knowledge.service.EmbeddingClient;
import io.supportops.knowledge.service.PassageReranker;
import io.supportops.knowledge.service.impl.KnowledgeServiceImpl;
import io.supportops.knowledge.service.impl.OnnxPassageReranker;
import io.supportops.knowledge.service.support.RerankingException;
import io.supportops.knowledge.vo.KnowledgeSearchResult;
import io.supportops.knowledge.vo.RankingInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 用确定性评分验证候选预算、模型最终排序、发布代保护及历史响应兼容。 */
class KnowledgeRerankingTest {
    private KnowledgeCatalogMapper catalog;
    private ElasticKnowledgeIndex elastic;
    private PassageReranker reranker;
    private KnowledgeServiceImpl knowledge;

    /** 仅替代召回协议和模型评分，真实模型另由可显式执行的本地测试验证。 */
    @BeforeEach
    void setup() {
        catalog = mock(KnowledgeCatalogMapper.class);
        elastic = mock(ElasticKnowledgeIndex.class);
        reranker = mock(PassageReranker.class);
        when(catalog.applicableBatches("2.0")).thenReturn(List.of("batch"));
        when(reranker.enabled()).thenReturn(true);
        when(reranker.candidateLimit()).thenReturn(20);
        EmbeddingClient embedding = new EmbeddingClient(new ProviderRegistry(new MockEnvironment()), new ObjectMapper());
        knowledge = new KnowledgeServiceImpl(mock(DocumentLibraryService.class), catalog,
                mock(DocumentReleaseMapper.class), mock(IndexTaskMapper.class), embedding, elastic, reranker);
    }

    /** 原关键词第 20 名可通过模型排第一；负分合法，先去重且最多处理 20 个候选。 */
    @Test
    void reranksBeforeTopFiveAndPreservesRrfAndSource() {
        List<Hit> hits = new ArrayList<>();
        for (int index = 0; index < 30; index++) {
            hits.add(new Hit(source(index), 30 - index));
        }
        hits.addFirst(hits.getFirst());
        when(elastic.lexical("升级", "2.0", List.of("batch"), 50)).thenReturn(hits);
        when(reranker.score(eq("升级"), anyList())).thenAnswer(invocation -> {
            List<String> texts = invocation.getArgument(1);
            assertThat(texts).hasSize(20).doesNotHaveDuplicates();
            List<Double> scores = new ArrayList<>();
            for (int index = 0; index < texts.size(); index++) {
                scores.add(index - 25.0);
            }
            return new RerankResult(scores, new RankingInfo("ONNX", "test-model", 20, 512, 2));
        });
        KnowledgeSearchResult result = knowledge.search("升级", "2.0", 5, true);
        assertThat(result.mode()).isEqualTo("LEXICAL");
        assertThat(result.ranking().method()).isEqualTo("ONNX");
        assertThat(result.passages()).hasSize(5);
        assertThat(result.passages().getFirst().id()).isEqualTo("chunk-19");
        assertThat(result.passages().getFirst().rerankScore()).isEqualTo(-6.0);
        assertThat(result.passages().getFirst().score()).isPositive();
        assertThat(result.passages().getFirst().batchId()).isEqualTo("batch");
        assertThat(result.passages().getFirst().originalUrl()).contains("revision/original");
    }

    /** 重排期间删除或替换发布指针后，不得再返回旧片段。 */
    @Test
    void checksVisibilityAfterInference() {
        when(elastic.lexical(anyString(), eq("2.0"), anyList(), eq(50))).thenReturn(List.of(new Hit(source(0), 1)));
        when(reranker.score(anyString(), anyList())).thenReturn(new RerankResult(List.of(1.0),
                new RankingInfo("ONNX", "test-model", 1, 512, 1)));
        when(catalog.applicableBatches("2.0")).thenReturn(List.of("batch"), List.of("batch"), List.of());
        assertThat(knowledge.search("升级", "2.0", 5, true).passages()).isEmpty();
    }

    /** 推理失败和分数损坏均不能返回 RRF 候选充当成功。 */
    @Test
    void errorsDoNotFallBack() {
        when(elastic.lexical(anyString(), eq("2.0"), anyList(), eq(50))).thenReturn(List.of(new Hit(source(0), 1)));
        when(reranker.score(anyString(), anyList())).thenThrow(new RerankingException("RERANK_TIMED_OUT"));
        assertThatThrownBy(() -> knowledge.search("升级", "2.0", 5, true)).hasMessage("RERANK_TIMED_OUT");
    }

    /** 两路召回均参与候选集合，最终模型可提升只由向量召回的片段。 */
    @Test
    void hybridRecallFeedsBothSourcesToModel() {
        MapperBuilderAssistant metadata = new MapperBuilderAssistant(new MybatisConfiguration(), "rerank-test");
        TableInfoHelper.initTableInfo(metadata, DocumentReleaseEntity.class);
        TableInfoHelper.initTableInfo(metadata, IndexTaskEntity.class);
        EmbeddingClient embedding = mock(EmbeddingClient.class);
        ProviderRegistry.Endpoint endpoint = mock(ProviderRegistry.Endpoint.class);
        when(endpoint.configured()).thenReturn(true);
        when(embedding.snapshot()).thenReturn(endpoint);
        when(embedding.fingerprint(endpoint)).thenReturn("profile");
        double[] vector = {1, 0};
        when(embedding.embed(endpoint, "升级")).thenReturn(vector);
        DocumentReleaseMapper releases = mock(DocumentReleaseMapper.class);
        DocumentReleaseEntity release = new DocumentReleaseEntity();
        release.setVectorTaskId("task");
        when(releases.selectList(any())).thenReturn(List.of(release));
        IndexTaskMapper tasks = mock(IndexTaskMapper.class);
        IndexTaskEntity task = new IndexTaskEntity();
        task.setId("task");
        task.setDimensions(2);
        when(tasks.selectList(any())).thenReturn(List.of(task));
        when(elastic.lexical("升级", "2.0", List.of("batch"), 50)).thenReturn(List.of(new Hit(source(0), 1)));
        when(elastic.vectorIndex("profile", 2)).thenReturn("index");
        when(elastic.vector("index", "2.0", List.of("batch"), List.of("task"), vector, 50))
                .thenReturn(List.of(new Hit(source(1), 1)));
        when(reranker.score("升级", List.of("正文0", "正文1"))).thenReturn(new RerankResult(List.of(-5.0, 3.0),
                new RankingInfo("ONNX", "test-model", 2, 512, 1)));
        KnowledgeServiceImpl hybrid = new KnowledgeServiceImpl(mock(DocumentLibraryService.class), catalog,
                releases, tasks, embedding, elastic, reranker);
        KnowledgeSearchResult result = hybrid.search("升级", "2.0", 5, false);
        assertThat(result.mode()).isEqualTo("HYBRID");
        assertThat(result.passages()).extracting(passage -> passage.id()).containsExactly("chunk-1", "chunk-0");
    }

    /** 缺分及非有限分数不能参与排序，避免返回不完整或不可解释的结果。 */
    @Test
    void invalidModelScoresFailExplicitly() {
        when(elastic.lexical(anyString(), eq("2.0"), anyList(), eq(50))).thenReturn(List.of(new Hit(source(0), 1)));
        when(reranker.score(anyString(), anyList())).thenReturn(new RerankResult(List.of(Double.NaN),
                new RankingInfo("ONNX", "test-model", 1, 512, 1)));
        assertThatThrownBy(() -> knowledge.search("升级", "2.0", 5, true)).hasMessage("RERANK_OUTPUT_INVALID");
    }

    /** 未启用与空结果不加载本地库，历史引用没有新增字段仍可读取。 */
    @Test
    void disabledAndHistoricalRecordsRemainReadable() throws Exception {
        try (OnnxPassageReranker local = new OnnxPassageReranker(new MockEnvironment())) {
            assertThat(local.score("升级", List.of("正文")).ranking().method()).isEqualTo("RRF");
            assertThat(local.score("升级", List.of()).ranking().method()).isEqualTo("EMPTY");
        }
        KnowledgeSearchResult old = new ObjectMapper().readValue("""
                {"mode":"LEXICAL","passages":[{"id":"old","documentId":"doc","title":"历史",
                "version":"2.0","location":"第1页","content":"正文","score":0.01}]}
                """, KnowledgeSearchResult.class);
        assertThat(old.ranking()).isNull();
        assertThat(old.passages().getFirst().rerankScore()).isNull();
        assertThat(old.passages().getFirst().score()).isEqualTo(0.01);
    }

    /** 显式启用但缺少模型时，首次有候选必须明确失败。 */
    @Test
    void missingModelFailsOnlyWhenUsed() {
        try (OnnxPassageReranker local = new OnnxPassageReranker(new MockEnvironment()
                .withProperty("supportops.reranking.enabled", "true")
                .withProperty("supportops.reranking.model-path", ".cache/absent-reranker.onnx"))) {
            assertThat(local.score("升级", List.of()).ranking().method()).isEqualTo("EMPTY");
            assertThatThrownBy(() -> local.score("升级", List.of("正文"))).hasMessage("RERANK_MODEL_MISSING");
        }
    }

    /** 构造带固定修订来源的合成候选，不依赖真实工作台数据。 */
    private static ChunkSource source(int index) {
        return new ChunkSource("chunk-" + index, "doc", "revision", "batch", "file", "说明", "guide.md",
                "MARKDOWN", "2.0", 1, index + 1, "正文" + index, "hash", "标题", "标题", "行1", null, null,
                1, 1, 0, 4, 4);
    }
}
