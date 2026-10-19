-- 无关联提案的人工场景变更单独保存；保留V9所有表、约束和历史记录。
CREATE TABLE demo_environment_events (
 id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '单调递增场景审计游标',
 actor_id VARCHAR(36) NOT NULL COMMENT '服务端捕获的操作账号',
 target_id VARCHAR(64) NOT NULL COMMENT '固定内置目标标识',
 instance_id VARCHAR(36) NOT NULL COMMENT '实际变更的环境实例UUID',
 kind VARCHAR(40) NOT NULL COMMENT '事件类型，SCENARIO_RESET',
 scenario_before VARCHAR(32) NOT NULL COMMENT '变更前场景标识',
 scenario_after VARCHAR(32) NOT NULL COMMENT '请求切换的场景标识',
 before_revision BIGINT NOT NULL COMMENT '变更前环境修订号',
 after_revision BIGINT NULL COMMENT '读回的变更后修订号，未知为空',
 before_json MEDIUMTEXT NOT NULL COMMENT '有界脱敏变更前事实JSON',
 after_json MEDIUMTEXT NULL COMMENT '有界脱敏变更后事实及HTTP结果，未知为空',
 status VARCHAR(24) NOT NULL COMMENT 'STARTED已登记，APPLIED确认应用，UNKNOWN结果不明',
 error_code VARCHAR(80) NOT NULL DEFAULT '' COMMENT '稳定错误码，确认成功为空串',
 started_at VARCHAR(40) NOT NULL COMMENT '受理时间ISO8601 UTC',
 finished_at VARCHAR(40) NULL COMMENT '收尾时间ISO8601 UTC，未保存为空',
 FOREIGN KEY (actor_id) REFERENCES app_users(id),
 INDEX idx_demo_environment_events(target_id,id)
) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin COMMENT='合成环境人工场景变更持久化审计';
