package io.supportops.agent.constant;

/** 诊断状态名称同时用于数据库和公开接口，重构时不得更改既有取值。 */
public enum RunStatus {
    /** 等待执行。 */
    QUEUED,
    /** 正在执行。 */
    RUNNING,
    /** 正常完成。 */
    COMPLETED,
    /** 执行失败。 */
    FAILED,
    /** 人员主动取消。 */
    CANCELLED,
    /** 超出时间预算。 */
    TIMED_OUT,
    /** 因关闭或重启而中断。 */
    INTERRUPTED,
    /** 达到推理轮数上限。 */
    LIMIT_REACHED
}
