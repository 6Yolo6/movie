CREATE TABLE IF NOT EXISTS `social_publish_target` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '发布目标主键',
  `platform` varchar(30) NOT NULL COMMENT '平台：QQ_CHANNEL、WEIBO',
  `account_key` varchar(60) NOT NULL COMMENT '独立账号标识',
  `name` varchar(120) NOT NULL COMMENT '后台显示名称',
  `target_ref` varchar(120) NOT NULL DEFAULT '' COMMENT '频道号或平台目标标识',
  `channel_ref` varchar(120) DEFAULT NULL COMMENT 'QQ 版块 ID，留空自动选择全部或帖子广场',
  `enabled` tinyint(1) NOT NULL DEFAULT 1 COMMENT '目标是否启用',
  `auto_post_enabled` tinyint(1) NOT NULL DEFAULT 0 COMMENT '是否启用每日定时发布',
  `schedule_time` varchar(5) NOT NULL DEFAULT '10:00' COMMENT '每日发布时间，HH:mm',
  `posts_per_run` int NOT NULL DEFAULT 1 COMMENT '每次发布条数',
  `post_interval_seconds` int NOT NULL DEFAULT 60 COMMENT '同一批次每条间隔秒数',
  `template` text COMMENT '发布正文模板',
  `last_auto_run_at` datetime DEFAULT NULL COMMENT '最近一次自动调度时间',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_social_publish_target` (`platform`, `account_key`, `target_ref`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='多平台发布目标';

CREATE TABLE IF NOT EXISTS `social_post_log` (
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

INSERT IGNORE INTO social_publish_target
  (platform, account_key, name, target_ref, channel_ref, enabled, auto_post_enabled, schedule_time, posts_per_run, post_interval_seconds, template)
VALUES
  ('QQ_CHANNEL', 'secondary', 'QQ pd14267329', 'pd14267329', NULL, 1, 0, '10:00', 1, 60, '标题：{{title}}\n年份：{{year}}\n类型：{{type}}\n链接：{{link}}\n简介：{{intro}}'),
  ('QQ_CHANNEL', 'secondary', 'QQ pd58754560', 'pd58754560', NULL, 1, 0, '10:10', 1, 60, '标题：{{title}}\n年份：{{year}}\n类型：{{type}}\n链接：{{link}}\n简介：{{intro}}'),
  ('QQ_CHANNEL', 'secondary', 'QQ pd24958827', 'pd24958827', NULL, 1, 0, '10:20', 1, 60, '标题：{{title}}\n年份：{{year}}\n类型：{{type}}\n链接：{{link}}\n简介：{{intro}}'),
  ('QQ_CHANNEL', 'secondary', 'QQ pd66263704', 'pd66263704', NULL, 1, 0, '10:30', 1, 60, '标题：{{title}}\n年份：{{year}}\n类型：{{type}}\n链接：{{link}}\n简介：{{intro}}'),
  ('WEIBO', 'default', '新浪微博', 'default', NULL, 1, 0, '11:00', 1, 60, '{{title}}（{{year}}）\n{{type}}\n{{intro}}\n{{link}}');
