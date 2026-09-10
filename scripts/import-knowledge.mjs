import {resolve} from 'node:path';
import {pathToFileURL} from 'node:url';
import {setTimeout as delay} from 'node:timers/promises';

/** 使用管理员会话导入内置资料，等待异步完成；不自动重试写请求。 */
export async function importKnowledge({base, username, password, index = false, timeoutMs = 120000, pollMs = 500}) {
    const url = new URL(base);
    if (url.protocol !== 'http:' || !['localhost', '127.0.0.1'].includes(url.hostname)
        || url.username || url.password || url.pathname !== '/' || url.search || url.hash) {
        throw new Error('示例导入只接受本机 HTTP 根地址。');
    }
    if (!username || !password) {
        throw new Error('请设置 SUPPORTOPS_USERNAME 和 SUPPORTOPS_PASSWORD，使用已创建的管理员账号。');
    }
    let cookie;
    let failure;
    const deadline = Date.now() + timeoutMs;

    /** 限制请求时间、禁止重定向，错误不带响应正文或凭据。 */
    async function api(path, method = 'GET', body) {
        const remaining = deadline - Date.now();
        if (remaining <= 0) {
            throw new Error('文档处理超时；已受理任务仍保留，请在工作台核对状态。');
        }
        let response;
        try {
            response = await fetch(url.origin + '/api' + path, {
                method,
                redirect: 'error',
                headers: {'X-SupportOps-Request': '1', ...(cookie ? {Cookie: cookie} : {}),
                    ...(body ? {'Content-Type': 'application/json'} : {})},
                body: body ? JSON.stringify(body) : undefined,
                signal: AbortSignal.timeout(Math.min(remaining, 30000))
            });
        } catch {
            throw new Error('请求未完成；请核对服务及任务状态，不要据此重复提交写请求。');
        }
        if (!response.ok) {
            throw new Error(`${path}: HTTP ${response.status}`);
        }
        if (path === '/auth/login') {
            cookie = response.headers.getSetCookie().find(value => value.startsWith('JSESSIONID='))?.split(';')[0];
            if (!cookie) {
                throw new Error('登录未返回有效会话。');
            }
        }
        const text = await response.text();
        try {
            return text ? JSON.parse(text) : null;
        } catch {
            throw new Error('服务响应格式异常，请在工作台核对任务状态。');
        }
    }

    /** 关键词与向量分阶段等待，避免把受理成功当作索引完成。 */
    async function awaitDocument(id, vector) {
        while (true) {
            const {document} = await api(`/documents/${encodeURIComponent(id)}`);
            if (['FAILED', 'CANCELLED'].includes(document.processingStatus)
                || ['FAILED', 'BLOCKED', 'SUPERSEDED'].includes(document.textStatus)
                || vector && ['FAILED', 'BLOCKED', 'SUPERSEDED'].includes(document.vectorStatus)) {
                throw new Error('文档处理失败；请查看工作台中的持久化任务记录。');
            }
            if (document.processingStatus === 'READY' && document.textStatus === 'SUCCEEDED'
                && (!vector || document.vectorStatus === 'SUCCEEDED')) {
                return document;
            }
            await delay(pollMs);
        }
    }

    try {
        const user = await api('/auth/login', 'POST', {username, password});
        if (user.role !== 'ADMIN') {
            throw new Error('导入示例资料需要管理员账号。');
        }
        if (index && !(await api('/status')).embeddingConfigured) {
            throw new Error('--index 需要先在工作台配置可用的向量服务。');
        }
        const accepted = await api('/documents/import-examples', 'POST');
        const completed = [];
        for (const item of accepted) {
            let document = await awaitDocument(item.id, false);
            if (index) {
                // 使用当前向量配置重建，不能仅凭旧配置的 SUCCEEDED 判定完成。
                await api(`/documents/${encodeURIComponent(item.id)}/index`, 'POST');
                document = await awaitDocument(item.id, true);
            }
            completed.push(document);
        }
        return completed;
    } catch (error) {
        failure = error;
        throw error;
    } finally {
        if (cookie) {
            try {
                // 清理本次会话另设时间上限，处理超时也仍尝试注销。
                const response = await fetch(url.origin + '/api/auth/logout', {
                    method: 'POST', redirect: 'error', signal: AbortSignal.timeout(5000),
                    headers: {Cookie: cookie, 'X-SupportOps-Request': '1'}
                });
                if (!response.ok) {
                    throw new Error('注销未完成');
                }
            } catch {
                if (!failure) {
                    throw new Error('资料已处理，但临时登录会话注销失败；请核对服务状态。');
                }
            }
        }
    }
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
    const args = process.argv.slice(2);
    if (args.includes('--help')) {
        console.log('用法：node scripts/import-knowledge.mjs [--index]\n进程环境变量：SUPPORTOPS_USERNAME、SUPPORTOPS_PASSWORD；SUPPORTOPS_URL 默认 http://127.0.0.1:18080。');
    } else {
        try {
            if (args.some(arg => arg !== '--index') || args.length > 1) {
                throw new Error('仅支持 --index 或 --help，不接受命令行密码。');
            }
            const documents = await importKnowledge({
                base: process.env.SUPPORTOPS_URL || 'http://127.0.0.1:18080',
                username: process.env.SUPPORTOPS_USERNAME, password: process.env.SUPPORTOPS_PASSWORD,
                index: args.includes('--index')
            });
            console.log(`已完成 ${documents.length} 份示例资料的${args.includes('--index') ? '关键词与向量' : '关键词'}索引。`);
        } catch (error) {
            console.error(error.message);
            process.exitCode = 1;
        }
    }
}
