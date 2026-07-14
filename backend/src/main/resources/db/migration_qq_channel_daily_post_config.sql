-- QQ channel daily auto-post settings.
INSERT INTO sys_config (config_key, config_value, description)
SELECT 'qq.channel.auto_post.daily_time', '09:00', 'QQ channel daily post time HH:mm'
WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'qq.channel.auto_post.daily_time');

INSERT INTO sys_config (config_key, config_value, description)
SELECT 'qq.channel.auto_post.post_total', COALESCE((SELECT config_value FROM sys_config s WHERE s.config_key = 'qq.channel.auto_post.max_posts_per_run' LIMIT 1), '1'), 'QQ channel total posts per day'
WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'qq.channel.auto_post.post_total');

INSERT INTO sys_config (config_key, config_value, description)
SELECT 'qq.channel.auto_post.post_interval_seconds', '60', 'QQ channel interval seconds between posts'
WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'qq.channel.auto_post.post_interval_seconds');

INSERT INTO sys_config (config_key, config_value, description)
SELECT 'qq.channel.auto_post.template', '标题：{{title}}\n链接：{{link}}\n简介：{{intro}}', 'QQ channel post template'
WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'qq.channel.auto_post.template');
