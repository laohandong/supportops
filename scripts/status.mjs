import {readFile} from 'node:fs/promises';
import {resolve} from 'node:path';
import {pathToFileURL} from 'node:url';
import {options, printResult, check} from './reliability/common.mjs';
import {inspect} from './reliability/runtime.mjs';

export async function status(args = process.argv.slice(2)) {
  let input, result;
  try {
    input = options(args, ['runtime'], ['json']);
    if (!input.runtime) throw new Error('INPUT_INVALID');
    let record;
    try { record = JSON.parse(await readFile(resolve(input.runtime), 'utf8')); }
    catch { throw new Error('INPUT_INVALID'); }
    result = await inspect(record);
  } catch { result = {schemaVersion: 1, phase: 'UNKNOWN', exitCode: 2, checks: [check('input', 'FAIL', 'INPUT_INVALID')]}; }
  printResult(result, input?.json || args.includes('--json'));
  if (!input?.json) console.log(`phase=${result.phase}`);
  return result;
}
if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) process.exitCode = (await status()).exitCode;
