-- Resource Hub incremental migration.
-- Safe to run multiple times on MySQL because each ALTER is guarded by information_schema checks.

DROP PROCEDURE IF EXISTS gying_add_column_if_missing;
DROP PROCEDURE IF EXISTS gying_add_index_if_missing;

DELIMITER //

CREATE PROCEDURE gying_add_column_if_missing(
    IN p_table_name VARCHAR(64),
    IN p_column_name VARCHAR(64),
    IN p_column_definition TEXT
)
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = p_table_name
          AND COLUMN_NAME = p_column_name
    ) THEN
        SET @ddl = CONCAT('ALTER TABLE `', p_table_name, '` ADD COLUMN ', p_column_definition);
        PREPARE stmt FROM @ddl;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END//

CREATE PROCEDURE gying_add_index_if_missing(
    IN p_table_name VARCHAR(64),
    IN p_index_name VARCHAR(64),
    IN p_index_definition TEXT
)
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = p_table_name
          AND INDEX_NAME = p_index_name
    ) THEN
        SET @ddl = CONCAT('ALTER TABLE `', p_table_name, '` ADD ', p_index_definition);
        PREPARE stmt FROM @ddl;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END//

DELIMITER ;

CALL gying_add_column_if_missing('movie_metadata', 'tmdb_id', '`tmdb_id` bigint DEFAULT NULL COMMENT ''TMDB ID'' AFTER `id`');
CALL gying_add_column_if_missing('movie_metadata', 'tmdb_type', '`tmdb_type` varchar(20) DEFAULT NULL COMMENT ''TMDB media type: movie, tv'' AFTER `tmdb_id`');
CALL gying_add_column_if_missing('movie_metadata', 'tmdb_popularity', '`tmdb_popularity` decimal(12,4) DEFAULT NULL COMMENT ''TMDB popularity'' AFTER `imdb_score`');
CALL gying_add_column_if_missing('movie_metadata', 'tmdb_vote_average', '`tmdb_vote_average` decimal(3,1) DEFAULT NULL COMMENT ''TMDB vote average'' AFTER `tmdb_popularity`');
CALL gying_add_column_if_missing('movie_metadata', 'tmdb_last_sync_at', '`tmdb_last_sync_at` datetime DEFAULT NULL COMMENT ''Last TMDB sync time'' AFTER `popularity`');
CALL gying_add_column_if_missing('movie_metadata', 'resource_status', '`resource_status` varchar(30) DEFAULT ''UNKNOWN'' COMMENT ''Resource status: UNKNOWN, TRAILER, AVAILABLE'' AFTER `status`');
CALL gying_add_index_if_missing('movie_metadata', 'idx_tmdb_type_id', 'INDEX `idx_tmdb_type_id` (`tmdb_type`, `tmdb_id`)');
CALL gying_add_index_if_missing('movie_metadata', 'idx_tmdb_last_sync_at', 'INDEX `idx_tmdb_last_sync_at` (`tmdb_last_sync_at`)');
CALL gying_add_index_if_missing('movie_metadata', 'idx_resource_status', 'INDEX `idx_resource_status` (`resource_status`)');

-- Keep movie_metadata.popularity as the local favorite count.
-- TMDB ranking data is stored separately in movie_metadata.tmdb_popularity.
UPDATE movie_metadata m
LEFT JOIN (
  SELECT movie_id, COUNT(*) AS favorite_count
  FROM user_favorite
  GROUP BY movie_id
) f ON f.movie_id = m.id
SET m.popularity = COALESCE(f.favorite_count, 0)
WHERE m.tmdb_id IS NOT NULL;

CALL gying_add_column_if_missing('resource_link', 'url_hash', '`url_hash` char(64) DEFAULT NULL COMMENT ''SHA-256 hash of normalized URL'' AFTER `url`');
CALL gying_add_column_if_missing('resource_link', 'source', '`source` varchar(50) DEFAULT ''USER'' COMMENT ''USER, RESOURCE_HUB, CRAWLER'' AFTER `report_count`');
CALL gying_add_column_if_missing('resource_link', 'source_ref', '`source_ref` varchar(100) DEFAULT NULL COMMENT ''External source reference'' AFTER `source`');
CALL gying_add_column_if_missing('resource_link', 'source_url', '`source_url` text COMMENT ''Original discovered URL'' AFTER `source_ref`');
CALL gying_add_column_if_missing('resource_link', 'auto_collected', '`auto_collected` tinyint(1) DEFAULT ''0'' COMMENT ''Created by Resource Hub automation'' AFTER `source_url`');
CALL gying_add_column_if_missing('resource_link', 'validated_at', '`validated_at` datetime DEFAULT NULL COMMENT ''Last validation time'' AFTER `auto_collected`');
CALL gying_add_column_if_missing('resource_link', 'last_check_error', '`last_check_error` varchar(1000) DEFAULT NULL COMMENT ''Last validation error'' AFTER `validated_at`');
CALL gying_add_index_if_missing('resource_link', 'idx_resource_url_hash', 'INDEX `idx_resource_url_hash` (`movie_id`, `url_hash`)');
CALL gying_add_index_if_missing('resource_link', 'idx_resource_source', 'INDEX `idx_resource_source` (`source`, `source_ref`)');
CALL gying_add_index_if_missing('resource_link', 'idx_resource_validation', 'INDEX `idx_resource_validation` (`link_status`, `validated_at`)');

CREATE TABLE IF NOT EXISTS `resource_hub_task` (
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

CREATE TABLE IF NOT EXISTS `resource_discovery_result` (
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

CREATE TABLE IF NOT EXISTS `quark_transfer_task` (
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

CREATE TABLE IF NOT EXISTS `xunlei_transfer_task` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Xunlei Transfer Tasks';

INSERT INTO sys_config (config_key, config_value, description) VALUES
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
('resource.hub.worker.xunlei_limit', '5', 'Xunlei transfers submitted per worker run'),
('resource.hub.worker.publish_limit', '20', 'Discoveries published per worker run')
ON DUPLICATE KEY UPDATE config_value = config_value;

DROP PROCEDURE IF EXISTS gying_add_column_if_missing;
DROP PROCEDURE IF EXISTS gying_add_index_if_missing;
