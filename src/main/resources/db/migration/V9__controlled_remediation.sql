-- 受控修复 V1 增量迁移；不改变诊断、账号、评测或审阅记录。
CREATE TABLE remediation_plans (
 id VARCHAR(36) PRIMARY KEY COMMENT '提案 UUID', name VARCHAR(120) NOT NULL COMMENT '提案名称',
 status VARCHAR(32) NOT NULL COMMENT '提案状态', version INT NOT NULL COMMENT '乐观锁版本', plan_hash CHAR(64) NOT NULL COMMENT '不可变提案 SHA256',
 snapshot_json MEDIUMTEXT NOT NULL COMMENT '不可变提案；只含脱敏配置和固定动作',
 creator_id VARCHAR(36) NOT NULL COMMENT '创建账号', source_run_id VARCHAR(36) NULL COMMENT '可选的已结束诊断',
 target_id VARCHAR(64) NOT NULL COMMENT '固定目标编号', instance_id VARCHAR(36) NOT NULL COMMENT '合成环境实例 UUID',
 created_at VARCHAR(40) NOT NULL COMMENT '创建时间 ISO8601 UTC', expires_at BIGINT NOT NULL COMMENT '提案到期时间，毫秒时间戳',
 CONSTRAINT fk_remediation_creator FOREIGN KEY (creator_id) REFERENCES app_users(id),
 CONSTRAINT fk_remediation_source FOREIGN KEY (source_run_id) REFERENCES runs(id),
 INDEX idx_remediation_list(status,created_at,id)
) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin COMMENT='受控修复不可变提案';
CREATE TABLE remediation_decisions (
 id VARCHAR(36) PRIMARY KEY COMMENT '决策 UUID', plan_id VARCHAR(36) NOT NULL UNIQUE COMMENT '每提案唯一决策',
 plan_hash CHAR(64) NOT NULL COMMENT '审批绑定提案摘要', kind VARCHAR(16) NOT NULL COMMENT '批准或驳回', actor_id VARCHAR(36) NOT NULL COMMENT '决策账号',
 reason VARCHAR(1000) NOT NULL COMMENT '理由，无理由为空串', expires_at BIGINT NOT NULL COMMENT '审批到期时间，毫秒时间戳', created_at VARCHAR(40) NOT NULL COMMENT '决策时间 ISO8601 UTC',
 FOREIGN KEY (plan_id) REFERENCES remediation_plans(id),
 FOREIGN KEY (actor_id) REFERENCES app_users(id)
) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin COMMENT='受控修复人工决策';
CREATE TABLE remediation_executions (
 id VARCHAR(36) PRIMARY KEY COMMENT '执行 UUID', plan_id VARCHAR(36) NOT NULL UNIQUE COMMENT '每提案唯一执行', target_id VARCHAR(64) NOT NULL COMMENT '固定目标编号',
 operation_id VARCHAR(36) NOT NULL UNIQUE COMMENT '配置操作 UUID', verification_request_id VARCHAR(36) NOT NULL UNIQUE COMMENT '唯一业务验证请求 UUID',
 instance_id VARCHAR(36) NOT NULL COMMENT '执行环境实例 UUID', process_id BIGINT NOT NULL COMMENT '执行进程 PID', process_started_at VARCHAR(40) NULL COMMENT '进程启动时间，空值表示身份不确定',
 actor_id VARCHAR(36) NOT NULL COMMENT '执行账号', status VARCHAR(32) NOT NULL COMMENT '执行状态',
 application_status VARCHAR(24) NOT NULL COMMENT '配置应用事实', verification_status VARCHAR(24) NOT NULL COMMENT '业务验证事实',
 receipt_json MEDIUMTEXT NULL COMMENT '原操作回执，空值表示未保存', error_code VARCHAR(80) NOT NULL DEFAULT '' COMMENT '稳定错误码，无错误为空串',
 created_at VARCHAR(40) NOT NULL COMMENT '受理时间 ISO8601 UTC', finished_at VARCHAR(40) NULL COMMENT '收尾时间，未收尾为空',
 FOREIGN KEY (plan_id) REFERENCES remediation_plans(id),
 FOREIGN KEY (actor_id) REFERENCES app_users(id), INDEX idx_remediation_unresolved(target_id,status)
) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin COMMENT='受控修复执行及原回执';
CREATE TABLE remediation_requests (
 id VARCHAR(36) PRIMARY KEY COMMENT '请求记录 UUID', actor_id VARCHAR(36) NOT NULL COMMENT '请求账号', request_id VARCHAR(36) NOT NULL COMMENT '账号范围内请求 UUID',
 kind VARCHAR(32) NOT NULL COMMENT '请求动作', target_id VARCHAR(80) NOT NULL COMMENT '请求提案或固定目标', digest CHAR(64) NOT NULL COMMENT '规范化请求摘要',
 plan_id VARCHAR(36) NULL COMMENT '关联提案，拒绝可能为空', execution_id VARCHAR(36) NULL COMMENT '关联执行，非执行请求为空', http_status INT NOT NULL COMMENT '持久化受理或拒绝状态码',
 error_code VARCHAR(80) NOT NULL DEFAULT '' COMMENT '拒绝错误码，受理为空串', created_at VARCHAR(40) NOT NULL COMMENT '请求保存时间 ISO8601 UTC',
 UNIQUE KEY uk_remediation_actor_request(actor_id,request_id),
 FOREIGN KEY (actor_id) REFERENCES app_users(id),
 FOREIGN KEY (plan_id) REFERENCES remediation_plans(id),
 FOREIGN KEY (execution_id) REFERENCES remediation_executions(id)
) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin COMMENT='受控修复固定请求幂等记录';
CREATE TABLE remediation_events (
 id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '单调审计游标', plan_id VARCHAR(36) NOT NULL COMMENT '关联提案', execution_id VARCHAR(36) NULL COMMENT '关联执行，无执行时为空',
 actor_id VARCHAR(36) NULL COMMENT '操作账号，启动恢复为空', kind VARCHAR(40) NOT NULL COMMENT '审计事件类型', payload_json MEDIUMTEXT NOT NULL COMMENT '脱敏审计事实 JSON',
 created_at VARCHAR(40) NOT NULL COMMENT '事件时间 ISO8601 UTC', FOREIGN KEY (plan_id) REFERENCES remediation_plans(id),
 FOREIGN KEY (execution_id) REFERENCES remediation_executions(id),
 FOREIGN KEY (actor_id) REFERENCES app_users(id), INDEX idx_remediation_events(plan_id,id)
) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin COMMENT='受控修复追加审计事件';
