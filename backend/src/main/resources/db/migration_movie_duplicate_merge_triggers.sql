-- Additive canonical-write guards. Verify existing names/bodies before applying.
DELIMITER $$
CREATE TRIGGER gying_merge_baidu_temporary_cleanup_job_insert BEFORE INSERT ON baidu_temporary_cleanup_job
FOR EACH ROW
BEGIN
  DECLARE merge_canonical VARCHAR(64) DEFAULT NULL;
  IF NEW.movie_id IS NOT NULL THEN
    SELECT canonical_movie_id INTO merge_canonical FROM movie_merge_alias
      WHERE source_movie_id = CONVERT(NEW.movie_id USING utf8mb4) COLLATE utf8mb4_bin FOR SHARE;
    IF merge_canonical IS NOT NULL THEN
      SET NEW.movie_id = merge_canonical;
    END IF;
  END IF;
END$$
CREATE TRIGGER gying_merge_baidu_temporary_cleanup_job_update BEFORE UPDATE ON baidu_temporary_cleanup_job
FOR EACH ROW
BEGIN
  DECLARE merge_canonical VARCHAR(64) DEFAULT NULL;
  IF NEW.movie_id IS NOT NULL THEN
    SELECT canonical_movie_id INTO merge_canonical FROM movie_merge_alias
      WHERE source_movie_id = CONVERT(NEW.movie_id USING utf8mb4) COLLATE utf8mb4_bin FOR SHARE;
    IF merge_canonical IS NOT NULL THEN
      SET NEW.movie_id = merge_canonical;
    END IF;
  END IF;
END$$
CREATE TRIGGER gying_merge_baidu_transfer_task_insert BEFORE INSERT ON baidu_transfer_task
FOR EACH ROW
BEGIN
  DECLARE merge_canonical VARCHAR(64) DEFAULT NULL;
  IF NEW.movie_id IS NOT NULL THEN
    SELECT canonical_movie_id INTO merge_canonical FROM movie_merge_alias
      WHERE source_movie_id = CONVERT(NEW.movie_id USING utf8mb4) COLLATE utf8mb4_bin FOR SHARE;
    IF merge_canonical IS NOT NULL THEN
      SET NEW.movie_id = merge_canonical;
    END IF;
  END IF;
END$$
CREATE TRIGGER gying_merge_baidu_transfer_task_update BEFORE UPDATE ON baidu_transfer_task
FOR EACH ROW
BEGIN
  DECLARE merge_canonical VARCHAR(64) DEFAULT NULL;
  IF NEW.movie_id IS NOT NULL THEN
    SELECT canonical_movie_id INTO merge_canonical FROM movie_merge_alias
      WHERE source_movie_id = CONVERT(NEW.movie_id USING utf8mb4) COLLATE utf8mb4_bin FOR SHARE;
    IF merge_canonical IS NOT NULL THEN
      SET NEW.movie_id = merge_canonical;
    END IF;
  END IF;
END$$
CREATE TRIGGER gying_merge_movie_source_identity_insert BEFORE INSERT ON movie_source_identity
FOR EACH ROW
BEGIN
  DECLARE merge_canonical VARCHAR(64) DEFAULT NULL;
  IF NEW.movie_id IS NOT NULL THEN
    SELECT canonical_movie_id INTO merge_canonical FROM movie_merge_alias
      WHERE source_movie_id = CONVERT(NEW.movie_id USING utf8mb4) COLLATE utf8mb4_bin FOR SHARE;
    IF merge_canonical IS NOT NULL THEN
      SET NEW.movie_id = merge_canonical;
    END IF;
  END IF;
END$$
CREATE TRIGGER gying_merge_movie_source_identity_update BEFORE UPDATE ON movie_source_identity
FOR EACH ROW
BEGIN
  DECLARE merge_canonical VARCHAR(64) DEFAULT NULL;
  IF NEW.movie_id IS NOT NULL THEN
    SELECT canonical_movie_id INTO merge_canonical FROM movie_merge_alias
      WHERE source_movie_id = CONVERT(NEW.movie_id USING utf8mb4) COLLATE utf8mb4_bin FOR SHARE;
    IF merge_canonical IS NOT NULL THEN
      SET NEW.movie_id = merge_canonical;
    END IF;
  END IF;
