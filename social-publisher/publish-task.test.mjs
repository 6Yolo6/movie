import assert from 'node:assert/strict';
import test from 'node:test';
import { createPublishTaskRunner, publicationSql as sql } from './publish-task.mjs';

const result = { platform: 'WEIBO', externalUrl: 'https://example.test/posted/1' };
const deferred = () => { let resolve; const promise = new Promise(done => { resolve = done; }); return { promise, resolve }; };

function database(status = 'PENDING') {
  const db = { row: { id: 1, platform: 'WEIBO', status, external_url: null, error_message: null },
    history: [], before: () => {}, after: () => {} };
  db.query = async (query, params) => {
    db.history.push({ query, params });
    db.before(query, params);
    let changed = 0;
    const row = db.row;
    if (query === sql.state) return [row ? [{ ...row }] : []];
    if (query === sql.claim && row && ['PENDING', 'PREPARE_FAILED'].includes(row.status)) {
      row.status = 'PREPARING'; row.error_message = null; changed = 1;
    } else if (query === sql.start && row?.status === 'PREPARING') {
      row.status = 'PUBLISHING'; changed = 1;
    } else if (query === sql.posted && row?.status === 'PUBLISHING') {
      row.status = 'POSTED'; row.external_url = params[0]; row.error_message = null; changed = 1;
    } else if (query === sql.prepareFailed && row?.status === 'PREPARING') {
      row.status = 'PREPARE_FAILED'; row.error_message = params[0]; changed = 1;
    } else if (query === sql.unknown && ['PREPARING', 'PUBLISHING'].includes(row?.status)) {
      row.status = 'UNKNOWN'; row.error_message = params[0]; row.external_url = params[1] || row.external_url; changed = 1;
    }
    db.after(query, params);
    return [{ affectedRows: changed }];
  };
  return db;
}

const successfulWork = async (_id, beforeSend) => { await beforeSend(); return result; };

test('forty independent runner instances share one durable claim and one submission', async () => {
  const db = database(), release = deferred(), entered = deferred();
  let submissions = 0;
  const work = async (id, beforeSend) => {
    entered.resolve(); await release.promise;
    assert.equal(id, 1); await beforeSend();
    assert.equal(db.row.status, 'PUBLISHING'); submissions++;
    return result;
  };
  const owner = createPublishTaskRunner({ db, work })(1);
  await entered.promise;
  const duplicates = await Promise.all(Array.from({ length: 39 }, () => createPublishTaskRunner({ db, work })(1)));
  assert.ok(duplicates.every(value => value.blocked && value.status === 'PREPARING' && value.reused));
  release.resolve();
  assert.equal((await owner).status, 'POSTED');
  assert.equal(submissions, 1);
  assert.equal(db.row.status, 'POSTED');
  const replay = await createPublishTaskRunner({ db, work })(1);
  assert.equal(replay.status, 'POSTED'); assert.equal(replay.externalUrl, result.externalUrl);
  assert.equal(replay.reused, true); assert.equal(submissions, 1);
});

test('only failures before the submission boundary become safely retryable', async () => {
  const db = database();
  const failed = await createPublishTaskRunner({ db, work: async () => { throw new Error('Missing poster'); } })(1);
  assert.equal(failed.status, 'PREPARE_FAILED'); assert.equal(failed.retryable, true);
  assert.ok(!db.history.some(entry => entry.query === sql.start));
  assert.equal((await createPublishTaskRunner({ db, work: successfulWork })(1)).status, 'POSTED');
});

test('an accepted request followed by a timeout stays UNKNOWN and cannot be replayed', async () => {
  const db = database(); let submissions = 0;
  const publish = createPublishTaskRunner({ db, work: async (_id, beforeSend) => {
    await beforeSend(); submissions++;
    throw new Error('Timeout after acceptance; fixture-cookie-value must not be persisted');
  } });
  const failed = await publish(1);
  assert.equal(failed.status, 'UNKNOWN'); assert.equal(failed.retryable, false);
  assert.ok(!db.row.error_message.includes('fixture-cookie-value'));
  assert.equal((await publish(1)).blocked, true); assert.equal(submissions, 1);
});

test('legacy failures, in-flight attempts, unknown states and restarts never auto-release ownership', async () => {
  for (const status of ['FAILED', 'PREPARING', 'PUBLISHING', 'UNKNOWN', 'UNRECOGNIZED']) {
    const db = database(status);
    const response = await createPublishTaskRunner({ db, work: () => assert.fail('must not submit') })(1);
    assert.equal(response.status, status); assert.equal(response.retryable, false); assert.equal(response.blocked, true);
    assert.equal(db.row.status, status);
  }
});

