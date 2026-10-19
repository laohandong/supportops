# 拆分文件归属与兼容清单

下表记录拆分前路径在 SupportOps 中的保留或退出行为。评测平台实现及其测试由独立项目维护，不纳入本仓库和 SupportOps 源码包。历史迁移保留原文件及校验值，移除运行入口不删除历史数据。

| 原范围 | SupportOps 当前职责 | 兼容行为 |
| --- | --- | --- |
| 原 evaluation 业务包、Mapper 与网页 | 移除评测业务运行入口 | 旧 API 鉴权后返回 410，不加载外部实现 |
| evaluation/run.mjs、scripts/evaluate.mjs | 保留退出提示 | 退出码 2，不读取工作台密钥或创建实验 |
| 原案例、标准、注入材料与评测测试 | 由独立项目维护 | 不随 SupportOps 生产资源或源码包发布 |
| scripts/lib/runtime-safety.mjs | 本项目运行安全纯函数 | 无相邻源码依赖，无导入副作用 |
| outcome | 人工处理结果及修订 | 原诊断权限、修复引用、用量和归档保留 |
| source 与 V16–V19 | 来源协议、导出及迁移写保护 | 人工确认脱敏、当前修订冲突、固定请求回执、唯一目标绑定 |
| B3 与 V4–V15 | 历史 Flyway 和旧评测表 | 文件校验值与旧数据保留，不自动迁走或清空 |
| OutcomePersistenceTest、OutcomeMigrationTest | 处理结果与历史数据验证 | 保留旧表保真、事务及权限检查 |
| scripts/reliability | 容量、维护与停写保护 | 当前备份格式仅管理 SupportOps 权威范围 |
| scripts/reliability/backup-v1、restore-legacy | 旧整体备份的显式兼容入口 | 仅恢复到全新资源，禁止自动启动 |
| 新 backup-v2 | SupportOps 自有业务与存储 | 不管理外部评测项目秘密或执行材料 |

## 原评测接口

下列原路径在 SupportOps 均经鉴权后返回 `410 EVALUATION_MOVED`，POST 不重定向或转发。外部项目使用独立账号与来源导入契约，不能用旧命令直接查询 SupportOps 诊断数据库。

- `GET /api/evaluations/case-library/capabilities`
- `GET /api/evaluations/case-library`
- `GET /api/evaluations/case-library/{id}`
- `GET /api/evaluations/case-library/{id}/revisions`
- `GET /api/evaluations/case-library/{id}/events`
- `POST /api/evaluations/case-library`
- `POST /api/evaluations/case-library/{id}/draft`
- `POST /api/evaluations/case-library/{id}/refresh-source`
- `POST /api/evaluations/case-library/{id}/publish`
- `POST /api/evaluations/case-library/{id}/archive`
- `POST /api/evaluations/case-library/{id}/restore`
- `POST /api/evaluations/datasets/from-cases`
- `GET /api/evaluations/library/requests/{requestId}`
- `GET /api/evaluations/capabilities`
- `GET /api/evaluations/datasets`
- `GET /api/evaluations/datasets/{id}`
- `POST /api/evaluations/datasets/import-builtin`
- `POST /api/evaluations/experiments`
- `GET /api/evaluations/experiments`
- `GET /api/evaluations/experiments/{id}`
- `POST /api/evaluations/experiments/{id}/update`
- `POST /api/evaluations/experiments/{id}/clone`
- `POST /api/evaluations/experiments/{id}/preflight`
- `POST /api/evaluations/experiments/{id}/start`
- `GET /api/evaluations/requests/{requestId}`
- `POST /api/evaluations/experiments/{id}/cancel`
- `GET /api/evaluations/experiments/{id}/events`
- `GET /api/evaluations/experiments/{id}/samples`
- `GET /api/evaluations/samples/{id}`
- `GET /api/evaluations/samples/{id}/reviews`
- `POST /api/evaluations/samples/{id}/reviews`
- `GET /api/evaluations/comparison`
- `GET /api/evaluations/experiments/{id}/export`
- `POST /api/evaluations/experiments/{id}/cleanup`