END$$
CREATE TRIGGER gying_merge_qq_bot_search_log_insert BEFORE INSERT ON qq_bot_search_log
FOR EACH ROW
BEGIN
  DECLARE merge_canonical VARCHAR(64) DEFAULT NULL;
  IF NEW.movie_id IS NOT NULL THEN
    SELECT canonical_movie_id INTO merge_canonical FROM movie_merge_alias
      WHERE source_movie_id = CONVERT(NEW.movie_id USING utf8mb4) COLLATE utf8mb4_bin FOR SHARE;
    IF merge_canonical IS NOT NULL THEN
      SET NEW.movie_id = merge_canonical;
    END IF;
  END IF;
END$$
CREATE TRIGGER gying_merge_qq_bot_search_log_update BEFORE UPDATE ON qq_bot_search_log
FOR EACH ROW
BEGIN
  DECLARE merge_canonical VARCHAR(64) DEFAULT NULL;
  IF NEW.movie_id IS NOT NULL THEN
    SELECT canonical_movie_id INTO merge_canonical FROM movie_merge_alias
      WHERE source_movie_id = CONVERT(NEW.movie_id USING utf8mb4) COLLATE utf8mb4_bin FOR SHARE;
    IF merge_canonical IS NOT NULL THEN
      SET NEW.movie_id = merge_canonical;
    END IF;
  END IF;
END$$
CREATE TRIGGER gying_merge_qq_channel_post_log_insert BEFORE INSERT ON qq_channel_post_log
FOR EACH ROW
BEGIN
  DECLARE merge_canonical VARCHAR(64) DEFAULT NULL;
  IF NEW.movie_id IS NOT NULL THEN
    SELECT canonical_movie_id INTO merge_canonical FROM movie_merge_alias
      WHERE source_movie_id = CONVERT(NEW.movie_id USING utf8mb4) COLLATE utf8mb4_bin FOR SHARE;
    IF merge_canonical IS NOT NULL THEN
      SET NEW.movie_id = merge_canonical;
    END IF;
  END IF;
END$$
CREATE TRIGGER gying_merge_qq_channel_post_log_update BEFORE UPDATE ON qq_channel_post_log
FOR EACH ROW
BEGIN
  DECLARE merge_canonical VARCHAR(64) DEFAULT NULL;
  IF NEW.movie_id IS NOT NULL THEN
    SELECT canonical_movie_id INTO merge_canonical FROM movie_merge_alias
      WHERE source_movie_id = CONVERT(NEW.movie_id USING utf8mb4) COLLATE utf8mb4_bin FOR SHARE;
    IF merge_canonical IS NOT NULL THEN
      SET NEW.movie_id = merge_canonical;
    END IF;
  END IF;
END$$
CREATE TRIGGER gying_merge_qq_transfer_cleanup_job_insert BEFORE INSERT ON qq_transfer_cleanup_job
FOR EACH ROW
BEGIN
  DECLARE merge_canonical VARCHAR(64) DEFAULT NULL;
  IF NEW.movie_id IS NOT NULL THEN
    SELECT canonical_movie_id INTO merge_canonical FROM movie_merge_alias
      WHERE source_movie_id = CONVERT(NEW.movie_id USING utf8mb4) COLLATE utf8mb4_bin FOR SHARE;
    IF merge_canonical IS NOT NULL THEN
      SET NEW.movie_id = merge_canonical;
    END IF;
  END IF;
END$$
CREATE TRIGGER gying_merge_qq_transfer_cleanup_job_update BEFORE UPDATE ON qq_transfer_cleanup_job
FOR EACH ROW
BEGIN
  DECLARE merge_canonical VARCHAR(64) DEFAULT NULL;
  IF NEW.movie_id IS NOT NULL THEN
    SELECT canonical_movie_id INTO merge_canonical FROM movie_merge_alias
      WHERE source_movie_id = CONVERT(NEW.movie_id USING utf8mb4) COLLATE utf8mb4_bin FOR SHARE;
    IF merge_canonical IS NOT NULL THEN
      SET NEW.movie_id = merge_canonical;
    END IF;
  END IF;
