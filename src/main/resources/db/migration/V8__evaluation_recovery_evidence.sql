-- 恢复补录使用独立权限，原执行令牌与封存事件身份不能被恢复令牌覆盖。
ALTER TABLE evaluation_attempts
    ADD COLUMN execution_token BIGINT NULL COMMENT '原执行令牌；排队未领取或旧中断记录尚未核实为空',
    ADD COLUMN recovery_status VARCHAR(24) NOT NULL DEFAULT 'NOT_REQUIRED' COMMENT '补录状态 NOT_REQUIRED PENDING IMPORTING COMPLETE FAILED',
    ADD COLUMN recovery_error VARCHAR(80) NOT NULL DEFAULT '' COMMENT '独立补录错误码，不覆盖执行或清理错误',
    ADD COLUMN recovery_event_sha256 VARCHAR(64) NULL COMMENT '进程退出后封存原事件文件的字节 SHA256',
    ADD COLUMN recovery_event_bytes BIGINT NULL COMMENT '封存事件文件字节数，未封存为空';
-- 已经提升过令牌的旧中断记录不能猜测原令牌，恢复时核对受限清单。
UPDATE evaluation_attempts SET execution_token=fencing_token
WHERE fencing_token > 0 AND state <> 'INTERRUPTED';
