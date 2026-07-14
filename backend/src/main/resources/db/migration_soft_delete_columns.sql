-- Soft delete metadata for core tables.
SET @schema_name = DATABASE();

SET @sql = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE movie_metadata ADD COLUMN deleted_at datetime DEFAULT NULL COMMENT ''软删除时间'' AFTER updated_at',
        'SELECT 1')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = @schema_name
      AND TABLE_NAME = 'movie_metadata'
      AND COLUMN_NAME = 'deleted_at'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE resource_link ADD COLUMN deleted_at datetime DEFAULT NULL COMMENT ''软删除时间'' AFTER created_at',
        'SELECT 1')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = @schema_name
      AND TABLE_NAME = 'resource_link'
      AND COLUMN_NAME = 'deleted_at'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
