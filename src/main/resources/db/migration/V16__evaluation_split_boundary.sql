-- 新增拆分边界；旧评测表及历史迁移保持原样。
CREATE TABLE outcome_export_installation (id INT PRIMARY KEY, source_system_id VARCHAR(36) NULL) ENGINE=InnoDB;
INSERT INTO outcome_export_installation (id) VALUES (1);
CREATE TABLE outcome_export_snapshots (id VARCHAR(36) PRIMARY KEY, source_run_id VARCHAR(36) NOT NULL, outcome_revision_id VARCHAR(36) NOT NULL, actor_id VARCHAR(36) NOT NULL, package_json LONGTEXT NOT NULL, content_sha256 VARCHAR(64) NOT NULL, created_at VARCHAR(40) NOT NULL) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE outcome_export_requests (id VARCHAR(36) PRIMARY KEY, actor_id VARCHAR(36) NOT NULL, request_id VARCHAR(36) NOT NULL, request_digest VARCHAR(64) NOT NULL, export_id VARCHAR(36) NULL, http_status INT NOT NULL, error_code VARCHAR(80) NULL, created_at VARCHAR(40) NOT NULL, UNIQUE KEY export_request(actor_id,request_id), FOREIGN KEY(export_id) REFERENCES outcome_export_snapshots(id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE evaluation_migration_control (id INT PRIMARY KEY, state VARCHAR(24) NOT NULL, generation VARCHAR(36) NOT NULL, updated_at VARCHAR(40) NOT NULL) ENGINE=InnoDB;
INSERT INTO evaluation_migration_control VALUES (1,'ACTIVE',UUID(),DATE_FORMAT(UTC_TIMESTAMP(),'%Y-%m-%dT%H:%i:%sZ'));
