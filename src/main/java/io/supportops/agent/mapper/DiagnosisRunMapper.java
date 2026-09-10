package io.supportops.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import io.supportops.agent.entity.DiagnosisRunEntity;
import io.supportops.agent.memory.ConversationTurn;
import io.supportops.agent.vo.DiagnosisRun;
import io.supportops.agent.vo.UsageGroup;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 诊断任务持久化与带旧状态条件的并发更新。 */
@Mapper
public interface DiagnosisRunMapper extends BaseMapper<DiagnosisRunEntity> {
    /** 汇总全部范围记录，不受历史列表限制。 */
    UsageGroup selectUsageSummary(@Param("from") String from, @Param("until") String until);

    /** 按服务端固定前缀长度聚合 UTC 时间段。 */
    List<UsageGroup> selectUsageTimeline(
            @Param("from") String from, @Param("until") String until, @Param("length") int length);

    /** 按会话累计 Token 降序返回前二十名。 */
    List<UsageGroup> selectUsageTop(@Param("from") String from, @Param("until") String until);

    /** 分页读取问答，每页五十轮。 */
    List<DiagnosisRun> selectUsageSession(
            @Param("sessionId") String sessionId, @Param("offset") int offset);

    /** 读取最近一百条任务，按创建时间倒序。 */
    List<DiagnosisRunEntity> selectRecentRuns();

    /** 读取同会话最近三轮已完成问答，按创建时间倒序。 */
    List<ConversationTurn> selectRecentHistory(@Param("sessionId") String sessionId);

    /** 只将仍在执行的任务写入终态，返回实际更新数量。 */
    int finishActiveRun(@Param("run") DiagnosisRunEntity run);

    /** 原子累计模型报告用量，防止并发读改写丢失计量。 */
    int incrementUsage(
            @Param("id") String id,
            @Param("inputTokens") long inputTokens,
            @Param("outputTokens") long outputTokens);
}
