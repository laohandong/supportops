package io.supportops.knowledge.service;

import io.supportops.knowledge.entity.IndexTaskEntity;
import io.supportops.knowledge.entity.ProcessingBatchEntity;

/** 执行 TEXT 和 VECTOR 阶段，分别验证完整性后发布，失败保留旧可用结果。 */
public interface DocumentIndexService {
    /** 将正文与元数据同步至 ES，完整后发布文本批次并创建向量任务。 */
    void indexText(IndexTaskEntity task, ProcessingBatchEntity batch);

    /** 固定模型配置，逐片生成和补偿向量，完整后关联可用的向量任务。 */
    void indexVector(IndexTaskEntity task, ProcessingBatchEntity batch);
}
