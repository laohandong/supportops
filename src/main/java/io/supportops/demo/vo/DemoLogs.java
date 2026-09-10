package io.supportops.demo.vo;

import java.util.List;

/** MCP 日志事实快照；observedAt 为采集时间，entries 为实际请求记录。 */
public record DemoLogs(List<DemoLogEntry> entries, String observedAt) {}
