-- QQ 群临时转存清理任务：只允许清理带 QQ_BOT 标记且位于专用临时根目录的文件。
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

INSERT INTO sys_config (`config_key`, `config_value`, `description`)
SELECT 'qq.bot.transfer_cleanup.enabled', 'true', '是否自动清理QQ群搜索产生的临时转存文件'
WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'qq.bot.transfer_cleanup.enabled');

INSERT INTO sys_config (`config_key`, `config_value`, `description`)
SELECT 'qq.bot.transfer_cleanup.delay_minutes', '10', 'QQ群临时转存成功后的保留时间（分钟）'
WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'qq.bot.transfer_cleanup.delay_minutes');

INSERT INTO sys_config (`config_key`, `config_value`, `description`)
SELECT 'qq.bot.transfer_cleanup.quark_root', '/GYing QQ Temp', 'QQ群夸克临时转存专用根目录'
WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'qq.bot.transfer_cleanup.quark_root');

INSERT INTO sys_config (`config_key`, `config_value`, `description`)
SELECT 'qq.bot.transfer_cleanup.xunlei_root', '/影视剧资源分享(先转存后再查看)/GYing QQ Temp', 'QQ群迅雷临时转存专用根目录'
WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'qq.bot.transfer_cleanup.xunlei_root');

