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
  `rt_score` varchar(50) DEFAULT NULL COMMENT 'Rotten Tomatoes',
  `summary` text COMMENT 'Description',
  `status` varchar(50) DEFAULT 'ACTIVE' COMMENT 'Status',
  `popularity` int DEFAULT '0' COMMENT 'Popularity score',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_year` (`year`),
  KEY `idx_title_cn` (`title_cn`),
  KEY `idx_category_status_year` (`category`, `status`, `year`),
  KEY `idx_status_created_at` (`status`, `created_at`),
  KEY `idx_status_douban_score` (`status`, `douban_score`),
  KEY `idx_popularity` (`popularity`)
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
  `code` varchar(50) DEFAULT NULL COMMENT 'Access Code',
  `uploader_id` bigint DEFAULT NULL,
  `audit_status` int DEFAULT '0' COMMENT '0:Pending, 1:Approved, 2:Rejected',
  `status` varchar(20) DEFAULT 'ACTIVE' COMMENT 'ACTIVE, DELETED',
  `link_status` varchar(20) DEFAULT 'NORMAL' COMMENT 'NORMAL, SUSPECTED_INVALID, INVALID',
  `report_count` int DEFAULT '0',
  `remark` varchar(255) DEFAULT NULL,
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_movie_id` (`movie_id`),
  KEY `idx_resource_status` (`status`, `audit_status`, `link_status`)
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

SET FOREIGN_KEY_CHECKS = 1;

-- Initial Data
INSERT INTO sys_user (username, password, role) VALUES ('admin', '$2a$10$HRsXUOtHmXt8qhrcbLko2uWXa6evM5pHnff1ITMCSEkA6WqMKmKk6', 'ADMIN');
INSERT INTO sys_config (config_key, config_value, description) VALUES
('resource.audit.enabled', 'true', 'Enable resource submission audit (true/false)'),
('resource.max.per.user', '100', 'Maximum resources per user'),
('resource.submit.interval.seconds', '60', 'Minimum seconds between resource submissions')
ON DUPLICATE KEY UPDATE config_value = config_value;
