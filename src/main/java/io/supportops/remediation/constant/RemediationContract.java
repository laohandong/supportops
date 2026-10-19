package io.supportops.remediation.constant;

/** V1 固定动作与资源预算；客户端不能扩展目标、路径或调用次数。 */
public final class RemediationContract {
    public static final String ACTION = "ORDERBRIDGE_MIGRATE_SYNC_PATH";
    public static final String TARGET = "BUILTIN_ORDERBRIDGE";
    public static final String PATH = "/v2/orders";
    public static final int SCHEMA_VERSION = 1;
    public static final int ACTION_VERSION = 1;
    public static final int VERIFICATION_VERSION = 1;
    public static final int APPROVAL_POLICY_VERSION = 1;
    public static final long PLAN_TTL_MS = 1_800_000;
    public static final long APPROVAL_TTL_MS = 600_000;
    public static final int RECEIPT_CAPACITY = 64;
    public static final int MAX_EVIDENCE_BYTES = 65_536;
    public static final int EXECUTION_BUDGET_SECONDS = 10;
    public static final int HTTP_BUDGET_SECONDS = 3;

    /** 固定契约不允许实例化。 */
    private RemediationContract() {}
}
