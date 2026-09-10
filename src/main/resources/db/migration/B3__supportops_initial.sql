-- SupportOps MySQL 8.4 全量初始化脚本（Flyway 全量基线版本 3）。
-- 仅在已选定的空业务数据库执行；不建库、不建账号、不删除或覆盖已有表。
-- 空库启动时由 Flyway 自动执行；手动导入及已有库接管说明见 docs/knowledge.md。
-- Excel 动态数据表由上传流程创建，不包含在固定表结构中。
SET NAMES utf8mb4;

-- 逻辑文档及内容去重入口
CREATE TABLE documents (
    id VARCHAR(36) PRIMARY KEY COMMENT '记录唯一标识',
    title VARCHAR(180) NOT NULL COMMENT '文档标题',
    version VARCHAR(30) NOT NULL COMMENT '适用产品版本，星号表示通用资料',
    filename VARCHAR(200) NOT NULL COMMENT '原始文件名',
    checksum VARCHAR(64) NOT NULL COMMENT '文件内容的 SHA-256 摘要',
    created_at VARCHAR(40) NOT NULL COMMENT '创建时间，ISO-8601 字符串',
    embedding_key VARCHAR(500) NOT NULL COMMENT '历史向量配置标识',
    deleted BOOLEAN NOT NULL DEFAULT FALSE COMMENT '逻辑删除标记，0 为未删除，1 为已删除',
    canonical_key CHAR(64) UNIQUE COMMENT '内容去重键，空值表示未设置'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='逻辑文档及内容去重入口';

-- 文档分片及原文定位
CREATE TABLE chunks (
    id VARCHAR(100) PRIMARY KEY COMMENT '记录唯一标识',
    document_id VARCHAR(36) NOT NULL COMMENT '关联逻辑文档标识',
    location VARCHAR(160) NOT NULL COMMENT '原文位置描述',
    content LONGTEXT NOT NULL COMMENT '正文内容',
    embedding LONGTEXT COMMENT '历史向量序列化内容，空值表示未保存',
    version_id VARCHAR(36) COMMENT '关联文档修订标识',
    batch_id VARCHAR(36) COMMENT '关联处理批次标识',
    chunk_index INT NOT NULL DEFAULT 0 COMMENT '批次内的分片顺序',
    heading VARCHAR(500) NOT NULL DEFAULT '' COMMENT '分片所属标题',
    heading_path VARCHAR(1500) NOT NULL DEFAULT '' COMMENT '分片所属标题层级路径',
    page_start INT COMMENT '原文起始页码，非 PDF 可为空',
    page_end INT COMMENT '原文结束页码，非 PDF 可为空',
    line_start INT COMMENT '原文起始行号，无行定位时为空',
    line_end INT COMMENT '原文结束行号，无行定位时为空',
    char_start INT COMMENT '源区块内起始码点偏移，无精确定位时为空',
    char_end INT COMMENT '源区块内结束码点偏移，无精确定位时为空',
    content_hash CHAR(64) COMMENT '分片正文的 SHA-256 摘要',
    char_count INT NOT NULL DEFAULT 0 COMMENT '分片正文长度，单位 Unicode 码点'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='文档分片及原文定位';

-- 人员明确确认的项目记忆
CREATE TABLE memories (
    id VARCHAR(36) PRIMARY KEY COMMENT '记录唯一标识',
    content VARCHAR(1000) NOT NULL COMMENT '正文内容',
    created_at VARCHAR(40) NOT NULL COMMENT '创建时间，ISO-8601 字符串'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='人员明确确认的项目记忆';

-- 诊断任务及累计用量
CREATE TABLE runs (
    id VARCHAR(36) PRIMARY KEY COMMENT '记录唯一标识',
    session_id VARCHAR(36) NOT NULL COMMENT '诊断会话标识',
    question VARCHAR(6000) NOT NULL COMMENT '用户诊断问题',
    status VARCHAR(30) NOT NULL COMMENT '处理状态',
    answer LONGTEXT NOT NULL COMMENT '诊断回答正文',
    error_code VARCHAR(80) NOT NULL COMMENT '脱敏错误码，无错误时为空字符串',
    created_at VARCHAR(40) NOT NULL COMMENT '创建时间，ISO-8601 字符串',
    finished_at VARCHAR(40) COMMENT '结束时间，ISO-8601 字符串，未结束时为空',
    elapsed_ms BIGINT NOT NULL DEFAULT 0 COMMENT '执行耗时，单位毫秒',
    input_tokens BIGINT NOT NULL DEFAULT 0 COMMENT '累计输入 Token 数，是否报告需结合用量事件判断',
    output_tokens BIGINT NOT NULL DEFAULT 0 COMMENT '累计输出 Token 数，是否报告需结合用量事件判断',
    user_id VARCHAR(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL COMMENT '所属用户标识，历史未归属记录可为空'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='诊断任务及累计用量';

-- 诊断过程事件
CREATE TABLE run_events (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增事件序号，用于保持事件顺序',
    run_id VARCHAR(36) NOT NULL COMMENT '关联诊断任务标识',
    kind VARCHAR(60) NOT NULL COMMENT '事件或任务类型',
    content LONGTEXT NOT NULL COMMENT '正文内容',
    created_at VARCHAR(40) NOT NULL COMMENT '创建时间，ISO-8601 字符串'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='诊断过程事件';

-- 原件存储及恢复状态
CREATE TABLE source_files (
    id VARCHAR(36) PRIMARY KEY COMMENT '记录唯一标识',
    object_key VARCHAR(300) NOT NULL COMMENT 'MinIO 私有桶中的原件对象键',
    filename VARCHAR(200) NOT NULL COMMENT '原始文件名',
    media_type VARCHAR(100) NOT NULL COMMENT '原件媒体类型',
    size_bytes BIGINT NOT NULL COMMENT '原件大小，单位字节',
    checksum CHAR(64) NOT NULL COMMENT '文件内容的 SHA-256 摘要',
    status VARCHAR(30) NOT NULL COMMENT '处理状态',
    created_at VARCHAR(40) NOT NULL COMMENT '创建时间，ISO-8601 字符串',
    document_id VARCHAR(36) COMMENT '关联逻辑文档标识'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='原件存储及恢复状态';

-- 文档不可变修订
CREATE TABLE document_versions (
    id VARCHAR(36) PRIMARY KEY COMMENT '记录唯一标识',
    document_id VARCHAR(36) NOT NULL COMMENT '关联逻辑文档标识',
    revision INT NOT NULL COMMENT '同一逻辑文档的递增修订号',
    product_version VARCHAR(30) NOT NULL COMMENT '适用产品版本，星号表示通用资料',
    title VARCHAR(180) NOT NULL COMMENT '文档标题',
    file_id VARCHAR(36) NOT NULL COMMENT '关联原件标识',
    file_type VARCHAR(16) NOT NULL COMMENT '原件文件类型',
    note VARCHAR(1000) NOT NULL COMMENT '修订说明',
    created_at VARCHAR(40) NOT NULL COMMENT '创建时间，ISO-8601 字符串'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='文档不可变修订';

-- 文档上传请求及处理结果
CREATE TABLE document_uploads (
    id VARCHAR(36) PRIMARY KEY COMMENT '记录唯一标识',
    request_key VARCHAR(100) NOT NULL UNIQUE COMMENT '上传请求幂等键',
    request_hash CHAR(64) NOT NULL COMMENT '上传请求载荷的 SHA-256 摘要',
    document_id VARCHAR(36) COMMENT '关联逻辑文档标识',
    version_id VARCHAR(36) COMMENT '关联文档修订标识',
    batch_id VARCHAR(36) COMMENT '关联处理批次标识',
    file_id VARCHAR(36) COMMENT '关联原件标识',
    filename VARCHAR(200) NOT NULL COMMENT '原始文件名',
    size_bytes BIGINT NOT NULL COMMENT '原件大小，单位字节',
    checksum CHAR(64) NOT NULL COMMENT '文件内容的 SHA-256 摘要',
    source VARCHAR(30) NOT NULL COMMENT '上传来源',
    status VARCHAR(30) NOT NULL COMMENT '处理状态',
    error_code VARCHAR(100) NOT NULL COMMENT '脱敏错误码，无错误时为空字符串',
    created_at VARCHAR(40) NOT NULL COMMENT '创建时间，ISO-8601 字符串',
    finished_at VARCHAR(40) COMMENT '结束时间，ISO-8601 字符串，未结束时为空',
    user_id VARCHAR(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL COMMENT '所属用户标识，历史未归属记录可为空'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='文档上传请求及处理结果';

-- 文档解析批次及处理配置
CREATE TABLE document_batches (
    id VARCHAR(36) PRIMARY KEY COMMENT '记录唯一标识',
    document_id VARCHAR(36) NOT NULL COMMENT '关联逻辑文档标识',
    version_id VARCHAR(36) NOT NULL COMMENT '关联文档修订标识',
    chunk_size INT NOT NULL COMMENT '分片大小，单位 Unicode 码点',
    overlap INT NOT NULL COMMENT '分片重叠大小，单位 Unicode 码点',
    config_json LONGTEXT NOT NULL COMMENT '处理参数的 JSON 快照',
    config_hash CHAR(64) NOT NULL COMMENT '处理参数的 SHA-256 摘要',
    parser_version VARCHAR(40) NOT NULL COMMENT '解析器版本',
    status VARCHAR(30) NOT NULL COMMENT '处理状态',
    text_status VARCHAR(30) NOT NULL COMMENT '关键词索引处理状态',
    vector_status VARCHAR(30) NOT NULL COMMENT '向量索引处理状态',
    chunk_count INT NOT NULL COMMENT '分片数量',
    row_count BIGINT NOT NULL COMMENT '数据行数',
    error_code VARCHAR(100) NOT NULL COMMENT '脱敏错误码，无错误时为空字符串',
    created_at VARCHAR(40) NOT NULL COMMENT '创建时间，ISO-8601 字符串',
    generation INT NOT NULL DEFAULT 1 COMMENT '同一修订的处理代次，从 1 开始'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='文档解析批次及处理配置';

-- 当前可检索的文档发布指针
CREATE TABLE document_releases (
    id VARCHAR(36) PRIMARY KEY COMMENT '记录唯一标识',
    document_id VARCHAR(36) NOT NULL COMMENT '关联逻辑文档标识',
    product_version VARCHAR(30) NOT NULL COMMENT '适用产品版本，星号表示通用资料',
    version_id VARCHAR(36) NOT NULL COMMENT '关联文档修订标识',
    batch_id VARCHAR(36) NOT NULL COMMENT '关联处理批次标识',
    vector_task_id VARCHAR(36) COMMENT '当前发布的完整向量任务标识，空值表示未发布向量',
    updated_at VARCHAR(40) NOT NULL COMMENT '更新时间，ISO-8601 字符串'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='当前可检索的文档发布指针';

-- 知识处理任务及租约重试状态
CREATE TABLE knowledge_tasks (
    id VARCHAR(36) PRIMARY KEY COMMENT '记录唯一标识',
    document_id VARCHAR(36) NOT NULL COMMENT '关联逻辑文档标识',
    version_id VARCHAR(36) NOT NULL COMMENT '关联文档修订标识',
    batch_id VARCHAR(36) NOT NULL COMMENT '关联处理批次标识',
    kind VARCHAR(20) NOT NULL COMMENT '事件或任务类型',
    status VARCHAR(30) NOT NULL COMMENT '处理状态',
    stage VARCHAR(30) NOT NULL COMMENT '当前执行阶段',
    profile_key VARCHAR(600) NOT NULL COMMENT '模型及端点配置标识，不含凭据',
    index_name VARCHAR(200) NOT NULL COMMENT 'Elasticsearch 目标索引名称，未分配时为空字符串',
    dimensions INT NOT NULL COMMENT '向量维度，未生成时为 0',
    completed_chunks INT NOT NULL COMMENT '已确认成功处理的分片数',
    attempt_count INT NOT NULL COMMENT '累计执行次数，包含首次执行',
    budget_start INT NOT NULL DEFAULT 0 COMMENT '本轮重试预算起点对应的累计执行次数',
    next_retry_at BIGINT NOT NULL COMMENT '下次可执行时间，UTC 毫秒时间戳',
    lease_owner VARCHAR(100) NOT NULL COMMENT '本次领取的租约令牌',
    lease_until BIGINT NOT NULL COMMENT '租约到期时间，UTC 毫秒时间戳',
    heartbeat_at BIGINT NOT NULL COMMENT '最近心跳时间，UTC 毫秒时间戳',
    error_code VARCHAR(100) NOT NULL COMMENT '脱敏错误码，无错误时为空字符串',
    created_at VARCHAR(40) NOT NULL COMMENT '创建时间，ISO-8601 字符串',
    finished_at VARCHAR(40) COMMENT '结束时间，ISO-8601 字符串，未结束时为空'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='知识处理任务及租约重试状态';

-- 知识任务执行尝试历史
CREATE TABLE knowledge_attempts (
    id VARCHAR(36) PRIMARY KEY COMMENT '记录唯一标识',
    task_id VARCHAR(36) NOT NULL COMMENT '关联知识处理任务标识',
    attempt_number INT NOT NULL COMMENT '任务内的执行尝试序号',
    lease_owner VARCHAR(100) NOT NULL COMMENT '本次领取的租约令牌',
    status VARCHAR(30) NOT NULL COMMENT '处理状态',
    stage VARCHAR(30) NOT NULL COMMENT '当前执行阶段',
    error_code VARCHAR(100) NOT NULL COMMENT '脱敏错误码，无错误时为空字符串',
    started_at VARCHAR(40) NOT NULL COMMENT '开始时间，ISO-8601 字符串',
    finished_at VARCHAR(40) COMMENT '结束时间，ISO-8601 字符串，未结束时为空'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='知识任务执行尝试历史';

-- Excel 工作表数据集及物理表注册
CREATE TABLE excel_datasets (
    id VARCHAR(36) PRIMARY KEY COMMENT '记录唯一标识',
    document_id VARCHAR(36) NOT NULL COMMENT '关联逻辑文档标识',
    version_id VARCHAR(36) NOT NULL COMMENT '关联文档修订标识',
    batch_id VARCHAR(36) NOT NULL COMMENT '关联处理批次标识',
    sheet_index INT NOT NULL COMMENT '工作表序号，从 0 开始',
    sheet_name VARCHAR(200) NOT NULL COMMENT '原始工作表名称',
    header_row INT NOT NULL COMMENT '表头在原件中的行号，从 1 开始',
    table_name VARCHAR(64) NOT NULL UNIQUE COMMENT '服务端生成的 Excel 物理表名称',
    row_count BIGINT NOT NULL COMMENT '数据行数',
    status VARCHAR(30) NOT NULL COMMENT '处理状态',
    created_at VARCHAR(40) NOT NULL COMMENT '创建时间，ISO-8601 字符串'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='Excel 工作表数据集及物理表注册';

-- Excel 数据集列元数据
CREATE TABLE excel_columns (
    id VARCHAR(36) PRIMARY KEY COMMENT '记录唯一标识',
    dataset_id VARCHAR(36) NOT NULL COMMENT '关联 Excel 数据集标识',
    column_index INT NOT NULL COMMENT '原始列序号，从 0 开始',
    column_name VARCHAR(32) NOT NULL COMMENT '受控物理列名',
    original_header VARCHAR(500) NOT NULL COMMENT '原始表头文本',
    data_type VARCHAR(20) NOT NULL COMMENT '推断或指定的列数据类型',
    sample_json TEXT NOT NULL COMMENT '预览样例的 JSON 内容',
    format_hint VARCHAR(200) NOT NULL COMMENT '原始单元格格式提示'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='Excel 数据集列元数据';

-- 工作台用户账号
CREATE TABLE app_users (
    id VARCHAR(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin PRIMARY KEY COMMENT '记录唯一标识',
    username VARCHAR(32) COLLATE utf8mb4_bin NOT NULL UNIQUE COMMENT '登录用户名，区分大小写',
    password_hash VARCHAR(256) NOT NULL COMMENT '密码哈希，不保存明文密码',
    role VARCHAR(16) NOT NULL COMMENT '账号角色，ADMIN 为管理员，USER 为普通用户',
    created_at VARCHAR(40) NOT NULL COMMENT '创建时间，ISO-8601 字符串',
    CONSTRAINT user_role CHECK (role IN ('ADMIN', 'USER'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='工作台用户账号';

-- 首次管理员创建的并发互斥锁
CREATE TABLE user_setup_lock (
    id INT PRIMARY KEY COMMENT '固定锁记录主键，值为 1'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='首次管理员创建的并发互斥锁';

-- 索引、关系约束与必要初始记录（全部表创建后执行）。
CREATE INDEX document_content_version ON documents(checksum, version);
CREATE INDEX run_events_by_run ON run_events(run_id, id);
ALTER TABLE document_versions ADD CONSTRAINT uq_document_revision UNIQUE (document_id, revision), ADD FOREIGN KEY (document_id) REFERENCES documents(id), ADD FOREIGN KEY (file_id) REFERENCES source_files(id);
ALTER TABLE document_releases ADD CONSTRAINT uq_document_release UNIQUE (document_id, product_version), ADD FOREIGN KEY (version_id) REFERENCES document_versions(id);
ALTER TABLE document_batches ADD FOREIGN KEY (version_id) REFERENCES document_versions(id);
ALTER TABLE excel_columns ADD CONSTRAINT uq_dataset_column UNIQUE (dataset_id, column_index), ADD FOREIGN KEY (dataset_id) REFERENCES excel_datasets(id);
CREATE INDEX task_due ON knowledge_tasks(status, next_retry_at, lease_until);
CREATE INDEX task_batch ON knowledge_tasks(batch_id, kind, created_at);
CREATE INDEX attempt_task ON knowledge_attempts(task_id, attempt_number);
CREATE INDEX upload_document ON document_uploads(document_id, created_at);
CREATE INDEX dataset_batch ON excel_datasets(batch_id);
CREATE UNIQUE INDEX chunk_batch_order ON chunks(batch_id, chunk_index);
ALTER TABLE chunks ADD FOREIGN KEY (document_id) REFERENCES documents(id) ON DELETE CASCADE, ADD FOREIGN KEY (version_id) REFERENCES document_versions(id), ADD FOREIGN KEY (batch_id) REFERENCES document_batches(id);
ALTER TABLE run_events ADD FOREIGN KEY (run_id) REFERENCES runs(id);
ALTER TABLE document_releases ADD FOREIGN KEY (document_id) REFERENCES documents(id), ADD FOREIGN KEY (batch_id) REFERENCES document_batches(id), ADD FOREIGN KEY (vector_task_id) REFERENCES knowledge_tasks(id);
ALTER TABLE document_batches ADD FOREIGN KEY (document_id) REFERENCES documents(id);
ALTER TABLE knowledge_tasks ADD FOREIGN KEY (document_id) REFERENCES documents(id);
ALTER TABLE knowledge_attempts ADD FOREIGN KEY (task_id) REFERENCES knowledge_tasks(id), ADD UNIQUE(task_id, attempt_number);
ALTER TABLE excel_datasets ADD FOREIGN KEY (document_id) REFERENCES documents(id), ADD FOREIGN KEY (version_id) REFERENCES document_versions(id), ADD FOREIGN KEY (batch_id) REFERENCES document_batches(id);
ALTER TABLE source_files ADD FOREIGN KEY (document_id) REFERENCES documents(id);
CREATE UNIQUE INDEX batch_generation ON document_batches(version_id,generation);
CREATE INDEX file_recovery ON source_files(status,created_at);
INSERT INTO user_setup_lock(id) VALUES (1);
ALTER TABLE runs ADD CONSTRAINT runs_user FOREIGN KEY (user_id) REFERENCES app_users(id);
CREATE INDEX runs_user_created ON runs(user_id, created_at);
ALTER TABLE document_uploads ADD CONSTRAINT uploads_user FOREIGN KEY (user_id) REFERENCES app_users(id);
CREATE INDEX uploads_user_created ON document_uploads(user_id, created_at);
