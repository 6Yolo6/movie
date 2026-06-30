-- Add resource report governance and resource quality metadata.
ALTER TABLE resource_link
  ADD COLUMN quality varchar(50) DEFAULT NULL COMMENT 'Quality label, e.g. 4K/1080P' AFTER report_count,
  ADD COLUMN subtitle varchar(50) DEFAULT NULL COMMENT 'Subtitle information' AFTER quality,
  ADD COLUMN file_size varchar(50) DEFAULT NULL COMMENT 'File size label' AFTER subtitle,
  ADD COLUMN version_note varchar(255) DEFAULT NULL COMMENT 'Version or release note' AFTER file_size,
  ADD COLUMN reject_reason varchar(255) DEFAULT NULL COMMENT 'Audit rejection reason' AFTER version_note;

CREATE TABLE IF NOT EXISTS resource_report (
  id bigint NOT NULL AUTO_INCREMENT,
  resource_id bigint NOT NULL,
  user_id bigint NOT NULL,
  reason varchar(255) DEFAULT NULL,
  status varchar(20) DEFAULT 'PENDING' COMMENT 'PENDING, HANDLED, FALSE_REPORT',
  created_at datetime DEFAULT CURRENT_TIMESTAMP,
  handled_at datetime DEFAULT NULL,
  PRIMARY KEY (id),  KEY idx_status_created (status, created_at),
  KEY idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Resource Invalid Reports';

INSERT INTO sys_config (config_key, config_value, description) VALUES
('resource.report.threshold', '3', 'Reports needed before a resource is treated as suspected invalid'),
('auth.register.enabled', 'true', 'Allow public registration')
ON DUPLICATE KEY UPDATE config_value = config_value;