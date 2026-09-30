import {readFile, mkdir, writeFile} from 'node:fs/promises';
import {spawn} from 'node:child_process';
import {resolve, dirname} from 'node:path';
import {fileURLToPath, pathToFileURL} from 'node:url';
import {question, inputManifest, retrievalCheck, diagnosticChecks, renderReview} from './report.mjs';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const active = run => ['QUEUED', 'RUNNING'].includes(run.status);

export async function evaluate({base, directory, sourcesOnly = false, hybrid = false, metadata = {}, signal, cookie}) {
  const url = new URL(base);
  if (url.protocol !== 'http:' || !['127.0.0.1', 'localhost'].includes(url.hostname) || url.username || url.password || url.search || url.hash || url.pathname !== '/') throw new Error('Evaluation requires an isolated local demo instance.');
  const cases = JSON.parse(await readFile(new URL('./cases.json', import.meta.url), 'utf8'));
  const report = {createdAt: new Date().toISOString(), kind: sourcesOnly ? 'SOURCE_SMOKE' : 'MODEL_EVALUATION', retrieval: hybrid ? 'HYBRID' : 'LEXICAL', modelQualityVerdict: 'NOT_EVALUATED', question, metadata, inputs: await inputManifest(root), cases: []};
  await mkdir(directory, {recursive: true});
  const reportPath = resolve(directory, 'report.json');
  async function save() {
    await writeFile(reportPath, JSON.stringify(report, null, 2));
    await writeFile(resolve(directory, 'review.md'), renderReview(report));
  }
  async function api(path, options = {}) {
    if (options.body && !(options.body instanceof FormData)) {
      options.headers = {'Content-Type': 'application/json'};
      options.body = JSON.stringify(options.body);
    }
    options.headers = {...options.headers, 'X-SupportOps-Request': '1', ...(cookie ? {Cookie: cookie} : {})};
    const timeout = AbortSignal.timeout(45000);
    const response = await fetch(url.origin + '/api' + path, {...options, redirect: 'error', signal: signal ? AbortSignal.any([signal, timeout]) : timeout});
    // Do not copy server/provider response bodies into errors.
    if (!response.ok) throw new Error(`${path}: HTTP ${response.status}`);
    const raw = await response.text();
    return raw ? JSON.parse(raw) : null;
  }
  async function awaitDocument(documentId) {
    const deadline = Date.now() + 120000;
    while (Date.now() < deadline) {
      signal?.throwIfAborted();
      const {document} = await api(`/documents/${documentId}`);
      if (['FAILED', 'CANCELLED'].includes(document.processingStatus) || ['FAILED', 'BLOCKED'].includes(document.textStatus)
          || hybrid && ['FAILED', 'BLOCKED', 'SUPERSEDED'].includes(document.vectorStatus)) {
        // 隔离资源随后会被回收；先保存状态与公开错误码，不复制响应正文或原件内容。
        const code = value => typeof value === 'string' && /^[A-Z][A-Z0-9_]{0,99}$/.test(value) ? value : 'UNKNOWN';
        const observation = {documentId, processingStatus: code(document.processingStatus),
          textStatus: code(document.textStatus), vectorStatus: code(document.vectorStatus)};
        let recoveringImport = false;
        try {
          const tasks = await api(`/documents/${documentId}/tasks`);
          observation.tasks = tasks.map(task => ({kind: code(task.kind), status: code(task.status),
            stage: code(task.stage), errorCode: code(task.errorCode)}));
          // IMPORT 的单次失败先落批次，任务调度随后安排补偿；只等待同批次既有任务。
          // SUCCEEDED 也需重新读取文档，不能将两次查询间的恢复误判为失败或直接判成功。
          recoveringImport = document.processingStatus === 'FAILED' && Boolean(document.batchId)
            && !['FAILED', 'BLOCKED'].includes(document.textStatus)
            && !(hybrid && ['FAILED', 'BLOCKED', 'SUPERSEDED'].includes(document.vectorStatus))
            && tasks.some(task => task.batchId === document.batchId && task.kind === 'IMPORT'
              && ['RETRY_WAIT', 'RUNNING', 'SUCCEEDED'].includes(task.status));
        } catch {
          observation.diagnosticsStatus = 'UNAVAILABLE';
        }
        if (recoveringImport) {
          report.documentRetryObservations ??= [];
          if (!report.documentRetryObservations.some(item => item.documentId === documentId)) {
            report.documentRetryObservations.push(observation);
            await save();
          }
          await new Promise(resolve => setTimeout(resolve, 250));
          continue;
        }
        report.documentFailure = observation;
        throw new Error('Document processing failed; inspect documentFailure in report.json.');
      }
      if (document.processingStatus === 'READY' && document.textStatus === 'SUCCEEDED' && (!hybrid || document.vectorStatus === 'SUCCEEDED')) return;
      await new Promise(resolve => setTimeout(resolve, 250));
    }
    throw new Error('Document processing timed out; accepted work remains in task history.');
  }
  try {
    report.configuration = await api('/status');
    const missing = [];
    for (const role of sourcesOnly ? [] : hybrid ? ['model', 'embedding'] : ['model']) {
      if (report.configuration[role + 'Configured']) continue;
      const route = report.configuration[role + 'Route'];
      missing.push(route?.error && !route.error.endsWith('NOT_CONFIGURED') ? route.error : route?.credentialEnv || (role === 'model' ? 'SUPPORTOPS_API_KEY' : 'SUPPORTOPS_EMBEDDING_API_KEY'));
    }
    if (missing.length) {
      report.status = 'NOT_RUN'; report.reason = `Missing configuration: ${missing.join(', ')}.`;
      await save(); return {report, reportPath, exitCode: 2};
    }
    if ((await api('/runs')).length || (await api('/memories')).length || (await api('/documents')).length) throw new Error('Evaluation database must be empty. Start a fresh isolated instance.');
    const docs = await api('/documents/import-examples', {method: 'POST'});
    for (const doc of docs) await awaitDocument(doc.id);
    for (const test of cases) {
      const item = {id: test.id, reviewCriteria: test.review}; report.cases.push(item);
      let injectionId, runId, abortSuite = false;
      try {
        const environment = await api('/demo/scenario', {method: 'POST', body: {scenario: test.scenario}});
        item.environmentCheck = {expected: test.expectedHttpStatus, actual: environment.lastRequest.httpStatus, passed: environment.lastRequest.httpStatus === test.expectedHttpStatus};
        if (!item.environmentCheck.passed) throw new Error('Fault environment did not match expected behavior.');
        if (test.scenario === 'injection') {
          const body = new FormData(); body.set('title', '升级同步补充材料'); body.set('version', '2.0');
          body.set('file', new Blob([await readFile(new URL('./fixtures/injection-2.0.md', import.meta.url))]), 'injection-2.0.md');
          const document = await api('/documents', {method: 'POST', body}); injectionId = document.id;
          await awaitDocument(document.id);
        }
        const retrieval = await api('/documents/search?query=sync.targetPath&version=2.0&lexicalOnly=' + !hybrid);
        item.retrievalCheck = retrievalCheck(retrieval, hybrid);
        item.retrievalProbe = retrieval;
        if (!item.retrievalCheck.passed) throw new Error('Retrieval returned no evidence, an inapplicable version, or an unexpected mode.');
        if (sourcesOnly) { item.status = 'SOURCE_CHECKED'; item.modelDiagnosis = 'NOT_RUN'; }
        else {
          // Never retry a submission: a lost response may still have created a task.
          abortSuite = true;
          const run = await api('/runs', {method: 'POST', body: {question, lexicalOnly: !hybrid}});
          runId = run.id; item.runId = runId;
          let result, deadline = Date.now() + 150000;
          do {
            await new Promise(r => setTimeout(r, 600)); result = await api(`/runs/${runId}`);
          } while (active(result) && Date.now() < deadline);
          if (active(result)) result = await api(`/runs/${runId}/cancel`, {method: 'POST'});
          item.run = result;
          // Read the terminal state before its events so late persisted evidence is included.
          item.events = await api(`/runs/${runId}/events`);
          item.status = result.status; item.humanReview = 'REQUIRED';
          item.checks = diagnosticChecks(result, item.events, injectionId, hybrid);
          if (injectionId) item.attackDocumentRetrieved = item.checks.attackExposure === 'EXPOSED';
          abortSuite = result.status !== 'COMPLETED';
        }
      } catch (error) {
        item.status = 'ERROR'; item.error = error.message;
        if (signal?.aborted) abortSuite = true;
        if (runId) {
          try {
            let run = await api(`/runs/${runId}`);
            if (active(run)) run = await api(`/runs/${runId}/cancel`, {method: 'POST'});
            item.run = run; item.events = await api(`/runs/${runId}/events`);
          } catch { item.recovery = 'TASK_STATE_UNCONFIRMED'; }
        }
      } finally {
        if (injectionId) {
          try { await api(`/documents/${injectionId}/delete`, {method: 'POST'}); }
          catch (error) { item.cleanupError = error.message; abortSuite = true; }
        }
        await save();
      }
      console.log(`${item.id}: ${item.status}`);
      if (abortSuite) { report.stopReason = 'Stopped after a failed or uncertain model run; no automatic resubmission.'; break; }
    }
    report.status = report.cases.length !== cases.length || report.cases.some(c => c.status === 'ERROR' || c.cleanupError || c.checks?.status === 'HAS_VIOLATIONS' || (!sourcesOnly && c.status !== 'COMPLETED')) ? 'HAS_FAILURES' : 'EXECUTION_FINISHED';
  } catch (error) { report.status = 'ERROR'; report.reason = error.message; }
  await save();
  return {report, reportPath, exitCode: report.status === 'EXECUTION_FINISHED' ? 0 : 1};
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  if (process.env.SUPPORTOPS_URL) {
    console.error('评测不接受 SUPPORTOPS_URL。请移除此变量，使用 node scripts/evaluate.mjs 创建全新隔离实例。');
    process.exitCode = 1;
  } else {
    // 旧命令统一转交隔离启动器，避免对日常工作台执行场景写操作。
    const child = spawn(process.execPath, [resolve(root, 'scripts/evaluate.mjs'), ...process.argv.slice(2)], {
      stdio: 'inherit', windowsHide: true
    });
    process.exitCode = await new Promise((resolveExit, reject) => {
      child.on('error', reject);
      child.on('exit', code => resolveExit(code ?? 1));
    });
  }
}
