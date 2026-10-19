-- V16 已应用；通过新增迁移补齐边界表说明，不更改历史校验值。
ALTER TABLE outcome_export_installation
    COMMENT='诊断来源导出安装身份',
    MODIFY id INT NOT NULL COMMENT '单例主键，固定为1',
    MODIFY source_system_id VARCHAR(36) NULL COMMENT '首次导出时生成的安装UUID，空值表示尚未导出';
ALTER TABLE outcome_export_snapshots
    COMMENT='管理员确认的不可变脱敏来源包',
    MODIFY id VARCHAR(36) NOT NULL COMMENT '来源包UUID',
    MODIFY source_run_id VARCHAR(36) NOT NULL COMMENT '来源诊断UUID，仅追溯不建立删除级联',
    MODIFY outcome_revision_id VARCHAR(36) NOT NULL COMMENT '确认时的结果修订UUID',
    MODIFY actor_id VARCHAR(36) NOT NULL COMMENT '服务端捕获的导出管理员UUID',
    MODIFY package_json LONGTEXT NOT NULL COMMENT '完整不可变来源包JSON原文',
    MODIFY content_sha256 VARCHAR(64) NOT NULL COMMENT '规范JSON内容的SHA256摘要',
    MODIFY created_at VARCHAR(40) NOT NULL COMMENT '服务端UTC导出时间';
ALTER TABLE outcome_export_requests
    COMMENT='脱敏来源导出固定请求回执',
    MODIFY id VARCHAR(36) NOT NULL COMMENT '内部回执UUID',
    MODIFY actor_id VARCHAR(36) NOT NULL COMMENT '服务端捕获的管理员UUID',
    MODIFY request_id VARCHAR(36) NOT NULL COMMENT '调用方固定请求UUID，同管理员范围唯一',
    MODIFY request_digest VARCHAR(64) NOT NULL COMMENT '固定请求正文的SHA256摘要',
    MODIFY export_id VARCHAR(36) NULL COMMENT '成功导出包UUID，拒绝时为空',
    MODIFY http_status INT NOT NULL COMMENT '原始处理HTTP状态码',
    MODIFY error_code VARCHAR(80) NULL COMMENT '终局拒绝代码，成功时为空',
    MODIFY created_at VARCHAR(40) NOT NULL COMMENT '服务端UTC受理时间';
ALTER TABLE evaluation_migration_control
    COMMENT='历史评测表迁移单写门禁',
    MODIFY id INT NOT NULL COMMENT '单例主键，固定为1',
    MODIFY state VARCHAR(24) NOT NULL COMMENT 'ACTIVE可写，READ_ONLY冻结，MOVED已转移',
    MODIFY generation VARCHAR(36) NOT NULL COMMENT '来源迁移门禁代次UUID',
    MODIFY updated_at VARCHAR(40) NOT NULL COMMENT '最近状态变化UTC时间';
