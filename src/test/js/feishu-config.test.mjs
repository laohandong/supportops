import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdir, mkdtemp, writeFile, unlink, rmdir} from 'node:fs/promises';
import {join, resolve} from 'node:path';
import {parseLocalConfig, loadLocalConfig, configurationSecrets} from '../../../scripts/local-config.mjs';

test('Feishu configuration is parsed as data and its app secret is redacted', () => {
    const values = parseLocalConfig([
        'SUPPORTOPS_FEISHU_ENABLED=true', 'SUPPORTOPS_FEISHU_APP_ID=cli_fixture',
        'SUPPORTOPS_FEISHU_APP_SECRET=synthetic-feishu-secret',
        'SUPPORTOPS_FEISHU_TENANT_KEY=tenant_fixture'
    ].join('\n'));
    assert.equal(values.SUPPORTOPS_FEISHU_ENABLED, 'true');
    assert.deepEqual(configurationSecrets(values), ['synthetic-feishu-secret']);
    assert.throws(() => parseLocalConfig('SUPPORTOPS_FEISHU_BASE_URL=https://untrusted.example'), /Invalid/);
});

test('retired Feishu retrieval override is ignored in legacy files and inherited environments', async () => {
    const legacyKey = 'SUPPORTOPS_FEISHU_LEXICAL_ONLY';
    for (const value of ['true', 'false']) {
        assert.deepEqual(parseLocalConfig(`${legacyKey}=${value}\nSUPPORTOPS_FEISHU_ENABLED=true`),
            {SUPPORTOPS_FEISHU_ENABLED: 'true'});
    }
    assert.throws(() => parseLocalConfig(`${legacyKey}=true\n${legacyKey}=false`), /duplicate/);
    const root = resolve('.cache/feishu-config-tests');
    await mkdir(root, {recursive: true});
    const directory = await mkdtemp(join(root, 'legacy-'));
    const file = join(directory, 'model.env');
    await writeFile(file, `${legacyKey}=true\nSUPPORTOPS_EMBEDDING_PROVIDER=disabled`);
    try {
        const inherited = {[legacyKey]: 'true', SUPPORTOPS_MODEL_PROVIDER: 'custom'};
        const configured = await loadLocalConfig(file, inherited);
        assert.equal(Object.hasOwn(configured, legacyKey), false);
        assert.equal(configured.SUPPORTOPS_EMBEDDING_PROVIDER, 'disabled');
        assert.equal(configured.SUPPORTOPS_MODEL_PROVIDER, 'custom');
        assert.deepEqual(await loadLocalConfig(join(directory, 'absent.env'), inherited),
            {SUPPORTOPS_MODEL_PROVIDER: 'custom'});
        assert.equal(inherited[legacyKey], 'true');
    } finally {
        await unlink(file);
        await rmdir(directory);
    }
});
