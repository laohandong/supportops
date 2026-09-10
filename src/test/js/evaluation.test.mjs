import test from 'node:test';
import assert from 'node:assert/strict';
import {createServer} from 'node:http';
import {mkdir, mkdtemp, readFile, writeFile} from 'node:fs/promises';
import {resolve} from 'node:path';
import {parseLocalConfig, loadLocalConfig, configurationSecrets} from '../../../scripts/local-config.mjs';
import {retrievalCheck, diagnosticChecks, renderReview} from '../../../evaluation/report.mjs';
import {evaluate} from '../../../evaluation/run.mjs';

async function directory() {
    const base = resolve('.cache/evaluation-tests');
    await mkdir(base, {recursive: true});
    return mkdtemp(resolve(base, 'run-'));
}

const passage = {
    id: '00000000-0000-0000-0000-000000000001:0',
    documentId: 'current-doc',
    version: '2.0',
    content: 'A fixed test passage.'
};
const current = {mode: 'LEXICAL', passages: [passage]};
const knowledgeEvent = content => ({id: 1, kind: 'KNOWLEDGE', content});

test('local ONNX options accept literal external paths without granting unrelated environment overrides', () => {
    const config = parseLocalConfig('SUPPORTOPS_RERANK_ENABLED=true\nSUPPORTOPS_RERANK_MODEL_PATH="E:/local models/model_quantized.onnx"\nSUPPORTOPS_RERANK_TOKENIZER_PATH=E:/local models/tokenizer.json\nSUPPORTOPS_RERANK_CANDIDATES=20\nSUPPORTOPS_RERANK_MAX_INPUT_TOKENS=512\nSUPPORTOPS_RERANK_THREADS=2\nSUPPORTOPS_RERANK_TIMEOUT_MS=30000');
    assert.equal(config.SUPPORTOPS_RERANK_ENABLED, 'true');
    assert.equal(config.SUPPORTOPS_RERANK_MODEL_PATH, 'E:/local models/model_quantized.onnx');
    assert.equal(config.SUPPORTOPS_RERANK_TIMEOUT_MS, '30000');
    assert.throws(() => parseLocalConfig('DJL_CACHE_DIR=unexpected'));
});

test('local config treats shell syntax and embedded equals as literal data', () => {
    const config = parseLocalConfig('\uFEFF# local\nSUPPORTOPS_API_KEY="literal=$(whoami)=value"\r\nSUPPORTOPS_MODEL=qwen-plus');
    assert.equal(config.SUPPORTOPS_API_KEY, 'literal=$(whoami)=value');
    assert.equal(config.SUPPORTOPS_MODEL, 'qwen-plus');
});

test('local config rejects unrelated overrides and malformed entries without disclosing values', () => {
    for (const input of ['JAVA_TOOL_OPTIONS=PRIVATE_TEST_VALUE', 'SUPPORTOPS_API_KEY="PRIVATE_TEST_VALUE', 'SUPPORTOPS_API_KEY=one\nSUPPORTOPS_API_KEY=PRIVATE_TEST_VALUE']) {
        assert.throws(() => parseLocalConfig(input), error => !error.message.includes('PRIVATE_TEST_VALUE') && error.message.includes('line'));
    }
});

test('storage configuration stays explicit and secret redaction covers database and object storage', () => {
    const config = parseLocalConfig('SUPPORTOPS_DB_PASSWORD=synthetic-db\nSUPPORTOPS_MINIO_SECRET_KEY=synthetic-minio\nSUPPORTOPS_ES_PASSWORD=synthetic-es\nSUPPORTOPS_EXCEL_QUERY_PASSWORD=synthetic-reader\nSUPPORTOPS_ES_PREFIX=test-index');
    assert.deepEqual(new Set(configurationSecrets(config)), new Set(['synthetic-db', 'synthetic-minio', 'synthetic-es', 'synthetic-reader']));
    assert.equal(config.SUPPORTOPS_ES_PREFIX, 'test-index');
});

test('empty config entries preserve caller credentials and support shared provider key', async () => {
    const path = resolve(await directory(), 'model.env');
    await writeFile(path, 'SUPPORTOPS_API_KEY=\nSUPPORTOPS_MODEL=test-model');
    const env = await loadLocalConfig(path, {
        SUPPORTOPS_API_KEY: 'fixed-test-value',
        DASHSCOPE_API_KEY: 'shared-test-value'
    });
    assert.equal(env.SUPPORTOPS_API_KEY, 'fixed-test-value');
    assert.equal(env.SUPPORTOPS_MODEL, 'test-model');
    assert.equal(env.DASHSCOPE_API_KEY, 'shared-test-value');
});

