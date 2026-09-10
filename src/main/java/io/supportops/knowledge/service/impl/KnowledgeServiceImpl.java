package io.supportops.knowledge.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import io.supportops.config.ProviderRegistry.Endpoint;
import io.supportops.knowledge.dto.ChunkSource;
import io.supportops.knowledge.dto.ImportOptions;
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
import io.supportops.knowledge.service.KnowledgeService;
import io.supportops.knowledge.service.PassageReranker;
import io.supportops.knowledge.service.support.RerankingException;
import io.supportops.knowledge.vo.DocumentChunk;
import io.supportops.knowledge.vo.KnowledgeDocument;
import io.supportops.knowledge.vo.KnowledgePassage;
import io.supportops.knowledge.vo.KnowledgeSearchResult;
import io.supportops.knowledge.vo.KnowledgeViews;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 兼容文档入口并融合 ES 排名；MySQL 发布指针决定可以被引用的资料代。 */
@Service
public class KnowledgeServiceImpl implements KnowledgeService {
    private final DocumentLibraryService library;
    private final KnowledgeCatalogMapper catalog;
    private final DocumentReleaseMapper releases;
    private final IndexTaskMapper tasks;
    private final EmbeddingClient embedding;
    private final ElasticKnowledgeIndex elastic;
    private final PassageReranker reranker;

    /** 注入发布目录与外部检索协议边界。 */
    public KnowledgeServiceImpl(
            DocumentLibraryService library,
            KnowledgeCatalogMapper catalog,
            DocumentReleaseMapper releases,
            IndexTaskMapper tasks,
            EmbeddingClient embedding,
            ElasticKnowledgeIndex elastic,
            PassageReranker reranker) {
        this.library = library;
        this.catalog = catalog;
        this.releases = releases;
        this.tasks = tasks;
        this.embedding = embedding;
        this.elastic = elastic;
        this.reranker = reranker;
    }

    /** 受理原件与异步处理，返回最新文档状态。 */
    @Override
    public KnowledgeDocument ingest(String filename, String title, String version, byte[] bytes) {
        KnowledgeViews.Upload upload =
                library.submit(
                        filename,
                        title,
                        version,
                        null,
                        "",
                        UUID.randomUUID().toString(),
                        "API",
                        bytes,
                        ImportOptions.defaults());
        return library.document(upload.documentId());
    }

    /** 创建异步向量重建任务，成功发布之前仍保留旧代。 */
    @Override
    public KnowledgeDocument reindex(String id) {
        library.reindex(id);
        return library.document(id);
    }

    /** 查询最新处理状态。 */
    @Override
    public List<KnowledgeDocument> documents() {
        return library.documents();
    }

    /** 查询指定文档。 */
    @Override
    public KnowledgeDocument document(String id) {
        return library.document(id);
    }

    /** 查询当前展示批次的片段。 */
    @Override
    public List<DocumentChunk> content(String id) {
        return library.content(id, null);
    }

    /** 逻辑删除立即隐藏，外部清理由持久化任务完成。 */
    @Override
    public void delete(String id) {
        library.delete(id);
    }

    /** 先过滤版本并以 RRF 初筛，再按显式配置进行本地模型精排；返回前重查发布代。 */
    @Override
    public KnowledgeSearchResult search(
            String query, String version, int limit, boolean lexicalOnly) {
        if (query == null
                || query.isBlank()
                || query.length() > 2000
                || version == null
                || version.length() > 30
                || version.isBlank()) {
            throw new IllegalArgumentException("INVALID_INPUT");
        }
        int count = Math.clamp(limit, 1, 5);
        List<String> batchIds = catalog.applicableBatches(version);
        List<Hit> lexical = elastic.lexical(query, version, batchIds, 50);
        List<Hit> vectors = List.of();
        Endpoint endpoint = embedding.snapshot();
        String profile = embedding.fingerprint(endpoint);
        List<IndexTaskEntity> active =
                lexicalOnly || !endpoint.configured()
                        ? List.of()
                        : activeVectors(batchIds, profile);
        String mode = "LEXICAL";
        if (!active.isEmpty()) {
            double[] vector = embedding.embed(endpoint, query);
            List<IndexTaskEntity> compatible =
                    active.stream().filter(task -> task.getDimensions() == vector.length).toList();
            if (compatible.size() != active.size()) {
                throw new IllegalStateException("EMBEDDING_DIMENSION_CHANGED");
            }
            vectors =
                    elastic.vector(
                            elastic.vectorIndex(profile, vector.length),
                            version,
                            batchIds,
                            compatible.stream().map(IndexTaskEntity::getId).toList(),
                            vector,
                            50);
            mode = "HYBRID";
        }
        // 网络请求期间可能发布新版或删除文档，返回前再次收紧可见集合。
        Set<String> visible = new HashSet<>(catalog.applicableBatches(version));
        Map<String, ChunkSource> sources = new LinkedHashMap<>();
        Map<String, Double> scores = new HashMap<>();
        rank(lexical, visible, sources, scores);
        rank(vectors, visible, sources, scores);
        List<ChunkSource> candidates =
                sources.values().stream()
                        .sorted(
                                Comparator.<ChunkSource>comparingDouble(
                                                source -> scores.get(source.id()))
                                        .reversed()
                                        .thenComparing(ChunkSource::id))
                        .limit(reranker.enabled() ? reranker.candidateLimit() : sources.size())
                        .toList();
        RerankResult reranked = reranker.score(query, candidates.stream().map(ChunkSource::content).toList());
        Map<String, Double> modelScores = new HashMap<>();
        boolean modelRanked = "ONNX".equals(reranked.ranking().method());
        if (reranker.enabled() && !candidates.isEmpty() && !modelRanked) {
            throw new RerankingException("RERANK_OUTPUT_INVALID");
        }
        if (modelRanked) {
            if (reranked.scores().size() != candidates.size()) {
                throw new RerankingException("RERANK_OUTPUT_INVALID");
            }
            for (int index = 0; index < candidates.size(); index++) {
                Double score = reranked.scores().get(index);
                if (score == null || !Double.isFinite(score)) {
                    throw new RerankingException("RERANK_OUTPUT_INVALID");
                }
                modelScores.put(candidates.get(index).id(), score);
            }
        }
        // 模型可能耗时较长，不能在精排后返回期间被删除或替换的资料。
        Set<String> finalVisible = new HashSet<>(catalog.applicableBatches(version));
        Map<String, Double> finalScores = modelRanked ? modelScores : scores;
        List<KnowledgePassage> passages = candidates.stream()
                .filter(source -> finalVisible.contains(source.batchId()))
                .sorted(Comparator.<ChunkSource>comparingDouble(source -> finalScores.get(source.id()))
                        .reversed().thenComparing(ChunkSource::id))
                .limit(count)
                .map(source -> passage(source, scores.get(source.id()), modelScores.get(source.id())))
                .toList();
        return new KnowledgeSearchResult(mode, passages, reranked.ranking());
    }

