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
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_year` (`year`),
  KEY `idx_title_cn` (`title_cn`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Movie Metadata';

-- ----------------------------
-- Table structure for resource_link
-- ----------------------------
DROP TABLE IF EXISTS `resource_link`;
CREATE TABLE `resource_link` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `movie_id` varchar(64) NOT NULL,
  `type` varchar(50) DEFAULT 'DISK' COMMENT 'DISK, MAGNET, ONLINE',
  `provider` varchar(50) DEFAULT NULL COMMENT 'BAIDU, QUARK, XUNLEI, etc.',
  `url` text NOT NULL,
  `code` varchar(50) DEFAULT NULL COMMENT 'Access Code',
  `uploader_id` bigint DEFAULT NULL,
  `audit_status` int DEFAULT '0' COMMENT '0:Pending, 1:Approved, 2:Rejected',
  `remark` varchar(255) DEFAULT NULL,
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_movie_id` (`movie_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Resource Links';

-- ----------------------------
-- Table structure for sys_user
-- ----------------------------
DROP TABLE IF EXISTS `sys_user`;
CREATE TABLE `sys_user` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `username` varchar(100) NOT NULL,
  `password` varchar(100) NOT NULL,
  `role` varchar(50) DEFAULT 'USER' COMMENT 'ADMIN, USER',
  `score` int DEFAULT '0',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='System Users';

-- ----------------------------
-- Table structure for comment
-- ----------------------------
DROP TABLE IF EXISTS `comment`;
CREATE TABLE `comment` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `relate_id` varchar(64) NOT NULL COMMENT 'Movie ID',
  `user_id` bigint DEFAULT NULL,
  `content` text NOT NULL,
  `ip_address` varchar(100) DEFAULT NULL,
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_relate_id` (`relate_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Comments';

SET FOREIGN_KEY_CHECKS = 1;

-- Initial Data
INSERT INTO sys_user (username, password, role) VALUES ('admin', '$2a$10$HRsXUOtHmXt8qhrcbLko2uWXa6evM5pHnff1ITMCSEkA6WqMKmKk6', 'ADMIN');