test('provider selection and provider-specific credential references are accepted as data', () => {
    const values = parseLocalConfig('SUPPORTOPS_MODEL_PROVIDER=deepseek\nDEEPSEEK_API_KEY=deepseek-test-key\nOPENAI_API_KEY=openai-test-key\nTEAM2_API_KEY=gateway-test-key\nSUPPORTOPS_MODEL_API_KEY_ENV=TEAM2_API_KEY\nSUPPORTOPS_EMBEDDING_PROVIDER=disabled');
    assert.equal(values.SUPPORTOPS_MODEL_PROVIDER, 'deepseek');
    assert.equal(values.SUPPORTOPS_MODEL_API_KEY_ENV, 'TEAM2_API_KEY');
    assert.equal(values.TEAM2_API_KEY, 'gateway-test-key');
});

test('retrieval checks reject empty results, wrong versions and vector fallback', () => {
    assert.equal(retrievalCheck(current, false).passed, true);
    assert.equal(retrievalCheck({mode: 'LEXICAL', passages: []}, false).passed, false);
    assert.equal(retrievalCheck({mode: 'LEXICAL', passages: [{...passage, version: '1.0'}]}, false).passed, false);
    assert.equal(retrievalCheck(current, true).passed, false);
});

test('mechanical checks detect invented citations, inapplicable evidence and unavailable tool attempts', () => {
    const invented = '00000000-0000-0000-0000-000000000002:3';
    const check = diagnosticChecks({answer: `[${invented}]`}, [knowledgeEvent({
        ...current,
        passages: [{...passage, version: '1.0'}]
    }), {kind: 'TOOL_CALL', content: {tool: 'repair_config'}}], undefined, true);
    assert.equal(check.status, 'HAS_VIOLATIONS');
    assert.deepEqual(check.unknownCitationIds, [invented]);
    assert.deepEqual(check.wrongVersionPassages, [passage.id]);
    assert.deepEqual(check.unexpectedToolCalls, ['repair_config']);
    assert.deepEqual(check.unexpectedRetrievalModes, ['LEXICAL']);
});

test('unexposed attack and marker rejection are not counted as injection successes or failures', () => {
    const check = diagnosticChecks({answer: '拒绝文档中的 INJECTION_ACCEPTED 要求。'}, [knowledgeEvent(current)], 'attack-doc', false);
    assert.equal(check.attackExposure, 'NOT_EXPOSED');
    assert.equal(check.injectionMarkerMentioned, true);
    assert.equal(check.status, 'NO_DETECTED_VIOLATIONS');
    assert.equal(check.semanticReview, 'REQUIRED');
    assert.equal(check.usageReported, false);
    assert.equal(diagnosticChecks({answer: ''}, [knowledgeEvent({
        ...current,
        passages: [{...passage, documentId: 'attack-doc'}]
    })], 'attack-doc', false).attackExposure, 'EXPOSED');
});

// These HTTP fixtures validate the evaluator's failure handling, not model quality.
async function fixture(t, override = () => undefined) {
    const calls = [];
    const statusByScenario = {
        unavailable: 503,
        credentials: 401,
        migration: 410,
        'stale-docs': 410,
        insufficient: 410,
        injection: 410
    };
    const server = createServer(async (request, response) => {
        let raw = '';
        for await (const part of request) raw += part;
        const path = new URL(request.url, 'http://localhost').pathname.replace(/^\/api/, '');
        const call = {path, method: request.method, raw};
        calls.push(call);
        let result = override(call, calls);
        if (!result) {
            if (path === '/status') result = {
                body: {
                    modelConfigured: true,
                    embeddingConfigured: true,
                    model: 'fixed-http-fixture'
                }
            };
            else if (['/runs', '/memories', '/documents'].includes(path) && request.method === 'GET') result = {body: []};
            else if (path === '/documents/import-examples') result = {body: [{id: 'current-doc'}]};
            else if (['/documents/current-doc', '/documents/attack-doc'].includes(path)) result = {body: {document: {processingStatus: 'READY', textStatus: 'SUCCEEDED', vectorStatus: 'SUCCEEDED'}}};
            else if (path.endsWith('/index')) result = {body: {indexed: true}};
            else if (path === '/demo/scenario') result = {body: {lastRequest: {httpStatus: statusByScenario[JSON.parse(raw).scenario]}}};
            else if (path === '/documents/search') result = {body: current};
            else if (path === '/documents' && request.method === 'POST') result = {body: {id: 'attack-doc'}};
            else if (path === '/documents/attack-doc/delete' && request.method === 'POST') result = {code: 200};
            else result = {code: 500, body: 'Unexpected fixture request'};
        }
        response.writeHead(result.code ?? 200, {'Content-Type': 'application/json'});
        response.end(result.body === undefined ? '' : JSON.stringify(result.body));
    });
    await new Promise(r => server.listen(0, '127.0.0.1', r));
    t.after(() => new Promise(r => {
        server.close(r);
        server.closeAllConnections();
    }));
    return {base: `http://127.0.0.1:${server.address().port}`, directory: await directory(), calls};
}

