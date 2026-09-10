package io.supportops.knowledge.dto;

import io.supportops.knowledge.vo.RankingInfo;
import java.util.List;

/** 分数与输入候选一一对应；模型关闭或空候选时分数列表为空。 */
public record RerankResult(List<Double> scores, RankingInfo ranking) {}
