-- 企业独立部署、项目资源与标准只读接入。保留既有问答、事件和原迁移。
CREATE TABLE projects (
 id VARCHAR(36) PRIMARY KEY, code VARCHAR(64) NOT NULL UNIQUE, name VARCHAR(128) NOT NULL,
 status VARCHAR(16) NOT NULL, version INT NOT NULL DEFAULT 1, created_at VARCHAR(40) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='企业内部项目';
CREATE TABLE project_members (
 id VARCHAR(36) PRIMARY KEY, project_id VARCHAR(36) NOT NULL, user_id VARCHAR(36) NOT NULL,
 version INT NOT NULL DEFAULT 1, UNIQUE KEY member_identity(project_id,user_id),
 FOREIGN KEY(project_id) REFERENCES projects(id), FOREIGN KEY(user_id) REFERENCES app_users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='项目成员';
CREATE TABLE managed_systems (
 id VARCHAR(36) PRIMARY KEY, project_id VARCHAR(36) NOT NULL, code VARCHAR(64) NOT NULL,
 name VARCHAR(128) NOT NULL, product_key VARCHAR(64) NOT NULL, status VARCHAR(16) NOT NULL,
 version INT NOT NULL DEFAULT 1, UNIQUE KEY system_identity(project_id,code),
 FOREIGN KEY(project_id) REFERENCES projects(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='被诊断业务系统';
CREATE TABLE system_environments (
 id VARCHAR(36) PRIMARY KEY, system_id VARCHAR(36) NOT NULL, code VARCHAR(64) NOT NULL,
 name VARCHAR(128) NOT NULL, product_version VARCHAR(30) NOT NULL, status VARCHAR(16) NOT NULL,
 current_connector_revision_id VARCHAR(36) NULL, version INT NOT NULL DEFAULT 1,
 UNIQUE KEY environment_identity(system_id,code), FOREIGN KEY(system_id) REFERENCES managed_systems(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='明确诊断环境';
CREATE TABLE environment_user_grants (
 id VARCHAR(36) PRIMARY KEY, environment_id VARCHAR(36) NOT NULL, user_id VARCHAR(36) NOT NULL,
 version INT NOT NULL DEFAULT 1, UNIQUE KEY environment_user(environment_id,user_id),
 FOREIGN KEY(environment_id) REFERENCES system_environments(id), FOREIGN KEY(user_id) REFERENCES app_users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='人员环境授权';
CREATE TABLE credentials (
 id VARCHAR(36) PRIMARY KEY, purpose VARCHAR(32) NOT NULL, owner_id VARCHAR(64) NOT NULL,
 revision INT NOT NULL, key_id VARCHAR(64) NOT NULL, encryption_origin_deployment_id VARCHAR(64) NOT NULL,
 nonce VARCHAR(64) NOT NULL, ciphertext MEDIUMTEXT NOT NULL, tag VARCHAR(64) NOT NULL,
 status VARCHAR(16) NOT NULL, created_at VARCHAR(40) NOT NULL, UNIQUE KEY credential_revision(purpose,owner_id,revision)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='按用途加密保存的可恢复秘密';
CREATE TABLE connector_revisions (
 id VARCHAR(36) PRIMARY KEY, environment_id VARCHAR(36) NOT NULL, revision INT NOT NULL,
 type VARCHAR(32) NOT NULL, config_json MEDIUMTEXT NOT NULL, credential_id VARCHAR(36) NULL,
 digest CHAR(64) NOT NULL, created_at VARCHAR(40) NOT NULL,
 UNIQUE KEY connector_revision(environment_id,revision), FOREIGN KEY(environment_id) REFERENCES system_environments(id),
 FOREIGN KEY(credential_id) REFERENCES credentials(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='不可变连接配置';
ALTER TABLE system_environments ADD FOREIGN KEY(current_connector_revision_id) REFERENCES connector_revisions(id);
CREATE TABLE connector_tests (
 id VARCHAR(36) PRIMARY KEY, revision_id VARCHAR(36) NOT NULL, status VARCHAR(16) NOT NULL,
 result_json MEDIUMTEXT NOT NULL, started_at VARCHAR(40) NOT NULL, finished_at VARCHAR(40) NOT NULL,
 FOREIGN KEY(revision_id) REFERENCES connector_revisions(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='连接测试分项记录';
CREATE TABLE integration_applications (
 id VARCHAR(36) PRIMARY KEY, project_id VARCHAR(36) NOT NULL, code VARCHAR(64) NOT NULL,
 name VARCHAR(128) NOT NULL, status VARCHAR(16) NOT NULL, permissions_json TEXT NOT NULL,
 submit_per_minute INT NOT NULL DEFAULT 30, read_per_minute INT NOT NULL DEFAULT 120,
 max_active INT NOT NULL DEFAULT 2, version INT NOT NULL DEFAULT 1,
 UNIQUE KEY application_identity(project_id,code), FOREIGN KEY(project_id) REFERENCES projects(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='调用诊断能力的应用身份';
CREATE TABLE application_environment_grants (
 id VARCHAR(36) PRIMARY KEY, application_id VARCHAR(36) NOT NULL, environment_id VARCHAR(36) NOT NULL,
 UNIQUE KEY application_environment(application_id,environment_id),
 FOREIGN KEY(application_id) REFERENCES integration_applications(id), FOREIGN KEY(environment_id) REFERENCES system_environments(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='应用可访问环境';
CREATE TABLE application_access_keys (
 id VARCHAR(36) PRIMARY KEY, application_id VARCHAR(36) NOT NULL, secret_hash CHAR(64) NOT NULL,
 expires_at VARCHAR(40) NOT NULL, status VARCHAR(16) NOT NULL, created_by VARCHAR(36) NOT NULL,
 created_at VARCHAR(40) NOT NULL, FOREIGN KEY(application_id) REFERENCES integration_applications(id),
 FOREIGN KEY(created_by) REFERENCES app_users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='只保存摘要的应用访问密钥';
CREATE TABLE integration_requests (
 id VARCHAR(36) PRIMARY KEY, application_id VARCHAR(36) NOT NULL, request_id VARCHAR(36) NOT NULL,
 schema_version INT NOT NULL, payload_hash CHAR(64) NOT NULL, run_id VARCHAR(36) NOT NULL UNIQUE,
 accepted_at VARCHAR(40) NOT NULL, UNIQUE KEY application_request(application_id,request_id),
 FOREIGN KEY(application_id) REFERENCES integration_applications(id), FOREIGN KEY(run_id) REFERENCES runs(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='原子诊断受理回执';
CREATE TABLE integration_management_requests (
 id VARCHAR(36) PRIMARY KEY, actor_user_id VARCHAR(36) NOT NULL, request_id VARCHAR(36) NOT NULL,
 operation VARCHAR(64) NOT NULL, payload_hash CHAR(64) NOT NULL, resource_id VARCHAR(36) NULL,
 receipt_json MEDIUMTEXT NOT NULL, created_at VARCHAR(40) NOT NULL,
 UNIQUE KEY management_request(actor_user_id,request_id), FOREIGN KEY(actor_user_id) REFERENCES app_users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='不含秘密的固定管理回执';
CREATE TABLE run_target_snapshots (
 run_id VARCHAR(36) PRIMARY KEY, environment_id VARCHAR(36) NOT NULL, snapshot_json MEDIUMTEXT NOT NULL,
 snapshot_kind VARCHAR(32) NOT NULL, FOREIGN KEY(run_id) REFERENCES runs(id),
 FOREIGN KEY(environment_id) REFERENCES system_environments(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='诊断目标历史快照';
CREATE TABLE integration_audit_events (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, actor_type VARCHAR(24) NOT NULL, actor_id VARCHAR(36) NULL,
 action VARCHAR(64) NOT NULL, resource_type VARCHAR(32) NOT NULL, resource_id VARCHAR(36) NULL,
 request_id VARCHAR(36) NULL, detail_json MEDIUMTEXT NOT NULL, created_at VARCHAR(40) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='追加式接入审计';
CREATE TABLE deployment_runtime_guard (
 id INT PRIMARY KEY, deployment_id VARCHAR(64) NULL, holder_runtime_id VARCHAR(36) NULL,
 host VARCHAR(128) NULL, pid BIGINT NULL, process_started_at VARCHAR(40) NULL, version INT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='不自动过期的单实例执行保护';
CREATE TABLE deployment_setup_state (
 id INT PRIMARY KEY, setup_secret_hash CHAR(64) NULL, consumed_at VARCHAR(40) NULL,
 administrator_id VARCHAR(36) NULL, FOREIGN KEY(administrator_id) REFERENCES app_users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='企业首次初始化秘密状态';
INSERT INTO deployment_runtime_guard(id) VALUES(1);
INSERT INTO deployment_setup_state(id) VALUES(1);
INSERT INTO projects VALUES('00000000-0000-4000-8000-000000000001','legacy-demo','历史示例项目','ACTIVE',1,'2026-10-04T00:00:00Z');
INSERT INTO managed_systems VALUES('00000000-0000-4000-8000-000000000002','00000000-0000-4000-8000-000000000001','orderbridge','OrderBridge','orderbridge','ACTIVE',1);
INSERT INTO system_environments VALUES('00000000-0000-4000-8000-000000000003','00000000-0000-4000-8000-000000000002','demo','历史示例环境','2.0','ACTIVE',NULL,1);
INSERT INTO connector_revisions VALUES('00000000-0000-4000-8000-000000000004','00000000-0000-4000-8000-000000000003',1,'BUILTIN_DEMO','{"baseUrl":"","allowedAddresses":[],"capabilities":["APP_INFO","EFFECTIVE_CONFIG","RECENT_LOGS","DOWNSTREAM_HEALTH"],"modelData":["APP_INFO","EFFECTIVE_CONFIG","RECENT_LOGS","DOWNSTREAM_HEALTH"],"allowLocalHttp":false}',NULL,REPEAT('0',64),'2026-10-04T00:00:00Z');
UPDATE system_environments SET current_connector_revision_id='00000000-0000-4000-8000-000000000004' WHERE id='00000000-0000-4000-8000-000000000003';
INSERT INTO project_members SELECT UUID(),'00000000-0000-4000-8000-000000000001',id,1 FROM app_users;
INSERT INTO environment_user_grants SELECT UUID(),'00000000-0000-4000-8000-000000000003',id,1 FROM app_users;
ALTER TABLE runs ADD application_id VARCHAR(36) NULL, ADD actor_type VARCHAR(24) NOT NULL DEFAULT 'USER';
UPDATE runs SET actor_type='LEGACY_UNKNOWN' WHERE user_id IS NULL;
ALTER TABLE runs ADD FOREIGN KEY(application_id) REFERENCES integration_applications(id), ADD CONSTRAINT run_actor_exclusive CHECK(application_id IS NULL OR user_id IS NULL);
CREATE INDEX runs_application_created ON runs(application_id,created_at);
INSERT INTO run_target_snapshots SELECT id,'00000000-0000-4000-8000-000000000003','{}','LEGACY_INFERRED' FROM runs;
ALTER TABLE documents ADD system_id VARCHAR(36) NOT NULL DEFAULT '00000000-0000-4000-8000-000000000002';
ALTER TABLE documents ADD FOREIGN KEY(system_id) REFERENCES managed_systems(id);
CREATE INDEX documents_system_version ON documents(system_id,version,deleted);
ALTER TABLE memories ADD project_id VARCHAR(36) NOT NULL DEFAULT '00000000-0000-4000-8000-000000000001', ADD system_id VARCHAR(36) NULL, ADD environment_id VARCHAR(36) NULL;
ALTER TABLE memories ADD FOREIGN KEY(project_id) REFERENCES projects(id), ADD FOREIGN KEY(system_id) REFERENCES managed_systems(id), ADD FOREIGN KEY(environment_id) REFERENCES system_environments(id);
ALTER TABLE feishu_bindings ADD default_environment_id VARCHAR(36) NOT NULL DEFAULT '00000000-0000-4000-8000-000000000003';
ALTER TABLE feishu_bindings ADD FOREIGN KEY(default_environment_id) REFERENCES system_environments(id);
