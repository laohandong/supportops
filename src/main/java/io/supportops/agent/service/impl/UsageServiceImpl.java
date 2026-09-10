package io.supportops.agent.service.impl;

import io.supportops.agent.mapper.DiagnosisRunMapper;
import io.supportops.agent.service.UsageService;
import io.supportops.agent.vo.DiagnosisRun;
import io.supportops.agent.vo.UsageAnalysis;
import io.supportops.agent.vo.UsageGroup;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 短只读事务汇总任务，不调用模型或重复累计事件。 */
@Service
public class UsageServiceImpl implements UsageService {
    private final DiagnosisRunMapper runs;

    /** 注入诊断查询 Mapper。 */
    public UsageServiceImpl(DiagnosisRunMapper runs) {
        this.runs = runs;
    }

    /** 同一快照读取总览、趋势和排行；日期前缀兼容旧 ISO 时间的小数精度。 */
    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public UsageAnalysis analyze(String from, String to, String granularity) {
        LocalDate start = parseDate(from);
        LocalDate end = parseDate(to);
        long days = ChronoUnit.DAYS.between(start, end) + 1;
        if (days < 1 || days > 90 || !("hour".equals(granularity) || "day".equals(granularity))) {
            throw new IllegalArgumentException("INVALID_INPUT");
        }
        String lower = start.toString();
        String upper = end.plusDays(1).toString();
        int length = "hour".equals(granularity) ? 13 : 10;
        Map<String, UsageGroup> found = new HashMap<>();
        for (UsageGroup group : runs.selectUsageTimeline(lower, upper, length)) {
            found.put(group.groupId(), group);
        }
        List<UsageGroup> timeline = new ArrayList<>();
        LocalDateTime cursor = start.atStartOfDay();
        while (cursor.isBefore(end.plusDays(1).atStartOfDay())) {
            String key = cursor.toString().substring(0, length);
            timeline.add(found.getOrDefault(key, new UsageGroup(key, "", 0, 0, 0, 0, 0, 0, 0)));
            cursor = length == 13 ? cursor.plusHours(1) : cursor.plusDays(1);
        }
        return new UsageAnalysis(
                lower,
                end.toString(),
                granularity,
                runs.selectUsageSummary(lower, upper),
                timeline,
                runs.selectUsageTop(lower, upper));
    }

    /** 限制为四位公历年份，格式错误统一返回参数错误。 */
    private LocalDate parseDate(String value) {
        try {
            if (value == null || !value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
                throw new IllegalArgumentException("INVALID_INPUT");
            }
            return LocalDate.parse(value);
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException("INVALID_INPUT");
        }
    }

    /** 分页回看完整会话，不受最近一百条列表限制。 */
    @Override
    public List<DiagnosisRun> session(String sessionId, int offset) {
        // 与创建接口保持一致，旧记录可能保存可解析的短 UUID 文本。
        UUID.fromString(sessionId);
        if (offset < 0 || offset > 1000000) {
            throw new IllegalArgumentException("INVALID_INPUT");
        }
        return runs.selectUsageSession(sessionId, offset);
    }
}
