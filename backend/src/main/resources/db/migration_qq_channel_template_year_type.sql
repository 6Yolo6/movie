UPDATE sys_config
SET config_value = '标题：{{title}}\n年份：{{year}}\n类型：{{type}}\n链接：{{link}}\n简介：{{intro}}',
    description = 'QQ channel post template'
WHERE config_key = 'qq.channel.auto_post.template'
  AND config_value = '标题：{{title}}\n链接：{{link}}\n简介：{{intro}}';