END$$
CREATE TRIGGER gying_merge_quark_transfer_task_insert BEFORE INSERT ON quark_transfer_task
FOR EACH ROW
BEGIN
  DECLARE merge_canonical VARCHAR(64) DEFAULT NULL;
  IF NEW.movie_id IS NOT NULL THEN
    SELECT canonical_movie_id INTO merge_canonical FROM movie_merge_alias
      WHERE source_movie_id = CONVERT(NEW.movie_id USING utf8mb4) COLLATE utf8mb4_bin FOR SHARE;
    IF merge_canonical IS NOT NULL THEN
      SET NEW.movie_id = merge_canonical;
    END IF;
  END IF;
END$$
CREATE TRIGGER gying_merge_quark_transfer_task_update BEFORE UPDATE ON quark_transfer_task
FOR EACH ROW
BEGIN
  DECLARE merge_canonical VARCHAR(64) DEFAULT NULL;
  IF NEW.movie_id IS NOT NULL THEN
    SELECT canonical_movie_id INTO merge_canonical FROM movie_merge_alias
      WHERE source_movie_id = CONVERT(NEW.movie_id USING utf8mb4) COLLATE utf8mb4_bin FOR SHARE;
    IF merge_canonical IS NOT NULL THEN
      SET NEW.movie_id = merge_canonical;
    END IF;
  END IF;
END$$
CREATE TRIGGER gying_merge_resource_discovery_result_insert BEFORE INSERT ON resource_discovery_result
FOR EACH ROW
BEGIN
  DECLARE merge_canonical VARCHAR(64) DEFAULT NULL;
  IF NEW.movie_id IS NOT NULL THEN
    SELECT canonical_movie_id INTO merge_canonical FROM movie_merge_alias
      WHERE source_movie_id = CONVERT(NEW.movie_id USING utf8mb4) COLLATE utf8mb4_bin FOR SHARE;
    IF merge_canonical IS NOT NULL THEN
      SET NEW.movie_id = merge_canonical;
    END IF;
  END IF;
END$$
CREATE TRIGGER gying_merge_resource_discovery_result_update BEFORE UPDATE ON resource_discovery_result
FOR EACH ROW
BEGIN
  DECLARE merge_canonical VARCHAR(64) DEFAULT NULL;
  IF NEW.movie_id IS NOT NULL THEN
    SELECT canonical_movie_id INTO merge_canonical FROM movie_merge_alias
      WHERE source_movie_id = CONVERT(NEW.movie_id USING utf8mb4) COLLATE utf8mb4_bin FOR SHARE;
    IF merge_canonical IS NOT NULL THEN
      SET NEW.movie_id = merge_canonical;
    END IF;
  END IF;
END$$
CREATE TRIGGER gying_merge_resource_hub_task_insert BEFORE INSERT ON resource_hub_task
FOR EACH ROW
BEGIN
  DECLARE merge_canonical VARCHAR(64) DEFAULT NULL;
  IF NEW.movie_id IS NOT NULL THEN
    SELECT canonical_movie_id INTO merge_canonical FROM movie_merge_alias
      WHERE source_movie_id = CONVERT(NEW.movie_id USING utf8mb4) COLLATE utf8mb4_bin FOR SHARE;
    IF merge_canonical IS NOT NULL THEN
      SET NEW.movie_id = merge_canonical;
    END IF;
  END IF;
  SET merge_canonical = NULL;
  IF NEW.payload IS NOT NULL AND JSON_VALID(NEW.payload) THEN
    IF JSON_TYPE(JSON_EXTRACT(NEW.payload,'$.movieId')) = 'STRING' THEN
      SELECT canonical_movie_id INTO merge_canonical FROM movie_merge_alias
        WHERE source_movie_id = CONVERT(JSON_UNQUOTE(JSON_EXTRACT(NEW.payload,'$.movieId')) USING utf8mb4) COLLATE utf8mb4_bin FOR SHARE;
      IF merge_canonical IS NOT NULL THEN
        SET NEW.payload = JSON_SET(NEW.payload,'$.movieId',merge_canonical);
      END IF;
    END IF;
    SET merge_canonical = NULL;
    SELECT source_movie_id INTO merge_canonical FROM movie_merge_alias
      WHERE INSTR(BINARY JSON_REMOVE(NEW.payload,'$.deferredItems'),BINARY CONCAT('"',source_movie_id,'"')) > 0 LIMIT 1 FOR SHARE;
    IF merge_canonical IS NOT NULL THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'MERGED_MOVIE_PAYLOAD_REQUIRES_REVIEW';
    END IF;
  END IF;