    /** 发布指针只指向完成全量校验的任务，排除其他模型或未发布的暂存数据。 */
    private List<IndexTaskEntity> activeVectors(List<String> batches, String profile) {
        if (batches.isEmpty()) {
            return List.of();
        }
        List<String> ids =
                releases
                        .selectList(
                                Wrappers.<DocumentReleaseEntity>lambdaQuery()
                                        .in(DocumentReleaseEntity::getBatchId, batches)
                                        .isNotNull(DocumentReleaseEntity::getVectorTaskId))
                        .stream()
                        .map(DocumentReleaseEntity::getVectorTaskId)
                        .toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        return tasks.selectList(
                Wrappers.<IndexTaskEntity>lambdaQuery()
                        .in(IndexTaskEntity::getId, ids)
                        .eq(IndexTaskEntity::getProfileKey, profile)
                        .gt(IndexTaskEntity::getDimensions, 0));
    }

    /** 每份召回结果只贡献一次排名，常数 60 降低极少数头部结果的波动。 */
    private void rank(
            List<Hit> hits,
            Set<String> visible,
            Map<String, ChunkSource> sources,
            Map<String, Double> scores) {
        Set<String> seen = new HashSet<>();
        for (int index = 0; index < hits.size(); index++) {
            ChunkSource source = hits.get(index).source();
            if (!visible.contains(source.batchId()) || !seen.add(source.id())) {
                continue;
            }
            sources.put(source.id(), source);
            scores.merge(source.id(), 1.0 / (61 + index), Double::sum);
        }
    }

    /** 引用绑定不可变修订，PDF 页码可直接用于原件定位。 */
    private KnowledgePassage passage(ChunkSource source, double score, Double rerankScore) {
        String url = "/api/documents/versions/" + source.versionId() + "/original";
        if (source.pageStart() != null) {
            url += "#page=" + source.pageStart();
        }
        return new KnowledgePassage(
                source.id(),
                source.documentId(),
                source.title(),
                source.productVersion(),
                source.location(),
                source.content(),
                score,
                source.versionId(),
                source.batchId(),
                url,
                rerankScore);
    }

    /** 内置资料与用户上传共用原件、去重和持久化任务流程。 */
    @Override
    public List<KnowledgeDocument> importExamples() {
        List<ExampleDocument> examples =
                List.of(
                        new ExampleDocument("orderbridge-2.0.md", "OrderBridge 2.0 运行手册", "2.0"),
                        new ExampleDocument(
                                "migration-2.0.md", "OrderBridge 1.0 到 2.0 配置迁移", "2.0"),
                        new ExampleDocument("orderbridge-1.0.md", "OrderBridge 1.0 历史手册", "1.0"));
        List<KnowledgeDocument> result = new ArrayList<>();
        for (ExampleDocument example : examples) {
            try (InputStream input =
                    new ClassPathResource("bundled-knowledge/" + example.filename())
                            .getInputStream()) {
                result.add(
                        ingest(
                                example.filename(),
                                example.title(),
                                example.version(),
                                input.readAllBytes()));
            } catch (IOException exception) {
                throw new IllegalStateException("BUNDLED_DOCUMENT_MISSING");
            }
        }
        return result;
    }

    /** 内置资源的不可变说明。 */
    private record ExampleDocument(String filename, String title, String version) {}
}
