-- Additive duplicate-review schema. No existing business table or row is removed.
CREATE TABLE IF NOT EXISTS movie_duplicate_review (
  id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
  actor_id BIGINT NOT NULL,
  plan_json LONGTEXT NOT NULL,
  fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  eligible BOOLEAN NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'PREVIEWED',
  idempotency_key VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
  result_json LONGTEXT NULL,
  created_at DATETIME(6) NOT NULL,
  expires_at DATETIME(6) NOT NULL,
  completed_at DATETIME(6) NULL,
  UNIQUE KEY uk_duplicate_actor_key(actor_id,idempotency_key),
  KEY idx_duplicate_actor_created(actor_id,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE IF NOT EXISTS movie_merge_alias (
  source_movie_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin PRIMARY KEY,
  canonical_movie_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  review_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  actor_id BIGINT NOT NULL,
  created_at DATETIME(6) NOT NULL,
  KEY idx_merge_canonical(canonical_movie_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE IF NOT EXISTS movie_merge_favorite_history (
  review_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  original_favorite_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  original_movie_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  canonical_movie_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  original_created_at DATETIME NULL,
  retained_favorite_id BIGINT NOT NULL,
  recorded_at DATETIME(6) NOT NULL,
  PRIMARY KEY(review_id,original_favorite_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE IF NOT EXISTS movie_merge_reference_history (
  review_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  reference_table VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  reference_row_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  original_movie_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  canonical_movie_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  original_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  recorded_at DATETIME(6) NOT NULL,
  PRIMARY KEY(review_id,reference_table,reference_row_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
