package io.supportops.knowledge.service.support;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 关键词覆盖与余弦评分的确定性计算，不访问外部模型或数据库。 */
public final class KnowledgeScoring {
    /** 工具类不允许实例化。 */
    private KnowledgeScoring() {}

    /** 提取英文词、配置键和中文二元词，保持关键词基线可重复。 */
    public static Set<String> terms(String text) {
        Set<String> result = new HashSet<String>();
        Matcher matcher =
                Pattern.compile("[a-zA-Z0-9_.-]+|[\\p{IsHan}]+")
                        .matcher(text.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            String term = matcher.group();
            result.add(term);
            if (term.matches("[\\p{IsHan}]+")) {
                for (int i = 0; i + 1 < term.length(); i++) {
                    result.add(term.substring(i, i + 2));
                }
            }
        }
        return result;
    }

    /** 计算查询词在候选正文中的覆盖比例，不代表诊断置信度。 */
    public static double lexical(Set<String> terms, String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        return terms.isEmpty()
                ? 0
                : (double) terms.stream().filter(lower::contains).count() / terms.size();
    }

    /** 计算同维向量的余弦相似度；维度不一致时拒绝计算。 */
    public static double cosine(double[] a, double[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException("Dimension mismatch");
        }
        double dot = 0, aa = 0, bb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            aa += a[i] * a[i];
            bb += b[i] * b[i];
        }
        return aa == 0 || bb == 0 ? 0 : dot / Math.sqrt(aa * bb);
    }
}