test('failed claim acknowledgement never resets a possibly owned row', async () => {
  for (const phase of ['before', 'after']) {
    const db = database();
    db[phase] = query => { if (query === sql.claim) throw new Error('DB claim acknowledgement lost'); };
    await assert.rejects(createPublishTaskRunner({ db, work: () => assert.fail('must not submit') })(1));
    assert.deepEqual(db.history.map(entry => entry.query), [sql.claim]);
    assert.equal(db.row.status, phase === 'after' ? 'PREPARING' : 'PENDING');
  }
});

test('failed boundary persistence stops the provider before the external request', async () => {
  const db = database(); let submissions = 0;
  db.before = query => { if (query === sql.start) throw new Error('DB unavailable'); };
  const response = await createPublishTaskRunner({ db, work: async (_id, beforeSend) => {
    await beforeSend(); submissions++; return result;
  } })(1);
  assert.equal(submissions, 0); assert.equal(response.status, 'UNKNOWN');
  assert.equal(response.retryable, false);
});

test('acknowledged provider success with failed accounting preserves UNKNOWN and the known external URL', async () => {
  const db = database();
  db.before = query => { if (query === sql.posted) throw new Error('DB result write failed'); };
  const response = await createPublishTaskRunner({ db, work: successfulWork })(1);
  assert.equal(response.status, 'UNKNOWN'); assert.equal(response.externalUrl, result.externalUrl);
  assert.equal(response.retryable, false);
});

test('lost POSTED acknowledgement never downgrades a durable success', async () => {
  const db = database();
  db.after = query => { if (query === sql.posted) throw new Error('DB acknowledgement lost after commit'); };
  const response = await createPublishTaskRunner({ db, work: successfulWork })(1);
  assert.equal(db.row.status, 'POSTED'); assert.equal(response.status, 'POSTED');
  assert.equal(response.ok, true); assert.equal(response.externalUrl, result.externalUrl);
});

test('failure to persist UNKNOWN leaves the in-flight state blocked', async () => {
  const db = database(), errors = [];
  db.before = query => { if ([sql.posted, sql.unknown].includes(query)) throw new Error('DB write failed'); };
  const response = await createPublishTaskRunner({ db, work: successfulWork, onPersistenceError: error => errors.push(error) })(1);
  assert.equal(db.row.status, 'PUBLISHING'); assert.equal(response.blocked, true);
  assert.equal(response.retryable, false); assert.equal(errors.length, 1);
});

test('total database outage after submission reports uncertainty rather than success or retryability', async () => {
  const db = database();
  const response = await createPublishTaskRunner({ db, work: async (_id, beforeSend) => {
    await beforeSend(); db.before = () => { throw new Error('DB outage'); }; return result;
  } })(1);
  assert.equal(response.status, 'UNKNOWN'); assert.equal(response.ok, false); assert.equal(response.retryable, false);
  assert.equal(db.row.status, 'PUBLISHING');
});

test('loss of ownership before result persistence does not overwrite a protected state', async () => {
  const db = database();
  const response = await createPublishTaskRunner({ db, work: async (_id, beforeSend) => {
    await beforeSend(); db.row.status = 'UNKNOWN'; return result;
  } })(1);
  assert.equal(response.status, 'UNKNOWN'); assert.equal(response.blocked, true);
});

test('the external boundary may only be entered once', async () => {
  const db = database();
  const response = await createPublishTaskRunner({ db, work: async (_id, beforeSend) => {
    await beforeSend(); await beforeSend(); return result;
  } })(1);
  assert.equal(response.status, 'UNKNOWN');
  assert.equal(db.history.filter(entry => entry.query === sql.start).length, 1);
});

test('providers cannot report POSTED without using the submission boundary', async () => {
  const db = database();
  const response = await createPublishTaskRunner({ db, work: async () => result })(1);
  assert.equal(response.status, 'PREPARE_FAILED'); assert.equal(response.ok, false);
});

test('invalid or missing log IDs never invoke providers', async () => {
  const db = database();
  const publish = createPublishTaskRunner({ db, work: () => assert.fail('must not submit') });
  for (const id of [0, -1, NaN, Infinity, 1.5, Number.MAX_SAFE_INTEGER + 1, '1']) await assert.rejects(publish(id));
  assert.equal(db.history.length, 0);
  db.row = null;
  await assert.rejects(publish(1), /not found/);
});
