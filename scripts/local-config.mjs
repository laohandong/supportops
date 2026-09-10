import {readFile} from 'node:fs/promises';

const allowed = new Set([
  'SUPPORTOPS_RERANK_ENABLED', 'SUPPORTOPS_RERANK_MODEL_PATH', 'SUPPORTOPS_RERANK_TOKENIZER_PATH',
  'SUPPORTOPS_RERANK_CANDIDATES', 'SUPPORTOPS_RERANK_MAX_INPUT_TOKENS', 'SUPPORTOPS_RERANK_THREADS', 'SUPPORTOPS_RERANK_TIMEOUT_MS',
  'SUPPORTOPS_API_KEY', 'SUPPORTOPS_MODEL_BASE_URL', 'SUPPORTOPS_MODEL',
  'SUPPORTOPS_EMBEDDING_API_KEY', 'SUPPORTOPS_EMBEDDING_BASE_URL', 'SUPPORTOPS_EMBEDDING_MODEL',
  'SUPPORTOPS_MODEL_PROVIDER', 'SUPPORTOPS_EMBEDDING_PROVIDER',
  'SUPPORTOPS_MODEL_NAME', 'SUPPORTOPS_EMBEDDING_NAME',
  'SUPPORTOPS_MODEL_API_KEY_ENV', 'SUPPORTOPS_EMBEDDING_API_KEY_ENV',
  'SUPPORTOPS_MODEL_MAX_TOKENS_FIELD', 'SUPPORTOPS_MODEL_MAX_OUTPUT_TOKENS',
  'SUPPORTOPS_MODEL_TEMPERATURE', 'SUPPORTOPS_MODEL_THINKING', 'SUPPORTOPS_MODEL_REASONING_EFFORT',
  'SUPPORTOPS_DB_URL', 'SUPPORTOPS_DB_USER', 'SUPPORTOPS_DB_PASSWORD',
  'SUPPORTOPS_MINIO_ENDPOINT', 'SUPPORTOPS_MINIO_ACCESS_KEY', 'SUPPORTOPS_MINIO_SECRET_KEY', 'SUPPORTOPS_MINIO_BUCKET',
  'SUPPORTOPS_ES_ENDPOINT', 'SUPPORTOPS_ES_USER', 'SUPPORTOPS_ES_PASSWORD', 'SUPPORTOPS_ES_PREFIX',
  'SUPPORTOPS_EXCEL_SCHEMA', 'SUPPORTOPS_EXCEL_QUERY_USER', 'SUPPORTOPS_EXCEL_QUERY_PASSWORD',
]);

export function configurationSecrets(env) {
  return Object.entries(env).filter(([key, value]) => /(_API_KEY|_PASSWORD|_SECRET_KEY)$/.test(key) && value)
    .map(([, value]) => value).sort((a, b) => b.length - a.length);
}

// Data parser only: no interpolation, shell evaluation, or arbitrary environment overrides.
export function parseLocalConfig(source) {
  const values = {};
  for (const [index, raw] of source.replace(/^\uFEFF/, '').split(/\r?\n/).entries()) {
    const line = raw.trim();
    if (!line || line.startsWith('#')) continue;
    const match = /^([A-Z][A-Z0-9_]*)\s*=(.*)$/.exec(line);
    if (!match || (!allowed.has(match[1]) && !/^[A-Z][A-Z0-9_]*_API_KEY$/.test(match[1])) || Object.hasOwn(values, match[1])) {
      throw new Error(`Invalid or duplicate configuration entry at line ${index + 1}.`);
    }
    let value = match[2].trim();
    if (value.startsWith('"') || value.startsWith("'")) {
      if (value.length < 2 || value.at(-1) !== value[0]) throw new Error(`Unclosed quote at line ${index + 1}.`);
      value = value.slice(1, -1);
    }
    if (value.includes('\0')) throw new Error(`Invalid configuration value at line ${index + 1}.`);
    values[match[1]] = value;
  }
  return values;
}

export async function loadLocalConfig(path, inherited = process.env) {
  let values = {};
  try { values = parseLocalConfig(await readFile(path, 'utf8')); }
  catch (error) { if (error.code !== 'ENOENT') throw error; }
  // An empty template entry must not erase a key supplied through the caller's environment.
  return {...inherited, ...Object.fromEntries(Object.entries(values).filter(([, value]) => value !== ''))};
}
