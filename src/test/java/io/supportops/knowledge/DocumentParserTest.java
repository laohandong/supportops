package io.supportops.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.supportops.knowledge.dto.ImportOptions;
import io.supportops.knowledge.service.support.DocumentParser;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** 校验正文提取和位置计算拆分后，引用仍对应规范化原文的真实区间。 */
class DocumentParserTest {
    /** 默认参数必须真正形成 100 码点窗口及 10 码点重叠，而不只是响应里标记数值。 */
    @Test
    void defaultWindowsContainHundredCodePointsAndTenCodePointOverlap() {
        ImportOptions options = ImportOptions.defaults();
        assertThat(options.chunkSize()).isEqualTo(100);
        assertThat(options.overlap()).isEqualTo(10);
        String text = "甲乙😀".repeat(100);
        List<DocumentParser.Part> parts =
                new DocumentParser().parse("defaults.md", text.getBytes(StandardCharsets.UTF_8));
        assertThat(parts).hasSize(4);
        for (int index = 0; index < parts.size() - 1; index++) {
            int[] current = parts.get(index).content().codePoints().toArray();
            int[] next = parts.get(index + 1).content().codePoints().toArray();
            assertThat(current).hasSize(100);
            assertThat(Arrays.copyOfRange(current, 90, 100))
                    .isEqualTo(Arrays.copyOfRange(next, 0, 10));
        }
        assertThat(parts.getLast().content().codePointCount(0, parts.getLast().content().length()))
                .isEqualTo(30);
        assertThatThrownBy(() -> new ImportOptions(100, 91, List.of(), 0, Map.of(), false))
                .hasMessage("INVALID_IMPORT_OPTIONS");
    }

    /** CRLF 规范化、代码围栏与标题层级共同决定分片边界和原文行号。 */
    @Test
    void markdownKeepsFencedHeadingsInsideTheirOriginalSection() {
        String text = "# 顶层\r\n第一行\r\n```text\r\n# 围栏正文\r\n```\r\n## 子节\r\n第二行\r\n";
        List<DocumentParser.Part> parts =
                new DocumentParser()
                        .parse(
                                "headings.md",
                                text.getBytes(StandardCharsets.UTF_8),
                                ImportOptions.defaults());

        assertThat(parts).hasSize(2);
        DocumentParser.Part first = parts.getFirst();
        assertThat(first.content()).isEqualTo("# 顶层\n第一行\n```text\n# 围栏正文\n```\n");
        assertThat(first.headingPath()).isEqualTo("顶层");
        assertThat(first.lineStart()).isEqualTo(1);
        assertThat(first.lineEnd()).isEqualTo(5);
        DocumentParser.Part second = parts.getLast();
        assertThat(second.headingPath()).isEqualTo("顶层 / 子节");
        assertThat(second.lineStart()).isEqualTo(6);
        assertThat(second.lineEnd()).isEqualTo(7);
        assertThat(second.charStart()).isZero();
    }

    /** 补充字符恰落在窗口末尾时不截断，重叠片段的行区间仍覆盖原文。 */
    @Test
    void overlappingWindowKeepsSupplementaryCharacterAndExactLineRange() {
        String text = "x".repeat(198) + "\n😀\n尾";
        ImportOptions options = new ImportOptions(200, 50, List.of(), 0, Map.of(), false);
        List<DocumentParser.Part> parts =
                new DocumentParser()
                        .parse("positions.md", text.getBytes(StandardCharsets.UTF_8), options);

        assertThat(parts).hasSize(2);
        assertThat(parts.getFirst().content()).endsWith("\n😀");
        assertThat(parts.getFirst().charEnd()).isEqualTo(200);
        assertThat(parts.getFirst().lineStart()).isEqualTo(1);
        assertThat(parts.getFirst().lineEnd()).isEqualTo(2);
        assertThat(parts.getLast().charStart()).isEqualTo(150);
        assertThat(parts.getLast().lineStart()).isEqualTo(1);
        assertThat(parts.getLast().lineEnd()).isEqualTo(3);
        assertThat(parts.getLast().content()).endsWith("😀\n尾");
    }
}
