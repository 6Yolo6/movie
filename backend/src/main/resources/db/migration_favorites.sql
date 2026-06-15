-- ============================================================
-- Feature: Favorites & Popularity
-- ============================================================

-- 1. New table: user_favorite
CREATE TABLE IF NOT EXISTS `user_favorite` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `movie_id` varchar(64) NOT NULL,
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_movie` (`user_id`, `movie_id`),
  KEY `idx_movie_id` (`movie_id`),
  KEY `idx_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='User Favorites';

-- 2. Add popularity column to movie_metadata
ALTER TABLE `movie_metadata` ADD COLUMN `popularity` int DEFAULT 0 COMMENT 'Popularity score' AFTER `status`;
ALTER TABLE `movie_metadata` ADD INDEX `idx_popularity` (`popularity`);
