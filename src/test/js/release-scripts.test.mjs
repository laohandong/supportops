import test from 'node:test';
import assert from 'node:assert/strict';
import {createServer} from 'node:http';
import {execFile} from 'node:child_process';
import {promisify} from 'node:util';
import {mkdir, mkdtemp, readFile, writeFile} from 'node:fs/promises';
import {resolve} from 'node:path';
import {setupLocal} from '../../../scripts/setup-local.mjs';
import {importKnowledge} from '../../../scripts/import-knowledge.mjs';

/** 提供真实本机 HTTP 端点，记录认证、写入次数与异步状态轮询。 */
async function fixture(context, options = {}) {
    const calls = [];
    let reads = 0;
    let indexing = false;
    const server = createServer(async (request, response) => {
        const body = [];
        for await (const chunk of request) {
            body.push(chunk);
        }
        calls.push({path: request.url, method: request.method, headers: request.headers, body: Buffer.concat(body).toString()});
        response.setHeader('Content-Type', 'application/json');
        if (request.url === '/api/auth/login') {
            if (options.redirect) {
                response.writeHead(307, {Location: '/credentials-must-not-arrive'});
                response.end();
                return;
            }
            if (options.loginFailure) {
                response.writeHead(401);
                response.end('synthetic-password-private');
                return;
            }
            response.setHeader('Set-Cookie', 'JSESSIONID=synthetic-session; HttpOnly; Path=/');
            response.end(JSON.stringify({role: options.role || 'ADMIN'}));
            return;
        }
        if (request.headers.cookie !== 'JSESSIONID=synthetic-session'
            || request.method === 'POST' && request.headers['x-supportops-request'] !== '1') {
            response.writeHead(403);
            response.end('{}');
            return;
        }
        if (request.url === '/api/status') {
            response.end(JSON.stringify({embeddingConfigured: options.embedding ?? true}));
        } else if (request.url === '/api/documents/import-examples') {
            if (options.writeFailure) {
                response.writeHead(503);
                response.end('synthetic-password-private');
            } else {
                response.end('[{"id":"demo"}]');
            }
        } else if (request.url === '/api/documents/demo/index') {
            indexing = true;
            response.end('{}');
        } else if (request.url === '/api/documents/demo') {
            reads++;
            response.end(JSON.stringify({document: {
                id: 'demo', chunks: 5,
                processingStatus: options.pending || reads < 2 ? 'PENDING' : 'READY',
                textStatus: options.textFailure ? 'FAILED' : reads < 2 ? 'PENDING' : 'SUCCEEDED',
                vectorStatus: indexing ? options.vectorFailure ? 'FAILED' : 'SUCCEEDED' : 'BLOCKED'
            }}));
        } else if (request.url === '/api/auth/logout') {
            response.end('{}');
        } else {
            response.writeHead(404);
            response.end('{}');
        }
    });
    await new Promise(resolveListening => server.listen(0, '127.0.0.1', resolveListening));
    context.after(() => new Promise(resolveClosed => {
        server.close(resolveClosed);
        server.closeAllConnections();
    }));
    return {
        calls,
        run: (overrides = {}) => importKnowledge({base: `http://127.0.0.1:${server.address().port}`,
            username: 'synthetic-admin', password: 'synthetic-password-private', pollMs: 5, ...overrides})
    };
}

test('configuration initialization preserves existing credentials, including concurrent initialization', async () => {
    const base = resolve('.cache/release-script-tests');
    await mkdir(base, {recursive: true});
    const directory = await mkdtemp(resolve(base, 'config-'));
    await mkdir(resolve(directory, 'infrastructure'));
    await writeFile(resolve(directory, 'infrastructure/local.env.example'), 'SUPPORTOPS_DB_PASSWORD=synthetic-template');
    const created = await Promise.all([setupLocal(directory), setupLocal(directory)]);
    assert.deepEqual(created.sort(), [false, true]);
    const configuration = resolve(directory, '.local/model.env');
    await writeFile(configuration, 'SUPPORTOPS_DB_PASSWORD=synthetic-existing');
    assert.equal(await setupLocal(directory), false);
    assert.equal(await readFile(configuration, 'utf8'), 'SUPPORTOPS_DB_PASSWORD=synthetic-existing');
});

