package io.supportops.knowledge.service;

import io.supportops.knowledge.entity.IndexTaskEntity;
import io.supportops.knowledge.entity.ProcessingBatchEntity;

/** 执行 IMPORT 阶段：恢复原件及参数，解析文本或 Excel，提交完整结果。 */
public interface DocumentImportService {
    /** 仅由有效租约持有者调用；文本结果与后续 TEXT 任务在同一事务提交。 */
    void importBatch(IndexTaskEntity task, ProcessingBatchEntity batch);
}
