-- Document manual QQ channel post queue status.
ALTER TABLE qq_channel_post_log
  MODIFY COLUMN status varchar(40) NOT NULL COMMENT '发帖状态：PENDING、POSTED、FAILED、SKIPPED';
