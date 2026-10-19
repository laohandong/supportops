import test from 'node:test';
import assert from 'node:assert/strict';
import {explicitPort} from '../../../scripts/reliability/port.mjs';
import {parseLocalConfig} from '../../../scripts/local-config.mjs';

test('explicit port preserves omission and rejects ambiguous or invalid input', () => {
  assert.equal(explicitPort(undefined), undefined);
  assert.equal(explicitPort('0'), 0);
  assert.equal(explicitPort('65535'), 65535);
  for (const value of ['', ' ', '-1', '65536', '2.5', '0x10', 'Infinity', 'synthetic-secret']) {
    assert.throws(() => explicitPort(value), /^Error: INPUT_INVALID$/);
  }
});

test('PORT remains a data-only local configuration entry', () => {
  assert.equal(parseLocalConfig('PORT=45101').PORT, '45101');
  assert.equal(parseLocalConfig('PORT="$(never-execute)"').PORT, '$(never-execute)');
});
