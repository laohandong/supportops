-- 合成本地账号，仅供回环开发环境；正式部署单独创建账号和授予对应数据库权限。
CREATE DATABASE supportops CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE supportops_excel CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER 'supportops'@'%' IDENTIFIED BY 'synthetic-knowledge-app';
CREATE USER 'supportops_reader'@'%' IDENTIFIED BY 'synthetic-knowledge-reader';
GRANT ALL PRIVILEGES ON supportops.* TO 'supportops'@'%';
GRANT ALL PRIVILEGES ON supportops_excel.* TO 'supportops'@'%';
GRANT SELECT ON supportops_excel.* TO 'supportops_reader'@'%';