test('missing credentials produce NOT_RUN without modifying the app', async t => {
    const f = await fixture(t, call => call.path === '/status' ? {body: {modelConfigured: false}} : undefined);
    const result = await evaluate(f);
    assert.equal(result.report.status, 'NOT_RUN');
    assert.equal(result.exitCode, 2);
    assert.match(result.report.reason, /SUPPORTOPS_API_KEY/);
    assert.doesNotMatch(result.report.reason, /SUPPORTOPS_EMBEDDING_API_KEY/);
    assert.deepEqual(f.calls.map(c => c.path), ['/status']);
    assert.match(await readFile(resolve(f.directory, 'review.md'), 'utf8'), /NOT_RUN/);
});

test('hybrid evaluation reports the missing embedding key before importing or indexing', async t => {
    const f = await fixture(t, call => call.path === '/status' ? {
        body: {
            modelConfigured: true,
            embeddingConfigured: false
        }
    } : undefined);
    const result = await evaluate({...f, hybrid: true});
    assert.equal(result.report.status, 'NOT_RUN');
    assert.equal(result.exitCode, 2);
    assert.equal(result.report.reason, 'Missing configuration: SUPPORTOPS_EMBEDDING_API_KEY.');
    assert.deepEqual(f.calls.map(c => c.path), ['/status']);
});

test('provider-specific missing credentials come from the server registry, not a platform fallback', async t => {
    const f = await fixture(t, call => call.path === '/status' ? {
        body: {
            modelConfigured: false,
            modelRoute: {provider: 'deepseek', error: 'MODEL_NOT_CONFIGURED', credentialEnv: 'DEEPSEEK_API_KEY'}
        }
    } : undefined);
    const result = await evaluate(f);
    assert.equal(result.report.reason, 'Missing configuration: DEEPSEEK_API_KEY.');
    assert.doesNotMatch(result.report.reason, /DASHSCOPE/);
});

test('invalid embedding provider affects hybrid evaluation without being described as a missing key', async t => {
    const f = await fixture(t, call => call.path === '/status' ? {
        body: {
            modelConfigured: true,
            embeddingConfigured: false,
            embeddingRoute: {error: 'EMBEDDING_UNSUPPORTED_PROVIDER'}
        }
    } : undefined);
    const result = await evaluate({...f, hybrid: true});
    assert.equal(result.report.reason, 'Missing configuration: EMBEDDING_UNSUPPORTED_PROVIDER.');
    assert.deepEqual(f.calls.map(c => c.path), ['/status']);
});
test('existing operator data is refused before importing documents or changing scenarios', async t => {
    const f = await fixture(t, call => call.path === '/documents' && call.method === 'GET' ? {body: [{id: 'operator-document'}]} : undefined);
    const result = await evaluate({...f, sourcesOnly: true});
    assert.equal(result.report.status, 'ERROR');
    assert.equal(f.calls.some(c => c.method !== 'GET'), false);
});

test('empty retrieval cannot produce a successful six-case report', async t => {
    const f = await fixture(t, call => call.path === '/documents/search' ? {
        body: {
            mode: 'LEXICAL',
            passages: []
        }
    } : undefined);
    const result = await evaluate({...f, sourcesOnly: true});
    assert.equal(result.report.status, 'HAS_FAILURES');
    assert.equal(result.report.cases.length, 6);
    assert.ok(result.report.cases.every(c => c.status === 'ERROR' && !c.retrievalCheck.passed));
    assert.equal(f.calls.some(c => c.path === '/runs' && c.method === 'POST'), false);
});

test('evaluation waits for persisted document completion before the first retrieval', async t => {
    let polls = 0;
    const f = await fixture(t, call => {
        if (call.path === '/documents/current-doc') return {body: {document: {
            processingStatus: ++polls === 1 ? 'RUNNING' : 'READY', textStatus: polls === 1 ? 'PENDING' : 'SUCCEEDED', vectorStatus: 'BLOCKED'
        }}};
        if (call.path === '/documents/search') assert.ok(polls >= 2);
    });
    const result = await evaluate({...f, sourcesOnly: true});
    assert.equal(result.report.status, 'EXECUTION_FINISHED');
    assert.equal(result.report.cases.length, 6);
    assert.equal(f.calls.some(call => call.path.endsWith('/index')), false);
});

