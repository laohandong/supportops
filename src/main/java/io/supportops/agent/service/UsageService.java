package io.supportops.agent.service;

import io.supportops.agent.vo.DiagnosisRun;
import io.supportops.agent.vo.UsageAnalysis;

import java.util.List;

/** 对持久化诊断记录执行只读用量分析。 */
public interface UsageService {
    /** 查询 UTC 日期范围，最多 90 天，粒度为 hour 或 day。 */
    UsageAnalysis analyze(String from, String to, String granularity);

    /** 按创建时间读取会话历史，每页最多 50 轮。 */
    List<DiagnosisRun> session(String sessionId, int offset);
}
