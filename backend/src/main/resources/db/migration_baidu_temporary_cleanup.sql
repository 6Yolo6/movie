-- 百度QQ与网站搜索临时目录独立清理；不改写旧任务，不执行全局回收站清空。
CREATE TABLE IF NOT EXISTS baidu_temporary_cleanup_job (
  id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '临时清理任务ID',
  transfer_task_id BIGINT NOT NULL COMMENT '本系统新临时百度转存任务ID',
  scope_key VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '不可变临时请求范围',
  movie_id VARCHAR(64) NOT NULL COMMENT '关联片目，仅核对不批量清理',
  discovery_result_id BIGINT NOT NULL COMMENT '独立临时发现记录ID',
  resource_link_id BIGINT NULL COMMENT '本次临时资源链接ID',
  original_url VARCHAR(1000) COLLATE utf8mb4_bin NOT NULL COMMENT '原始来源地址，用于关联核对',
  original_url_hash CHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '原始来源哈希',
  share_url_hash CHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '首次确认自有分享哈希',
  account_uid VARCHAR(30) COLLATE utf8mb4_bin NOT NULL COMMENT '首次确认百度帐号UID',
  target_path VARCHAR(300) COLLATE utf8mb4_bin NOT NULL COMMENT '精确临时目录路径，禁止别名',
  directory_id VARCHAR(30) COLLATE utf8mb4_bin NOT NULL COMMENT '首次确认目录唯一标识',
  plan_json LONGTEXT NOT NULL COMMENT '首次确认执行计划，不含认证材料',
  evidence_json LONGTEXT NOT NULL COMMENT '首次确认全树路径大小类型标识与可用摘要证据',
  recycle_evidence LONGTEXT NULL COMMENT '已核验的精确回收站目录标识路径与帐号',
  delete_mode VARCHAR(20) NOT NULL DEFAULT 'PERMANENT' COMMENT '仅精确临时目录彻底删除',
  status VARCHAR(32) NOT NULL DEFAULT 'PENDING' COMMENT '独立回收与彻底删除意图状态，未知只读对账',
  attempts INT NOT NULL DEFAULT 0 COMMENT '租约领取次数，仅未提交读失败允许有界重试',
  last_error VARCHAR(100) NULL COMMENT '固定错误码，不含上游响应或敏感信息',
  lease_owner VARCHAR(36) COLLATE utf8mb4_bin NULL COMMENT '独占租约持有者',
  lease_until DATETIME(6) NULL COMMENT '租约到期时间',
  share_confirmed_at DATETIME(6) NOT NULL COMMENT '首次确认自有分享时间，不可延长',
  delete_after DATETIME(6) NOT NULL COMMENT '首次确认时间加任务创建时冻结的保留分钟数',
  finished_at DATETIME(6) NULL COMMENT '精确回收站对象消失及本地核对完成时间',
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间',
  UNIQUE KEY uk_baidu_temporary_cleanup_task (transfer_task_id),
  KEY idx_baidu_temporary_cleanup_due (status,delete_after,lease_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='百度搜索临时目录精确彻底清理任务';

INSERT INTO sys_config (config_key,config_value,description) VALUES
('qq.bot.transfer_cleanup.baidu_root','/GYing QQ Temp','QQ与网站百度搜索临时转存专用根目录'),
('qq.bot.transfer_cleanup.baidu_mode','PERMANENT','百度新临时任务清理模式：彻底删除无法恢复，仅本任务目录')
ON DUPLICATE KEY UPDATE description=VALUES(description);
