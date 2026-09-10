package io.supportops.knowledge.service;

import io.supportops.knowledge.dto.ImportOptions;
import io.supportops.knowledge.entity.IndexTaskEntity;
import io.supportops.knowledge.vo.DocumentChunk;
import io.supportops.knowledge.vo.KnowledgeDocument;
import io.supportops.knowledge.vo.KnowledgeViews;

import java.io.InputStream;
import java.util.List;

/** 上传记录、修订、处理批次及后台执行的完整业务边界。 */
public interface DocumentLibraryService {
    /** 查询文档列表及最新目标批次的状态。 */
    List<KnowledgeDocument> documents();

    /** 查询单份文档，保留既有公开字段。 */
    KnowledgeDocument document(String id);

    /** 查询指定批次；空值选择当前展示批次。 */
    List<DocumentChunk> content(String id, String batchId);

    /** 逻辑删除并创建外部存储清理任务。 */
    void delete(String id);

    /** 为当前目标批次创建新的向量任务。 */
    void reindex(String id);

    /** 保存原件并受理持久化任务，相同幂等键与正文复用结果。 */
    KnowledgeViews.Upload submit(
            String filename,
            String title,
            String version,
            String documentId,
            String note,
            String requestKey,
            String source,
            byte[] bytes,
            ImportOptions options);

    /** 查询一次上传的真实结果。 */
    KnowledgeViews.Upload upload(String id);

    /** 查询文档上传历史，null 表示最近上传。 */
    List<KnowledgeViews.Upload> uploads(String documentId);

    /** 查询不可变修订历史。 */
    List<KnowledgeViews.Version> versions(String documentId);

    /** 查询处理批次与索引状态。 */
    List<KnowledgeViews.Batch> batches(String documentId);

    /** 使用原件创建新处理批次，旧可用结果保持到发布完成。 */
    KnowledgeViews.Batch reprocess(String versionId, ImportOptions options);

    /** 打开指定修订原件，调用方负责关闭。 */
    InputStream original(String versionId);

    /** 查询原件名称，用于下载头。 */
    String originalFilename(String versionId);

    /** 为旧修订补传摘要完全一致的缺失原件，不允许替换现有原件。 */
    void restoreOriginal(String versionId, byte[] bytes);

    /** 由租约持有者执行任务，阶段之间检查执行权。 */
    void execute(IndexTaskEntity task);

    /** 按当前配置唤醒等待配置的向量任务。 */
    void wakeConfiguredTasks();
}