END$$
CREATE TRIGGER gying_merge_resource_hub_task_update BEFORE UPDATE ON resource_hub_task
FOR EACH ROW
BEGIN
  DECLARE merge_canonical VARCHAR(64) DEFAULT NULL;
  IF NEW.movie_id IS NOT NULL THEN
    SELECT canonical_movie_id INTO merge_canonical FROM movie_merge_alias
      WHERE source_movie_id = CONVERT(NEW.movie_id USING utf8mb4) COLLATE utf8mb4_bin FOR SHARE;
    IF merge_canonical IS NOT NULL THEN
      SET NEW.movie_id = merge_canonical;
    END IF;
  END IF;
  SET merge_canonical = NULL;
  IF NEW.payload IS NOT NULL AND JSON_VALID(NEW.payload) THEN
    IF JSON_TYPE(JSON_EXTRACT(NEW.payload,'$.movieId')) = 'STRING' THEN
      SELECT canonical_movie_id INTO merge_canonical FROM movie_merge_alias
        WHERE source_movie_id = CONVERT(JSON_UNQUOTE(JSON_EXTRACT(NEW.payload,'$.movieId')) USING utf8mb4) COLLATE utf8mb4_bin FOR SHARE;
      IF merge_canonical IS NOT NULL THEN
        SET NEW.payload = JSON_SET(NEW.payload,'$.movieId',merge_canonical);
      END IF;
    END IF;
    SET merge_canonical = NULL;
    SELECT source_movie_id INTO merge_canonical FROM movie_merge_alias
      WHERE INSTR(BINARY JSON_REMOVE(NEW.payload,'$.deferredItems'),BINARY CONCAT('"',source_movie_id,'"')) > 0 LIMIT 1 FOR SHARE;
    IF merge_canonical IS NOT NULL THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'MERGED_MOVIE_PAYLOAD_REQUIRES_REVIEW';
    END IF;
  END IF;
END$$
CREATE TRIGGER gying_merge_resource_link_insert BEFORE INSERT ON resource_link
FOR EACH ROW
BEGIN
  DECLARE merge_canonical VARCHAR(64) DEFAULT NULL;
  IF NEW.movie_id IS NOT NULL THEN
    SELECT canonical_movie_id INTO merge_canonical FROM movie_merge_alias
      WHERE source_movie_id = CONVERT(NEW.movie_id USING utf8mb4) COLLATE utf8mb4_bin FOR SHARE;
    IF merge_canonical IS NOT NULL THEN
      SET NEW.movie_id = merge_canonical;
    END IF;
  END IF;
END$$
CREATE TRIGGER gying_merge_resource_link_update BEFORE UPDATE ON resource_link
FOR EACH ROW
BEGIN
  DECLARE merge_canonical VARCHAR(64) DEFAULT NULL;
  IF NEW.movie_id IS NOT NULL THEN
    SELECT canonical_movie_id INTO merge_canonical FROM movie_merge_alias
      WHERE source_movie_id = CONVERT(NEW.movie_id USING utf8mb4) COLLATE utf8mb4_bin FOR SHARE;
    IF merge_canonical IS NOT NULL THEN
      SET NEW.movie_id = merge_canonical;
    END IF;
  END IF;
END$$
CREATE TRIGGER gying_merge_resource_operation_log_insert BEFORE INSERT ON resource_operation_log
FOR EACH ROW
BEGIN
  DECLARE merge_canonical VARCHAR(64) DEFAULT NULL;
  IF NEW.movie_id IS NOT NULL THEN
    SELECT canonical_movie_id INTO merge_canonical FROM movie_merge_alias
      WHERE source_movie_id = CONVERT(NEW.movie_id USING utf8mb4) COLLATE utf8mb4_bin FOR SHARE;
    IF merge_canonical IS NOT NULL THEN
      SET NEW.movie_id = merge_canonical;
    END IF;
  END IF;