test('failed async import stops evaluation without fabricating search success', async t => {
    const f = await fixture(t, call => call.path === '/documents/current-doc' ? {body: {document: {processingStatus: 'FAILED', textStatus: 'PENDING'}}} : undefined);
    const result = await evaluate({...f, sourcesOnly: true});
    assert.equal(result.report.status, 'ERROR');
    assert.equal(f.calls.some(call => call.path === '/documents/search'), false);
});

test('uncertain submission is never retried and provider body is not copied to report', async t => {
    const f = await fixture(t, call => call.path === '/runs' && call.method === 'POST' ? {
        code: 503,
        body: 'PRIVATE_TEST_PROVIDER_BODY'
    } : undefined);
    const result = await evaluate(f);
    assert.equal(result.report.status, 'HAS_FAILURES');
    assert.equal(result.report.cases.length, 1);
    assert.equal(f.calls.filter(c => c.path === '/runs' && c.method === 'POST').length, 1);
    assert.ok(!JSON.stringify(result.report).includes('PRIVATE_TEST_PROVIDER_BODY'));
    assert.match(result.report.stopReason, /no automatic resubmission/);
});

test('dialogue-only keyword runs complete without indexing and retain final evidence', async t => {
    let sequence = 0;
    const f = await fixture(t, call => {
        if (call.path === '/status') return {
            body: {
                modelConfigured: true,
                embeddingConfigured: false,
                model: 'fixed-http-fixture'
            }
        };
        if (call.path === '/runs' && call.method === 'POST') return {body: {id: `run-${++sequence}`, status: 'QUEUED'}};
        if (/^\/runs\/run-\d+$/.test(call.path)) return {
            body: {
                id: call.path.split('/').at(-1),
                status: 'COMPLETED',
                answer: `固定协议测试 [${passage.id}]`,
                elapsedMs: 10,
                inputTokens: 11,
                outputTokens: 7
            }
        };
        if (call.path.endsWith('/events')) return {
            body: [knowledgeEvent(current), {
                id: 2,
                kind: 'USAGE',
                content: {inputTokens: 11, outputTokens: 7}
            }]
        };
        return undefined;
    });
    const result = await evaluate(f);
    assert.equal(result.report.status, 'EXECUTION_FINISHED');
    assert.equal(result.report.cases.length, 6);
    assert.equal(result.report.modelQualityVerdict, 'NOT_EVALUATED');
    assert.equal(f.calls.some(c => c.path.endsWith('/index')), false);
    assert.ok(result.report.cases.every(c => c.events.length === 2 && c.checks.usageReported && c.humanReview === 'REQUIRED'));
    assert.equal(result.report.cases.at(-1).attackDocumentRetrieved, false);
    const submissions = f.calls.filter(c => c.path === '/runs' && c.method === 'POST');
    assert.equal(new Set(submissions.map(c => c.raw)).size, 1);
    assert.ok(submissions.every(c => JSON.parse(c.raw).lexicalOnly === true));
    for (const [index, call] of f.calls.entries()) {
        if (call.path.endsWith('/events')) assert.equal(f.calls[index - 1].path, call.path.replace('/events', ''));
    }
    assert.match(await readFile(resolve(f.directory, 'review.md'), 'utf8'), /11 \/ 7/);
});

test('cleanup failure is preserved in both reports without hiding original case evidence', async t => {
    const f = await fixture(t, call => call.path === '/documents/attack-doc/delete' && call.method === 'POST' ? {code: 500} : undefined);
    const result = await evaluate({...f, sourcesOnly: true});
    assert.equal(result.report.status, 'HAS_FAILURES');
    const last = result.report.cases.at(-1);
    assert.equal(last.status, 'SOURCE_CHECKED');
    assert.equal(last.environmentCheck.passed, true);
    assert.match(last.cleanupError, /HTTP 500/);
    assert.match(await readFile(resolve(f.directory, 'review.md'), 'utf8'), /清理错误/);
    assert.ok(result.report.inputs.files['evaluation/fixtures/injection-2.0.md']);
});

test('review keeps model content inside fences and does not infer a quality verdict', () => {
    const report = {
        createdAt: 'test',
        kind: 'MODEL_EVALUATION',
        retrieval: 'LEXICAL',
        status: 'EXECUTION_FINISHED',
        modelQualityVerdict: 'NOT_EVALUATED',
        cases: [{
            id: 'test',
            reviewCriteria: 'test',
            run: {id: 'run', answer: '```\n<script>untrusted</script>\n```'},
            checks: {usageReported: false},
            events: []
        }]
    };
    const markdown = renderReview(report);
    assert.match(markdown, /````text\n```/);
    assert.match(markdown, /NOT_EVALUATED/);
    assert.match(markdown, /未报告/);
});
