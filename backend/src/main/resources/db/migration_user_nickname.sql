-- 站内昵称：站内展示用昵称，登录仍使用用户名（也可用邮箱登录）。
SET @schema_name = DATABASE();

SET @sql = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE sys_user ADD COLUMN nickname varchar(50) DEFAULT NULL COMMENT ''站内昵称'' AFTER username',
        'SELECT ''column already exists'' AS note')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = @schema_name
      AND TABLE_NAME = 'sys_user'
      AND COLUMN_NAME = 'nickname'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

UPDATE sys_user SET nickname = username WHERE nickname IS NULL OR nickname = '';
