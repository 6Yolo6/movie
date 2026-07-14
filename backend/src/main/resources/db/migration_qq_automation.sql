CREATE TABLE IF NOT EXISTS `qq_bot_search_log` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键 ID',
  `user_key` varchar(100) DEFAULT NULL COMMENT 'QQ 用户标识或 OpenClaw senderId',
  `keyword` varchar(255) DEFAULT NULL COMMENT '用户搜索关键词',
  `status` varchar(40) NOT NULL COMMENT '搜索状态：成功、无资源、无元数据、敏感词、限流等',
  `movie_id` varchar(100) DEFAULT NULL COMMENT '命中的影片 ID',
  `resource_count` int DEFAULT 0 COMMENT '本次回复资源数量',
  `reply_preview` varchar(1000) DEFAULT NULL COMMENT '机器人回复内容预览',
  `failure_reason` varchar(1000) DEFAULT NULL COMMENT '失败、拦截或无结果原因',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_qq_bot_search_status` (`status`),
  KEY `idx_qq_bot_search_created` (`created_at`),
  KEY `idx_qq_bot_search_keyword` (`keyword`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='QQ 群机器人搜索记录';

CREATE TABLE IF NOT EXISTS `qq_channel_post_log` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键 ID',
  `resource_link_id` bigint DEFAULT NULL COMMENT '已发布资源链接 ID',
  `movie_id` varchar(100) DEFAULT NULL COMMENT '影片 ID',
  `title` varchar(500) DEFAULT NULL COMMENT '帖子标题',
  `link_url` varchar(1000) DEFAULT NULL COMMENT '发布到频道的资源链接',
  `channel_type` varchar(40) DEFAULT NULL COMMENT '频道内容类型：movie 或 tv',
  `channel_id` varchar(100) DEFAULT NULL COMMENT '腾讯频道版块 ID',
  `status` varchar(40) NOT NULL COMMENT '发帖状态：POSTED、FAILED、SKIPPED',
  `error_message` varchar(1000) DEFAULT NULL COMMENT '发帖失败原因',
  `posted_at` datetime DEFAULT NULL COMMENT '成功发帖时间',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_qq_channel_post_resource` (`resource_link_id`),
  KEY `idx_qq_channel_post_status` (`status`),
  KEY `idx_qq_channel_post_created` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='腾讯频道自动发帖记录';

INSERT INTO sys_config (`config_key`, `config_value`, `description`)
SELECT 'qq.bot.min_keyword_length', '2', 'QQ bot minimum search keyword length'
WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'qq.bot.min_keyword_length');

INSERT INTO sys_config (`config_key`, `config_value`, `description`)
SELECT 'qq.bot.rate_limit_per_minute', '5', 'QQ bot per-user search rate limit'
WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'qq.bot.rate_limit_per_minute');

INSERT INTO sys_config (`config_key`, `config_value`, `description`)
SELECT 'qq.bot.max_results', '3', 'QQ bot maximum reply resources'
WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'qq.bot.max_results');

INSERT INTO sys_config (`config_key`, `config_value`, `description`)
SELECT 'qq.bot.blocked_keywords', '', 'QQ bot blocked search keywords'
WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'qq.bot.blocked_keywords');

INSERT INTO sys_config (`config_key`, `config_value`, `description`)
SELECT 'qq.channel.auto_post.enabled', 'false', 'Enable QQ channel auto posting'
WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'qq.channel.auto_post.enabled');

INSERT INTO sys_config (`config_key`, `config_value`, `description`)
SELECT 'qq.channel.auto_post.interval_minutes', '60', 'QQ channel auto post interval in minutes'
WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'qq.channel.auto_post.interval_minutes');

INSERT INTO sys_config (`config_key`, `config_value`, `description`)
SELECT 'qq.channel.auto_post.max_posts_per_run', '1', 'QQ channel posts per run'
WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'qq.channel.auto_post.max_posts_per_run');

INSERT INTO sys_config (`config_key`, `config_value`, `description`)
SELECT 'qq.channel.auto_post.candidate_limit', '10', 'QQ channel candidate resource limit per run'
WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'qq.channel.auto_post.candidate_limit');

INSERT INTO sys_config (`config_key`, `config_value`, `description`)
SELECT 'qq.channel.guild_id', '', 'QQ channel guild ID'
WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'qq.channel.guild_id');

INSERT INTO sys_config (`config_key`, `config_value`, `description`)
SELECT 'qq.channel.movie_channel_id', '', 'QQ channel movie board/channel ID'
WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'qq.channel.movie_channel_id');

INSERT INTO sys_config (`config_key`, `config_value`, `description`)
SELECT 'qq.channel.tv_channel_id', '', 'QQ channel TV board/channel ID'
WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'qq.channel.tv_channel_id');
