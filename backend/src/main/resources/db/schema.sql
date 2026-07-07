-- Database Initialization
-- CREATE DATABASE IF NOT EXISTS gying DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
-- USE gying;

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ----------------------------
-- Table structure for movie_metadata
-- ----------------------------
DROP TABLE IF EXISTS `movie_metadata`;
CREATE TABLE `movie_metadata` (
  `id` varchar(64) NOT NULL COMMENT 'Movie ID (e.g., 31z0)',
  `tmdb_id` bigint DEFAULT NULL COMMENT 'TMDB ID',
  `tmdb_type` varchar(20) DEFAULT NULL COMMENT 'TMDB media type: movie, tv',
  `title_cn` varchar(255) DEFAULT NULL COMMENT 'Chinese Title',
  `title_en` varchar(500) DEFAULT NULL COMMENT 'Original/English Title',
  `series_name` varchar(255) DEFAULT NULL COMMENT 'Series Name',
  `season` int DEFAULT NULL COMMENT 'Season Number',
  `year` int DEFAULT NULL COMMENT 'Year',
  `runtime` varchar(100) DEFAULT NULL COMMENT 'Runtime',
  `directors` json DEFAULT NULL COMMENT 'Directors List',
  `actors` json DEFAULT NULL COMMENT 'Actors List',
  `genres` json DEFAULT NULL COMMENT 'Genres List',
  `regions` json DEFAULT NULL COMMENT 'Regions List',
  `languages` json DEFAULT NULL COMMENT 'Languages List',
  `release_dates` varchar(500) DEFAULT NULL COMMENT 'Release Dates',
  `aliases` text COMMENT 'Also known as',
  `category` varchar(20) DEFAULT 'mv' COMMENT 'mv, tv, ac',
  `poster_url` varchar(500) DEFAULT NULL COMMENT 'MinIO URL',
  `douban_score` decimal(3,1) DEFAULT NULL,
  `imdb_score` decimal(3,1) DEFAULT NULL,
  `tmdb_popularity` decimal(12,4) DEFAULT NULL COMMENT 'TMDB popularity',
  `tmdb_vote_average` decimal(3,1) DEFAULT NULL COMMENT 'TMDB vote average',
  `rt_score` varchar(50) DEFAULT NULL COMMENT 'Rotten Tomatoes',
  `summary` text COMMENT 'Description',
  `status` varchar(50) DEFAULT 'ACTIVE' COMMENT 'Status',
  `resource_status` varchar(30) DEFAULT 'UNKNOWN' COMMENT 'Resource status: UNKNOWN, TRAILER, AVAILABLE',
  `popularity` int DEFAULT '0' COMMENT 'Popularity score',
  `tmdb_last_sync_at` datetime DEFAULT NULL COMMENT 'Last TMDB sync time',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_year` (`year`),
  KEY `idx_title_cn` (`title_cn`),
  KEY `idx_category_status_year` (`category`, `status`, `year`),
  KEY `idx_status_created_at` (`status`, `created_at`),
  KEY `idx_status_douban_score` (`status`, `douban_score`),
  KEY `idx_resource_status` (`resource_status`),
  KEY `idx_popularity` (`popularity`),
  KEY `idx_tmdb_type_id` (`tmdb_type`, `tmdb_id`),
  KEY `idx_tmdb_last_sync_at` (`tmdb_last_sync_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Movie Metadata';

-- ----------------------------
-- Table structure for resource_link
-- ----------------------------
DROP TABLE IF EXISTS `resource_link`;
CREATE TABLE `resource_link` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `movie_id` varchar(64) NOT NULL,
  `name` varchar(255) DEFAULT NULL COMMENT 'Resource Name',
  `type` varchar(50) DEFAULT 'DISK' COMMENT 'DISK, MAGNET, ONLINE',
  `provider` varchar(50) DEFAULT NULL COMMENT 'BAIDU, QUARK, XUNLEI, etc.',
  `url` text NOT NULL,
  `url_hash` char(64) DEFAULT NULL COMMENT 'SHA-256 hash of normalized URL',
  `code` varchar(50) DEFAULT NULL COMMENT 'Access Code',
  `uploader_id` bigint DEFAULT NULL,
  `audit_status` int DEFAULT '0' COMMENT '0:Pending, 1:Approved, 2:Rejected',
  `status` varchar(20) DEFAULT 'ACTIVE' COMMENT 'ACTIVE, DELETED',
  `link_status` varchar(20) DEFAULT 'NORMAL' COMMENT 'NORMAL, SUSPECTED_INVALID, INVALID',
  `report_count` int DEFAULT '0',
  `source` varchar(50) DEFAULT 'USER' COMMENT 'USER, RESOURCE_HUB, CRAWLER',
  `source_ref` varchar(100) DEFAULT NULL COMMENT 'External source reference',
  `source_url` text COMMENT 'Original discovered URL',
  `auto_collected` tinyint(1) DEFAULT '0' COMMENT 'Created by Resource Hub automation',
  `validated_at` datetime DEFAULT NULL COMMENT 'Last validation time',
  `last_check_error` varchar(1000) DEFAULT NULL COMMENT 'Last validation error',
  `quality` varchar(50) DEFAULT NULL COMMENT 'Quality label, e.g. 4K/1080P',
  `subtitle` varchar(50) DEFAULT NULL COMMENT 'Subtitle information',
  `file_size` varchar(50) DEFAULT NULL COMMENT 'File size label',
  `version_note` varchar(255) DEFAULT NULL COMMENT 'Version or release note',
  `reject_reason` varchar(255) DEFAULT NULL COMMENT 'Audit rejection reason',
  `remark` varchar(255) DEFAULT NULL,
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_movie_id` (`movie_id`),
  KEY `idx_resource_status` (`status`, `audit_status`, `link_status`),
  KEY `idx_resource_url_hash` (`movie_id`, `url_hash`),
  KEY `idx_resource_source` (`source`, `source_ref`),
  KEY `idx_resource_validation` (`link_status`, `validated_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Resource Links';

-- ----------------------------
-- Table structure for sys_user
-- ----------------------------
DROP TABLE IF EXISTS `sys_user`;
CREATE TABLE `sys_user` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `username` varchar(100) NOT NULL,
  `password` varchar(100) NOT NULL,
  `email` varchar(200) DEFAULT NULL,
  `role` varchar(50) DEFAULT 'USER' COMMENT 'ADMIN, USER',
  `score` int DEFAULT '0',
  `enabled` tinyint(1) DEFAULT '1',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='System Users';

-- ----------------------------
-- Table structure for sys_config
-- ----------------------------
DROP TABLE IF EXISTS `sys_config`;
CREATE TABLE `sys_config` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `config_key` varchar(100) NOT NULL COMMENT 'Configuration key',
  `config_value` varchar(500) NOT NULL COMMENT 'Configuration value',
  `description` varchar(255) DEFAULT NULL COMMENT 'Configuration description',
  `created_at` timestamp DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_config_key` (`config_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='System Configuration';

-- ----------------------------
-- Table structure for comment
-- ----------------------------
DROP TABLE IF EXISTS `comment`;
CREATE TABLE `comment` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `relate_id` varchar(64) NOT NULL COMMENT 'Movie ID',
  `user_id` bigint DEFAULT NULL,
  `nickname` varchar(100) DEFAULT NULL COMMENT 'User nickname at comment time',
  `content` text NOT NULL,
  `status` int DEFAULT '1' COMMENT '0:Pending, 1:Published, 2:Hidden',
  `upvotes` int DEFAULT '0',
  `parent_id` bigint DEFAULT '0' COMMENT 'Parent comment ID (0=root)',
  `ip_address` varchar(100) DEFAULT NULL,
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_relate_id` (`relate_id`),
  KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Comments';

-- ----------------------------
-- Table structure for comment_vote
-- ----------------------------
DROP TABLE IF EXISTS `comment_vote`;
CREATE TABLE `comment_vote` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `comment_id` bigint NOT NULL,
  `user_id` bigint NOT NULL,
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_comment_user` (`comment_id`, `user_id`),
  KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Comment Votes';

-- ----------------------------
-- Table structure for resource_report
-- ----------------------------
DROP TABLE IF EXISTS `resource_report`;
CREATE TABLE `resource_report` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `resource_id` bigint NOT NULL,
  `user_id` bigint NOT NULL,
  `reason` varchar(255) DEFAULT NULL,
  `status` varchar(20) DEFAULT 'PENDING' COMMENT 'PENDING, HANDLED, FALSE_REPORT',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  `handled_at` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_resource_user_pending` (`resource_id`, `user_id`, `status`),
  KEY `idx_status_created` (`status`, `created_at`),
  KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Resource Invalid Reports';
-- ----------------------------
-- Table structure for user_favorite
-- ----------------------------
DROP TABLE IF EXISTS `user_favorite`;
CREATE TABLE `user_favorite` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `movie_id` varchar(64) NOT NULL,
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_movie` (`user_id`, `movie_id`),
  KEY `idx_movie_id` (`movie_id`),
  KEY `idx_created_at` (`created_at`),
  KEY `idx_created_movie` (`created_at`, `movie_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='User Favorites';

-- ----------------------------
-- Table structure for user_notification
-- ----------------------------
DROP TABLE IF EXISTS `user_notification`;
CREATE TABLE `user_notification` (
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


-- ----------------------------
-- Table structure for resource_hub_task
-- ----------------------------
DROP TABLE IF EXISTS `resource_hub_task`;
CREATE TABLE `resource_hub_task` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `task_type` varchar(50) NOT NULL COMMENT 'METADATA_SYNC, RESOURCE_DISCOVERY, QUARK_TRANSFER, VALIDATION',
  `movie_id` varchar(64) DEFAULT NULL,
  `tmdb_id` bigint DEFAULT NULL,
  `tmdb_type` varchar(20) DEFAULT NULL,
  `keyword` varchar(255) DEFAULT NULL,
  `source` varchar(50) DEFAULT NULL,
  `status` varchar(30) DEFAULT 'PENDING' COMMENT 'PENDING, RUNNING, SUCCEEDED, FAILED, CANCELED',
  `priority` int DEFAULT '0',
  `attempts` int DEFAULT '0',
  `max_attempts` int DEFAULT '3',
  `last_error` varchar(1000) DEFAULT NULL,
  `payload` json DEFAULT NULL,
  `scheduled_at` datetime DEFAULT CURRENT_TIMESTAMP,
  `started_at` datetime DEFAULT NULL,
  `finished_at` datetime DEFAULT NULL,
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_hub_task_status_schedule` (`status`, `scheduled_at`, `priority`),
  KEY `idx_hub_task_movie` (`movie_id`),
  KEY `idx_hub_task_tmdb` (`tmdb_type`, `tmdb_id`),
  KEY `idx_hub_task_source_status` (`source`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Resource Hub Tasks';

-- ----------------------------
-- Table structure for resource_discovery_result
-- ----------------------------
DROP TABLE IF EXISTS `resource_discovery_result`;
CREATE TABLE `resource_discovery_result` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `task_id` bigint DEFAULT NULL,
  `movie_id` varchar(64) NOT NULL,
  `source` varchar(50) NOT NULL,
  `source_ref` varchar(100) DEFAULT NULL,
  `title` varchar(255) DEFAULT NULL,
  `provider` varchar(50) DEFAULT NULL,
  `resource_type` varchar(50) DEFAULT 'DISK',
  `original_url` text,
  `original_url_hash` char(64) DEFAULT NULL,
  `share_url` text,
  `share_url_hash` char(64) DEFAULT NULL,
  `code` varchar(50) DEFAULT NULL,
  `quality` varchar(50) DEFAULT NULL,
  `subtitle` varchar(50) DEFAULT NULL,
  `file_size` varchar(50) DEFAULT NULL,
  `version_note` varchar(255) DEFAULT NULL,
  `confidence` decimal(5,2) DEFAULT NULL,
  `status` varchar(30) DEFAULT 'DISCOVERED' COMMENT 'DISCOVERED, SAVED, DUPLICATE, IGNORED, FAILED',
  `failure_reason` varchar(1000) DEFAULT NULL,
  `resource_link_id` bigint DEFAULT NULL,
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_discovery_task` (`task_id`),
  KEY `idx_discovery_movie_status` (`movie_id`, `status`),
  KEY `idx_discovery_original_hash` (`movie_id`, `original_url_hash`),
  KEY `idx_discovery_share_hash` (`movie_id`, `share_url_hash`),
  KEY `idx_discovery_source_ref` (`source`, `source_ref`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Resource Hub Discovery Results';

-- ----------------------------
-- Table structure for quark_transfer_task
-- ----------------------------
DROP TABLE IF EXISTS `quark_transfer_task`;
CREATE TABLE `quark_transfer_task` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `discovery_result_id` bigint DEFAULT NULL,
  `movie_id` varchar(64) NOT NULL,
  `original_url` text NOT NULL,
  `original_url_hash` char(64) DEFAULT NULL,
  `saved_path` varchar(500) DEFAULT NULL,
  `share_url` text,
  `share_url_hash` char(64) DEFAULT NULL,
  `status` varchar(30) DEFAULT 'PENDING' COMMENT 'PENDING, RUNNING, SUCCEEDED, FAILED, CANCELED',
  `attempts` int DEFAULT '0',
  `last_error` varchar(1000) DEFAULT NULL,
  `request_payload` json DEFAULT NULL,
  `response_payload` json DEFAULT NULL,
  `started_at` datetime DEFAULT NULL,
  `finished_at` datetime DEFAULT NULL,
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_quark_discovery` (`discovery_result_id`),
  KEY `idx_quark_movie_status` (`movie_id`, `status`),
  KEY `idx_quark_status_created` (`status`, `created_at`),
  KEY `idx_quark_original_hash` (`movie_id`, `original_url_hash`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Quark Transfer Tasks';
SET FOREIGN_KEY_CHECKS = 1;

-- Initial Data
INSERT INTO sys_user (username, password, role) VALUES ('admin', '$2a$10$HRsXUOtHmXt8qhrcbLko2uWXa6evM5pHnff1ITMCSEkA6WqMKmKk6', 'ADMIN');
INSERT INTO sys_config (config_key, config_value, description) VALUES
('resource.audit.enabled', 'true', 'Enable resource submission audit (true/false)'),
('resource.max.per.user', '100', 'Maximum resources per user'),
('resource.submit.interval.seconds', '60', 'Minimum seconds between resource submissions'),
('resource.report.threshold', '3', 'Reports needed before a resource is treated as suspected invalid'),
('auth.register.enabled', 'true', 'Allow public registration'),
('resource.hub.enabled', 'false', 'Enable Resource Hub automation (true/false)'),
('resource.hub.auto_approve', 'true', 'Auto approve Resource Hub imported resources'),
('resource.hub.validation.enabled', 'false', 'Enable scheduled Resource Hub link validation'),
('resource.hub.discovery.max_attempts', '3', 'Maximum discovery attempts per task'),
('resource.hub.tmdb.auto_sync_enabled', 'false', 'Enable TMDB scheduled metadata sync'),
('resource.hub.tmdb.auto_sync_sources', 'TRENDING_MOVIE_DAY,TRENDING_TV_DAY,POPULAR_MOVIE,POPULAR_TV', 'TMDB scheduled sync sources'),
('resource.hub.tmdb.auto_sync_page', '1', 'TMDB scheduled sync page'),
('resource.hub.tmdb.auto_sync_max_items', '20', 'TMDB scheduled sync item limit'),
('resource.hub.tmdb.auto_sync_interval_hours', '24', 'TMDB scheduled sync interval in hours'),
('resource.hub.tmdb.auto_discovery_enabled', 'true', 'Create discovery tasks after TMDB sync'),
('resource.hub.tmdb.discovery_max_results', '10', 'PanSou discovery result limit'),
('resource.hub.tmdb.discovery_cooldown_hours', '24', 'Discovery retry cooldown in hours'),
('resource.hub.worker.enabled', 'false', 'Enable Resource Hub worker'),
('resource.hub.worker.task_limit', '5', 'Tasks processed per worker run'),
('resource.hub.worker.quark_limit', '5', 'Quark transfers submitted per worker run'),
('resource.hub.worker.publish_limit', '20', 'Discoveries published per worker run')
ON DUPLICATE KEY UPDATE config_value = config_value;
