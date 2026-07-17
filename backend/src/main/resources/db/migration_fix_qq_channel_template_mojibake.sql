-- Fix mojibake left by earlier QQ channel post template defaults.
UPDATE sys_config
SET config_value = '标题：{{title}}\n链接：{{link}}\n简介：{{intro}}',
    description = 'QQ channel post template',
    updated_at = CURRENT_TIMESTAMP
WHERE config_key = 'qq.channel.auto_post.template'
  AND (
    config_value LIKE '%æ%'
    OR config_value LIKE '%é%'
    OR config_value LIKE '%ç%'
    OR config_value LIKE '%鏍%'
    OR config_value LIKE '%閾%'
    OR config_value LIKE '%绠%'
  );
