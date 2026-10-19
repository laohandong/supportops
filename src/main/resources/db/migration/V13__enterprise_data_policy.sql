-- 企业资料外发策略独立于现场连接策略；未配置的新系统默认禁止模型和向量外发。
CREATE TABLE system_data_policies (
 system_id VARCHAR(36) PRIMARY KEY, chat_allowed BOOLEAN NOT NULL DEFAULT FALSE,
 embedding_allowed BOOLEAN NOT NULL DEFAULT FALSE, version INT NOT NULL DEFAULT 1,
 updated_at VARCHAR(40) NOT NULL,
 FOREIGN KEY(system_id) REFERENCES managed_systems(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='系统资料对话与向量外发批准';
INSERT INTO system_data_policies VALUES('00000000-0000-4000-8000-000000000002',TRUE,TRUE,1,'2026-10-04T00:00:00Z');
ALTER TABLE integration_requests ADD external_reference_json TEXT NULL;
