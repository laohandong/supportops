package io.supportops.knowledge.service;

import io.supportops.knowledge.entity.IndexTaskEntity;
import io.supportops.knowledge.entity.ProcessingBatchEntity;
import io.supportops.knowledge.vo.KnowledgeViews;

import java.util.List;

/** 持久化任务创建、租约、状态和补偿边界。 */
public interface KnowledgeTaskService {
    /** 在调用方事务中创建可由定时扫描补捞的任务。 */
    IndexTaskEntity create(ProcessingBatchEntity batch, String kind, String profile);

    /** 列出文档的任务。 */
    List<KnowledgeViews.Task> list(String documentId);

    /** 列出任务执行历史。 */
    List<KnowledgeViews.Attempt> attempts(String taskId);

    /** 重置失败任务，仍保留过去的尝试历史。 */
    void retry(String taskId);

    /** 在文档事务中取消未完成任务；批次非空时仅将该批次的向量任务标记为被替代。 */
    void cancel(String documentId, String batchId);

    /** 尝试领取一个到期任务，返回带新令牌的快照。 */
    IndexTaskEntity claim();

    /** 续租并校验领取令牌；失败表示执行权已失效。 */
    boolean heartbeat(IndexTaskEntity task);

    /** 校验领取权并更新阶段与进度。 */
    void progress(IndexTaskEntity task, String stage, int completed);

    /** 完成一次执行，失败按类型进入补偿或人工处理状态。 */
    void finish(IndexTaskEntity task, Throwable error);

    /** 校验执行权，过期线程不能发布结果。 */
    void requireLease(IndexTaskEntity task);
}
