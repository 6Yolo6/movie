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
  `nickname` varchar(50) DEFAULT NULL COMMENT '站内昵称',
  `password` varchar(100) NOT NULL,
  `email` varchar(200) DEFAULT NULL,
  `email_updated_at` datetime DEFAULT NULL COMMENT '邮箱最后变更时间',
  `role` varchar(50) DEFAULT 'USER' COMMENT 'ADMIN, USER',
  `score` int DEFAULT '0',
  `enabled` tinyint(1) DEFAULT '1',
  `invited_by_user_id` bigint DEFAULT NULL COMMENT 'Inviter user ID',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_username` (`username`),
  UNIQUE KEY `uk_sys_user_email` (`email`)
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
  `relate_id` varchar(64) NOT NULL COMMENT 'Movie ID or message-board key',
  `comment_type` varchar(32) NOT NULL DEFAULT 'GENERAL' COMMENT 'GENERAL, REQUEST, INVALID_RESOURCE, SUGGESTION, OTHER',
  `user_id` bigint DEFAULT NULL,
  `nickname` varchar(100) DEFAULT NULL COMMENT 'User nickname at comment time',
  `content` text NOT NULL,
  `status` int DEFAULT '1' COMMENT '0:Pending, 1:Published, 2:Hidden',
  `upvotes` int DEFAULT '0',
  `parent_id` bigint DEFAULT '0' COMMENT 'Parent comment ID (0=root)',
  `ip_address` varchar(100) DEFAULT NULL,
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_relate_type_status` (`relate_id`, `comment_type`, `status`),
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

