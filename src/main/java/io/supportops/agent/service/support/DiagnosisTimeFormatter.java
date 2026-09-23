package io.supportops.agent.service.support;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 将诊断正文中有明确时区的 ISO 时间显示为北京时间，不改写原始工具证据。 */
public final class DiagnosisTimeFormatter {
    private static final Pattern TIMESTAMP = Pattern.compile(
            "(?<![A-Za-z0-9_/?=&%#.+-])\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}"
                    + "(?:\\.\\d{1,9})?(?:Z|[+-]\\d{2}:\\d{2})(?![A-Za-z0-9_/?=&%#+-])");
    private static final Pattern FENCE = Pattern.compile("(?s)^ {0,3}(`{3,}|~{3,})(.*)$");
    private static final Pattern QUOTE_PREFIX = Pattern.compile("^(?: {0,3}>[ \\t]?)+");
    private static final DateTimeFormatter DISPLAY = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss");
    private static final ZoneOffset BEIJING = ZoneOffset.ofHours(8);

    /** 工具类不承载配置或可变状态。 */
    private DiagnosisTimeFormatter() {}

    /** 格式化正文和行内时间；代码块、无时区日期、非法日期和链接中的值保持原样。 */
    public static String format(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String[] lines = text.split("\n", -1);
        String fence = "";
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index];
            // Markdown 引用块可嵌套代码围栏；识别结构时去掉引用标记，输出仍保留原文。
            String content = QUOTE_PREFIX.matcher(line).replaceFirst("");
            Matcher marker = FENCE.matcher(content);
            if (marker.matches()) {
                String delimiter = marker.group(1);
                if (fence.isEmpty()) {
                    fence = delimiter;
                } else if (delimiter.charAt(0) == fence.charAt(0)
                        && delimiter.length() >= fence.length() && marker.group(2).isBlank()) {
                    fence = "";
                }
                continue;
            }
            // 原样保留日志和请求代码块，避免把供复制核验的时间改成展示值。
            if (fence.isEmpty() && !content.startsWith("    ") && !content.startsWith("\t")) {
                lines[index] = TIMESTAMP.matcher(line).replaceAll(match ->
                        Matcher.quoteReplacement(formatTimestamp(match.group())));
            }
        }
        return String.join("\n", lines);
    }

    /** 只转换可严格解析的带偏移时间；展示精确到秒，原始小数秒仍保留在证据中。 */
    private static String formatTimestamp(String timestamp) {
        try {
            return OffsetDateTime.parse(timestamp).withOffsetSameInstant(BEIJING).format(DISPLAY)
                    + "（北京时间）";
        } catch (DateTimeParseException exception) {
            return timestamp;
        }
    }
}
