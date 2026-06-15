ALTER TABLE resource_link
  ADD COLUMN status varchar(20) DEFAULT 'ACTIVE' COMMENT 'ACTIVE, DELETED',
  ADD COLUMN link_status varchar(20) DEFAULT 'NORMAL' COMMENT 'NORMAL, SUSPECTED_INVALID, INVALID',
  ADD COLUMN report_count int DEFAULT '0',
  ADD INDEX idx_resource_status (status, audit_status, link_status);

ALTER TABLE sys_user
  ADD COLUMN enabled tinyint(1) DEFAULT '1';

CREATE TABLE IF NOT EXISTS comment_vote (
  id bigint NOT NULL AUTO_INCREMENT,
  comment_id bigint NOT NULL,
  user_id bigint NOT NULL,
  created_at datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_comment_user (comment_id, user_id),
  KEY idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Comment Votes';

ALTER TABLE movie_metadata
  ADD INDEX idx_category_status_year (category, status, year),
  ADD INDEX idx_status_created_at (status, created_at),
  ADD INDEX idx_status_douban_score (status, douban_score);
