// Durable, per-log-ID publication ownership. Never hold a DB transaction across a provider call.
export const publicationSql = Object.freeze({
  claim: `UPDATE social_post_log SET status = 'PREPARING', error_message = NULL, updated_at = NOW()
          WHERE id = ? AND status IN ('PENDING', 'PREPARE_FAILED')`,
  start: `UPDATE social_post_log SET status = 'PUBLISHING', updated_at = NOW()
          WHERE id = ? AND status = 'PREPARING'`,
  posted: `UPDATE social_post_log SET status = 'POSTED', external_url = ?, error_message = NULL,
           posted_at = NOW(), updated_at = NOW() WHERE id = ? AND status = 'PUBLISHING'`,
  prepareFailed: `UPDATE social_post_log SET status = 'PREPARE_FAILED', error_message = ?, updated_at = NOW()
                  WHERE id = ? AND status = 'PREPARING'`,
  unknown: `UPDATE social_post_log SET status = 'UNKNOWN', error_message = ?,
            external_url = COALESCE(?, external_url), updated_at = NOW()
            WHERE id = ? AND status IN ('PREPARING', 'PUBLISHING')`,
  state: `SELECT id, platform, status, external_url, error_message FROM social_post_log WHERE id = ?`,
});

const retryableStates = new Set(['PENDING', 'PREPARE_FAILED']);
const unknownMessage = 'Publication outcome is unconfirmed. Verify the platform before any retry.';

function view(row, reused) {
  const retryable = retryableStates.has(row.status);
  return { ok: row.status === 'POSTED', logId: Number(row.id), platform: row.platform,
    status: row.status, externalUrl: row.external_url || null, reused, retryable,
    blocked: !retryable && row.status !== 'POSTED' };
}

export function createPublishTaskRunner({ db, work, onPersistenceError = () => {} }) {
  async function state(logId) {
    const [rows] = await db.query(publicationSql.state, [logId]);
    if (!rows.length) throw new Error('Social post log not found');
    return rows[0];
  }

  return async function publish(logId) {
    if (!Number.isSafeInteger(logId) || logId <= 0) throw new Error('Invalid social post log ID');
    // A failed/uncertain claim must never be compensated by resetting someone else's row.
    const [claim] = await db.query(publicationSql.claim, [logId]);
    if (claim.affectedRows !== 1) return view(await state(logId), true);

    let boundaryEntered = false;
    let published;
    const beforeSend = async () => {
      if (boundaryEntered) throw new Error('Publication boundary was already entered');
      // Even a lost DB acknowledgement here is fail-closed, never automatically retried.
      boundaryEntered = true;
      const [started] = await db.query(publicationSql.start, [logId]);
      if (started.affectedRows !== 1) throw new Error('Publication claim is no longer owned');
    };

    try {
      // Providers must await beforeSend immediately before the irreversible request.
      published = await work(logId, beforeSend);
      if (!boundaryEntered) throw new Error('Provider did not enter the publication boundary');
      const [saved] = await db.query(publicationSql.posted, [published.externalUrl || null, logId]);
      if (saved.affectedRows !== 1) throw new Error('Publication result was not persisted');
      return { ok: true, logId, platform: published.platform, externalUrl: published.externalUrl || null,
        status: 'POSTED', reused: false, retryable: false, blocked: false };
    } catch (error) {
      try {
        if (boundaryEntered) {
          await db.query(publicationSql.unknown, [unknownMessage, published?.externalUrl || null, logId]);
        } else {
          // Only failures proven to precede the provider boundary are eligible for a retry.
          const detail = String(error?.message || 'Preparation failed').replace(/\s+/g, ' ').slice(0, 850);
          await db.query(publicationSql.prepareFailed, [`Failed before publication: ${detail}`, logId]);
        }
      } catch (persistenceError) {
        // PREPARING/PUBLISHING remain non-retryable if the database is unavailable.
        onPersistenceError(persistenceError);
      }
      try {
        // A POSTED commit can succeed even if its acknowledgement is lost; don't downgrade it.
        return view(await state(logId), false);
      } catch (persistenceError) {
        onPersistenceError(persistenceError);
        return { ok: false, logId, status: 'UNKNOWN', retryable: false, blocked: true,
          reused: false, message: unknownMessage };
      }
    }
  };
}
