-- 评论类型、登录设备授权与安全约束增量迁移（MySQL 8）
SET @db = DATABASE();

SET @sql = (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE comment ADD COLUMN comment_type varchar(32) NOT NULL DEFAULT ''GENERAL'' COMMENT ''GENERAL, REQUEST, INVALID_RESOURCE, SUGGESTION, OTHER'' AFTER relate_id',
  'SELECT 1') FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=@db AND TABLE_NAME='comment' AND COLUMN_NAME='comment_type');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE comment ADD INDEX idx_relate_type_status (relate_id, comment_type, status)',
  'SELECT 1') FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=@db AND TABLE_NAME='comment' AND INDEX_NAME='idx_relate_type_status');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

UPDATE comment SET comment_type='GENERAL' WHERE comment_type IS NULL OR comment_type='';

CREATE TABLE IF NOT EXISTS login_device (
  id bigint NOT NULL AUTO_INCREMENT,
  user_id bigint NOT NULL,
  jti varchar(64) NOT NULL,
  device_name varchar(120) NOT NULL,
  ip_address varchar(100) DEFAULT NULL,
  user_agent varchar(512) DEFAULT NULL,
  login_at datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  last_seen_at datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  expires_at datetime NOT NULL,
  revoked_at datetime DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_login_device_jti (jti),
  KEY idx_login_device_user_active (user_id, revoked_at, expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='登录设备授权';
