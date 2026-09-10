package io.supportops.knowledge.service;

import io.supportops.knowledge.dto.RerankResult;
import java.util.List;

/** 对已经过版本过滤的候选正文评分，不承担召回或权限筛选。 */
public interface PassageReranker {
    /** 返回 RRF 初筛数量上限，只有开启模型时才用于缩小候选集合。 */
    int candidateLimit();

    /** 是否显式启用本地模型；启用后失败必须抛错，不降级为 RRF。 */
    boolean enabled();

    /** 按输入顺序返回相关性原始 logits；该值不是概率或诊断置信度。 */
    RerankResult score(String query, List<String> passages);
}