-- 将系统设置页已有英文说明更新为中文；不修改配置值。
UPDATE sys_config SET description = '是否允许访客自行注册账号；关闭后仅管理员可创建用户', updated_at = CURRENT_TIMESTAMP WHERE config_key = 'auth.register.enabled';
UPDATE sys_config SET description = '用户提交的资源是否需要管理员审核后才公开', updated_at = CURRENT_TIMESTAMP WHERE config_key = 'resource.audit.enabled';
UPDATE sys_config SET description = '每个有发布权限的账号最多可保留的有效资源数量', updated_at = CURRENT_TIMESTAMP WHERE config_key = 'resource.max.per.user';
UPDATE sys_config SET description = '同一资源达到该举报次数后标记为疑似失效', updated_at = CURRENT_TIMESTAMP WHERE config_key = 'resource.report.threshold';
UPDATE sys_config SET description = '同一发布账号两次提交资源之间的最短间隔（秒）', updated_at = CURRENT_TIMESTAMP WHERE config_key = 'resource.submit.interval.seconds';
UPDATE sys_config SET description = '资源标题表单可一键插入的快捷参数，支持逗号或换行分隔', updated_at = CURRENT_TIMESTAMP WHERE config_key = 'resource.form.quick_params';
UPDATE sys_config SET description = '影视资源中心总开关', updated_at = CURRENT_TIMESTAMP WHERE config_key = 'resource.hub.enabled';
UPDATE sys_config SET description = '影视资源中心自动入库资源是否直接通过审核', updated_at = CURRENT_TIMESTAMP WHERE config_key = 'resource.hub.auto_approve';
UPDATE sys_config SET description = '单个资源发现任务允许重试的最大次数', updated_at = CURRENT_TIMESTAMP WHERE config_key = 'resource.hub.discovery.max_attempts';
UPDATE sys_config SET description = '是否定期检查已入库网盘链接的有效性', updated_at = CURRENT_TIMESTAMP WHERE config_key = 'resource.hub.validation.enabled';
UPDATE sys_config SET description = '自动发现资源时是否优先从 GYING 获取候选', updated_at = CURRENT_TIMESTAMP WHERE config_key = 'resource.hub.gying.discovery_enabled';
UPDATE sys_config SET description = '是否按计划从 GYING 自动同步影片元数据', updated_at = CURRENT_TIMESTAMP WHERE config_key = 'resource.hub.gying.auto_sync_enabled';
UPDATE sys_config SET description = 'GYING 自动同步任务之间的最小间隔（小时）', updated_at = CURRENT_TIMESTAMP WHERE config_key = 'resource.hub.gying.auto_sync_interval_hours';
UPDATE sys_config SET description = '每轮 GYING 自动同步最多处理的影片数', updated_at = CURRENT_TIMESTAMP WHERE config_key = 'resource.hub.gying.auto_sync_max_items';
UPDATE sys_config SET description = 'GYING 自动同步读取的目录页码', updated_at = CURRENT_TIMESTAMP WHERE config_key = 'resource.hub.gying.auto_sync_page';
UPDATE sys_config SET description = 'GYING 自动同步的数据源类型列表', updated_at = CURRENT_TIMESTAMP WHERE config_key = 'resource.hub.gying.auto_sync_sources';
UPDATE sys_config SET description = '是否按计划从 TMDB 自动同步影片元数据', updated_at = CURRENT_TIMESTAMP WHERE config_key = 'resource.hub.tmdb.auto_sync_enabled';
UPDATE sys_config SET description = 'TMDB 自动同步任务之间的最小间隔（小时）', updated_at = CURRENT_TIMESTAMP WHERE config_key = 'resource.hub.tmdb.auto_sync_interval_hours';
UPDATE sys_config SET description = '每轮 TMDB 自动同步最多处理的影片数', updated_at = CURRENT_TIMESTAMP WHERE config_key = 'resource.hub.tmdb.auto_sync_max_items';
UPDATE sys_config SET description = 'TMDB 自动同步读取的目录页码', updated_at = CURRENT_TIMESTAMP WHERE config_key = 'resource.hub.tmdb.auto_sync_page';
UPDATE sys_config SET description = 'TMDB 自动同步的数据源类型列表', updated_at = CURRENT_TIMESTAMP WHERE config_key = 'resource.hub.tmdb.auto_sync_sources';
UPDATE sys_config SET description = 'TMDB 同步影片后是否自动创建资源发现任务', updated_at = CURRENT_TIMESTAMP WHERE config_key = 'resource.hub.tmdb.auto_discovery_enabled';
UPDATE sys_config SET description = '同一影片再次自动发现资源前的冷却时间（小时）', updated_at = CURRENT_TIMESTAMP WHERE config_key = 'resource.hub.tmdb.discovery_cooldown_hours';
UPDATE sys_config SET description = '单次 PanSou 资源发现最多保留的候选数量', updated_at = CURRENT_TIMESTAMP WHERE config_key = 'resource.hub.tmdb.discovery_max_results';
UPDATE sys_config SET description = '影视资源中心后台 Worker 开关', updated_at = CURRENT_TIMESTAMP WHERE config_key = 'resource.hub.worker.enabled';
UPDATE sys_config SET description = 'Worker 每轮最多执行的资源发现任务数', updated_at = CURRENT_TIMESTAMP WHERE config_key = 'resource.hub.worker.task_limit';
UPDATE sys_config SET description = 'Worker 每轮最多提交的夸克转存任务数', updated_at = CURRENT_TIMESTAMP WHERE config_key = 'resource.hub.worker.quark_limit';
UPDATE sys_config SET description = 'Worker 每轮最多提交的迅雷转存任务数', updated_at = CURRENT_TIMESTAMP WHERE config_key = 'resource.hub.worker.xunlei_limit';
UPDATE sys_config SET description = 'Worker 每轮最多发布到正式资源库的发现结果数', updated_at = CURRENT_TIMESTAMP WHERE config_key = 'resource.hub.worker.publish_limit';
UPDATE sys_config SET description = '是否启用已发现但未完成转存资源的定时重试', updated_at = CURRENT_TIMESTAMP WHERE config_key = 'resource.hub.worker.discovered_retry_enabled';
UPDATE sys_config SET description = '已发现资源定时重试的 Cron 表达式', updated_at = CURRENT_TIMESTAMP WHERE config_key = 'resource.hub.worker.discovered_retry_cron';
UPDATE sys_config SET description = '批量重试每条资源之间的等待时间（毫秒）', updated_at = CURRENT_TIMESTAMP WHERE config_key = 'resource.hub.worker.discovered_retry_delay_ms';
UPDATE sys_config SET description = '每轮定时重试最多处理的发现结果数', updated_at = CURRENT_TIMESTAMP WHERE config_key = 'resource.hub.worker.discovered_retry_limit';
UPDATE sys_config SET description = 'QQ群机器人接受的最短搜索关键词字数', updated_at = CURRENT_TIMESTAMP WHERE config_key = 'qq.bot.min_keyword_length';
UPDATE sys_config SET description = '每个群成员每分钟最多可发起的搜索次数', updated_at = CURRENT_TIMESTAMP WHERE config_key = 'qq.bot.rate_limit_per_minute';
UPDATE sys_config SET description = 'QQ群机器人单次回复展示的资源候选数量', updated_at = CURRENT_TIMESTAMP WHERE config_key = 'qq.bot.max_results';
UPDATE sys_config SET description = 'QQ群机器人拒绝搜索的关键词', updated_at = CURRENT_TIMESTAMP WHERE config_key = 'qq.bot.blocked_keywords';
UPDATE sys_config SET description = '是否开启QQ群每日影片推荐', updated_at = CURRENT_TIMESTAMP WHERE config_key = 'qq.bot.daily_recommendation.enabled';
UPDATE sys_config SET description = 'QQ群每日推荐执行时间（HH:mm）', updated_at = CURRENT_TIMESTAMP WHERE config_key = 'qq.bot.daily_recommendation.time';
UPDATE sys_config SET description = '每个群每天推荐的影片数量', updated_at = CURRENT_TIMESTAMP WHERE config_key = 'qq.bot.daily_recommendation.count';
UPDATE sys_config SET description = '接收每日推荐的 QQ 群号', updated_at = CURRENT_TIMESTAMP WHERE config_key = 'qq.bot.daily_recommendation.group_ids';
UPDATE sys_config SET description = 'QQ群每日推荐消息模板', updated_at = CURRENT_TIMESTAMP WHERE config_key = 'qq.bot.daily_recommendation.template';
UPDATE sys_config SET description = '是否开启 QQ 频道自动发布', updated_at = CURRENT_TIMESTAMP WHERE config_key = 'qq.channel.auto_post.enabled';
UPDATE sys_config SET description = 'QQ 频道自动发布批次间隔（分钟）', updated_at = CURRENT_TIMESTAMP WHERE config_key = 'qq.channel.auto_post.interval_minutes';
UPDATE sys_config SET description = 'QQ 频道每轮最多发布的帖子数', updated_at = CURRENT_TIMESTAMP WHERE config_key = 'qq.channel.auto_post.max_posts_per_run';
UPDATE sys_config SET description = 'QQ 频道每日自动发布开始时间（HH:mm）', updated_at = CURRENT_TIMESTAMP WHERE config_key = 'qq.channel.auto_post.daily_time';
UPDATE sys_config SET description = 'QQ 频道每天计划发布的帖子总数', updated_at = CURRENT_TIMESTAMP WHERE config_key = 'qq.channel.auto_post.post_total';
UPDATE sys_config SET description = 'QQ 频道连续两篇帖子之间的等待时间（秒）', updated_at = CURRENT_TIMESTAMP WHERE config_key = 'qq.channel.auto_post.post_interval_seconds';
UPDATE sys_config SET description = 'QQ 频道帖子正文模板', updated_at = CURRENT_TIMESTAMP WHERE config_key = 'qq.channel.auto_post.template';
UPDATE sys_config SET description = 'QQ 频道每轮选取的候选资源数量上限', updated_at = CURRENT_TIMESTAMP WHERE config_key = 'qq.channel.auto_post.candidate_limit';
UPDATE sys_config SET description = '用于自动发布的 QQ 频道 ID', updated_at = CURRENT_TIMESTAMP WHERE config_key = 'qq.channel.guild_id';
UPDATE sys_config SET description = '电影内容发布到的 QQ 子频道 ID', updated_at = CURRENT_TIMESTAMP WHERE config_key = 'qq.channel.movie_channel_id';
UPDATE sys_config SET description = '剧集和动漫内容发布到的 QQ 子频道 ID', updated_at = CURRENT_TIMESTAMP WHERE config_key = 'qq.channel.tv_channel_id';

UPDATE sys_config
SET description = '指定 QQ 群每日推荐最近一次成功执行日期，由系统自动维护', updated_at = CURRENT_TIMESTAMP
WHERE config_key LIKE 'qq.bot.daily_recommendation.last_run.%';
