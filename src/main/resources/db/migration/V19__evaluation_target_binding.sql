-- 将单写切换绑定到唯一目标代次；历史源库必须升级后才能执行新的离线切换。
ALTER TABLE evaluation_migration_control
    ADD COLUMN active_target_generation VARCHAR(36) NULL
    COMMENT '已绑定的 AgentEval 目标代次；仅无增量安全回退后清除';
