package io.supportops.knowledge.service;

import io.supportops.knowledge.vo.DocumentChunk;
import io.supportops.knowledge.vo.KnowledgeDocument;
import io.supportops.knowledge.vo.KnowledgeSearchResult;

import java.util.List;

/** 按产品版本隔离的文档管理、索引与检索业务边界。 */
public interface KnowledgeService {
    /** 保存原件并受理持久化处理任务，返回状态不代表分片或向量已经完成。 */
    KnowledgeDocument ingest(String filename, String title, String version, byte[] bytes);

    /** 受理异步重建，固定端点生成向量并在全量校验后发布。 */
    KnowledgeDocument reindex(String id);

    /** 按创建时间倒序读取文档元数据和分片数量。 */
    List<KnowledgeDocument> documents();

    /** 读取指定文档，不存在时明确报告。 */
    KnowledgeDocument document(String id);

    /** 读取文档的原始分片，不返回存储向量。 */
    List<DocumentChunk> content(String id);

    /** 逻辑删除立即停止检索，原件和外部索引由补偿任务清理，保留审计历史。 */
    void delete(String id);

    /** 先执行版本过滤，再按明确的关键词或混合模式检索。 */
    KnowledgeSearchResult search(String query, String version, int limit, boolean lexicalOnly);

    /** 导入三份内置资料，重复调用依照内容摘要和版本去重。 */
    List<KnowledgeDocument> importExamples();
}
