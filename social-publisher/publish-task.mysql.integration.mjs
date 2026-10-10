// Run only through tools/tests/social-publishing-mysql.ps1: synthetic, empty, network-isolated MySQL.
import assert from 'node:assert/strict';
import mysql from 'mysql2/promise';
import { createPublishTaskRunner, publicationSql } from './publish-task.mjs';

if (process.env.PUBLICATION_MYSQL_FIXTURE !== 'network-none-tmpfs') {
  throw new Error('An explicitly isolated MySQL fixture is required');
}
const config = { host: '127.0.0.1', port: 3306, user: 'root', password: '',
  database: 'gying_publication_fixture', connectionLimit: 4 };
const db = mysql.createPool(config);
const results = [];
const seed = (id, status = 'PENDING') => db.query(
  'INSERT INTO social_post_log (id, platform, status) VALUES (?, ?, ?)', [id, 'WEIBO', status]);
const read = async id => (await db.query(publicationSql.state, [id]))[0][0];
const success = async (_, beforeSend) => {
  await beforeSend();
  return { platform: 'WEIBO', externalUrl: 'https://example.invalid/fixture-post' };
};
async function run(name, work) {
  await work(); results.push(name); console.log('PASS ' + name);
}
try {
  const [[identity]] = await db.query('SELECT @@hostname AS hostname');
  assert.equal(identity.hostname, 'gying-publication-fixture', 'Never write to a non-fixture server');
  const [tables] = await db.query('SHOW TABLES');
  assert.equal(tables.length, 0, 'Fixture schema must be empty; no existing data may be reused or removed');
  await db.query(`CREATE TABLE social_post_log (
    id BIGINT UNSIGNED NOT NULL PRIMARY KEY, platform VARCHAR(30) NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING', external_url VARCHAR(2000),
    error_message TEXT, posted_at DATETIME, updated_at DATETIME
  ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci`);

  await run('40 independent MySQL connections claim a log only once', async () => {
    await seed(1);
    const connections = [];
    try {
      const { connectionLimit, ...connectionConfig } = config;
      const opened = await Promise.allSettled(Array.from({ length: 40 }, async () => {
        const connection = await mysql.createConnection(connectionConfig);
        connections.push(connection);
        return connection;
      }));
      assert.ok(opened.every(result => result.status === 'fulfilled'), 'All fixture connections must open');
      const ids = await Promise.all(connections.map(async connection =>
        (await connection.query('SELECT CONNECTION_ID() AS id'))[0][0].id));
      assert.equal(new Set(ids).size, 40);
      let sends = 0;
      const responses = await Promise.all(connections.map(connection => createPublishTaskRunner({
        db: connection, async work(id, beforeSend) {
          await beforeSend(); sends++;
          await new Promise(resolve => setTimeout(resolve, 50));
          return { platform: 'WEIBO', externalUrl: 'https://example.invalid/fixture-concurrent' };
        },
      })(1)));
      assert.equal(sends, 1);
      assert.equal((await read(1)).status, 'POSTED');
      assert.ok(responses.every(response => !response.retryable));
      assert.ok(responses.some(response => response.ok && !response.reused));
      assert.equal(responses.filter(response => !response.reused).length, 1);
    } finally {
      await Promise.all(connections.map(connection => connection.end()));
    }
  });

  await run('pre-send preparation failure can be safely claimed by a fresh runner', async () => {
    await seed(2);
    const failed = await createPublishTaskRunner({ db, work: async () => {
      throw new Error('Fixture preparation failed before sending');
    } })(2);
    assert.equal(failed.status, 'PREPARE_FAILED'); assert.equal(failed.retryable, true);
    const retried = await createPublishTaskRunner({ db, work: success })(2);
    assert.equal(retried.status, 'POSTED'); assert.equal(retried.ok, true);
  });

  await run('an external timeout is UNKNOWN and cannot be replayed after restart', async () => {
    await seed(3);
    let sends = 0;
    const failed = await createPublishTaskRunner({ db, work: async (_, beforeSend) => {
      await beforeSend(); sends++; throw new Error('Fixture external acknowledgement lost');
    } })(3);
    assert.equal(failed.status, 'UNKNOWN'); assert.equal(failed.retryable, false);
    const reused = await createPublishTaskRunner({ db, work: async () => { sends++; } })(3);
    assert.equal(reused.status, 'UNKNOWN'); assert.equal(reused.blocked, true); assert.equal(sends, 1);
  });

  await run('in-flight, legacy failure and unfamiliar states stay protected in actual SQL', async () => {
    let sends = 0;
    const statuses = ['PREPARING', 'PUBLISHING', 'UNKNOWN', 'FAILED', 'FUTURE_STATE', 'POSTED'];
    for (const [i, status] of statuses.entries()) {
      const id = i + 10;
      await seed(id, status);
      const response = await createPublishTaskRunner({ db, work: async () => { sends++; } })(id);
      assert.equal(response.status, status); assert.equal(response.retryable, false);
      assert.equal(response.ok, status === 'POSTED'); assert.equal((await read(id)).status, status);
    }
    assert.equal(sends, 0);
  });

  await run('a committed POSTED result survives a lost write acknowledgement', async () => {
    await seed(30);
    const lostAckDb = { async query(sql, values) {
      const result = await db.query(sql, values);
      if (sql === publicationSql.posted) throw new Error('Fixture POSTED acknowledgement lost');
      return result;
    } };
    const response = await createPublishTaskRunner({ db: lostAckDb, work: success })(30);
    assert.equal(response.status, 'POSTED'); assert.equal(response.ok, true);
    assert.equal((await read(30)).external_url, 'https://example.invalid/fixture-post');
  });

  await run('a lost PUBLISHING acknowledgement prevents the external call and blocks replay', async () => {
    await seed(31);
    let sends = 0;
    const lostAckDb = { async query(sql, values) {
      const result = await db.query(sql, values);
      if (sql === publicationSql.start) throw new Error('Fixture PUBLISHING acknowledgement lost');
      return result;
    } };
    const response = await createPublishTaskRunner({ db: lostAckDb, work: async (_, beforeSend) => {
      await beforeSend(); sends++;
    } })(31);
    assert.equal(sends, 0); assert.equal(response.status, 'UNKNOWN'); assert.equal(response.retryable, false);
    assert.equal((await read(31)).status, 'UNKNOWN');
  });
  console.log(JSON.stringify({ suite: 'isolated-mysql-publication', passed: results.length, failed: 0 }));
} finally {
  await db.end();
}
