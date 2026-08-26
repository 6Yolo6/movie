UPDATE resource_link rl
JOIN resource_discovery_result dr
  ON dr.resource_link_id = rl.id
 AND dr.share_url = rl.url
 AND dr.status = 'SAVED'
JOIN quark_transfer_task qt
  ON qt.discovery_result_id = dr.id
 AND qt.share_url = rl.url
 AND qt.saved_path IS NOT NULL
SET rl.source = 'GYING_PUBLISHED',
    rl.updated_at = NOW()
WHERE rl.source = 'GYING'
  AND rl.provider = 'QUARK'
  AND rl.status = 'ACTIVE'
  AND rl.deleted_at IS NULL;
