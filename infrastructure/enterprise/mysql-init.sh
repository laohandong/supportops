#!/bin/sh
set -eu
app_password=$(cat /run/secrets/spring.datasource.password)
reader_password=$(cat /run/secrets/supportops.knowledge.excel.query-password)
case "$app_password$reader_password" in *[!A-Za-z0-9_-]*) exit 1;; esac
MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --protocol=socket -uroot <<SQL
CREATE DATABASE supportops CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;
CREATE DATABASE supportops_excel CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;
CREATE USER 'supportops'@'%' IDENTIFIED BY '$app_password';
CREATE USER 'supportops_reader'@'%' IDENTIFIED BY '$reader_password';
GRANT ALL PRIVILEGES ON supportops.* TO 'supportops'@'%';
GRANT ALL PRIVILEGES ON supportops_excel.* TO 'supportops'@'%';
GRANT SELECT ON supportops_excel.* TO 'supportops_reader'@'%';
SQL
