-- 飞书私聊：身份绑定、持久化收件及可恢复回复；不改写既有诊断与账号。
CREATE TABLE feishu_bindings (
    id VARCHAR(36) PRIMARY KEY COMMENT '绑定记录 UUID',
    app_id VARCHAR(128) NOT NULL COMMENT '飞书应用编号',
    tenant_key VARCHAR(128) NOT NULL COMMENT '允许接入的企业编号',
    user_id VARCHAR(36) NOT NULL COMMENT '关联工作台用户 UUID',
    open_id VARCHAR(128) NULL COMMENT '应用内用户编号，未绑定或撤销时为空',
    code_hash VARCHAR(64) NULL COMMENT '一次性绑定码 SHA-256 摘要，消费后为空',
    code_expires_at BIGINT NULL COMMENT '绑定码到期时间，Unix 毫秒',
    revoked BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否已撤销，撤销后停止投递',
    created_at VARCHAR(40) NOT NULL COMMENT '创建时间，UTC ISO 8601',
    UNIQUE KEY feishu_bound_identity (app_id, tenant_key, open_id),
    UNIQUE KEY feishu_binding_code (code_hash),
    KEY feishu_binding_user (user_id, created_at),
    FOREIGN KEY (user_id) REFERENCES app_users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='飞书身份绑定和一次性绑定码';

CREATE TABLE feishu_inbox (
    id VARCHAR(36) PRIMARY KEY COMMENT '收件 UUID，诊断使用同一编号',
    app_id VARCHAR(128) NOT NULL COMMENT '接收消息的飞书应用编号',
    tenant_key VARCHAR(128) NOT NULL COMMENT '消息所属企业编号',
    message_id VARCHAR(128) NOT NULL COMMENT '飞书消息编号，用于去重',
    open_id VARCHAR(128) NOT NULL COMMENT '发送者的应用内用户编号',
    binding_id VARCHAR(36) NULL COMMENT '受理时绑定编号，未绑定通知为空',
    user_id VARCHAR(36) NULL COMMENT '服务端确认的归属用户，未绑定通知为空',
    question VARCHAR(6000) NOT NULL COMMENT '诊断问题，绑定命令不保存原文',
    state VARCHAR(24) NOT NULL COMMENT 'RECEIVED 待受理、WAITING 等待结果、FINISHED 已生成回复、BLOCKED 绑定失效',
    error_code VARCHAR(80) NOT NULL DEFAULT '' COMMENT '脱敏受理错误码',
    created_at VARCHAR(40) NOT NULL COMMENT '接收时间，UTC ISO 8601',
    UNIQUE KEY feishu_received_message (app_id, tenant_key, message_id),
    KEY feishu_inbox_pending (state, created_at),
    FOREIGN KEY (binding_id) REFERENCES feishu_bindings(id),
    FOREIGN KEY (user_id) REFERENCES app_users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='飞书私聊收件及诊断关联';

CREATE TABLE feishu_outbox (
    id VARCHAR(36) PRIMARY KEY COMMENT '回复 UUID，也是飞书发送去重标识',
    inbox_id VARCHAR(36) NOT NULL COMMENT '关联收件记录 UUID',
    kind VARCHAR(24) NOT NULL COMMENT 'NOTICE 绑定提示、ACCEPTED 受理提示、RESULT 诊断结果',
    content TEXT NOT NULL COMMENT '冻结的纯文本回复正文',
    state VARCHAR(24) NOT NULL COMMENT 'PENDING 待发送、SENT 成功、FAILED 重试耗尽、UNKNOWN 超出去重窗口、BLOCKED 绑定失效',
    attempts INT NOT NULL DEFAULT 0 COMMENT '已开始的发送次数',
    first_attempt_at BIGINT NULL COMMENT '首次发送时间，Unix 毫秒',
    next_attempt_at BIGINT NOT NULL COMMENT '下次允许发送时间，Unix 毫秒',
    platform_message_id VARCHAR(128) NULL COMMENT '飞书返回的消息编号，未确认成功为空',
    error_code VARCHAR(80) NOT NULL DEFAULT '' COMMENT '脱敏发送错误码',
    created_at VARCHAR(40) NOT NULL COMMENT '创建时间，UTC ISO 8601',
    UNIQUE KEY feishu_reply_kind (inbox_id, kind),
    KEY feishu_outbox_pending (state, next_attempt_at),
    FOREIGN KEY (inbox_id) REFERENCES feishu_inbox(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='飞书冻结回复及投递恢复记录';