-- ----------------------------
-- Table structure for xunlei_transfer_task
-- ----------------------------
DROP TABLE IF EXISTS `xunlei_transfer_task`;
CREATE TABLE `xunlei_transfer_task` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `discovery_result_id` bigint DEFAULT NULL,
  `movie_id` varchar(64) NOT NULL,
  `original_url` text NOT NULL,
  `original_url_hash` char(64) DEFAULT NULL,
  `saved_path` varchar(500) DEFAULT NULL,
  `share_url` text,
  `share_url_hash` char(64) DEFAULT NULL,
  `status` varchar(30) DEFAULT 'PENDING' COMMENT 'PENDING, RUNNING, SUBMITTED, SUCCEEDED, WAITING_SHARE, FAILED, CANCELED',
  `attempts` int DEFAULT '0',
  `last_error` varchar(1000) DEFAULT NULL,
  `request_payload` json DEFAULT NULL,
  `response_payload` json DEFAULT NULL,
  `started_at` datetime DEFAULT NULL,
  `finished_at` datetime DEFAULT NULL,
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_xunlei_discovery` (`discovery_result_id`),
  KEY `idx_xunlei_movie_status` (`movie_id`, `status`),
  KEY `idx_xunlei_status_created` (`status`, `created_at`),
  KEY `idx_xunlei_original_hash` (`movie_id`, `original_url_hash`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='迅雷转存任务';

-- ----------------------------
-- Table structure for movie_source_identity
-- ----------------------------
DROP TABLE IF EXISTS `movie_source_identity`;
CREATE TABLE `movie_source_identity` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '来源身份关联主键',
  `movie_id` varchar(64) NOT NULL COMMENT '本站影片 ID',
  `source` varchar(20) NOT NULL COMMENT '数据来源：TMDB、GYING',
  `source_type` varchar(20) NOT NULL COMMENT '来源类型：movie、tv、mv、ac',
  `external_id` varchar(100) NOT NULL COMMENT '来源站点影片 ID',
  `season` int NOT NULL DEFAULT '0' COMMENT '季号，电影或未知为 0',
  `confidence` decimal(5,2) NOT NULL DEFAULT '100.00' COMMENT '匹配置信度',
  `match_method` varchar(50) NOT NULL COMMENT '匹配方式',
  `match_status` varchar(20) NOT NULL DEFAULT 'CONFIRMED' COMMENT '状态：AUTO、CONFIRMED、REVIEW、REJECTED',
  `evidence_json` json DEFAULT NULL COMMENT '匹配证据',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_source_identity` (`source`, `source_type`, `external_id`, `season`),
  KEY `idx_movie_source` (`movie_id`, `source`, `season`),
  KEY `idx_match_status` (`match_status`, `confidence`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='影片外部数据源身份关联';

-- ----------------------------
-- Table structure for social_publish_target
-- ----------------------------
DROP TABLE IF EXISTS `social_publish_target`;
CREATE TABLE `social_publish_target` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '发布目标主键',
  `platform` varchar(30) NOT NULL COMMENT '平台：QQ_CHANNEL、WEIBO',
  `account_key` varchar(60) NOT NULL COMMENT '独立账号标识',
  `name` varchar(120) NOT NULL COMMENT '后台显示名称',
  `target_ref` varchar(120) NOT NULL DEFAULT '' COMMENT '频道号或平台目标标识',
  `channel_ref` varchar(120) DEFAULT NULL COMMENT 'QQ 版块 ID，留空自动选择全部或帖子广场',
  `enabled` tinyint(1) NOT NULL DEFAULT '1' COMMENT '目标是否启用',
  `auto_post_enabled` tinyint(1) NOT NULL DEFAULT '0' COMMENT '是否启用每日定时发布',
  `schedule_time` varchar(5) NOT NULL DEFAULT '10:00' COMMENT '每日发布时间，HH:mm',
  `posts_per_run` int NOT NULL DEFAULT '1' COMMENT '每次发布条数',
  `post_interval_seconds` int NOT NULL DEFAULT '60' COMMENT '同一批次每条间隔秒数',
  `template` text COMMENT '发布正文模板',
  `last_auto_run_at` datetime DEFAULT NULL COMMENT '最近一次自动调度时间',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_social_publish_target` (`platform`, `account_key`, `target_ref`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='多平台发布目标';

-- ----------------------------
-- Table structure for social_post_log
-- ----------------------------
DROP TABLE IF EXISTS `social_post_log`;
CREATE TABLE `social_post_log` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '发布日志主键',
  `target_id` bigint NOT NULL COMMENT '发布目标 ID',
  `platform` varchar(30) NOT NULL COMMENT '平台：QQ_CHANNEL、WEIBO',
  `resource_link_id` bigint NOT NULL COMMENT '资源链接 ID',
  `movie_id` varchar(100) NOT NULL COMMENT '影片 ID',
  `title` varchar(500) DEFAULT NULL COMMENT '发布时影片标题',
  `status` varchar(30) NOT NULL COMMENT '状态：PENDING、POSTED、FAILED',
  `external_url` varchar(1000) DEFAULT NULL COMMENT '外部帖子地址',
  `error_message` varchar(1000) DEFAULT NULL COMMENT '发布失败原因',
  `posted_at` datetime DEFAULT NULL COMMENT '成功发布时间',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_social_post_target_resource` (`target_id`, `resource_link_id`),
  KEY `idx_social_post_status` (`status`),
  KEY `idx_social_post_created` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='多平台发布审计日志';

CREATE TABLE IF NOT EXISTS `qq_transfer_cleanup_job` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键 ID',
  `provider` varchar(20) NOT NULL COMMENT '网盘提供方：QUARK 或 XUNLEI',
  `transfer_task_id` bigint NOT NULL COMMENT '对应的转存任务 ID',
  `discovery_result_id` bigint DEFAULT NULL COMMENT '对应的资源发现结果 ID',
  `movie_id` varchar(100) DEFAULT NULL COMMENT '影片 ID',
  `saved_path` varchar(1000) NOT NULL COMMENT '提供方返回的精确保存路径或目录 ID',
  `target_path` varchar(1000) NOT NULL COMMENT '受安全根目录约束的 QQ 临时目标路径',
  `resource_link_id` bigint DEFAULT NULL COMMENT '临时发布的资源链接 ID',
  `status` varchar(30) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING、RUNNING、SUCCEEDED、FAILED、SKIPPED',
  `attempts` int NOT NULL DEFAULT 0 COMMENT '清理尝试次数',
  `last_error` varchar(1000) DEFAULT NULL COMMENT '最近一次清理错误',
  `delete_after` datetime NOT NULL COMMENT '计划删除时间',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `finished_at` datetime DEFAULT NULL COMMENT '完成时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_qq_cleanup_transfer` (`provider`, `transfer_task_id`),
  KEY `idx_qq_cleanup_due` (`status`, `delete_after`),
  KEY `idx_qq_cleanup_discovery` (`discovery_result_id`),
  KEY `idx_qq_cleanup_resource` (`resource_link_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='QQ群搜索临时转存清理任务';

SET FOREIGN_KEY_CHECKS = 1;

-- Initial Data
-- 首个管理员必须使用 tools/security/bootstrap_admin.py 交互创建，禁止预置共享密码。
INSERT INTO sys_config (config_key, config_value, description) VALUES
('resource.audit.enabled', 'true', 'Enable resource submission audit (true/false)'),
('resource.max.per.user', '100', 'Maximum resources per user'),
('resource.submit.interval.seconds', '60', 'Minimum seconds between resource submissions'),
('resource.report.threshold', '3', 'Reports needed before a resource is treated as suspected invalid'),
('auth.register.enabled', 'true', 'Allow public registration'),
('auth.register.max_users', '0', '公开自助注册的最大用户总数；0 表示不限制，达到上限后仅停止公开注册，仍可使用邀请码或由管理员建号。'),
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

INSERT INTO social_publish_target
  (platform, account_key, name, target_ref, channel_ref, enabled, auto_post_enabled, schedule_time, posts_per_run, post_interval_seconds, template)
VALUES
  ('WEIBO', 'default', '新浪微博', 'default', NULL, 1, 0, '11:00', 1, 60, '{{title}}（{{year}}）\n{{type}}\n{{intro}}\n{{link}}')
ON DUPLICATE KEY UPDATE name = VALUES(name);


-- QQ 群临时转存清理默认配置
INSERT INTO sys_config (config_key, config_value, description) VALUES
('qq.bot.transfer_cleanup.enabled', 'true', '是否自动清理QQ群搜索产生的临时转存文件'),
('qq.bot.transfer_cleanup.delay_minutes', '10', 'QQ群临时转存成功后的保留时间（分钟）'),
('qq.bot.transfer_cleanup.quark_root', '/GYing QQ Temp', 'QQ群夸克临时转存专用根目录'),
('qq.bot.transfer_cleanup.xunlei_root', '/影视剧资源分享(先转存后再查看)/GYing QQ Temp', 'QQ群迅雷临时转存专用根目录')
ON DUPLICATE KEY UPDATE config_value = config_value;


-- 2026-09-19 注册邮箱、邀请注册与后台监控
CREATE TABLE IF NOT EXISTS `user_invitation_code` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '邀请记录主键',
  `code_hash` char(64) NOT NULL COMMENT '邀请码 SHA-256，不保存明文',
  `code_hint` varchar(16) NOT NULL COMMENT '邀请码脱敏提示',
  `creator_user_id` bigint NOT NULL COMMENT '创建邀请码的用户 ID',
  `max_uses` int NOT NULL DEFAULT 1 COMMENT '最多使用次数',
  `used_count` int NOT NULL DEFAULT 0 COMMENT '已使用次数',
  `period_start` date NOT NULL COMMENT '额度统计周期开始日期',
  `expires_at` datetime NOT NULL COMMENT '过期时间',
  `status` varchar(20) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE、REVOKED、EXHAUSTED、EXPIRED',
  `last_used_at` datetime DEFAULT NULL COMMENT '最近使用时间',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_invitation_code_hash` (`code_hash`),
  KEY `idx_invitation_creator_period` (`creator_user_id`, `period_start`),
  KEY `idx_invitation_status_expires` (`status`, `expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户邀请码';

CREATE TABLE IF NOT EXISTS `user_invitation_use` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '使用记录主键',
  `invitation_id` bigint NOT NULL COMMENT '邀请码记录 ID',
  `inviter_user_id` bigint NOT NULL COMMENT '邀请人用户 ID',
  `invitee_user_id` bigint NOT NULL COMMENT '新注册用户 ID',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '使用时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_invitation_invitee` (`invitee_user_id`),
  KEY `idx_invitation_use_inviter` (`inviter_user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='邀请码使用记录';

CREATE TABLE IF NOT EXISTS `site_access_log` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '访问日志主键',
  `request_path` varchar(500) NOT NULL COMMENT '请求路径，不含敏感查询参数',
  `method` varchar(10) NOT NULL COMMENT 'HTTP 方法',
  `status_code` int NOT NULL COMMENT '响应状态码',
  `user_id` bigint DEFAULT NULL COMMENT '已登录用户 ID',
  `visitor_hash` char(64) NOT NULL COMMENT '访客匿名哈希',
  `user_agent_hash` char(64) DEFAULT NULL COMMENT 'User-Agent 匿名哈希',
  `referer` varchar(500) DEFAULT NULL COMMENT '来源页面',
  `duration_ms` bigint NOT NULL DEFAULT 0 COMMENT '处理耗时毫秒',
  `event_type` varchar(30) NOT NULL DEFAULT 'API' COMMENT 'API 或 PAGE_VIEW',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '访问时间',
  PRIMARY KEY (`id`),
  KEY `idx_access_created` (`created_at`),
  KEY `idx_access_visitor_created` (`visitor_hash`, `created_at`),
  KEY `idx_access_status_created` (`status_code`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='网站匿名访问日志';

CREATE TABLE IF NOT EXISTS `site_search_log` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '站内搜索日志主键',
  `keyword` varchar(255) NOT NULL COMMENT '规范化搜索词',
  `user_id` bigint DEFAULT NULL COMMENT '登录用户 ID',
  `visitor_hash` char(64) DEFAULT NULL COMMENT '匿名访客哈希',
  `source` varchar(30) NOT NULL DEFAULT 'WEB' COMMENT 'WEB 或 QQ',
  `result_count` int NOT NULL DEFAULT 0 COMMENT '结果数量',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '搜索时间',
  PRIMARY KEY (`id`),
  KEY `idx_site_search_created` (`created_at`),
  KEY `idx_site_search_keyword` (`keyword`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='站内搜索记录';

CREATE TABLE IF NOT EXISTS `resource_operation_log` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '资源操作日志主键',
  `user_id` bigint DEFAULT NULL COMMENT '操作用户 ID',
  `visitor_hash` char(64) DEFAULT NULL COMMENT '匿名访客哈希',
  `movie_id` varchar(64) DEFAULT NULL COMMENT '影片 ID',
  `resource_link_id` bigint DEFAULT NULL COMMENT '资源链接 ID',
  `operation_type` varchar(30) NOT NULL COMMENT 'VIEW、COPY、SUBMIT、SHARE、TRANSFER、DELETE、REPORT',
  `provider` varchar(50) DEFAULT NULL COMMENT '资源提供方',
  `status` varchar(30) NOT NULL DEFAULT 'SUCCESS' COMMENT 'SUCCESS 或 FAILED',
  `error_message` varchar(500) DEFAULT NULL COMMENT '脱敏错误摘要',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间',
  PRIMARY KEY (`id`),
  KEY `idx_resource_operation_created` (`created_at`),
  KEY `idx_resource_operation_type_created` (`operation_type`, `created_at`),
  KEY `idx_resource_operation_movie` (`movie_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='资源操作审计日志';

INSERT INTO sys_config (`config_key`, `config_value`, `description`) VALUES
('auth.email_verification.enabled', 'false', '注册时是否启用邮箱验证码；未配置邮件服务时保持关闭'),
('auth.invite.enabled', 'true', '是否允许邀请码注册'),
('auth.invite.min_account_age_days', '30', '普通用户账号注册满多少天后可生成邀请码'),
('auth.invite.period_days', '30', '邀请码生成额度周期天数'),
('auth.invite.max_per_period', '3', '每个合资格用户每周期最多生成邀请码数量'),
('auth.invite.default_max_uses', '1', '每个邀请码默认可使用次数'),
('auth.invite.expire_days', '14', '邀请码有效天数'),
('monitoring.access_log.retention_days', '90', '访问日志保留天数'),
('monitoring.hot_search.enabled', 'true', '是否使用 Redis 统计热门搜索')
ON DUPLICATE KEY UPDATE `description` = VALUES(`description`);


-- ----------------------------
-- Table structure for login_device
-- ----------------------------
CREATE TABLE `login_device` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `jti` varchar(64) NOT NULL,
  `device_name` varchar(120) NOT NULL,
  `ip_address` varchar(100) DEFAULT NULL,
  `user_agent` varchar(512) DEFAULT NULL,
  `login_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `last_seen_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `expires_at` datetime NOT NULL,
  `revoked_at` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_login_device_jti` (`jti`),
  KEY `idx_login_device_user_active` (`user_id`, `revoked_at`, `expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='登录设备授权';
