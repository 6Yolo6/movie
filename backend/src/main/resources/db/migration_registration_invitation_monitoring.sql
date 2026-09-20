-- 2026-09-19 注册邮箱、邀请注册与后台监控
ALTER TABLE `sys_user`
  ADD COLUMN IF NOT EXISTS `invited_by_user_id` bigint DEFAULT NULL COMMENT '邀请注册的用户 ID' AFTER `enabled`;

ALTER TABLE `sys_user`
  ADD UNIQUE KEY `uk_sys_user_email` (`email`);

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