test('import authenticates, waits for asynchronous keyword readiness and logs out without requiring embeddings', async context => {
    const application = await fixture(context);
    const documents = await application.run();
    assert.equal(documents[0].textStatus, 'SUCCEEDED');
    assert.equal(documents[0].vectorStatus, 'BLOCKED');
    assert.equal(application.calls.filter(call => call.path === '/api/documents/demo').length, 2);
    assert.deepEqual(application.calls.filter(call => call.method === 'POST').map(call => call.path),
        ['/api/auth/login', '/api/documents/import-examples', '/api/auth/logout']);
});

test('explicit vector import checks configuration, starts current indexing and waits for completion', async context => {
    const application = await fixture(context);
    const documents = await application.run({index: true});
    assert.equal(documents[0].vectorStatus, 'SUCCEEDED');
    assert.equal(application.calls.filter(call => call.path.endsWith('/index')).length, 1);
    assert.equal(application.calls.at(-1).path, '/api/auth/logout');
});

test('missing vector configuration aborts before import and still logs out', async context => {
    const application = await fixture(context, {embedding: false});
    await assert.rejects(application.run({index: true}), /向量服务/);
    assert.equal(application.calls.some(call => call.path.includes('/documents')), false);
    assert.equal(application.calls.at(-1).path, '/api/auth/logout');
});

test('asynchronous failures cannot be reported as successful imports', async context => {
    const application = await fixture(context, {textFailure: true});
    await assert.rejects(application.run(), /文档处理失败/);
    assert.equal(application.calls.at(-1).path, '/api/auth/logout');
});

test('vector errors remain failures after keyword success', async context => {
    const application = await fixture(context, {vectorFailure: true});
    await assert.rejects(application.run({index: true}), /文档处理失败/);
    assert.equal(application.calls.at(-1).path, '/api/auth/logout');
});

test('pending processing times out, retains accepted work and cleans its session', async context => {
    const application = await fixture(context, {pending: true});
    await assert.rejects(application.run({timeoutMs: 300}), /超时/);
    assert.equal(application.calls.filter(call => call.path === '/api/documents/import-examples').length, 1);
    assert.equal(application.calls.at(-1).path, '/api/auth/logout');
});

test('write failures do not retry or disclose the response body', async context => {
    const application = await fixture(context, {writeFailure: true});
    await assert.rejects(application.run(), error => error.message.endsWith('HTTP 503') && !error.message.includes('private'));
    assert.equal(application.calls.filter(call => call.path === '/api/documents/import-examples').length, 1);
});

test('login errors and non-admin accounts cannot import', async context => {
    const failed = await fixture(context, {loginFailure: true});
    await assert.rejects(failed.run(), error => error.message.endsWith('HTTP 401') && !error.message.includes('private'));
    assert.equal(failed.calls.length, 1);
    const ordinary = await fixture(context, {role: 'USER'});
    await assert.rejects(ordinary.run(), /管理员/);
    assert.deepEqual(ordinary.calls.map(call => call.path), ['/api/auth/login', '/api/auth/logout']);
});

test('login redirects never forward credentials to another path', async context => {
    const application = await fixture(context, {redirect: true});
    await assert.rejects(application.run(), /请求未完成/);
    assert.equal(application.calls.length, 1);
});

test('import rejects non-local targets and credentials in URLs before making requests', async () => {
    for (const base of ['https://example.com', 'http://127.0.0.1/path', 'http://user:synthetic@localhost', 'http://localhost?token=synthetic']) {
        await assert.rejects(importKnowledge({base, username: 'synthetic', password: 'synthetic'}), /本机 HTTP/);
    }
});

test('legacy evaluation CLI routes to the isolated launcher and rejects existing-instance targets', async () => {
    const env = {...process.env};
    delete env.SUPPORTOPS_URL;
    const run = promisify(execFile);
    const help = await run(process.execPath, ['evaluation/run.mjs', '--help'], {env, windowsHide: true});
    assert.match(help.stdout, /scripts\/evaluate.mjs/);
    await assert.rejects(run(process.execPath, ['evaluation/run.mjs', '--sources-only'], {
        env: {...env, SUPPORTOPS_URL: 'http://127.0.0.1:18080'}, windowsHide: true
    }), error => error.code === 1 && error.stderr.includes('评测不接受 SUPPORTOPS_URL'));
});
