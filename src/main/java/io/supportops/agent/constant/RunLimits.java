package io.supportops.agent.constant;

/** 诊断输入、历史上下文及工具证据的长度边界。 */
public final class RunLimits {
    public static final int QUESTION_LENGTH = 6000;
    public static final int HISTORY_ANSWER_LENGTH = 5000;
    public static final int TOOL_TEXT_LENGTH = 24000;

    /** 常量类不允许实例化。 */
    private RunLimits() {}
}
