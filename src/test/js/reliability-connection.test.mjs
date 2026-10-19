import test from 'node:test';
import assert from 'node:assert/strict';
import {EventEmitter} from 'node:events';
import {setImmediate} from 'node:timers/promises';
import {closeDatabase} from '../../../scripts/reliability/storage.mjs';

test('queued MySQL QUIT alone cannot confirm that a writer connection has closed', async () => {
  const database = new EventEmitter(); let returned = false, destroyed = false;
  database.end = async () => {}; database.destroy = () => {destroyed = true;};
  const closing = closeDatabase(database).then(() => {returned = true;});
  await setImmediate(); assert.equal(returned, false);
  database.emit('end'); await closing;
  assert.equal(returned, true); assert.equal(destroyed, false); assert.equal(database.listenerCount('end'), 0);
});

test('missing server close is bounded and cannot be reported as successful shutdown', async () => {
  const database = new EventEmitter(); let destroyed = false;
  database.end = async () => {}; database.destroy = () => {destroyed = true;};
  await assert.rejects(closeDatabase(database, 25), /^Error: MYSQL_CLOSE_UNCONFIRMED$/);
  assert.equal(destroyed, true); assert.equal(database.listenerCount('end'), 0); assert.equal(database.listenerCount('error'), 0);
});

test('close errors are propagated without credential-bearing driver text', async () => {
  const database = new EventEmitter(); let destroyed = false;
  database.end = async () => {throw new Error('synthetic-credential-must-not-print');}; database.destroy = () => {destroyed = true;};
  await assert.rejects(closeDatabase(database), /^Error: MYSQL_CLOSE_FAILED$/);
  assert.equal(destroyed, true); assert.equal(database.listenerCount('error'), 0);
});
