-- Track the last email change so the profile page can enforce a 90-day cooldown.
SET @schema_name = DATABASE();

SET @sql = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE sys_user ADD COLUMN email_updated_at datetime DEFAULT NULL COMMENT ''邮箱最后变更时间'' AFTER email',
        'SELECT 1')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = @schema_name
      AND TABLE_NAME = 'sys_user'
      AND COLUMN_NAME = 'email_updated_at'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
