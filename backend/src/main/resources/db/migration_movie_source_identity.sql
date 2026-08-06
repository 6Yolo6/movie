CREATE TABLE IF NOT EXISTS `movie_source_identity` (
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
