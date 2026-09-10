-- Resource submission form configuration.
INSERT INTO sys_config (config_key, config_value, description) VALUES
('resource.form.quick_params', '[影片名],REMUX,4K/2160P,1080P,720P,中英字幕,60帧,简体字幕,120帧,HDR杜比视界,繁体字幕,简繁字幕,杜比全景声,H264,H265,AV1,WEB-DL,BluRay', 'Resource form quick title parameters, comma or newline separated')
ON DUPLICATE KEY UPDATE config_value = config_value;