END$$
CREATE TRIGGER gying_merge_resource_operation_log_update BEFORE UPDATE ON resource_operation_log
FOR EACH ROW
BEGIN
  DECLARE merge_canonical VARCHAR(64) DEFAULT NULL;
  IF NEW.movie_id IS NOT NULL THEN
    SELECT canonical_movie_id INTO merge_canonical FROM movie_merge_alias
      WHERE source_movie_id = CONVERT(NEW.movie_id USING utf8mb4) COLLATE utf8mb4_bin FOR SHARE;
    IF merge_canonical IS NOT NULL THEN
      SET NEW.movie_id = merge_canonical;
    END IF;
  END IF;
END$$
CREATE TRIGGER gying_merge_social_post_log_insert BEFORE INSERT ON social_post_log
FOR EACH ROW
BEGIN
  DECLARE merge_canonical VARCHAR(64) DEFAULT NULL;
  IF NEW.movie_id IS NOT NULL THEN
    SELECT canonical_movie_id INTO merge_canonical FROM movie_merge_alias
      WHERE source_movie_id = CONVERT(NEW.movie_id USING utf8mb4) COLLATE utf8mb4_bin FOR SHARE;
    IF merge_canonical IS NOT NULL THEN
      SET NEW.movie_id = merge_canonical;
    END IF;
  END IF;
END$$
CREATE TRIGGER gying_merge_social_post_log_update BEFORE UPDATE ON social_post_log
FOR EACH ROW
BEGIN
  DECLARE merge_canonical VARCHAR(64) DEFAULT NULL;
  IF NEW.movie_id IS NOT NULL THEN
    SELECT canonical_movie_id INTO merge_canonical FROM movie_merge_alias
      WHERE source_movie_id = CONVERT(NEW.movie_id USING utf8mb4) COLLATE utf8mb4_bin FOR SHARE;
    IF merge_canonical IS NOT NULL THEN
      SET NEW.movie_id = merge_canonical;
    END IF;
  END IF;
END$$
CREATE TRIGGER gying_merge_user_favorite_insert BEFORE INSERT ON user_favorite
FOR EACH ROW
BEGIN
  DECLARE merge_canonical VARCHAR(64) DEFAULT NULL;
  IF NEW.movie_id IS NOT NULL THEN
    SELECT canonical_movie_id INTO merge_canonical FROM movie_merge_alias
      WHERE source_movie_id = CONVERT(NEW.movie_id USING utf8mb4) COLLATE utf8mb4_bin FOR SHARE;
    IF merge_canonical IS NOT NULL THEN
      SET NEW.movie_id = merge_canonical;
    END IF;
  END IF;
END$$
CREATE TRIGGER gying_merge_user_favorite_update BEFORE UPDATE ON user_favorite
FOR EACH ROW
BEGIN
  DECLARE merge_canonical VARCHAR(64) DEFAULT NULL;
  IF NEW.movie_id IS NOT NULL THEN
    SELECT canonical_movie_id INTO merge_canonical FROM movie_merge_alias
      WHERE source_movie_id = CONVERT(NEW.movie_id USING utf8mb4) COLLATE utf8mb4_bin FOR SHARE;
    IF merge_canonical IS NOT NULL THEN
      SET NEW.movie_id = merge_canonical;
    END IF;
  END IF;
END$$
CREATE TRIGGER gying_merge_xunlei_transfer_task_insert BEFORE INSERT ON xunlei_transfer_task
FOR EACH ROW
BEGIN
  DECLARE merge_canonical VARCHAR(64) DEFAULT NULL;
  IF NEW.movie_id IS NOT NULL THEN
    SELECT canonical_movie_id INTO merge_canonical FROM movie_merge_alias
      WHERE source_movie_id = CONVERT(NEW.movie_id USING utf8mb4) COLLATE utf8mb4_bin FOR SHARE;
    IF merge_canonical IS NOT NULL THEN
      SET NEW.movie_id = merge_canonical;
    END IF;
  END IF;
END$$
CREATE TRIGGER gying_merge_xunlei_transfer_task_update BEFORE UPDATE ON xunlei_transfer_task
FOR EACH ROW
BEGIN
  DECLARE merge_canonical VARCHAR(64) DEFAULT NULL;
  IF NEW.movie_id IS NOT NULL THEN
    SELECT canonical_movie_id INTO merge_canonical FROM movie_merge_alias
      WHERE source_movie_id = CONVERT(NEW.movie_id USING utf8mb4) COLLATE utf8mb4_bin FOR SHARE;
    IF merge_canonical IS NOT NULL THEN
      SET NEW.movie_id = merge_canonical;
    END IF;
  END IF;
