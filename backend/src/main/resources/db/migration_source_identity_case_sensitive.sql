-- 外部来源 ID 是大小写敏感的不透明标识；仅改变比较规则，不改写原值。
-- 生产执行前备份 movie_source_identity，并暂停元数据写入；不修改影片主键。
-- 本迁移可重复执行。应用回滚时保留此兼容性修正；禁止自动恢复为不区分大小写，
-- 因为迁移后可能合法并存 dWXo / dwXo，旧唯一索引无法同时容纳它们。
ALTER TABLE movie_source_identity
    MODIFY COLUMN external_id VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin
    NOT NULL COMMENT '来源站点影片 ID（区分大小写）';
