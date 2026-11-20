-- 百度搜索临时范围：仅增加列和索引，保留所有历史任务与原来源哈希。
-- 上线前必须完成备份和结构门禁；不重置任务或重放任何外部动作。
SET @baidu_scope_sql = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE baidu_transfer_task ADD COLUMN scope_key VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT ''PERMANENT'' COMMENT ''永久归档或独立临时搜索范围''', 'SELECT 1') FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'baidu_transfer_task' AND COLUMN_NAME = 'scope_key');
PREPARE baidu_scope_stmt FROM @baidu_scope_sql;
EXECUTE baidu_scope_stmt;
DEALLOCATE PREPARE baidu_scope_stmt;

SET @baidu_scope_sql = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE baidu_transfer_task ADD COLUMN search_request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT ''用户和来源选择的幂等摘要，不存用户标识''', 'SELECT 1') FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'baidu_transfer_task' AND COLUMN_NAME = 'search_request_hash');
PREPARE baidu_scope_stmt FROM @baidu_scope_sql;
EXECUTE baidu_scope_stmt;
DEALLOCATE PREPARE baidu_scope_stmt;

SET @baidu_scope_sql = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE baidu_transfer_task ADD COLUMN share_confirmed_at DATETIME(6) NULL COMMENT ''首次自有分享确认时间，临时清理时钟不延长''', 'SELECT 1') FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'baidu_transfer_task' AND COLUMN_NAME = 'share_confirmed_at');
PREPARE baidu_scope_stmt FROM @baidu_scope_sql;
EXECUTE baidu_scope_stmt;
DEALLOCATE PREPARE baidu_scope_stmt;

SET @baidu_scope_sql = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE baidu_transfer_task ADD COLUMN cleanup_evidence LONGTEXT NULL COMMENT ''首次分享确认的精确目录和文件证据，不含账号会话''', 'SELECT 1') FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'baidu_transfer_task' AND COLUMN_NAME = 'cleanup_evidence');
PREPARE baidu_scope_stmt FROM @baidu_scope_sql;
EXECUTE baidu_scope_stmt;
DEALLOCATE PREPARE baidu_scope_stmt;

SET @baidu_scope_sql = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE baidu_transfer_task ADD UNIQUE KEY uk_baidu_movie_source_scope (movie_id, original_url_hash, scope_key)', 'SELECT 1') FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'baidu_transfer_task' AND INDEX_NAME = 'uk_baidu_movie_source_scope');
PREPARE baidu_scope_stmt FROM @baidu_scope_sql;
EXECUTE baidu_scope_stmt;
DEALLOCATE PREPARE baidu_scope_stmt;

SET @baidu_scope_sql = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE baidu_transfer_task ADD KEY idx_baidu_search_request (search_request_hash, id)', 'SELECT 1') FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'baidu_transfer_task' AND INDEX_NAME = 'idx_baidu_search_request');
PREPARE baidu_scope_stmt FROM @baidu_scope_sql;
EXECUTE baidu_scope_stmt;
DEALLOCATE PREPARE baidu_scope_stmt;

SET @baidu_scope_sql = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE baidu_transfer_task ADD KEY idx_baidu_confirmed_temp (share_confirmed_at, id)', 'SELECT 1') FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'baidu_transfer_task' AND INDEX_NAME = 'idx_baidu_confirmed_temp');
PREPARE baidu_scope_stmt FROM @baidu_scope_sql;
EXECUTE baidu_scope_stmt;
DEALLOCATE PREPARE baidu_scope_stmt;

SET @baidu_scope_sql = (SELECT IF(COUNT(*) > 0, 'ALTER TABLE baidu_transfer_task DROP INDEX uk_baidu_movie_source', 'SELECT 1') FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'baidu_transfer_task' AND INDEX_NAME = 'uk_baidu_movie_source');
PREPARE baidu_scope_stmt FROM @baidu_scope_sql;
EXECUTE baidu_scope_stmt;
DEALLOCATE PREPARE baidu_scope_stmt;
