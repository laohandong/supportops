package io.supportops.knowledge.service;

import io.supportops.knowledge.entity.DocumentReleaseEntity;
import io.supportops.knowledge.entity.KnowledgeDocumentEntity;
import io.supportops.knowledge.entity.ProcessingBatchEntity;

import java.io.InputStream;

/** 集中维护文档可见性、确切修订原件和发布指针，供上传及后台处理共享。 */
public interface DocumentRevisionService {
    /** 读取未删除文档；lock 为 true 时必须在调用方的事务内持有行锁。 */
    KnowledgeDocumentEntity requireDocument(String id, boolean lock);

    /** 打开指定修订的私有原件，校验文档与原件状态；调用方负责关闭流。 */
    InputStream original(String versionId);

    /** 查询批次所属产品版本的当前发布指针；尚未发布时返回 null。 */
    DocumentReleaseEntity release(ProcessingBatchEntity batch);

    /** 发布已完整校验的批次；调用方须先在同一事务内锁定文档并核对任务租约。 */
    void publish(ProcessingBatchEntity batch, String vectorTask);
}