END$$
CREATE TRIGGER gying_merge_comment_insert BEFORE INSERT ON comment
FOR EACH ROW
BEGIN
  DECLARE merge_canonical VARCHAR(64) DEFAULT NULL;
  IF (NEW.comment_type = 'GENERAL' OR NEW.comment_type IS NULL) AND NEW.relate_id IS NOT NULL THEN
    SELECT canonical_movie_id INTO merge_canonical FROM movie_merge_alias
      WHERE source_movie_id = CONVERT(NEW.relate_id USING utf8mb4) COLLATE utf8mb4_bin FOR SHARE;
    IF merge_canonical IS NOT NULL THEN
      SET NEW.relate_id = merge_canonical;
    END IF;
  END IF;
END$$
CREATE TRIGGER gying_merge_comment_update BEFORE UPDATE ON comment
FOR EACH ROW
BEGIN
  DECLARE merge_canonical VARCHAR(64) DEFAULT NULL;
  IF (NEW.comment_type = 'GENERAL' OR NEW.comment_type IS NULL) AND NEW.relate_id IS NOT NULL THEN
    SELECT canonical_movie_id INTO merge_canonical FROM movie_merge_alias
      WHERE source_movie_id = CONVERT(NEW.relate_id USING utf8mb4) COLLATE utf8mb4_bin FOR SHARE;
    IF merge_canonical IS NOT NULL THEN
      SET NEW.relate_id = merge_canonical;
    END IF;
  END IF;
END$$
CREATE TRIGGER gying_merge_user_notification_insert BEFORE INSERT ON user_notification
FOR EACH ROW
BEGIN
  DECLARE merge_canonical VARCHAR(64) DEFAULT NULL;
  IF NEW.target_type = 'MOVIE' AND NEW.target_id IS NOT NULL THEN
    SELECT canonical_movie_id INTO merge_canonical FROM movie_merge_alias
      WHERE source_movie_id = CONVERT(NEW.target_id USING utf8mb4) COLLATE utf8mb4_bin FOR SHARE;
    IF merge_canonical IS NOT NULL THEN
      SET NEW.target_id = merge_canonical;
    END IF;
  END IF;
END$$
CREATE TRIGGER gying_merge_user_notification_update BEFORE UPDATE ON user_notification
FOR EACH ROW
BEGIN
  DECLARE merge_canonical VARCHAR(64) DEFAULT NULL;
  IF NEW.target_type = 'MOVIE' AND NEW.target_id IS NOT NULL THEN
    SELECT canonical_movie_id INTO merge_canonical FROM movie_merge_alias
      WHERE source_movie_id = CONVERT(NEW.target_id USING utf8mb4) COLLATE utf8mb4_bin FOR SHARE;
    IF merge_canonical IS NOT NULL THEN
      SET NEW.target_id = merge_canonical;
    END IF;
  END IF;
END$$
CREATE TRIGGER gying_merge_movie_metadata_insert BEFORE INSERT ON movie_metadata
FOR EACH ROW
BEGIN
  DECLARE merge_canonical VARCHAR(64) DEFAULT NULL;
  IF NEW.id IS NOT NULL THEN
    SELECT canonical_movie_id INTO merge_canonical FROM movie_merge_alias
      WHERE source_movie_id = CONVERT(NEW.id USING utf8mb4) COLLATE utf8mb4_bin FOR SHARE;
    IF merge_canonical IS NOT NULL THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'MERGED_MOVIE_ID_USE_CANONICAL';
    END IF;
  END IF;
END$$
CREATE TRIGGER gying_merge_movie_metadata_update BEFORE UPDATE ON movie_metadata
FOR EACH ROW
BEGIN
  DECLARE merge_canonical VARCHAR(64) DEFAULT NULL;
  IF NEW.id IS NOT NULL THEN
    SELECT canonical_movie_id INTO merge_canonical FROM movie_merge_alias
      WHERE source_movie_id = CONVERT(NEW.id USING utf8mb4) COLLATE utf8mb4_bin FOR SHARE;
    IF merge_canonical IS NOT NULL THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'MERGED_MOVIE_ID_USE_CANONICAL';
    END IF;
  END IF;
END$$
DELIMITER ;
