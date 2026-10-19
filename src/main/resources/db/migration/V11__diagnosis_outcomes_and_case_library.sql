-- 处理事实、修复执行和模型质量分别保存；修订、发布版本及请求回执只追加。
CREATE TABLE diagnosis_outcomes (
    id VARCHAR(36) PRIMARY KEY COMMENT '结果UUID',
    run_id VARCHAR(36) NOT NULL COMMENT '来源诊断轮次，不修改其终态',
    version INT NOT NULL COMMENT '并发版本，首次为1',
    current_revision_id VARCHAR(36) NULL COMMENT '当前不可变修订',
    created_at VARCHAR(40) NOT NULL COMMENT 'UTC创建时间',
    updated_at VARCHAR(40) NOT NULL COMMENT 'UTC更新时间',
    UNIQUE KEY outcome_run (run_id),
    FOREIGN KEY (run_id) REFERENCES runs(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='人员确认的诊断处理结果';
CREATE TABLE diagnosis_outcome_revisions (
    id VARCHAR(36) PRIMARY KEY COMMENT '修订UUID',
    outcome_id VARCHAR(36) NOT NULL COMMENT '结果UUID',
    revision_number INT NOT NULL COMMENT '不可变修订号',
    content_json LONGTEXT NOT NULL COMMENT '有界结果和服务端证据快照',
    content_sha256 VARCHAR(64) NOT NULL COMMENT '规范JSON摘要',
    actor_id VARCHAR(36) NOT NULL COMMENT '服务端登录身份',
    confirmed_at VARCHAR(40) NOT NULL COMMENT 'UTC确认时间',
    UNIQUE KEY outcome_revision (outcome_id, revision_number),
    FOREIGN KEY (outcome_id) REFERENCES diagnosis_outcomes(id),
    FOREIGN KEY (actor_id) REFERENCES app_users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='不可变处理结果修订';
ALTER TABLE diagnosis_outcomes ADD FOREIGN KEY (current_revision_id) REFERENCES diagnosis_outcome_revisions(id);
CREATE TABLE diagnosis_outcome_requests (
    id VARCHAR(36) PRIMARY KEY COMMENT '回执UUID',
    actor_id VARCHAR(36) NOT NULL COMMENT '固定请求命名空间',
    request_id VARCHAR(36) NOT NULL COMMENT '固定请求UUID',
    kind VARCHAR(32) NOT NULL COMMENT '命令动作',
    target_id VARCHAR(80) NOT NULL COMMENT '来源诊断或目标',
    request_digest VARCHAR(64) NOT NULL COMMENT '规范正文摘要，不保存原敏感请求',
    http_status INT NOT NULL COMMENT '终局HTTP状态',
    error_code VARCHAR(80) NULL COMMENT '成功为空',
    resource_id VARCHAR(36) NULL COMMENT '结果UUID',
    revision_id VARCHAR(36) NULL COMMENT '原不可变修订UUID',
    created_at VARCHAR(40) NOT NULL COMMENT 'UTC受理时间',
    UNIQUE KEY outcome_request (actor_id, request_id),
    FOREIGN KEY (actor_id) REFERENCES app_users(id),
    FOREIGN KEY (resource_id) REFERENCES diagnosis_outcomes(id),
    FOREIGN KEY (revision_id) REFERENCES diagnosis_outcome_revisions(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='原处理结果命令回执';
CREATE TABLE evaluation_case_entries (
    id VARCHAR(36) PRIMARY KEY COMMENT '案例条目UUID',
    case_key VARCHAR(80) NOT NULL COMMENT '稳定案例身份，与场景分离',
    source_run_id VARCHAR(36) NOT NULL COMMENT '来源轮次，仅引用',
    source_outcome_revision_id VARCHAR(36) NOT NULL COMMENT '草稿引用结果修订',
    source_summary VARCHAR(1000) NOT NULL COMMENT '最小来源摘要，无原回答',
    draft_json LONGTEXT NULL COMMENT '有界类型化草稿，发布后为空',
    draft_hash VARCHAR(64) NULL COMMENT '规范草稿摘要',
    version INT NOT NULL COMMENT '条目并发版本',
    usage_group VARCHAR(24) NULL COMMENT 'DEVELOPMENT或HOLDOUT，首次保存后固定',
    latest_revision_id VARCHAR(36) NULL COMMENT '最新已发布修订',
    archived_at VARCHAR(40) NULL COMMENT 'UTC归档时间，空为可见',
    created_at VARCHAR(40) NOT NULL COMMENT 'UTC创建时间',
    updated_at VARCHAR(40) NOT NULL COMMENT 'UTC更新时间',
    UNIQUE KEY library_case_key (case_key),
    UNIQUE KEY library_source_run (source_run_id),
    KEY library_page (archived_at, created_at, id),
    KEY library_usage_page (usage_group, created_at, id),
    FOREIGN KEY (source_run_id) REFERENCES runs(id),
    FOREIGN KEY (source_outcome_revision_id) REFERENCES diagnosis_outcome_revisions(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='管理员案例候选和工作草稿';
CREATE TABLE evaluation_case_revisions (
    id VARCHAR(36) PRIMARY KEY COMMENT '发布修订UUID',
    entry_id VARCHAR(36) NOT NULL COMMENT '稳定案例条目',
    revision_number INT NOT NULL COMMENT '发布修订号',
    source_outcome_revision_id VARCHAR(36) NOT NULL COMMENT '发布时锁定来源',
    manifest_json LONGTEXT NOT NULL COMMENT '脱敏合成复现内容和人工标准，不送入模型',
    content_sha256 VARCHAR(64) NOT NULL COMMENT '规范发布摘要',
    actor_id VARCHAR(36) NOT NULL COMMENT '人工确认管理员',
    published_at VARCHAR(40) NOT NULL COMMENT 'UTC发布时间',
    UNIQUE KEY library_revision (entry_id, revision_number),
    FOREIGN KEY (entry_id) REFERENCES evaluation_case_entries(id),
    FOREIGN KEY (source_outcome_revision_id) REFERENCES diagnosis_outcome_revisions(id),
    FOREIGN KEY (actor_id) REFERENCES app_users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='不可变案例发布版本';
ALTER TABLE evaluation_case_entries ADD FOREIGN KEY (latest_revision_id) REFERENCES evaluation_case_revisions(id);
CREATE TABLE evaluation_case_events (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '递增审计游标',
    entry_id VARCHAR(36) NOT NULL COMMENT '案例条目',
    kind VARCHAR(32) NOT NULL COMMENT '创建编辑刷新发布归档恢复',
    before_version INT NOT NULL COMMENT '变更前版本',
    after_version INT NOT NULL COMMENT '变更后版本',
    before_hash VARCHAR(64) NULL COMMENT '变更前摘要',
    after_hash VARCHAR(64) NULL COMMENT '变更后摘要',
    actor_id VARCHAR(36) NOT NULL COMMENT '登录身份',
    reason VARCHAR(1000) NOT NULL COMMENT '有界脱敏理由',
    created_at VARCHAR(40) NOT NULL COMMENT 'UTC时间',
    KEY library_events (entry_id, id),
    FOREIGN KEY (entry_id) REFERENCES evaluation_case_entries(id),
    FOREIGN KEY (actor_id) REFERENCES app_users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='案例追加审计';
CREATE TABLE evaluation_library_requests (
    id VARCHAR(36) PRIMARY KEY COMMENT '回执UUID',
    actor_id VARCHAR(36) NOT NULL COMMENT '固定请求命名空间',
    request_id VARCHAR(36) NOT NULL COMMENT '固定请求UUID',
    kind VARCHAR(32) NOT NULL COMMENT '案例或数据集命令',
    target_id VARCHAR(80) NOT NULL COMMENT '固定目标',
    request_digest VARCHAR(64) NOT NULL COMMENT '规范正文摘要',
    http_status INT NOT NULL COMMENT '原终局HTTP状态',
    error_code VARCHAR(80) NULL COMMENT '成功为空',
    resource_id VARCHAR(36) NULL COMMENT '案例或数据集UUID',
    revision_id VARCHAR(36) NULL COMMENT '发布版本UUID',
    created_at VARCHAR(40) NOT NULL COMMENT 'UTC受理时间',
    UNIQUE KEY library_request (actor_id, request_id),
    FOREIGN KEY (actor_id) REFERENCES app_users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='案例和数据集固定命令终局回执';
ALTER TABLE evaluation_cases ADD COLUMN library_revision_id VARCHAR(36) NULL COMMENT '旧内置行为空，新行追溯不可变发布修订',
    ADD FOREIGN KEY (library_revision_id) REFERENCES evaluation_case_revisions(id);
