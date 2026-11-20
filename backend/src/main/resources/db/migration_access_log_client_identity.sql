-- 访问日志原始客户端身份；仅追加可空列，不回填或删除历史记录。
-- MySQL 8.0.28 / InnoDB：列追加在末尾，ALGORITHM=INSTANT 不降级为重建表。
-- 生产执行前必须完成备份、字段规格预检与发布安全门禁；不得使用 schema.sql 覆盖现有库。
-- 可重复执行；回滚应用时保留新列和日志，不 DROP COLUMN。

SET @access_identity_ddl = IF(
    EXISTS(SELECT 1 FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'site_access_log' AND COLUMN_NAME = 'client_ip'),
    'SELECT 1',
    'ALTER TABLE site_access_log ADD COLUMN client_ip VARCHAR(45) DEFAULT NULL COMMENT ''来源IP：可信代理链解析或直连地址；历史为空'', ALGORITHM=INSTANT'
);
PREPARE access_identity_statement FROM @access_identity_ddl;
EXECUTE access_identity_statement;
DEALLOCATE PREPARE access_identity_statement;

SET @access_identity_ddl = IF(
    EXISTS(SELECT 1 FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'site_access_log' AND COLUMN_NAME = 'user_agent'),
    'SELECT 1',
    'ALTER TABLE site_access_log ADD COLUMN user_agent VARCHAR(1024) DEFAULT NULL COMMENT ''原始User-Agent，最多1024个字符；历史为空'', ALGORITHM=INSTANT'
);
PREPARE access_identity_statement FROM @access_identity_ddl;
EXECUTE access_identity_statement;
DEALLOCATE PREPARE access_identity_statement;
