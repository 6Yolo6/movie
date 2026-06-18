-- Add station/internal notifications for resource audit and link status updates.

CREATE TABLE IF NOT EXISTS `user_notification` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `type` varchar(50) NOT NULL COMMENT 'RESOURCE_AUDIT, RESOURCE_LINK_STATUS',
  `title` varchar(200) NOT NULL,
  `content` varchar(1000) DEFAULT NULL,
  `target_type` varchar(50) DEFAULT NULL COMMENT 'RESOURCE, MOVIE',
  `target_id` varchar(100) DEFAULT NULL,
  `read_flag` tinyint(1) DEFAULT '0',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_user_read_created` (`user_id`, `read_flag`, `created_at`),
  KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='User Notifications';
