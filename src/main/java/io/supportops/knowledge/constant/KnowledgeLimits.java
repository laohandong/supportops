package io.supportops.knowledge.constant;

/** 文档入库与检索的本地容量边界，兼容已有接口约束。 */
public final class KnowledgeLimits {
    /** 新上传与重处理默认使用的码点窗口。 */
    public static final int DEFAULT_CHUNK_SIZE = 100;

    /** 同一章节或 PDF 页内，相邻窗口默认重叠的码点数。 */
    public static final int DEFAULT_CHUNK_OVERLAP = 10;

    /** 可配置窗口下限与上限，均按 Unicode 码点计数。 */
    public static final int MIN_CHUNK_SIZE = 100;

    public static final int MAX_CHUNK_SIZE = 8000;

    /** 每次至少向前推进十个码点，允许 100/10 并避免极小步长放大处理量。 */
    public static final int MIN_CHUNK_STEP = 10;

    public static final int FILE_BYTES = 20 * 1024 * 1024;
    public static final int TITLE_LENGTH = 180;
    public static final int FILENAME_LENGTH = 200;
    public static final int VERSION_LENGTH = 30;
    public static final int MAX_CHUNKS = 10000;
    public static final int QUERY_LENGTH = 2000;
    public static final int MAX_SEARCH_RESULTS = 8;
    public static final String VERSION_PATTERN = "\\*|[A-Za-z0-9][A-Za-z0-9._-]{0,29}";

    /** 常量类不允许实例化。 */
    private KnowledgeLimits() {}
}
