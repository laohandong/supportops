-- 评测中心独立持久化域；不修改已有诊断、上传、账号、飞书记录。
CREATE TABLE evaluation_datasets (
    id VARCHAR(36) PRIMARY KEY,
    dataset_key VARCHAR(80) NOT NULL,
    version VARCHAR(64) NOT NULL,
    name VARCHAR(200) NOT NULL,
    manifest_json LONGTEXT NOT NULL COMMENT '不可变版本化案例、材料和审阅清单，无密钥',
    content_sha256 VARCHAR(64) NOT NULL,
    created_at VARCHAR(40) NOT NULL,
    UNIQUE KEY evaluation_dataset_version (dataset_key, version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE evaluation_cases (
    id VARCHAR(36) PRIMARY KEY,
    dataset_id VARCHAR(36) NOT NULL,
    case_key VARCHAR(80) NOT NULL,
    ordinal INT NOT NULL,
    question VARCHAR(6000) NOT NULL,
    scenario VARCHAR(40) NOT NULL,
    expected_http_status INT NOT NULL,
    review_criteria TEXT NOT NULL,
    UNIQUE KEY evaluation_case_key (dataset_id, case_key),
    KEY evaluation_case_order (dataset_id, ordinal),
    FOREIGN KEY (dataset_id) REFERENCES evaluation_datasets(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE evaluation_experiments (
    id VARCHAR(36) PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    description VARCHAR(2000) NOT NULL,
    kind VARCHAR(24) NOT NULL,
    dataset_id VARCHAR(36) NOT NULL,
    case_keys_json TEXT NOT NULL,
    strategies_json TEXT NOT NULL,
    repeats INT NOT NULL,
    total_minutes INT NOT NULL,
    status VARCHAR(24) NOT NULL,
    cleanup_status VARCHAR(24) NOT NULL DEFAULT 'NOT_CREATED',
    snapshot_json LONGTEXT NULL COMMENT '公开执行快照，禁止凭据和私密路径',
    input_sha256 VARCHAR(64) NULL,
    version INT NOT NULL DEFAULT 0,
    creator_id VARCHAR(36) NOT NULL,
    request_id VARCHAR(36) NULL,
    request_digest VARCHAR(64) NULL,
    preflight_id VARCHAR(36) NULL,
    preflight_expires_at BIGINT NULL,
    preflight_digest VARCHAR(64) NULL,
    config_revision VARCHAR(80) NULL,
    created_at VARCHAR(40) NOT NULL,
    started_at VARCHAR(40) NULL,
    finished_at VARCHAR(40) NULL,
    error_code VARCHAR(80) NOT NULL DEFAULT '',
    cleanup_error VARCHAR(80) NOT NULL DEFAULT '',
    UNIQUE KEY evaluation_start_request (request_id),
    KEY evaluation_experiment_order (created_at, id),
    KEY evaluation_experiment_queue (status, created_at, id),
    FOREIGN KEY (dataset_id) REFERENCES evaluation_datasets(id),
    FOREIGN KEY (creator_id) REFERENCES app_users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE evaluation_attempts (
    id VARCHAR(36) PRIMARY KEY,
    experiment_id VARCHAR(36) NOT NULL,
    owner VARCHAR(80) NOT NULL,
    fencing_token BIGINT NOT NULL,
    state VARCHAR(24) NOT NULL,
    lease_until BIGINT NOT NULL,
    heartbeat_at BIGINT NOT NULL,
    pid BIGINT NULL,
    process_started_at VARCHAR(40) NULL,
    executable_hash VARCHAR(64) NULL,
    workspace_path VARCHAR(2000) NOT NULL COMMENT '服务端受限私密路径，不进入公开响应',
    resources_json LONGTEXT NOT NULL,
    process_json LONGTEXT NOT NULL,
    cleanup_status VARCHAR(24) NOT NULL,
    cleanup_error VARCHAR(80) NOT NULL DEFAULT '',
    version INT NOT NULL DEFAULT 0,
    created_at VARCHAR(40) NOT NULL,
    KEY evaluation_attempt_experiment (experiment_id, created_at),
    FOREIGN KEY (experiment_id) REFERENCES evaluation_experiments(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE evaluation_groups (
    id VARCHAR(36) PRIMARY KEY,
    experiment_id VARCHAR(36) NOT NULL,
    strategy VARCHAR(24) NOT NULL,
    repeat_index INT NOT NULL,
    status VARCHAR(24) NOT NULL,
    attempt_id VARCHAR(36) NULL,
    metadata_json LONGTEXT NOT NULL,
    UNIQUE KEY evaluation_group_plan (experiment_id, strategy, repeat_index),
    FOREIGN KEY (experiment_id) REFERENCES evaluation_experiments(id),
    FOREIGN KEY (attempt_id) REFERENCES evaluation_attempts(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE evaluation_samples (
    id VARCHAR(36) PRIMARY KEY,
    experiment_id VARCHAR(36) NOT NULL,
    group_id VARCHAR(36) NOT NULL,
    case_id VARCHAR(36) NOT NULL,
    case_key VARCHAR(80) NOT NULL,
    ordinal INT NOT NULL,
    status VARCHAR(24) NOT NULL,
    run_id VARCHAR(36) NULL,
    answer LONGTEXT NULL,
    elapsed_ms BIGINT NULL,
    input_tokens BIGINT NULL COMMENT '未报告为空，不能补零',
    output_tokens BIGINT NULL COMMENT '未报告为空，不能补零',
    usage_state VARCHAR(24) NOT NULL DEFAULT 'NOT_REPORTED',
    automatic_status VARCHAR(32) NOT NULL DEFAULT 'NOT_RUN',
    checks_json LONGTEXT NULL,
    evidence_sha256 VARCHAR(64) NULL,
    result_json LONGTEXT NULL,
    error_code VARCHAR(80) NOT NULL DEFAULT '',
    review_version INT NOT NULL DEFAULT 0,
    UNIQUE KEY evaluation_sample_plan (group_id, case_id),
    KEY evaluation_sample_order (experiment_id, ordinal),
    FOREIGN KEY (experiment_id) REFERENCES evaluation_experiments(id),
    FOREIGN KEY (group_id) REFERENCES evaluation_groups(id),
    FOREIGN KEY (case_id) REFERENCES evaluation_cases(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE evaluation_evidence (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    sample_id VARCHAR(36) NOT NULL,
    ordinal INT NOT NULL,
    source_event_id VARCHAR(80) NULL COMMENT '原事件编号仅在本样本内解释',
    kind VARCHAR(40) NOT NULL,
    occurred_at VARCHAR(40) NULL,
    payload_json LONGTEXT NOT NULL,
    UNIQUE KEY evaluation_evidence_order (sample_id, ordinal),
    FOREIGN KEY (sample_id) REFERENCES evaluation_samples(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE evaluation_reviews (
    id VARCHAR(36) PRIMARY KEY,
    sample_id VARCHAR(36) NOT NULL,
    reviewer_id VARCHAR(36) NOT NULL,
    revision INT NOT NULL,
    dimensions_json LONGTEXT NOT NULL,
    note VARCHAR(6000) NOT NULL,
    verdict VARCHAR(32) NOT NULL,
    created_at VARCHAR(40) NOT NULL,
    UNIQUE KEY evaluation_review_revision (sample_id, revision),
    FOREIGN KEY (sample_id) REFERENCES evaluation_samples(id),
    FOREIGN KEY (reviewer_id) REFERENCES app_users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE evaluation_events (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    experiment_id VARCHAR(36) NOT NULL,
    attempt_id VARCHAR(36) NOT NULL,
    sequence_no BIGINT NOT NULL,
    type VARCHAR(40) NOT NULL,
    occurred_at VARCHAR(40) NOT NULL,
    payload_json LONGTEXT NOT NULL,
    digest VARCHAR(64) NOT NULL,
    UNIQUE KEY evaluation_event_sequence (attempt_id, sequence_no),
    KEY evaluation_event_cursor (experiment_id, id),
    FOREIGN KEY (experiment_id) REFERENCES evaluation_experiments(id),
    FOREIGN KEY (attempt_id) REFERENCES evaluation_attempts(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE evaluation_runner_guard (
    id INT PRIMARY KEY COMMENT '固定单行 1，受理和执行协调使用 SELECT FOR UPDATE',
    attempt_id VARCHAR(36) NULL,
    owner VARCHAR(80) NULL,
    fencing_token BIGINT NOT NULL DEFAULT 0,
    lease_until BIGINT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 0,
    FOREIGN KEY (attempt_id) REFERENCES evaluation_attempts(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
INSERT INTO evaluation_runner_guard (id) VALUES (1);
