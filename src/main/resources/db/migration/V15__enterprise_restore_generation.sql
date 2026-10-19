-- 恢复不清除历史或全表停用；只有新部署代次的人工批准才能启用外部能力。
ALTER TABLE deployment_runtime_guard ADD restore_generation INT NOT NULL DEFAULT 0 COMMENT '恢复代次，每次新部署恢复递增', ADD program_sha256 VARCHAR(64) NULL COMMENT '实际运行JAR摘要，开发运行为空';
ALTER TABLE system_environments ADD approved_generation INT NOT NULL DEFAULT 0 COMMENT '连接人工批准所属恢复代次';
ALTER TABLE integration_applications ADD approved_generation INT NOT NULL DEFAULT 0 COMMENT '应用人工批准所属恢复代次';
ALTER TABLE application_access_keys ADD approved_generation INT NOT NULL DEFAULT 0 COMMENT '访问密钥签发所属恢复代次';
ALTER TABLE system_data_policies ADD approved_generation INT NOT NULL DEFAULT 0 COMMENT '资料外发批准所属恢复代次';
