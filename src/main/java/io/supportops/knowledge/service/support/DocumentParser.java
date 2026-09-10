package io.supportops.knowledge.service.support;

import io.supportops.knowledge.constant.KnowledgeLimits;
import io.supportops.knowledge.dto.ImportOptions;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 按 Unicode 码点切分文本，保留规范化正文中的行、页和零基半开字符区间。 */
@Component
public class DocumentParser {
    /** 来源位置：行与页从 1 开始，字符区间按规范化章节或 PDF 页计数。 */
    public record Part(
            String location,
            String content,
            String heading,
            String headingPath,
            Integer pageStart,
            Integer pageEnd,
            Integer lineStart,
            Integer lineEnd,
            int charStart,
            int charEnd) {
        /** 兼容无结构位置的已有片段调用。 */
        public Part(String location, String content) {
            this(
                    location,
                    content,
                    "",
                    "",
                    null,
                    null,
                    null,
                    null,
                    0,
                    content.codePointCount(0, content.length()));
        }
    }

    /** 使用兼容窗口解析文档。 */
    public List<Part> parse(String filename, byte[] bytes) {
        return parse(filename, bytes, ImportOptions.defaults());
    }

    /** 限制页数和累计分片数，解析失败不返回截断内容。 */
    public List<Part> parse(String filename, byte[] bytes, ImportOptions options) {
        List<Part> parts = new ArrayList<>();
        try {
            String lower = filename.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".pdf")) {
                parsePdf(parts, bytes, options);
            } else if (lower.endsWith(".md")) {
                String text = decodeMarkdown(bytes);
                markdown(parts, normalize(text), options);
            } else {
                throw failure(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "ONLY_MD_PDF_XLS_XLSX_SUPPORTED");
            }
            if (parts.isEmpty()) {
                throw failure(HttpStatus.UNPROCESSABLE_ENTITY, "NO_EXTRACTABLE_TEXT");
            }
            return parts;
        } catch (CharacterCodingException exception) {
            throw failure(HttpStatus.BAD_REQUEST, "DOCUMENT_MUST_BE_UTF8");
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            throw failure(HttpStatus.UNPROCESSABLE_ENTITY, "DOCUMENT_PARSE_FAILED");
        }
    }

    /** 每页独立切分，重叠不会跨页，引用页码与原 PDF 保持一致。 */
    private void parsePdf(List<Part> parts, byte[] bytes, ImportOptions options)
            throws IOException {
        try (PDDocument pdf = Loader.loadPDF(bytes)) {
            if (pdf.getNumberOfPages() > 500) {
                throw failure(HttpStatus.PAYLOAD_TOO_LARGE, "PDF_TOO_LONG");
            }
            PDFTextStripper stripper = new PDFTextStripper();
            for (int page = 1; page <= pdf.getNumberOfPages(); page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                split(parts, normalize(stripper.getText(pdf)), "", "", page, null, options);
            }
        }
    }

    /** 非 UTF-8 内容直接拒绝，避免替换字符破坏原文与引用偏移。 */
    private String decodeMarkdown(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
    }

    /** 标题仅在代码围栏外生效；章节层级保留为可检索路径。 */
    private void markdown(List<Part> parts, String text, ImportOptions options) {
        String[] lines = text.split("\n", -1);
        List<String> headings = new ArrayList<>();
        StringBuilder section = new StringBuilder();
        String heading = "正文";
        String path = "正文";
        int sectionLine = 1;
        String fence = "";
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index];
            String trimmed = line.stripLeading();
            // 围栏中的 # 是代码正文，不能创建新章节并改变后续引用位置。
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                String current = trimmed.substring(0, 3);
                if (fence.isEmpty()) {
                    fence = current;
                } else if (fence.equals(current)) {
                    fence = "";
                }
            }
            if (fence.isEmpty() && line.matches("#{1,6} .*")) {
                // 先完成上一章节，再更新标题路径；章节边界两侧不做重叠。
                split(parts, section.toString(), heading, path, null, sectionLine, options);
                section.setLength(0);
                int level = line.indexOf(' ');
                heading = line.substring(level + 1).strip();
                while (headings.size() >= level) {
                    headings.removeLast();
                }
                while (headings.size() < level - 1) {
                    headings.add("");
                }
                headings.add(heading);
                path = String.join(" / ", headings);
                sectionLine = index + 1;
            }
            section.append(line);
            if (index < lines.length - 1) {
                section.append('\n');
            }
        }
        split(parts, section.toString(), heading, path, null, sectionLine, options);
    }

    /** 码点窗口不切断代理对；行号对应片段实际覆盖范围。 */
    private void split(
            List<Part> parts,
            String text,
            String heading,
            String path,
            Integer page,
            Integer firstLine,
            ImportOptions options) {
        if (text.isBlank()) {
            return;
        }
        int length = text.codePointCount(0, text.length());
        int step = options.chunkSize() - options.overlap();
        for (int start = 0; start < length; start += step) {
            if (parts.size() >= KnowledgeLimits.MAX_CHUNKS) {
                throw failure(HttpStatus.PAYLOAD_TOO_LARGE, "TOO_MANY_CHUNKS");
            }
            int end = Math.min(start + options.chunkSize(), length);
            // 对外偏移按码点计数；substring 使用 UTF-16 偏移，需要先转换，避免切断 emoji 等字符。
            int startOffset = text.offsetByCodePoints(0, start);
            int endOffset = text.offsetByCodePoints(0, end);
            String content = text.substring(startOffset, endOffset);
            Integer lineStart =
                    firstLine == null ? null : firstLine + countNewlines(text, startOffset);
            // 片段结尾的换行只结束当前行，不把下一行算进引用区间。
            Integer lineEnd =
                    lineStart == null
                            ? null
                            : lineStart + countNewlines(content, Math.max(0, content.length() - 1));
            String location =
                    page != null
                            ? "第 " + page + " 页"
                            : abbreviate(heading, 90) + " · 行 " + lineStart + "–" + lineEnd;
            parts.add(
                    new Part(
                            location + " · 段 " + (parts.size() + 1),
                            content,
                            abbreviate(heading, 500),
                            abbreviate(path, 1500),
                            page,
                            page,
                            lineStart,
                            lineEnd,
                            start,
                            end));
            if (end == length) {
                break;
            }
        }
    }

    /** 统计指定 UTF-16 前缀内的换行，用于从章节偏移恢复原文行号。 */
    private int countNewlines(String text, int endOffset) {
        return (int)
                text.substring(0, endOffset).chars().filter(character -> character == '\n').count();
    }

    /** 对照原文件按行定位，统一 CRLF 与 CR 换行。 */
    private String normalize(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }

    /** 限制可展示元数据长度，不截断正文。 */
    private String abbreviate(String value, int maximum) {
        return value.substring(0, Math.min(value.length(), maximum));
    }

    /** 创建不含原始文档内容的业务错误。 */
    private ResponseStatusException failure(HttpStatus status, String code) {
        return new ResponseStatusException(status, code);
    }
}
