import test from 'node:test';
import assert from 'node:assert/strict';
import {fileURLToPath} from 'node:url';
import {packageSource} from '../../../scripts/package-source.mjs';

test('source delivery rejects outputs inside copied trees before creating files',async()=>{
  const destination=fileURLToPath(new URL('../../generated-delivery-forbidden',import.meta.url));
  await assert.rejects(packageSource(destination),/SOURCE_OUTPUT_INSIDE_INCLUDED_DIRECTORY/);
});
