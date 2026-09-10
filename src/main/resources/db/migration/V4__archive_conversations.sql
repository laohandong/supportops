-- 对话逻辑归档：保留诊断、证据与用量，仅历史列表过滤归档记录。
ALTER TABLE runs ADD COLUMN archived_at VARCHAR(40) NULL COMMENT '对话归档时间，UTC ISO 8601；NULL 表示未归档';
CREATE INDEX runs_archive_created ON runs(archived_at, created_at, id);
